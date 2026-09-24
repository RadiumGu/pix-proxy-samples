package com.amazon.aws.pix.core.tls;

import javax.net.ssl.X509KeyManager;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Objects;

/**
 * Serves a client certificate whose private key lives inside an HSM and cannot be exported.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>A PSP's mTLS client key should be non-extractable: that is the property an HSM is bought for. The
 * obvious ways to give such a key to JSSE do not work, and one of them fails <em>silently</em>. Both
 * were measured on real CloudHSM hardware ({@code hsm2m.medium}, FIPS, Client SDK 5.18.0):
 *
 * <ul>
 *   <li>Putting the key in a {@code JKS} with {@code setKeyEntry} throws
 *       {@code KeyStoreException: Cannot get key bytes, not PKCS#8 encoded}. A keystore has to
 *       serialise what it stores, and {@link PrivateKey#getEncoded()} on an HSM handle returns
 *       {@code null} by design.</li>
 *   <li>Handing the CloudHSM keystore to {@code KeyManagerFactory.init} throws <b>nothing</b>, and a
 *       handshake then <b>completes with no client certificate sent</b>. A {@code KeyManager} must
 *       serve a private key <em>and</em> a certificate chain under one alias, and the HSM keystore
 *       holds keys with no chain attached. This is the dangerous one: the only symptom is that the
 *       server never asked twice.</li>
 * </ul>
 *
 * <p>The {@link X509KeyManager} contract asks for a {@link PrivateKey} <em>object</em> and never for
 * its encoded bytes, which is precisely why this works where a keystore cannot. Measured with this
 * shape: {@code clientCertsSent=1}, handshake {@code TLSv1.2} /
 * {@code TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384}, and the server logged the client's subject.
 *
 * <h2>Using it</h2>
 *
 * <pre>{@code
 * KeyStore hsm = KeyStore.getInstance(CloudHsmProvider.PROVIDER_NAME);   // SDK 5
 * hsm.load(null, null);                                                  // implicit login
 * PrivateKey key = (PrivateKey) hsm.getKey("pix-mtls-priv", null);
 *
 * SSLContext ctx = SSLContext.getInstance("TLS");
 * ctx.init(new KeyManager[]{ new HsmX509KeyManager(key, chain) },
 *          trustManagers, null);
 * }</pre>
 *
 * <p><b>The TLS provider must be the JDK one, not OpenSSL.</b> An OpenSSL-backed provider needs the
 * encoded key bytes and an HSM key has none, so {@code SslProvider.OPENSSL} cannot be used with a
 * non-extractable key however the key is supplied. That constraint is independent of this class.
 *
 * <p>This class holds a reference to a key it cannot read and a chain it does not validate beyond
 * emptiness; it is deliberately thin. Certificate path validation belongs to the trust manager, and
 * for the BCB leg that is {@link RevocationAwareTrustManagers}.
 */
public final class HsmX509KeyManager implements X509KeyManager {

    /** The single alias this manager serves. JSSE only needs it to be stable and non-empty. */
    public static final String DEFAULT_ALIAS = "hsm-mtls";

    private final PrivateKey privateKey;
    private final X509Certificate[] chain;
    private final String alias;

    /**
     * @param privateKey the HSM handle. Its {@code getEncoded()} may be {@code null}; that is the
     *                   normal case and the reason this class exists.
     * @param chain      the client certificate followed by any intermediates, leaf first.
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if the chain is empty
     */
    public HsmX509KeyManager(final PrivateKey privateKey, final X509Certificate[] chain) {
        this(privateKey, chain, DEFAULT_ALIAS);
    }

    public HsmX509KeyManager(final PrivateKey privateKey, final X509Certificate[] chain,
                             final String alias) {
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
        Objects.requireNonNull(chain, "chain");
        this.alias = Objects.requireNonNull(alias, "alias");
        if (chain.length == 0) {
            // Rejected at construction on purpose. An empty chain is exactly the state that makes
            // KeyManagerFactory over the CloudHSM keystore fail silently: JSSE finds no credential,
            // sends no client certificate, and the handshake still succeeds. Failing here converts a
            // silent mTLS downgrade into a startup error.
            throw new IllegalArgumentException(
                    "certificate chain is empty: a KeyManager with no chain serves no client "
                            + "certificate, and the handshake would silently proceed without mTLS");
        }
        if (alias.isEmpty()) {
            throw new IllegalArgumentException("alias must not be empty");
        }
        this.chain = chain.clone();
    }

    /** {@inheritDoc} Only ever one alias: this manager wraps exactly one key. */
    @Override
    public String[] getClientAliases(final String keyType, final Principal[] issuers) {
        return new String[]{alias};
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the alias unconditionally rather than filtering on {@code keyType} or {@code issuers}.
     * That is deliberate: the caller configured this manager with the one credential it is supposed to
     * present, and silently declining to present it is the failure mode this class exists to prevent.
     * If the server rejects the certificate, that is a visible handshake failure rather than an
     * invisible downgrade to one-way TLS.
     */
    @Override
    public String chooseClientAlias(final String[] keyType, final Principal[] issuers,
                                    final Socket socket) {
        return alias;
    }

    /** {@inheritDoc} This manager is client-side only. */
    @Override
    public String[] getServerAliases(final String keyType, final Principal[] issuers) {
        return null;
    }

    /** {@inheritDoc} This manager is client-side only. */
    @Override
    public String chooseServerAlias(final String keyType, final Principal[] issuers,
                                    final Socket socket) {
        return null;
    }

    @Override
    public X509Certificate[] getCertificateChain(final String requested) {
        return alias.equals(requested) ? chain.clone() : null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Hands back the HSM handle itself. JSSE will call {@code Signature} on it to produce
     * CertificateVerify; the bytes are never requested, which is what makes a non-extractable key
     * usable here.
     */
    @Override
    public PrivateKey getPrivateKey(final String requested) {
        return alias.equals(requested) ? privateKey : null;
    }

    @Override
    public String toString() {
        return "HsmX509KeyManager{alias=" + alias
                + ", chainLength=" + chain.length
                + ", leafSubject=" + chain[0].getSubjectX500Principal()
                + ", keyExportable=" + (privateKey.getEncoded() != null)
                + ", keyClass=" + privateKey.getClass().getName()
                + ", chainIssuers=" + Arrays.stream(chain)
                        .map(c -> c.getIssuerX500Principal().getName())
                        .reduce((a, b) -> a + "|" + b).orElse("")
                + "}";
    }
}
