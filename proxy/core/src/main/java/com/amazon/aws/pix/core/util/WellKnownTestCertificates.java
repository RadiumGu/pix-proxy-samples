package com.amazon.aws.pix.core.util;

import lombok.extern.slf4j.Slf4j;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Set;

/**
 * Detects the BACEN-simulator certificates that ship inside this repository, so that trusting one
 * in a real deployment is loud rather than silent.
 *
 * <p><strong>Why this exists.</strong> {@code proxy/test/src/main/docker/ssl} contains
 * {@code sig.cer} / {@code mtls.cer} <em>together with their RSA 2048 private keys</em>
 * ({@code sig.key}, {@code mtls.key}), and both certificates carry the subject
 * {@code C=BR, ST=DF, L=Brasilia, O=BCB, OU=PIX, CN=*.pi.rsfn.net.br} - BACEN's real production
 * RSFN domain - with a validity period running to 2030-07-03. The private keys are therefore
 * public knowledge for anyone who can read the repository.
 *
 * <p>That is fine for a simulator. What makes it worth a guard is that both READMEs tell you to
 * paste those certificates into the <em>same</em> SSM parameters production uses -
 * {@code BcbSignatureCertificate} and {@code BcbMtlsCertificate} - under "TO USE THE TEST -
 * SIMULATOR, use:". So the test value and the production value live in one place, and the
 * migration from simulator to real BACEN is "remember to change the parameter". If it is not
 * changed, the proxy accepts response signatures produced by a private key published on the
 * internet, and nothing else in the system would notice: the certificate is well-formed, in date
 * until 2030, and matches the trust store by construction, so the certificate-validity check added
 * for upstream issue #19 stays quiet too.
 *
 * <p>This deliberately WARNS rather than refuses to start: the simulator workflow is documented and
 * legitimate, so failing hard would break the path the READMEs tell people to walk. Alarm on this
 * log line in any environment that is supposed to talk to real BACEN.
 */
@Slf4j
public final class WellKnownTestCertificates {

    /**
     * SHA-256 fingerprints of the certificates committed under
     * {@code proxy/test/src/main/docker/ssl}. Fingerprints rather than subject matching: the
     * subject is BACEN's real DN, so matching on it would also flag genuine BACEN certificates.
     */
    private static final Set<String> FINGERPRINTS = Set.of(
            // proxy/test/src/main/docker/ssl/sig.cer  (serial 68B91106)
            "2ECA12B343882DE0AFC3D69B15487BD48D95FF4E42169F04502C8D4C2A6192F3",
            // proxy/test/src/main/docker/ssl/mtls.cer (serial 051E4775)
            "8F43D131CAB86A4FE53308A04C65523B73B7D42B77CD4EC1C1E0148D160DC274");

    private WellKnownTestCertificates() {
    }

    /**
     * @return {@code true} if this is one of the simulator certificates shipped in this repository,
     *         whose private key is public
     */
    public static boolean isWellKnownTestCertificate(X509Certificate certificate) {
        if (certificate == null) {
            return false;
        }
        try {
            return FINGERPRINTS.contains(sha256Fingerprint(certificate));
        } catch (Exception e) {
            // Never let a fingerprinting problem break certificate loading - the guard is
            // advisory, and a certificate that cannot be digested is not one of ours.
            log.debug("could not fingerprint a certificate while checking for simulator material", e);
            return false;
        }
    }

    /** Logs one ERROR line per simulator certificate found. */
    public static void warnIfWellKnownTestCertificate(Collection<X509Certificate> certificates) {
        if (certificates == null) {
            return;
        }
        for (X509Certificate certificate : certificates) {
            if (isWellKnownTestCertificate(certificate)) {
                log.error("TRUSTING A BACEN SIMULATOR CERTIFICATE whose PRIVATE KEY IS PUBLIC: "
                                + "subject={} serial={}. This certificate ships in this repository under "
                                + "proxy/test/src/main/docker/ssl alongside its private key, so anyone can "
                                + "forge a response that this proxy will accept as signed by BACEN. This is "
                                + "expected ONLY when pointing at the simulator. If this process is meant to "
                                + "talk to real BACEN, the BcbSignatureCertificate / BcbMtlsCertificate "
                                + "parameter still holds the test value - replace it now.",
                        certificate.getSubjectX500Principal().getName(),
                        certificate.getSerialNumber().toString(16));
            }
        }
    }

    private static String sha256Fingerprint(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }
}
