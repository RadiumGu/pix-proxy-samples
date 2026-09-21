package com.amazon.aws.pix.proxy.test.tls;

import com.amazon.aws.pix.core.tls.PixTlsEngineConfigurer;
import com.amazon.aws.pix.core.util.KeyStoreUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import org.junit.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetAddress;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Proves hostname verification on the stack the proxy actually uses: a real Netty channel with an
 * {@link SslHandler}, configured by the <em>production</em> {@link PixTlsEngineConfigurer}.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>An independent review measured that the previous test did not guard the fix at all. It used a
 * plain JSSE {@code SSLSocket} and set the algorithm inline, so deleting the production line left it
 * passing 2/2 — and the commit message presented it as pinning the behaviour when only a CI
 * text-grep did. It also exercised JSSE while production runs {@code SslProvider.OPENSSL}, so an
 * OpenSSL-specific regression would have gone unnoticed.
 *
 * <p>This test closes both gaps: it calls the same configurer the factory calls, and it drives a real
 * Netty handshake. A regression inside the configurer now fails a test rather than only a grep.
 *
 * <h2>Provider selection, stated rather than hidden</h2>
 *
 * <p>It runs on {@code OPENSSL} when that provider is available and falls back to {@code JDK}
 * otherwise, reporting which one ran. The repository pins the {@code linux-x86_64-fedora} tcnative
 * artifact, so {@code OpenSsl.isAvailable()} is false on any other architecture — including the
 * aarch64 build host this was written on. Silently testing JDK while believing OpenSSL was covered is
 * exactly the kind of gap this test was created to remove, so the provider is asserted and printed.
 */
public class NettyHostnameVerificationTest {

    /** The fixture certificate is issued to CN=pix.aws.com, so this name cannot match. */
    private static final String MISMATCHED_HOST = "evil.example.com";

    private static final int HANDSHAKE_TIMEOUT_MS = 15_000;

    /** With the production configurer applied, a mismatched name must fail the handshake. */
    @Test
    public void theProductionConfigurerMakesNettyRejectAMismatchedName() throws Exception {
        final Outcome outcome = handshake(true);

        assertFalse("provider=" + outcome.provider + " outcome=" + outcome,
                outcome.completed);
        assertNotNull("the handshake must fail with a cause", outcome.failure);
        assertTrue("the rejection must be about the NAME, not about trust. provider="
                        + outcome.provider + " chain=" + outcome.causeChain(),
                outcome.causeChain().contains("No name matching")
                        || outcome.causeChain().toLowerCase().contains("hostname")
                        || outcome.causeChain().contains("No subject alternative"));
    }

    /**
     * Negative control, and the reason the assertion above carries information: the identical
     * handshake with the configurer NOT applied must SUCCEED. Otherwise the test above would pass for
     * a handshake that fails for any unrelated reason — which is how the previous version of this
     * test managed to pass while the production fix was absent.
     */
    @Test
    public void theSameHandshakeSucceedsWithoutTheConfigurer() throws Exception {
        final Outcome outcome = handshake(false);

        assertTrue("without hostname verification the mismatched name is accepted. provider="
                + outcome.provider + " outcome=" + outcome, outcome.completed);
    }

    /**
     * Asserts the COVERAGE, not just the behaviour.
     *
     * <p>Without this, the two tests above quietly pass on {@code SslProvider.JDK} whenever tcnative
     * is missing, while appearing to cover the OpenSSL engine that production runs. That is the exact
     * failure mode this whole test class was written to remove — a check that reports success for a
     * property it never exercised — so the degradation is made loud instead of silent.
     *
     * <p>{@code netty-tcnative-boringssl-static} is on the test classpath for this reason: the
     * production pin carries the {@code linux-x86_64-fedora} classifier only, so OpenSSL is
     * unavailable on any other architecture. Measured here: {@code OpenSsl.isAvailable() = true},
     * {@code versionString = BoringSSL}.
     */
    @Test
    public void theTlsTestsActuallyRunOnTheProviderProductionUses() {
        assertTrue("OpenSSL is unavailable, so the hostname-verification tests above degraded to "
                        + "SslProvider.JDK and no longer cover the engine production runs. Cause: "
                        + OpenSsl.unavailabilityCause(),
                OpenSsl.isAvailable());
    }

