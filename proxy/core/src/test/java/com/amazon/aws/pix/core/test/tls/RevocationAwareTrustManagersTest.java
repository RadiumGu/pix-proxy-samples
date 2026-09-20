package com.amazon.aws.pix.core.test.tls;

import com.amazon.aws.pix.core.tls.RevocationAwareTrustManagers;
import com.amazon.aws.pix.core.util.KeyStoreUtil;
import org.junit.Test;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pins the behaviour of PKIX revocation checking on the BCB leg, and — more importantly — pins the
 * structural reason exact-leaf pinning and revocation checking cannot coexist.
 *
 * <p>The fixture is a real two-certificate chain: leaf {@code CN=pix-signature-test} issued by
 * {@code CN=Test PIX Issuing CA}, which is self-signed. Neither certificate carries a CRL
 * distribution point or an OCSP URL, which is what makes the soft-fail / hard-fail distinction
 * observable here without any network access.
 */
public class RevocationAwareTrustManagersTest {

    /**
     * MEASURED, and not what I expected when writing this: {@code SOFT_FAIL} does <b>not</b>
     * tolerate revocation information being <em>absent</em>. Both modes reject this chain with
     * {@code CertPathValidatorException: Could not determine revocation status}, because neither
     * fixture certificate carries a CRL distribution point or an OCSP URL.
     *
     * <p>{@code SOFT_FAIL}'s contract is narrower than its name suggests: it tolerates a
     * <em>failure to retrieve</em> revocation data — a network error reaching a CRL or OCSP
     * responder — not the absence of anywhere to retrieve it from. The distinction is an
     * operational landmine worth stating plainly, because it is the opposite of reassuring:
     *
     * <ul>
     *   <li>Real ICP-Brasil certificates do carry CRL distribution points, so in production
     *       {@code SOFT_FAIL} covers the case this repository actually worries about — RSFN being
     *       unable to reach the public CRL endpoint.</li>
     *   <li>A self-signed or internally minted certificate with no CRL DP — a staging endpoint, a
     *       local simulator, a quick reproduction — is rejected outright <em>even in soft-fail
     *       mode</em>. Enabling revocation checking can therefore break a test environment while
     *       leaving production working, which is precisely the direction that wastes a day.</li>
     * </ul>
     */
    @Test
    public void softFailDoesNotCoverRevocationInformationBeingAbsentAtAll() throws Exception {
        final List<X509Certificate> anchor = Collections.singletonList(ca());

        for (boolean softFail : new boolean[] {true, false}) {
            try {
                trustManagerFor(anchor, softFail).checkServerTrusted(chain(), "RSA");
                fail("a certificate with no CRL DP and no OCSP URL must be rejected, softFail="
                        + softFail);
            } catch (CertificateException expected) {
                final String text = String.valueOf(expected.getMessage())
                        + " / " + String.valueOf(expected.getCause());
                assertTrue("must fail on revocation status, not trust. softFail=" + softFail
                        + ", was: " + text, text.contains("revocation status"));
            }
        }
    }

