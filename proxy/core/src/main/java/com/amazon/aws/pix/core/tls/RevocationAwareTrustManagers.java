package com.amazon.aws.pix.core.tls;

import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.cert.CertPathValidator;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.EnumSet;

/**
 * Builds a {@link TrustManagerFactory} that validates a certificate path with PKIX <em>and</em>
 * checks revocation, which the default trust manager does not do at all.
 *
 * <h2>What this replaces, and the trade being made</h2>
 *
 * <p>The BCB leg's trust material is configured from a single parameter. When that parameter holds
 * the BCB <em>leaf</em> certificate, the result is exact-leaf pinning: extremely narrow trust, and
 * genuinely strong against mis-issuance. It has two costs. It breaks whenever BCB rotates the
 * certificate, and — less obviously — <b>it makes revocation checking impossible</b>. PKIX does not
 * revocation-check a trust anchor, because an anchor is trusted by assumption and there is no
 * issuer above it to publish a CRL. A pinned leaf is its own anchor, so "is this certificate
 * revoked?" is a question the validator is structurally unable to ask. That is why pinning and
 * revocation checking are alternatives rather than layers, and it is asserted as a test.
 *
 * <p>Pointing the same parameter at the ICP-Brasil CA instead makes rotation a non-event and makes
 * revocation checkable, at the cost of trusting everything that CA issues — which is exactly why
 * hostname verification had to be fixed first.
 *
 * <h2>Soft fail is the default, and that is a deliberate, uncomfortable choice</h2>
 *
 * <p>The proxy reaches BCB over RSFN, a private network. ICP-Brasil publishes its CRL and OCSP
 * endpoints on the public internet. Whether an RSFN-attached container can reach them is a
 * deployment fact this repository cannot determine, and both answers are bad if assumed:
 *
 * <ul>
 *   <li>Assume reachable and hard-fail — if it is not reachable, <em>every</em> TLS handshake to
 *       BCB fails and the proxy is down completely.</li>
 *   <li>Assume unreachable and soft-fail silently — revocation is never actually verified, and the
 *       configuration merely looks secure.</li>
 * </ul>
 *
 * <p>So the default is soft fail, and every unverifiable check is reported loudly by the caller
 * rather than passing in silence. Confirming CRL/OCSP reachability from inside RSFN and then
 * switching to hard fail is an explicit homologação gate — see CLOUDHSM_BCB_V2_HANDOFF.md. A
 * default that bricks the connection would simply be disabled wholesale, which is worse than a
 * default that complains.
 */
public final class RevocationAwareTrustManagers {

    private RevocationAwareTrustManagers() {
    }

    /**
     * @param anchors  trust anchors; a CA for revocation checking to be meaningful
     * @param softFail {@code true} to accept a certificate whose revocation status could not be
     *                 determined, {@code false} to reject it
     */
    public static TrustManagerFactory create(final Collection<X509Certificate> anchors,
            final boolean softFail) throws Exception {
        if (anchors == null || anchors.isEmpty()) {
            throw new IllegalArgumentException("at least one trust anchor is required");
        }

        final KeyStore anchorStore = KeyStore.getInstance(KeyStore.getDefaultType());
        anchorStore.load(null, null);
        int i = 0;
        for (X509Certificate anchor : anchors) {
            anchorStore.setCertificateEntry("anchor-" + i++, anchor);
        }

        final PKIXBuilderParameters pkix =
                new PKIXBuilderParameters(anchorStore, new X509CertSelector());

        // Must be false. This switches off the legacy built-in revocation path so that revocation
        // is driven solely by the explicitly configured checker below; leaving it true runs both,
        // and the built-in one does not honour the options set here.
        pkix.setRevocationEnabled(false);

        final PKIXRevocationChecker revocationChecker =
                (PKIXRevocationChecker) CertPathValidator.getInstance("PKIX").getRevocationChecker();

        // PREFER_CRLS with fallback left enabled: try CRLs first, fall back to OCSP. NO_FALLBACK is
        // deliberately NOT set - on a constrained network the second mechanism may be the only one
        // that is reachable.
        final EnumSet<PKIXRevocationChecker.Option> options =
                EnumSet.of(PKIXRevocationChecker.Option.PREFER_CRLS);
        if (softFail) {
            options.add(PKIXRevocationChecker.Option.SOFT_FAIL);
        }
        revocationChecker.setOptions(options);
        pkix.addCertPathChecker(revocationChecker);

        final TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
        final ManagerFactoryParameters parameters =
                new javax.net.ssl.CertPathTrustManagerParameters(pkix);
        factory.init(parameters);
        return factory;
    }
}
