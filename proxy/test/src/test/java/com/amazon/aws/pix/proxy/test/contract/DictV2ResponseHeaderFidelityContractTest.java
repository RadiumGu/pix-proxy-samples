package com.amazon.aws.pix.proxy.test.contract;

import org.apache.camel.CamelContext;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Asserts that BCB response headers survive the proxy, with {@code Cache-Control} as the case that
 * matters most.
 *
 * <h2>Why this is a correctness test, not a performance one</h2>
 *
 * <p>The DICT API page states, under <em>Consultar Vínculo → Cache</em>, that entry-query responses
 * "podem ter suas respostas <em>cacheadas</em> no PSP, devendo seguir as diretivas contidas no header
 * {@code Cache-Control}", linking the directive to RFC 7234 §5.2.
 *
 * <p>A {@code getEntry} response says which account owns a Pix key. If the proxy strips
 * {@code Cache-Control}, the PSP's own HTTP client loses the only instruction telling it how long the
 * answer may be reused — and a stale answer means initiating a payment to an account that no longer
 * owns the key. Conversely, if the proxy were to *rewrite* the directive upwards, it would license a
 * longer stale window than BCB permits. Either way the failure is paying the wrong person, not a
 * slow response.
 *
 * <p>The response leg is also where this repository has already found two real defects — a gzip body
 * destroyed before signature verification, and stale {@code Content-Encoding} / {@code Content-Length}
 * headers after decoding — so "headers presumably pass through" is not a safe assumption here.
 */
public class DictV2ResponseHeaderFidelityContractTest {

    private CamelContext camelContext;
    private int proxyPort;
    private int targetPort;

    private static final String CACHE_CONTROL = "max-age=3600, private";
    private static final String ETAG = "\"a9f13566e19f5ca51329479a5bae60c5\"";

    @Before
    public void setUp() throws Exception {
        proxyPort = freePort();
        targetPort = freePort();
        camelContext = new DefaultCamelContext();
    }

    @After
    public void tearDown() {
        if (camelContext != null) {
            camelContext.stop();
        }
    }

    /**
     * MEASURED DEFECT, and the reason this class exists: with the STOCK header filter strategy, BCB's
     * {@code Cache-Control} is silently dropped. camel-netty-http 3.4.2's
     * {@code NettyHttpHeaderFilterStrategy} carries an out-filter list containing
     * {@code cache-control}, so the caller receives no directive at all while neighbouring headers
     * such as {@code ETag} pass through untouched.
     *
     * <p>This test pins the defect rather than the fix, so that the fix below cannot be mistaken for
     * a property the framework already had.
     */
    @Test
    public void theStockFilterStrategySilentlyDropsCacheControl() throws Exception {
        startProxy(null, false);

        final HttpURLConnection connection = get("/api/v2/entries/11122233300");

        assertEquals(200, connection.getResponseCode());
        assertNull("stock camel-netty-http filters cache-control out",
                connection.getHeaderField("Cache-Control"));
        assertEquals("but not ETag - so this is filtering, not a broken response path",
                ETAG, connection.getHeaderField("ETag"));
    }

    /**
     * The fix: removing {@code cache-control} from the out-filter makes the directive survive. This is
     * exactly what {@code PixHttpHeaderFilterStrategy} does in production, and the CI gate pins that
     * the production route references it.
     */
    @Test
    public void removingCacheControlFromTheOutFilterMakesItSurvive() throws Exception {
        startProxy(null, true);

        final HttpURLConnection connection = get("/api/v2/entries/11122233300");

        assertEquals(200, connection.getResponseCode());
        assertEquals("the PSP client needs BCB's own directive, unmodified",
                CACHE_CONTROL, connection.getHeaderField("Cache-Control"));
    }

    /** A validator header must survive too - the same pass-through path carries both. */
    @Test
    public void otherResponseHeadersSurviveAsWell() throws Exception {
        startProxy(null, true);

        final HttpURLConnection connection = get("/api/v2/entries/11122233300");

        assertEquals(ETAG, connection.getHeaderField("ETag"));
    }

