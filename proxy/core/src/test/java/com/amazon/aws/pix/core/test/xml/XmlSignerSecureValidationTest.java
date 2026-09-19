package com.amazon.aws.pix.core.test.xml;

import com.amazon.aws.pix.core.util.KeyStoreUtil;
import com.amazon.aws.pix.core.xml.XmlSigner;
import lombok.SneakyThrows;
import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509IssuerSerial;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.xml.sax.InputSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

/**
 * Pins down XML DSig <strong>secure validation</strong> on the verification path.
 *
 * <p><strong>What was actually wrong, stated narrowly.</strong> {@code Iso20022URIDereferencer}
 * reads the {@code org.jcp.xml.dsig.secureValidation} property off the crypto context and forwards
 * it to {@code XMLSignatureInput#setSecureValidation}. Nothing in the project ever SET that
 * property, so the custom dereferencer evaluated it as {@code false} and disabled secure validation
 * on the {@code AppHdr} / {@code Document} node sets it constructs - even though the surrounding
 * JDK had its own checks switched on. {@link XmlSigner} now sets the property explicitly, so the
 * dereferencer's view agrees with the JDK's.
 *
 * <p><strong>What was NOT wrong - measured, so nobody re-derives it as a vulnerability.</strong>
 * On Corretto 11.0.32 a {@code DOMValidateContext} has secure validation ON by default: an incoming
 * {@code <ds:Reference URI="file:...">} is refused whether the property is unset or set to
 * {@code TRUE}. It is accepted only when the property is explicitly set to {@code FALSE}. So this
 * project was never exposed to attacker-chosen {@code file:} / {@code http:} reference
 * dereferencing, and setting the property to {@code TRUE} is a consistency fix, not a patch for an
 * exploitable hole.
 *
 * <p>{@link #explicitlyDisablingSecureValidationWouldAcceptFileUriReference()} is the positive
 * control: it proves these assertions can still fail, and documents exactly what a future change
 * to {@code FALSE} would cost.
 */
public class XmlSignerSecureValidationTest {

    private static final String SECURE_VALIDATION = "org.jcp.xml.dsig.secureValidation";

    private final PrivateKey privateKey;
    private final X509Certificate certificate;
    private final KeyStore keyStore;

    @SneakyThrows
    public XmlSignerSecureValidationTest() {
        keyStore = KeyStoreUtil.getKeyStoreFromResource("security/client.jks", "secret");
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(
                "client", new KeyStore.PasswordProtection("secret".toCharArray()));
        privateKey = entry.getPrivateKey();
        certificate = (X509Certificate) entry.getCertificate();
    }

    /**
     * The property must be present and TRUE on the validate context, because that is the value
     * {@code Iso20022URIDereferencer} reads for the node sets it builds.
     */
    @Test
    @SneakyThrows
    public void validateContextCarriesSecureValidationTrue() {
        ContextCapturingSigner signer = new ContextCapturingSigner(privateKey, certificate, keyStore);
        signer.verify(signer.sign("<Envelope><Document><Amount>1000.00</Amount></Document></Envelope>"));

        Assert.assertNotNull("no validate context was captured - the test no longer exercises verify()",
                signer.captured);
        Assert.assertEquals(
                "Iso20022URIDereferencer reads " + SECURE_VALIDATION + " off this context; if it is not "
                        + "TRUE the dereferencer disables secure validation on the node sets it builds",
                Boolean.TRUE, signer.captured.getProperty(SECURE_VALIDATION));
    }

    /** A signature carrying a {@code file:} URI reference must be refused. */
    @Test
    @SneakyThrows
    public void secureValidationRejectsFileUriReference() {
        String signed = signWithFileUriReference();
        XmlSigner signer = new XmlSigner(privateKey, certificate, keyStore);

        Assert.assertFalse(
                "a signature carrying a file: URI reference was accepted, so verification would read "
                        + "paths chosen by whoever sent the document",
                signer.verify(signed));
    }

    /**
     * Positive control. Explicitly setting the property to {@code FALSE} - the one configuration
     * that actually turns secure validation off - makes the same document verify, with the
     * {@code file:} reference dereferenced. This is what the assertions above are protecting
     * against, and it is why they are not vacuous.
     */
    @Test
    @SneakyThrows
    public void explicitlyDisablingSecureValidationWouldAcceptFileUriReference() {
        String signed = signWithFileUriReference();

        XmlSigner insecure = new XmlSigner(privateKey, certificate, keyStore) {
            @Override
            protected DOMValidateContext getValidateContext(XMLSignatureFactory factory, Node signatureNode) {
                DOMValidateContext context = new DOMValidateContext(this.keySelector, signatureNode);
                context.setProperty(SECURE_VALIDATION, Boolean.FALSE);
                return context;
            }
        };

        Assert.assertTrue(
                "positive control failed: with secure validation explicitly FALSE the file: reference "
                        + "should be dereferenced and the signature should validate. If this stops holding, "
                        + "the sibling assertions can no longer prove anything.",
                insecure.verify(signed));
    }

    /** The happy path must be unaffected. */
    @Test
    @SneakyThrows
    public void normallySignedDocumentStillVerifies() {
        XmlSigner signer = new XmlSigner(privateKey, certificate, keyStore);
        String signed = signer.sign("<Envelope><Document><Amount>1000.00</Amount></Document></Envelope>");
        Assert.assertTrue("secure validation must not reject a legitimately signed document",
                signer.verify(signed));
    }

    /** Exposes the validate context so the property can be asserted. */
    private static class ContextCapturingSigner extends XmlSigner {
        private DOMValidateContext captured;

        ContextCapturingSigner(PrivateKey privateKey, X509Certificate certificate, KeyStore trustStore) {
            super(privateKey, certificate, trustStore);
        }

        @Override
        protected DOMValidateContext getValidateContext(XMLSignatureFactory factory, Node signatureNode) {
            captured = super.getValidateContext(factory, signatureNode);
            return captured;
        }
    }

    /**
     * Signs a document with the usual whole-document reference plus one pointing at a {@code file:}
     * URI on the verifier's own filesystem.
     */
    @SneakyThrows
    private String signWithFileUriReference() {
        File target = new File(getClass().getClassLoader().getResource("xml/test.xml").getFile());

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document document = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(
                "<Envelope><Document><Amount>1000.00</Amount></Document></Envelope>")));

        XMLSignatureFactory factory = XMLSignatureFactory.getInstance();
        KeyInfoFactory keyInfoFactory = factory.getKeyInfoFactory();
        X509IssuerSerial issuerSerial = keyInfoFactory.newX509IssuerSerial(
                certificate.getIssuerX500Principal().getName(), certificate.getSerialNumber());
        KeyInfo keyInfo = keyInfoFactory.newKeyInfo(Collections.singletonList(
                keyInfoFactory.newX509Data(Collections.singletonList(issuerSerial))));

        List<Reference> references = List.of(
                factory.newReference(
                        "",
                        factory.newDigestMethod(DigestMethod.SHA256, null),
                        List.of(factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                                factory.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null)),
                        null, null),
                factory.newReference(target.toURI().toString(),
                        factory.newDigestMethod(DigestMethod.SHA256, null)));

        SignedInfo signedInfo = factory.newSignedInfo(
                factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                references);

        XMLSignature signature = factory.newXMLSignature(signedInfo, keyInfo);
        DOMSignContext signContext = new DOMSignContext(privateKey, document.getDocumentElement());
        signContext.putNamespacePrefix(XMLSignature.XMLNS, "ds");
        signature.sign(signContext);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.transform(new DOMSource(document), new StreamResult(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
