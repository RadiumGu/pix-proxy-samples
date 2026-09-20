package com.amazon.aws.pix.core.test.tls;

import com.amazon.aws.pix.core.util.KeyStoreUtil;
import org.junit.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetAddress;
import java.security.KeyStore;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Demonstrates why {@code NettyHttpClientInitializerFactory} must set an endpoint identification
 * algorithm, and proves the mechanism actually decides the outcome.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>The outbound client set an SNI server name but never set
 * {@code SSLParameters#setEndpointIdentificationAlgorithm}. Those two are easy to confuse and do
 * unrelated jobs. SNI is a <em>request</em>: it tells the server which name the client wants, as a
 * plaintext hint carried in the ClientHello. Endpoint identification is the <em>check</em>: it
 * compares the certificate the server actually presented against the name that was dialled. With
 * SNI alone, JSSE verifies the chain and then accepts that chain for any hostname whatsoever.
 *
 * <p>Exact-leaf pinning limited the blast radius while it lasted, since exactly one certificate was
 * trusted. That mitigation is not durable: introducing CRL/OCSP revocation checking replaces the
 * pinned leaf with a CA, and from then on every certificate that CA has ever issued would be
 * accepted for the BCB endpoint by a proxy that does not check the name.
 *
 * <h2>Why these two tests are a pair</h2>
 *
 * <p>The fixture's server certificate is {@code CN=pix.aws.com} while the socket is dialled on the
 * loopback address, so the name never matches. The first test asserts the handshake is rejected
 * with the algorithm set. On its own that proves little - a handshake can fail for many reasons,
 * and an untrusted chain would produce a failure that looks similar at a glance. The second test is
 * the negative control: the identical handshake with the algorithm left unset must SUCCEED. Only
 * together do they show that hostname verification, and not some unrelated trust problem, is what
 * turns the outcome.
 */
public class TlsHostnameVerificationTest {

    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;

    /** With the algorithm set, a certificate issued to another name must be refused. */
    @Test
    public void hostnameMismatchIsRejectedWhenEndpointIdentificationIsEnabled() throws Exception {
        try {
            handshakeAgainstLoopback("HTTPS");
            fail("a certificate for CN=pix.aws.com must not be accepted for the loopback address");
        } catch (SSLHandshakeException expected) {
            final String message = String.valueOf(expected.getMessage())
                    + String.valueOf(expected.getCause());
            assertTrue(
                    "the rejection must be about the name, not about trust. Was: " + message,
                    message.contains("No subject alternative")
                            || message.toLowerCase().contains("hostname")
                            || message.toLowerCase().contains("no name matching")
                            || message.contains("doesn't match"));
        }
    }

    /**
     * Negative control. The same handshake, differing only in that the algorithm is left unset,
     * must succeed - which is precisely the vulnerable behaviour the production fix removes.
     */
    @Test
    public void theSameMismatchIsAcceptedWhenEndpointIdentificationIsNotSet() throws Exception {
        final String protocol = handshakeAgainstLoopback(null);
        assertNotNull("without endpoint identification the mismatched name is accepted", protocol);
    }

    /**
     * Runs one client handshake against a loopback TLS server whose certificate is issued to
     * {@code CN=pix.aws.com}.
     *
     * @param endpointIdentificationAlgorithm the algorithm to set on the client, or {@code null} to
     *                                        leave it unset
     * @return the negotiated protocol when the handshake completes
     */
    private String handshakeAgainstLoopback(final String endpointIdentificationAlgorithm)
            throws Exception {
        final SSLContext context = testContext();
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try (SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {

            final int port = server.getLocalPort();
            final Future<?> accepted = pool.submit((Callable<Void>) () -> {
                try (SSLSocket peer = (SSLSocket) server.accept()) {
                    peer.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                    final InputStream in = peer.getInputStream();
                    // Reading drives the handshake. A rejected handshake surfaces here as an
                    // exception, which is the client's business rather than this thread's.
                    while (in.read() >= 0) {
                        // drain
                    }
                } catch (Exception ignored) {
                    // The client side owns every assertion in this test.
                }
                return null;
            });

            try (SSLSocket client = (SSLSocket) context.getSocketFactory()
                    .createSocket(InetAddress.getLoopbackAddress(), port)) {
                client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

                if (endpointIdentificationAlgorithm != null) {
                    final SSLParameters parameters = client.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm(endpointIdentificationAlgorithm);
                    client.setSSLParameters(parameters);
                }

                client.startHandshake();
                return client.getSession().getProtocol();
            } finally {
                // Close the client before waiting on the server: under TLS 1.3 close() sends
                // close_notify and then blocks for the peer's, so tearing these down in the wrong
                // order stalls for the full socket timeout.
                pool.shutdownNow();
                accepted.cancel(true);
                pool.awaitTermination(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    private static SSLContext testContext() throws Exception {
        final KeyStore keyStore =
                KeyStoreUtil.getKeyStoreFromResource("security/client.jks", "secret");

        final KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "secret".toCharArray());

        // The fixture is self-signed, so the same store serves as key and trust material. That
        // matters for this test: the chain is trusted, so any rejection can only come from the
        // name check.
        final TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return context;
    }
}
