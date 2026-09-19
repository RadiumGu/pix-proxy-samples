package com.amazon.aws.pix.core.test.util;

import com.amazon.aws.pix.core.util.KeyStoreUtil;
import com.amazon.aws.pix.core.util.WellKnownTestCertificates;
import lombok.SneakyThrows;
import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Collection;

/**
 * The simulator certificates committed under {@code proxy/test/src/main/docker/ssl} must be
 * recognised, because both READMEs route them into the same SSM parameters production uses and a
 * leftover test value means trusting a certificate whose private key is public.
 */
public class WellKnownTestCertificatesTest {

    /** Both simulator certificates must be detected. */
    @Test
    @SneakyThrows
    public void detectsBothSimulatorCertificates() {
        for (String resource : new String[]{"security/simulator-sig.cer", "security/simulator-mtls.cer"}) {
            Collection<X509Certificate> certificates = KeyStoreUtil.getCertificates(read(resource));
            Assert.assertEquals("expected exactly one certificate in " + resource, 1, certificates.size());

            X509Certificate certificate = certificates.iterator().next();
            Assert.assertTrue(
                    resource + " is a simulator certificate whose private key is committed next to it, "
                            + "so it must be recognised; if the fingerprint list drifts from the shipped "
                            + "files this assertion is the only thing that notices",
                    WellKnownTestCertificates.isWellKnownTestCertificate(certificate));
        }
    }

    /**
     * Negative control: an unrelated certificate must NOT be flagged, or the guard would cry wolf on
     * every real BACEN certificate and get ignored.
     */
    @Test
    @SneakyThrows
    public void doesNotFlagAnUnrelatedCertificate() {
        X509Certificate unrelated = (X509Certificate) KeyStoreUtil
                .getKeyStoreFromResource("security/client.jks", "secret")
                .getCertificate("client");

        Assert.assertNotNull("client.jks fixture is missing its certificate", unrelated);
        Assert.assertFalse(
                "a certificate unrelated to the simulator was flagged - the guard would fire on real "
                        + "BACEN certificates and be tuned out",
                WellKnownTestCertificates.isWellKnownTestCertificate(unrelated));
    }

    /** Null must be tolerated: the guard is advisory and must never break certificate loading. */
    @Test
    public void toleratesNullInputs() {
        Assert.assertFalse(WellKnownTestCertificates.isWellKnownTestCertificate(null));
        WellKnownTestCertificates.warnIfWellKnownTestCertificate(null);
    }

    @SneakyThrows
    private String read(String resource) {
        return IOUtils.toString(
                Thread.currentThread().getContextClassLoader().getResourceAsStream(resource),
                StandardCharsets.UTF_8);
    }
}
