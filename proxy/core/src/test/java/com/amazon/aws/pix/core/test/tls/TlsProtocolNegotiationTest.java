package com.amazon.aws.pix.core.test.tls;

import com.amazon.aws.pix.core.util.KeyStoreUtil;
import org.junit.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the TLS protocol behaviour the BCB <em>Manual de Seguran&ccedil;a do Pix</em> requires, and
 * de-risks offering TLS 1.3 alongside 1.2.
 *
 * <h2>What the manual actually says</h2>
 *
 * <p>Manual de Seguran&ccedil;a do Pix, v3.7, section 2 "Comunica&ccedil;&atilde;o segura":
 * <blockquote>
 * "O participante deve se conectar &agrave;s APIs dispon&iacute;veis no Pix exclusivamente por meio
 * do protocolo HTTP vers&atilde;o 1.1 utilizando criptografia <b>TLS vers&atilde;o 1.2 ou
 * superior</b>, com autentica&ccedil;&atilde;o m&uacute;tua obrigat&oacute;ria no estabelecimento
 * da conex&atilde;o. Deve ser suportada, <b>no m&iacute;nimo, a Cipher Suite
 * ECDHE-RSA-AES-128-GCM-SHA256 (0xc02f)</b>"
 * </blockquote>
 *
 * <p>So 1.2 is the FLOOR, not the ceiling - "ou superior" permits 1.3 - and exactly one cipher
 * suite is mandatory. The production route previously pinned {@code enabledProtocols("TLSv1.2")},
 * which is compliant but excludes 1.3 even when both peers support it.
 *
 * <h2>Why these tests exist</h2>
 *
 * <p>The obvious objection to adding 1.3 is "what if BCB's endpoint only speaks 1.2 - does the
 * handshake break?". {@link #clientOfferingBothStillConnectsToATls12OnlyServer()} answers it by
 * measurement rather than by appeal to how TLS is supposed to work: a peer offering 1.2 and 1.3
 * negotiates 1.2 with a 1.2-only server and the connection carries data.
 *
 * <p>These tests use plain JSSE on loopback with the repository's self-signed test keystore. They
 * say nothing about BCB's real endpoint, its certificate chain (the manual states the BC uses
 * ICP-Brasil chain v10 SSL certificates), or mTLS against the real service. Endpoint
 * identification is deliberately left off because the test certificate's subject does not match
 * {@code 127.0.0.1}; that is a property of the fixture, not an endorsement of skipping hostname
 * verification in production.
 */
public class TlsProtocolNegotiationTest {

    /** The one suite the manual makes mandatory, in JSSE's naming. */
    private static final String MANDATORY_SUITE = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256";

    private static final String TLS12 = "TLSv1.2";
    private static final String TLS13 = "TLSv1.3";

    /** What the production route now offers, per "TLS versao 1.2 ou superior". */
    private static final String[] PRODUCTION_PROTOCOLS = {TLS12, TLS13};

    /** Bounded so a broken handshake fails the build instead of hanging it. */
    private static final int HANDSHAKE_TIMEOUT_MS = 15_000;

    private static final byte[] PING = "ping".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** Reads until end of stream so the peer's close_notify is consumed. */
    private static void drainToEof(InputStream in) {
        try {
            final byte[] scratch = new byte[64];
            while (in.read(scratch) >= 0) {
                // discard
            }
        } catch (Exception ignored) {
            // A reset or timeout here says nothing about the negotiated protocol, which is the
            // only thing these tests assert.
        }
    }

    private static SSLContext testContext() throws Exception {
        final KeyStore keyStore =
                KeyStoreUtil.getKeyStoreFromResource("security/client.jks", "secret");

        final KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "secret".toCharArray());