    /** Result of one handshake attempt. */
    private static final class Outcome {
        private String provider;
        private boolean completed;
        private Throwable failure;

        String causeChain() {
            final StringBuilder text = new StringBuilder();
            Throwable t = failure;
            while (t != null) {
                text.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" | ");
                t = t.getCause();
            }
            return text.toString();
        }

        @Override
        public String toString() {
            return "completed=" + completed + " causes=" + causeChain();
        }
    }

    /**
     * Runs one Netty client handshake against a loopback JSSE server presenting CN=pix.aws.com, while
     * telling Netty the host is {@link #MISMATCHED_HOST}. The whole fixture store is trusted on the
     * client side, so the certificate chain is valid and the NAME is the only variable.
     *
     * @param applyConfigurer whether to apply the production engine configuration
     */
    private Outcome handshake(final boolean applyConfigurer) throws Exception {
        final Outcome outcome = new Outcome();
        final SslProvider provider = OpenSsl.isAvailable() ? SslProvider.OPENSSL : SslProvider.JDK;
        outcome.provider = provider.name() + (OpenSsl.isAvailable() ? "" : " (OpenSSL unavailable "
                + "on this architecture - the repository pins linux-x86_64-fedora tcnative)");

        final KeyStore fixture =
                KeyStoreUtil.getKeyStoreFromResource("security/client.jks", "secret");

        final ExecutorService serverPool = Executors.newSingleThreadExecutor();
        final EventLoopGroup clientGroup = new NioEventLoopGroup(1);
        try (SSLServerSocket server = startServer(fixture)) {
            final int port = server.getLocalPort();
            serverPool.submit(() -> {
                try (SSLSocket peer = (SSLSocket) server.accept()) {
                    peer.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                    final InputStream in = peer.getInputStream();
                    while (in.read() >= 0) {
                        // drain; the client owns every assertion
                    }
                } catch (Exception ignored) {
                    // A rejected handshake surfaces here too; it is the client's business.
                }
                return null;
            });

            final TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(fixture);

            final SslContext sslContext = SslContextBuilder.forClient()
                    .sslProvider(provider)
                    .trustManager(tmf)
                    .protocols("TLSv1.2", "TLSv1.3")
                    .build();

            final Bootstrap bootstrap = new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, HANDSHAKE_TIMEOUT_MS)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(final Channel channel) {
                            // Exactly what NettyHttpClientInitializerFactory does: create the engine
                            // for the target host, then hand it to the production configurer.
                            final SSLEngine engine = sslContext.newEngine(
                                    channel.alloc(), MISMATCHED_HOST, port);
                            if (applyConfigurer) {
                                PixTlsEngineConfigurer.configureClient(engine, MISMATCHED_HOST);
                            } else {
                                engine.setUseClientMode(true);
                            }
                            channel.pipeline().addLast(new SslHandler(engine));
                        }
                    });

            final Channel channel = bootstrap
                    .connect(InetAddress.getLoopbackAddress(), port).syncUninterruptibly().channel();
            try {
                final SslHandler handler = channel.pipeline().get(SslHandler.class);
                final io.netty.util.concurrent.Future<Channel> handshake =
                        handler.handshakeFuture().await(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                ? handler.handshakeFuture() : null;
                if (handshake == null) {
                    fail("the handshake neither completed nor failed within the timeout - provider="
                            + outcome.provider);
                }
                outcome.completed = handshake.isSuccess();
                outcome.failure = handshake.cause();
            } finally {
                channel.close().syncUninterruptibly();
            }
        } finally {
            serverPool.shutdownNow();
            clientGroup.shutdownGracefully(0, 200, TimeUnit.MILLISECONDS);
        }
        return outcome;
    }

    private static SSLServerSocket startServer(final KeyStore fixture) throws Exception {
        final KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(fixture, "secret".toCharArray());

        final TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(fixture);

        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        return (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }
}
