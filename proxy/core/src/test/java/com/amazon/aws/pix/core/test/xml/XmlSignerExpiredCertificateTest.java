package com.amazon.aws.pix.core.test.xml;

import com.amazon.aws.pix.core.util.KeyStoreUtil;
import com.amazon.aws.pix.core.xml.XmlSigner;
import lombok.SneakyThrows;
import org.junit.Assert;
import org.junit.Test;

import java.security.KeyStore;
import java.security.cert.X509Certificate;

/**
 * An expired certificate in the trust store makes {@link XmlSigner#verify} <strong>throw</strong>
 * {@link XmlSigner.CertificateValidityException} rather than return {@code false}. That is the
 * intent of the fix for upstream issue #19 - "rotate the certificate" must be distinguishable from
 * "the signature did not match".
 *
 * <p>This test exists to pin the <em>shape</em> of that behaviour, because the shape has a
 * consequence the fix did not account for. In the CloudHSM route
 * ({@code PixCloudHSMProxyRouteBuilder}) the verify step runs BEFORE the audit step, so a throw
 * from here aborts the exchange and the Firehose audit write never executes - losing the audit
 * record for exactly the transactions a compliance reader would most want to see. The fix for
 * upstream issue #18 was specifically about not letting audit problems damage a transaction; this
 * is the same class of problem arriving from the other direction.
 *
 * <p>{@code VerifyResponseProcessor} therefore catches this exception, records it in the audit
 * field and still lets the route reach the audit write. If someone removes that catch, this test
 * keeps documenting why it was there.
 */
public class XmlSignerExpiredCertificateTest {

    private final XmlSigner signerWithExpiredTrustStore;
    private final String signedDocument;

    @SneakyThrows
    public XmlSignerExpiredCertificateTest() {
        KeyStore expired = KeyStoreUtil.getKeyStoreFromResource("security/expired-cert.p12", "secret");
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) expired.getEntry(
                "expired", new KeyStore.PasswordProtection("secret".toCharArray()));

        // The trust store holds only the expired certificate, so the KeySelector is forced to it.
        signerWithExpiredTrustStore = new XmlSigner(
                entry.getPrivateKey(), (X509Certificate) entry.getCertificate(), expired);

        // Signing does not check validity, so an expired certificate still produces a document.
        signedDocument = signerWithExpiredTrustStore.sign(
                "<Envelope><Document><Amount>1000.00</Amount></Document></Envelope>");
    }

    /** The fixture must really be expired, or this whole test proves nothing. */
    @Test
    @SneakyThrows
    public void fixtureCertificateMustBeExpired() {
        KeyStore expired = KeyStoreUtil.getKeyStoreFromResource("security/expired-cert.p12", "secret");
        X509Certificate certificate = (X509Certificate) expired.getCertificate("expired");
        Assert.assertTrue(
                "fixture certificate notAfter is " + certificate.getNotAfter() + ", which is not in the past",
                certificate.getNotAfter().before(new java.util.Date()));
    }

    /**
     * The distinguishing behaviour: a validity problem must surface as its own exception, not as a
     * {@code false} that reads like a signature mismatch.
     */
    @Test
    public void expiredTrustedCertificateThrowsRatherThanReturningFalse() {
        try {
            boolean result = signerWithExpiredTrustStore.verify(signedDocument);
            Assert.fail("expected CertificateValidityException for an expired trusted certificate, "
                    + "but verify() returned " + result + " - an expired certificate would again be "
                    + "indistinguishable from a signature mismatch");
        } catch (XmlSigner.CertificateValidityException expected) {
            Assert.assertNotNull("the exception must carry the underlying validity failure as its cause",
                    expected.getCause());
            Assert.assertTrue(
                    "cause should be a certificate validity failure but was " + expected.getCause().getClass(),
                    expected.getCause() instanceof java.security.cert.CertificateExpiredException);
        }
    }
}