    /**
     * Negative control. With a step that strips the header, the assertion above must fail - proving
     * the test can actually detect a proxy that eats {@code Cache-Control} rather than passing
     * because the header happens to be absent from both sides.
     */
    @Test
    public void theTestDetectsAProxyThatStripsCacheControl() throws Exception {
        startProxy(exchange -> exchange.getMessage().removeHeader("Cache-Control"), true);

        final HttpURLConnection connection = get("/api/v2/entries/11122233300");

        assertEquals(200, connection.getResponseCode());
        assertNull("the control must genuinely lose the header",
                connection.getHeaderField("Cache-Control"));
    }

    /**
     * Mirrors the production response leg: bridgeEndpoint, then the byte-level step, then the string
     * conversion and a stand-in verifier. The saboteur, when supplied, runs where the production
     * processors run.
     */
    private void startProxy(final Processor saboteur, final boolean keepCachingDirectives)
            throws Exception {
        final org.apache.camel.component.netty.http.NettyHttpHeaderFilterStrategy filter =
                new org.apache.camel.component.netty.http.NettyHttpHeaderFilterStrategy();
        if (keepCachingDirectives) {
            // The one-line change PixHttpHeaderFilterStrategy makes in production.
            filter.getOutFilter().remove("cache-control");
            filter.getInFilter().remove("cache-control");
        }
        camelContext.getRegistry().bind("testHeaderFilter", filter);

        // Always unfiltered: used by the stand-in BCB so the header genuinely reaches the wire.
        final org.apache.camel.component.netty.http.NettyHttpHeaderFilterStrategy unfiltered =
                new org.apache.camel.component.netty.http.NettyHttpHeaderFilterStrategy();
        unfiltered.getOutFilter().remove("cache-control");
        unfiltered.getInFilter().remove("cache-control");
        camelContext.getRegistry().bind("unfilteredHeaderFilter", unfiltered);

        final String consumer = "netty-http:http://0.0.0.0:" + proxyPort + "?matchOnUriPrefix=true&headerFilterStrategy=#testHeaderFilter";
        final String producer = "netty-http:http://127.0.0.1:" + targetPort
                + "?bridgeEndpoint=true&throwExceptionOnFailure=false"
                + "&headerFilterStrategy=#testHeaderFilter";
        // The stand-in BCB needs the unfiltered strategy too. Without it the stand-in's OWN consumer
        // filters cache-control before the header ever reaches the wire, and the test would blame the
        // proxy for a loss that happened in the fixture - which is exactly what the first version of
        // this test did.
        final String target = "netty-http:http://0.0.0.0:" + targetPort
                + "?matchOnUriPrefix=true&headerFilterStrategy=#unfilteredHeaderFilter";

        camelContext.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                org.apache.camel.model.ProcessorDefinition<?> route = from(consumer).to(producer);
                if (saboteur != null) {
                    route = route.process(saboteur);
                }
                route.transform(body().convertToString())
                        .process(exchange -> {
                            // Stand-in for VerifyResponseProcessor: touches the body, as the real
                            // one does, so that header loss caused by body handling is caught.
                            exchange.getMessage().setBody(exchange.getMessage().getBody(String.class));
                        });

                // Stand-in for BCB, answering with the headers a real getEntry carries.
                from(target)
                        .setHeader("CamelHttpResponseCode", constant(200))
                        .setHeader("Cache-Control", constant(CACHE_CONTROL))
                        .setHeader("ETag", constant(ETAG))
                        .setBody(constant("<GetEntryResponse/>"));
            }
        });
        camelContext.start();
    }

    private HttpURLConnection get(final String path) throws Exception {
        final HttpURLConnection connection =
                (HttpURLConnection) new URL("http://127.0.0.1:" + proxyPort + path).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.getResponseCode();
        return connection;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
