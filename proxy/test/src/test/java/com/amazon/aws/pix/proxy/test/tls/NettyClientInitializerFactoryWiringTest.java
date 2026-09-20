package com.amazon.aws.pix.proxy.test.tls;

import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.component.netty.ClientInitializerFactory;
import org.apache.camel.component.netty.NettyConfiguration;
import org.apache.camel.component.netty.http.HttpClientInitializerFactory;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins a trap that silently disabled the CloudHSM mTLS identity on the BCB leg.
 *
 * <p>The production route binds its custom initializer into the Camel registry
 * ({@code getContext().getRegistry().bind("nettyHttpClientInitializerFactory", ...)}). That alone
 * does <strong>nothing</strong>: Camel does not autowire {@code clientInitializerFactory}, so
 * without an explicit {@code #reference} on the endpoint it instantiates the <em>stock</em>
 * {@link HttpClientInitializerFactory} instead.
 *
 * <p>Why that mattered so much here: {@code NettyHttpClientInitializerFactory} is the <em>only</em>
 * code that casts the endpoint's {@code sslContextParameters} to {@code NettySSLContextParameters}
 * and reads {@code getSslContext()} — the io.netty {@code SslContext} carrying the CloudHSM mTLS
 * client key and the pinned BCB trust anchor. With the stock factory in its place, the stock code
 * calls {@code SSLContextParameters.createSSLContext()} on an otherwise-empty parameters object, so
 * the handshake would present <strong>no client certificate at all</strong> — against a BCB that
 * mandates mutual authentication. The custom factory's SNI and its read-timeout hook were dead for
 * the same reason.
 *
 * <p>These tests do not need CloudHSM: the wiring is decided when the endpoint is created, which is
 * measurable on its own. They deliberately use a host that cannot resolve, since nothing connects.
 */
public class NettyClientInitializerFactoryWiringTest {

    private static final String FACTORY_NAME = "nettyHttpClientInitializerFactory";

    /** Stands in for the production factory; identity is all these tests need. */
    private static final class MarkerFactory extends ClientInitializerFactory {
        @Override
        public ClientInitializerFactory createPipelineFactory(
                org.apache.camel.component.netty.NettyProducer producer) {
            return this;
        }

        @Override
        protected void initChannel(io.netty.channel.Channel channel) {
            // never invoked; nothing connects in these tests
        }
    }

    /** Mirrors the production endpoint, minus the CloudHSM-backed SslContext. */
    private static final String BASE_URI =
            "netty-http:https://bcb.invalid:16422"
                    + "?bridgeEndpoint=true&throwExceptionOnFailure=false&ssl=true"
                    + "&enabledProtocols=TLSv1.2,TLSv1.3&requestTimeout=30000";

    private CamelContext camelContext;

    @Before
    public void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.getRegistry().bind(FACTORY_NAME, new MarkerFactory());
        camelContext.start();
    }

    @After
    public void tearDown() throws Exception {
        if (camelContext != null) {
            camelContext.stop();
        }
    }

    private NettyConfiguration configurationOf(String uri) throws Exception {
        final Endpoint endpoint = camelContext.getEndpoint(uri);
        return (NettyConfiguration) endpoint.getClass().getMethod("getConfiguration").invoke(endpoint);
    }

    /**
     * The trap itself: binding the factory by name is not enough. If this ever starts returning the
     * registry bean, Camel gained autowiring and the explicit reference could be reconsidered — but
     * do not remove it on the strength of a Camel upgrade alone.
     */
    @Test
    public void bindingIntoTheRegistryAloneDoesNotWireTheFactory() throws Exception {
        Assert.assertNotNull("the bean must be in the registry for this test to mean anything",
                camelContext.getRegistry().lookupByName(FACTORY_NAME));

        final ClientInitializerFactory wired = configurationOf(BASE_URI).getClientInitializerFactory();

        Assert.assertFalse(
                "Camel used the registry-bound factory without an explicit #reference. If this is "
                        + "now true, re-read the production route's comment before changing it.",
                wired instanceof MarkerFactory);
    }

    /** The fix: an explicit {@code #reference} is what actually wires it. */
    @Test
    public void anExplicitReferenceWiresTheCustomFactory() throws Exception {
        final ClientInitializerFactory wired =
                configurationOf(BASE_URI + "&clientInitializerFactory=#" + FACTORY_NAME)
                        .getClientInitializerFactory();

        Assert.assertTrue(
                "an explicit #reference must wire the custom factory; without it the CloudHSM "
                        + "SslContext never reaches TLS. Got: " + wired,
                wired instanceof MarkerFactory);
    }

    /**
     * {@code requestTimeout} must survive onto the configuration, because the custom factory only
     * installs a {@code ReadTimeoutHandler} when it is greater than zero. Its Camel default is 0,
     * i.e. reads are unbounded.
     */
    @Test
    public void requestTimeoutReachesTheConfigurationSoTheReadTimeoutHandlerIsInstalled()
            throws Exception {
        Assert.assertEquals("without this the BCB leg has no read timeout at all",
                30_000L, configurationOf(BASE_URI).getRequestTimeout());
    }

    /** Documents the default this repository must override: unbounded reads. */
    @Test
    public void camelsOwnDefaultRequestTimeoutIsUnbounded() throws Exception {
        final String withoutTimeout =
                "netty-http:https://bcb.invalid:16422?bridgeEndpoint=true&ssl=true";
        Assert.assertEquals(
                "Camel's default requestTimeout is 0 (disabled). If this is no longer 0, the "
                        + "production route's explicit value can be re-evaluated.",
                0L, configurationOf(withoutTimeout).getRequestTimeout());
    }

    /** The endpoint-level protocol list must reach the configuration. */
    @Test
    public void enabledProtocolsReachTheConfiguration() throws Exception {
        Assert.assertEquals("TLSv1.2,TLSv1.3", configurationOf(BASE_URI).getEnabledProtocols());
    }
}
