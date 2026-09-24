package com.amazon.aws.pix.core.tls;

import org.junit.BeforeClass;
import org.junit.Test;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Proves {@link HsmX509KeyManager} does the one thing a keystore cannot: serve a client certificate
 * whose private key refuses to be exported.
 *
 * <h2>How this test can run without an HSM, and why it is still meaningful</h2>
 *
 * <p>What makes an HSM key special to JSSE is not the hardware, it is that
 * {@link PrivateKey#getEncoded()} returns {@code null}. That was measured on real CloudHSM hardware:
 * {@code CloudHsmRsaPrivateCrtKey.getEncoded()} is {@code null}, while the key still signs. So the
 * behaviour under test is reproduced exactly by wrapping an ordinary RSA key in a delegate that
 * returns {@code null} from {@code getEncoded()} and forwards everything else.
 *
 * <p>That is a faithful stand-in rather than a convenient one, and the NEGATIVE CONTROLS are what
 * make it so: the same wrapped key is shown to be REJECTED by a {@code JKS} keystore with the exact
 * exception measured on hardware, and shown to produce a silent no-client-certificate handshake when
 * the chain is missing. If the wrapper were not behaving like an HSM key, those controls would pass
 * instead of failing.
 */
public class HsmX509KeyManagerTest {

    private static final char[] PASSWORD = "secret".toCharArray();
    private static final int HANDSHAKE_TIMEOUT_MS = 20_000;

    private static PrivateKey realKey;
    private static X509Certificate[] realChain;
    private static KeyStore trustStore;

    /**
     * An ordinary key made to behave like an HSM handle: it signs, its modulus is readable, and it
     * will not be exported.
     *
     * <p>It implements {@link java.security.interfaces.RSAPrivateKey} because the real thing does -
     * {@code CloudHsmRsaPrivateCrtKey} implements the RSA interfaces - and because JSSE needs the
     * MODULUS to choose a CertificateVerify signature scheme. A first version of this stand-in was a
     * bare {@code PrivateKey} and the handshake failed with
     * {@code No supported CertificateVerify signature algorithm for RSA key}: the stand-in was less
     * capable than an HSM key, not more, so the test was failing for the wrong reason.
     *
     * <p><b>Where this stand-in deliberately diverges from an HSM, and why that is the right trade.</b>
     * {@code getPrivateExponent()} delegates to the real key here, whereas a real HSM would refuse.
     * An HSM can refuse because it ships its own JCE provider that performs the signature inside the
     * device; in this test there is no such provider, so {@code SunRsaSign} must be able to sign or no
     * handshake can happen at all. Two earlier versions of this stand-in show why that matters: a bare
     * {@code PrivateKey} failed with {@code No supported CertificateVerify signature algorithm for RSA
     * key} because JSSE could not read the modulus, and a version that threw from
     * {@code getPrivateExponent()} failed with {@code Unsupported handshake message: finished} because
     * nothing could produce the signature.
     *
     * <p>The property the class under test actually depends on is {@code getEncoded() == null} - that is
     * what a keystore cannot swallow and what JSSE does not need - and that property is preserved
     * exactly, with {@link #controlAKeystoreRejectsTheSameKey()} proving it is still in force.
     */
    private static final class NonExportableKey
            implements java.security.interfaces.RSAPrivateKey {
        private static final long serialVersionUID = 1L;
        private final java.security.interfaces.RSAPrivateKey delegate;

        NonExportableKey(final PrivateKey delegate) {
            this.delegate = (java.security.interfaces.RSAPrivateKey) delegate;
        }

        @Override
        public String getAlgorithm() {
            return delegate.getAlgorithm();
        }

        /** HSM keys have no transfer format; this is the property that breaks keystores. */
        @Override
        public String getFormat() {
            return null;
        }

        /** Measured on CloudHSM hardware: null. This is the property under test. */
        @Override
        public byte[] getEncoded() {
            return null;
        }

        /** Public information; a real HSM exposes it, and JSSE needs it to pick a signature scheme. */
        @Override
        public java.math.BigInteger getModulus() {
            return delegate.getModulus();
        }

        /** See the class comment: the one place this stand-in is more permissive than an HSM. */
        @Override
        public java.math.BigInteger getPrivateExponent() {
            return delegate.getPrivateExponent();
        }
    }

    @BeforeClass
    public static void loadKeyMaterial() throws Exception {
        final KeyStore ks = KeyStore.getInstance("JKS");
        try (InputStream in = HsmX509KeyManagerTest.class
                .getResourceAsStream("/security/client.jks")) {
            assertNotNull("test fixture /security/client.jks must exist", in);
            ks.load(in, PASSWORD);
        }
        final String alias = ks.aliases().nextElement();
        realKey = (PrivateKey) ks.getKey(alias, PASSWORD);
        assertNotNull("fixture must contain a private key", realKey);
        final java.security.cert.Certificate[] c = ks.getCertificateChain(alias);
        assertNotNull("fixture must contain a certificate chain", c);
        realChain = new X509Certificate[c.length];
        for (int i = 0; i < c.length; i++) {
            realChain[i] = (X509Certificate) c[i];
        }

        trustStore = KeyStore.getInstance("JKS");
        trustStore.load(null, null);
        for (int i = 0; i < realChain.length; i++) {
            trustStore.setCertificateEntry("fixture-" + i, realChain[i]);
        }
    }

    // ---------------------------------------------------------------- the contract

    @Test
    public void servesItsAliasKeyAndChain() {
        final PrivateKey hsmLike = new NonExportableKey(realKey);
        final HsmX509KeyManager km = new HsmX509KeyManager(hsmLike, realChain);

        assertArrayEquals(new String[]{HsmX509KeyManager.DEFAULT_ALIAS},
                km.getClientAliases("RSA", null));
        assertEquals(HsmX509KeyManager.DEFAULT_ALIAS,
                km.chooseClientAlias(new String[]{"RSA"}, null, null));
        assertSame("the HSM handle itself must be handed to JSSE, not a copy",
                hsmLike, km.getPrivateKey(HsmX509KeyManager.DEFAULT_ALIAS));
        assertArrayEquals(realChain, km.getCertificateChain(HsmX509KeyManager.DEFAULT_ALIAS));
    }

    @Test
    public void returnsNothingForAnUnknownAlias() {
        final HsmX509KeyManager km = new HsmX509KeyManager(new NonExportableKey(realKey), realChain);
        assertNull(km.getPrivateKey("not-this-one"));
        assertNull(km.getCertificateChain("not-this-one"));
    }

    @Test
    public void isClientSideOnly() {
        final HsmX509KeyManager km = new HsmX509KeyManager(new NonExportableKey(realKey), realChain);
        assertNull(km.getServerAliases("RSA", null));
        assertNull(km.chooseServerAlias("RSA", null, null));
    }

    @Test
    public void neverAsksTheKeyForItsBytes() {
        // A key that throws if anyone tries to export it. If the manager touched getEncoded() on the
        // hot path this would fail - and an HSM key would have returned null rather than throwing,
        // hiding the mistake until a handshake silently lost its client certificate.
        final PrivateKey hostile = new PrivateKey() {
            private static final long serialVersionUID = 1L;

            @Override
            public String getAlgorithm() {
                return "RSA";
            }

            @Override
            public String getFormat() {
                return null;
            }

            @Override
            public byte[] getEncoded() {
                throw new UnsupportedOperationException("key material must never be requested");
            }
        };
        final HsmX509KeyManager km = new HsmX509KeyManager(hostile, realChain);
        assertSame(hostile, km.getPrivateKey(HsmX509KeyManager.DEFAULT_ALIAS));
        assertNotNull(km.getCertificateChain(HsmX509KeyManager.DEFAULT_ALIAS));
        assertEquals(HsmX509KeyManager.DEFAULT_ALIAS,
                km.chooseClientAlias(new String[]{"RSA"}, null, null));
    }

    // ---------------------------------------------------------------- the real thing

    @Test
    public void completesARealHandshakeAndSendsTheClientCertificate() throws Exception {
        final PrivateKey hsmLike = new NonExportableKey(realKey);
        final int sent = handshakeClientCertCount(
                new KeyManager[]{new HsmX509KeyManager(hsmLike, realChain)});
        assertEquals("the server must receive exactly one client certificate", 1, sent);
    }

    // ---------------------------------------------------------------- negative controls

    /**
     * CONTROL: the same key in a JKS fails, with the exception measured on CloudHSM hardware. Without
     * this, the test above would not show that {@link HsmX509KeyManager} solves anything - an ordinary
     * key in an ordinary keystore would have worked just as well.
     */
    @Test
    public void controlAKeystoreRejectsTheSameKey() throws Exception {
        final KeyStore jks = KeyStore.getInstance("JKS");
        jks.load(null, null);
        try {
            jks.setKeyEntry("mtls", new NonExportableKey(realKey), PASSWORD, realChain);
            fail("a JKS must refuse a key it cannot serialise; if this passes, the stand-in is not "
                    + "behaving like an HSM key and the rest of this test proves nothing");
        } catch (KeyStoreException expected) {
            assertTrue("must fail for the measured reason, not some other KeyStoreException: "
                            + expected.getMessage(),
                    expected.getMessage() != null
                            && expected.getMessage().contains("Cannot get key bytes"));
        }
    }

    /**
     * CONTROL: a credential with no certificate chain produces a handshake that SUCCEEDS while sending
     * no client certificate - the silent mTLS downgrade measured against the CloudHSM keystore. The
     * constructor refuses that state, and this control is what shows the refusal is worth having.
     */
    @Test
    public void controlAnEmptyChainIsRefusedAtConstruction() {
        try {
            new HsmX509KeyManager(new NonExportableKey(realKey), new X509Certificate[0]);
            fail("an empty chain must be refused: it would downgrade mTLS silently");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("empty"));
        }
    }

    /**
     * CONTROL: with no key manager at all the handshake still completes and zero client certificates
     * arrive. This is the shape of the silent failure, and it proves the assertion in
     * {@link #completesARealHandshakeAndSendsTheClientCertificate()} is measuring something real
     * rather than counting a certificate the server would have seen regardless.
     */
    @Test
    public void controlNoKeyManagerMeansNoClientCertificate() throws Exception {
        assertEquals("a client with no credential must send nothing",
                0, handshakeClientCertCount(null));
    }

    // ---------------------------------------------------------------- harness

    /**
     * Runs one real TLS handshake against a local server that REQUESTS a client certificate, and
     * returns how many the server received. The server requests rather than requires, so the
     * no-credential control can complete and be counted instead of throwing.
     */
    private int handshakeClientCertCount(final KeyManager[] clientKeyManagers) throws Exception {
        final KeyManagerFactory serverKmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        final KeyStore serverKs = KeyStore.getInstance("JKS");
        try (InputStream in = getClass().getResourceAsStream("/security/client.jks")) {
            serverKs.load(in, PASSWORD);
        }
        serverKmf.init(serverKs, PASSWORD);

        final TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        final SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(serverKmf.getKeyManagers(), tmf.getTrustManagers(), null);

        // The client trusts the fixture outright: this test is about the CLIENT credential, and
        // server-path validation is RevocationAwareTrustManagers' job, tested separately.
        final TrustManager[] trustAll = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(final X509Certificate[] c, final String a) {
            }

            @Override
            public void checkServerTrusted(final X509Certificate[] c, final String a) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        final SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(clientKeyManagers, trustAll, null);

        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try (SSLServerSocket server =
                     (SSLServerSocket) serverCtx.getServerSocketFactory().createServerSocket(0)) {
            server.setWantClientAuth(true);
            server.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            final int port = server.getLocalPort();

            final Future<Integer> accepted = pool.submit((Callable<Integer>) () -> {
                try (SSLSocket s = (SSLSocket) server.accept()) {
                    s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                    s.startHandshake();
                    try {
                        final java.security.cert.Certificate[] peer =
                                s.getSession().getPeerCertificates();
                        return peer == null ? 0 : peer.length;
                    } catch (javax.net.ssl.SSLPeerUnverifiedException none) {
                        return 0;   // the client sent nothing - the silent-failure shape
                    }
                }
            });

            try (SSLSocket client =
                         (SSLSocket) clientCtx.getSocketFactory().createSocket("127.0.0.1", port)) {
                client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                client.startHandshake();
                client.getOutputStream().write('x');
                client.getOutputStream().flush();
            }
            return accepted.get(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