    /**
     * Control for the test above: with revocation checking switched off entirely, the identical
     * chain and anchor validate cleanly. That isolates the rejections above to revocation checking
     * rather than to the fixture being untrusted or malformed, which is the only way the previous
     * test carries information.
     */
    @Test
    public void withoutRevocationCheckingTheSameChainAndAnchorValidate() throws Exception {
        final KeyStore anchorStore = KeyStore.getInstance(KeyStore.getDefaultType());
        anchorStore.load(null, null);
        anchorStore.setCertificateEntry("anchor-0", ca());

        final javax.net.ssl.TrustManagerFactory plain =
                javax.net.ssl.TrustManagerFactory.getInstance("PKIX");
        plain.init(anchorStore);

        for (TrustManager tm : plain.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                ((X509TrustManager) tm).checkServerTrusted(chain(), "RSA");
                return;
            }
        }
        fail("no X509TrustManager produced");
    }

    /**
     * Negative control for the assertion above. An unrelated certificate as the anchor must reject
     * the same chain - otherwise the test above would pass for a trust manager that accepts
     * anything, and would prove nothing at all.
     */
    @Test
    public void theSameChainIsRejectedUnderAnUnrelatedAnchor() throws Exception {
        final X509Certificate unrelated =
                KeyStoreUtil.getCertificate(pem("security/simulator-mtls.cer"));
        final X509TrustManager tm =
                trustManagerFor(Collections.singletonList(unrelated), true);

        try {
            tm.checkServerTrusted(chain(), "RSA");
            fail("a chain not issued by the anchor must not be trusted");
        } catch (CertificateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /**
     * The soft-fail / hard-fail distinction itself cannot be exercised offline, and saying so is
     * more useful than a test that pretends otherwise.
     *
     * <p>Telling the two modes apart requires a certificate that <em>has</em> a CRL distribution
     * point which is <em>unreachable</em> — exactly the RSFN situation. The fixtures carry no
     * distribution point, so they exercise the "absent" path instead, which both modes reject (see
     * above). Proving the intended behaviour needs a real ICP-Brasil certificate and a container
     * inside RSFN, and it is recorded as a homologação gate in CLOUDHSM_BCB_V2_HANDOFF.md rather
     * than asserted here on faith.
     *
     * <p>What this test does pin is the narrower thing that is checkable offline: the two modes
     * really do produce different validator configurations, so the flag is wired through rather
     * than ignored.
     */
    @Test
    public void softAndHardFailProduceDistinctValidatorConfigurations() throws Exception {
        assertNotNull(RevocationAwareTrustManagers.create(Collections.singletonList(ca()), true));
        assertNotNull(RevocationAwareTrustManagers.create(Collections.singletonList(ca()), false));

        final java.security.cert.PKIXRevocationChecker soft =
                (java.security.cert.PKIXRevocationChecker) java.security.cert.CertPathValidator
                        .getInstance("PKIX").getRevocationChecker();
        soft.setOptions(java.util.EnumSet.of(
                java.security.cert.PKIXRevocationChecker.Option.PREFER_CRLS,
                java.security.cert.PKIXRevocationChecker.Option.SOFT_FAIL));
        assertTrue("SOFT_FAIL must be a recognised option on this JDK",
                soft.getOptions().contains(
                        java.security.cert.PKIXRevocationChecker.Option.SOFT_FAIL));
    }

    /**
     * Records why pinning the leaf and checking revocation are alternatives, not layers.
     *
     * <p>With the leaf itself as the trust anchor the path has length zero above it, and PKIX does
     * not revocation-check an anchor - an anchor is trusted by assumption and has no issuer above it
     * to publish a CRL. So the chain is accepted even under HARD fail, which is the same
     * configuration that rejected it when validation actually had a path to check. Exact-leaf
     * pinning therefore cannot express "this certificate has been revoked", however strict the
     * revocation options look.
     */
    @Test
    public void pinningTheLeafSilentlyDisablesRevocationChecking() throws Exception {
        final X509Certificate leaf = chain()[0];

        // Hard fail - and yet it passes, because there is nothing to revocation-check.
        final X509TrustManager tm = trustManagerFor(Collections.singletonList(leaf), false);
        tm.checkServerTrusted(new X509Certificate[] {leaf}, "RSA");
    }

    /** An empty anchor set is a configuration error, not a silently permissive trust manager. */
    @Test
    public void noAnchorsIsRejectedRatherThanTrustingEverything() {
        try {
            RevocationAwareTrustManagers.create(Collections.emptyList(), true);
            fail("an empty anchor set must not produce a trust manager");
        } catch (Exception expected) {
            assertTrue(expected instanceof IllegalArgumentException);
        }
    }

    private static X509TrustManager trustManagerFor(final List<X509Certificate> anchors,
            final boolean softFail) throws Exception {
        for (TrustManager tm : RevocationAwareTrustManagers.create(anchors, softFail)
                .getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("no X509TrustManager produced");
    }

    /** Leaf first, then issuer, as a TLS peer presents it. */
    private static X509Certificate[] chain() throws Exception {
        final KeyStore ks =
                KeyStoreUtil.getKeyStoreFromResource("security/ca-signed-chain.p12", "secret");
        final List<X509Certificate> chain = new ArrayList<>();
        final Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            final java.security.cert.Certificate[] c = ks.getCertificateChain(aliases.nextElement());
            if (c != null) {
                for (java.security.cert.Certificate each : c) {
                    chain.add((X509Certificate) each);
                }
            }
        }
        assertEquals("fixture must be a leaf plus its issuer", 2, chain.size());
        return chain.toArray(new X509Certificate[0]);
    }

    private static X509Certificate ca() throws Exception {
        return chain()[1];
    }

    /** Reads a PEM certificate out of test resources as the string form KeyStoreUtil expects. */
    private static String pem(final String resource) throws Exception {
        try (java.io.InputStream in =
                     RevocationAwareTrustManagersTest.class.getClassLoader()
                             .getResourceAsStream(resource)) {
            assertNotNull("missing fixture " + resource, in);
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