        // The fixture is self-signed, so the same store is both key and trust material.
        final TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return context;
    }

    /**
     * Completes one handshake and returns the protocol that was negotiated.
     *
     * @param serverProtocols what the server side enables
     * @param clientProtocols what the client side enables
     */
    private String negotiate(String[] serverProtocols, String[] clientProtocols) throws Exception {
        final SSLContext context = testContext();
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try (SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {

            server.setEnabledProtocols(serverProtocols);
            server.setNeedClientAuth(true); // the manual makes mutual authentication mandatory
            final int port = server.getLocalPort();

            final Future<String> accepted = pool.submit((Callable<String>) () -> {
                try (SSLSocket peer = (SSLSocket) server.accept()) {
                    peer.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                    // Force the handshake to complete and the bytes to actually flow, then drain to
                    // EOF so the peer's close_notify has arrived before this socket closes.
                    final InputStream in = peer.getInputStream();
                    final String protocol;
                    final byte[] buffer = new byte[PING.length];
                    int read = 0;
                    while (read < buffer.length) {
                        int n = in.read(buffer, read, buffer.length - read);
                        if (n < 0) {
                            break;
                        }
                        read += n;
                    }
                    protocol = peer.getSession().getProtocol();
                    drainToEof(in);
                    return protocol;
                }
            });

            final String clientProtocol;
            try (SSLSocket client = (SSLSocket) context.getSocketFactory()
                    .createSocket(InetAddress.getLoopbackAddress(), port)) {
                client.setEnabledProtocols(clientProtocols);
                client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                client.startHandshake();
                final OutputStream out = client.getOutputStream();
                out.write(PING);
                out.flush();
                clientProtocol = client.getSession().getProtocol();
            }
            // The client socket is now closed, so its close_notify is on the wire. Only then is it
            // safe to wait for the server: under TLS 1.3 a close() sends close_notify and then
            // BLOCKS for the peer's, so closing the server first would stall for the whole
            // soTimeout. That is measurable - it cost 15s per TLS 1.3 test before this ordering.
            final String serverSide = accepted.get(30, TimeUnit.SECONDS);
            assertEquals("both peers must agree on the negotiated protocol",
                    serverSide, clientProtocol);
            return serverSide;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- the de-risking test

    /**
     * The objection this repository has to answer before offering 1.3: a 1.2-only peer must still
     * work. TLS version negotiation picks the highest version both sides support, so adding 1.3 to
     * the offer cannot break a 1.2-only server - measured here rather than assumed.
     */
    @Test
    public void clientOfferingBothStillConnectsToATls12OnlyServer() throws Exception {
        assertEquals("a 1.2-only server must still be reachable after 1.3 was added to the offer",
                TLS12, negotiate(new String[]{TLS12}, PRODUCTION_PROTOCOLS));
    }

    /** When both peers can do 1.3, "ou superior" means 1.3 is what should be used. */
    @Test
    public void bothPeersOfferingBothNegotiateTls13() throws Exception {
        assertEquals(TLS13, negotiate(PRODUCTION_PROTOCOLS, PRODUCTION_PROTOCOLS));
    }

    /** The reverse direction, for a BCB client that has moved to 1.3 before we do. */
    @Test
    public void tls13OnlyPeerIsStillServedWhenBothAreOffered() throws Exception {
        assertEquals(TLS13, negotiate(PRODUCTION_PROTOCOLS, new String[]{TLS13}));
    }

    // ---------------------------------------------------------------- mandatory cipher suite

    /**
     * The manual makes exactly one suite mandatory. If a future JVM or a crypto policy drops it,
     * this test fails loudly instead of the failure showing up during homologação.
     */
    @Test
    public void mandatoryCipherSuiteIsSupportedAndEnabledByDefault() throws Exception {
        final SSLContext context = testContext();
        final List<String> supported =
                Arrays.asList(context.getSupportedSSLParameters().getCipherSuites());
        final List<String> enabled =
                Arrays.asList(context.getDefaultSSLParameters().getCipherSuites());

        assertTrue(MANDATORY_SUITE + " (0xc02f) is mandatory per the Manual de Seguranca do Pix "
                + "but this JVM does not support it", supported.contains(MANDATORY_SUITE));
        assertTrue(MANDATORY_SUITE + " is supported but not enabled by default on this JVM",
                enabled.contains(MANDATORY_SUITE));
    }

    /** The mandatory suite must actually complete a TLS 1.2 handshake, not merely be listed. */
    @Test
    public void mandatoryCipherSuiteCompletesATls12Handshake() throws Exception {
        final SSLContext context = testContext();
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try (SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {

            server.setEnabledProtocols(new String[]{TLS12});
            server.setEnabledCipherSuites(new String[]{MANDATORY_SUITE});
            server.setNeedClientAuth(true);
            final int port = server.getLocalPort();

            final Future<String> accepted = pool.submit((Callable<String>) () -> {
                try (SSLSocket peer = (SSLSocket) server.accept()) {
                    peer.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                    final InputStream in = peer.getInputStream();
                    in.read();
                    final String suite = peer.getSession().getCipherSuite();
                    drainToEof(in);
                    return suite;
                }
            });

            try (SSLSocket client = (SSLSocket) context.getSocketFactory()
                    .createSocket(InetAddress.getLoopbackAddress(), port)) {
                client.setEnabledProtocols(new String[]{TLS12});
                client.setEnabledCipherSuites(new String[]{MANDATORY_SUITE});
                client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                client.startHandshake();
                client.getOutputStream().write(PING);
                client.getOutputStream().flush();
            }
            assertEquals("the mandatory suite must be the one actually negotiated",
                    MANDATORY_SUITE, accepted.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- deprecated versions

    /**
     * Why the route pins {@code enabledProtocols} at all instead of trusting the JVM default.
     * <p>
     * Measured on Corretto 11.0.32 the default enabled set is
     * {@code [TLSv1.3, TLSv1.2, TLSv1.1, TLSv1]} - it still includes TLS 1.0 and 1.1, which the
     * manual's floor of 1.2 excludes. Corretto 17 no longer enables them. Pinning the list makes
     * the floor hold on both.
     */
    @Test
    public void pinningTheProtocolListExcludesTls10And11() throws Exception {
        final SSLContext context = testContext();
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
            socket.setEnabledProtocols(PRODUCTION_PROTOCOLS);
            final List<String> enabled = Arrays.asList(socket.getEnabledProtocols());

            assertTrue("TLS 1.2 is the floor the manual requires", enabled.contains(TLS12));
            assertTrue("\"ou superior\" permits 1.3", enabled.contains(TLS13));
            assertFalse("TLS 1.1 is below the manual's floor", enabled.contains("TLSv1.1"));
            assertFalse("TLS 1.0 is below the manual's floor", enabled.contains("TLSv1"));
            assertEquals("nothing beyond the pinned pair may be offered", 2, enabled.size());
        }
    }
}
