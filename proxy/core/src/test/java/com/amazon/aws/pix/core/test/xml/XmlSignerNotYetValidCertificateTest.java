package com.amazon.aws.pix.core.test.xml;

import com.amazon.aws.pix.core.util.KeyStoreUtil;
import com.amazon.aws.pix.core.xml.XmlSigner;
import lombok.SneakyThrows;
import org.junit.Assert;
import org.junit.Test;

import java.security.KeyStore;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Certificate <strong>rotation</strong> boundary: a trusted certificate whose validity period has
 * not started yet.
 *
 * <p>This is the other half of the fix for upstream issue #19, and it was the untested half.
 * {@code XmlSigner.findCertificateValidityProblem} walks the cause chain for
 * {@link java.security.cert.CertificateExpiredException} <em>and</em>
 * {@link CertificateNotYetValidException}, but only the expired branch had a test — so the
 * not-yet-valid branch was reachable code with no evidence behind it.
 *
 * <p>It matters operationally, because it is precisely what a botched rotation looks like: a
 * replacement BCB certificate is installed before its {@code notBefore}, or the host clock is
 * behind. The failure must read as "rotate/wait for the certificate", not as "the signature did not
 * match" — the latter looks like an attack and sends whoever is on call in the wrong direction.
 *
 * <p>Needs no HSM, no AWS and no network: the fixture is a self-signed certificate dated 2090.
 */
public class XmlSignerNotYetValidCertificateTest {

    private final XmlSigner signerWithNotYetValidTrustStore;
    private final String signedDocument;

    @SneakyThrows
    public XmlSignerNotYetValidCertificateTest() {
        KeyStore notYetValid =
                KeyStoreUtil.getKeyStoreFromResource("security/not-yet-valid-cert.p12", "secret");
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) notYetValid.getEntry(
                "notyetvalid", new KeyStore.PasswordProtection("secret".toCharArray()));

        // Trust store holds only this certificate, so the KeySelector must resolve to it.
        signerWithNotYetValidTrustStore = new XmlSigner(
                entry.getPrivateKey(), (X509Certificate) entry.getCertificate(), notYetValid);

        // Signing does not check validity, so a not-yet-valid certificate still produces a document.
        signedDocument = signerWithNotYetValidTrustStore.sign(
                "<Envelope><Document><Amount>1000.00</Amount></Document></Envelope>");
    }

    /** The fixture must really be not-yet-valid, or this test proves nothing. */
    @Test
    @SneakyThrows
    public void fixtureCertificateMustNotBeValidYet() {
        KeyStore store = KeyStoreUtil.getKeyStoreFromResource("security/not-yet-valid-cert.p12", "secret");
        X509Certificate certificate = (X509Certificate) store.getCertificate("notyetvalid");

        Assert.assertTrue(
                "fixture notBefore is " + certificate.getNotBefore() + ", which is not in the future",
                certificate.getNotBefore().after(new Date()));
        Assert.assertTrue(
                "fixture notAfter is " + certificate.getNotAfter() + "; it must NOT be expired, "
                        + "otherwise this test would be exercising the expired branch instead",
                certificate.getNotAfter().after(new Date()));
    }

    /**
     * A not-yet-valid trusted certificate must surface as {@code CertificateValidityException} with
     * a {@link CertificateNotYetValidException} cause — distinctly from a signature mismatch.
     */
    @Test
    public void notYetValidTrustedCertificateThrowsWithTheCorrectCause() {
        try {
            boolean result = signerWithNotYetValidTrustStore.verify(signedDocument);
            Assert.fail("expected CertificateValidityException for a not-yet-valid trusted "
                    + "certificate, but verify() returned " + result + " - a rotation problem would "
                    + "again be indistinguishable from a signature mismatch");
        } catch (XmlSigner.CertificateValidityException expected) {
            Assert.assertNotNull("the exception must carry the underlying validity failure as its cause",
                    expected.getCause());
            Assert.assertTrue(
                    "the cause must be CertificateNotYetValidException so operators can tell a "
                            + "premature rotation from an expiry; was " + expected.getCause().getClass(),
                    expected.getCause() instanceof CertificateNotYetValidException);
        }
    }
}
