package com.amazon.aws.pix.proxy.test.tls;

import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.PrivateKey;

/**
 * Evidence for homologação gate 7.1: <strong>why the mTLS private key currently has to be
 * extractable</strong>.
 *
 * <p>The README states the reason as "Cavium has JCE, but not JSSE". That is an architectural
 * statement and it was true of CloudHSM Client SDK 3, but it is not the mechanism that actually
 * fails, and the difference matters because the two point at different remedies. This test pins the
 * real mechanism.
 *
 * <p>The production route builds its BCB leg as
 * {@code SslContextBuilder.forClient().sslProvider(SslProvider.OPENSSL).keyManager(privateKey, certs)}.
 * With the OPENSSL provider Netty must hand the key to a native TLS library, which it does by
 * PEM-encoding it. Verified against {@code netty-handler-4.1.49.Final} bytecode:
 * {@code PemPrivateKey.toPEM(ByteBufAllocator, boolean, PrivateKey)} calls
 * {@code PrivateKey.getEncoded()}, throws {@code IllegalArgumentException} when it is {@code null},
 * and its <em>only</em> caller is {@code ReferenceCountedOpenSslContext} — the OPENSSL path itself.
 *
 * <p>Per the JCA contract a key whose material cannot leave its device returns {@code null} from
 * {@code getEncoded()}. So a non-extractable HSM key cannot traverse this API. That is the whole
 * gate, and it is a property of <em>this Netty API</em> rather than of HSMs in general — which is
 * why remedies exist that do not require waiting for a JSSE integration.
 *
 * <h2>What this test is and is not</h2>
 *
 * <p><strong>Is</strong>: a runnable demonstration that the code path the production route uses
 * rejects a key with no extractable material, using a stub that mimics the one property of an
 * HSM key that matters here.
 *
 * <p><strong>Is not</strong>: a test against a real CloudHSM key. That needs a cluster, and it also
 * needs the {@code linux-x86_64-fedora} tcnative artifact this repository pins, so it cannot run in
 * CI or on any non-x86_64 machine. Confirming that a real Cavium key returns {@code null} here
 * remains a homologação-time check — see {@code CLOUDHSM_BCB_V2_HANDOFF.md} section 7.1.
 */
public class MtlsNonExtractableKeyTest {

    /**
     * Stands in for a CloudHSM key generated <em>without</em> {@code -nex}, i.e. with
     * {@code OBJ_ATTR_EXTRACTABLE = 0}. The JCA contract for such a key is a {@code null} encoding
     * and a {@code null} format; nothing else about the key is relevant to this code path.
     */
    private static final class NonExtractableKey implements PrivateKey {
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
            return null;
        }
    }

    private static Method toPemForPrivateKey() throws Exception {
        final Class<?> pemPrivateKey = Class.forName("io.netty.handler.ssl.PemPrivateKey");
        for (Method candidate : pemPrivateKey.getDeclaredMethods()) {
            if ("toPEM".equals(candidate.getName())
                    && candidate.getParameterCount() == 3
                    && PrivateKey.class.isAssignableFrom(candidate.getParameterTypes()[2])) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        throw new AssertionError(
                "netty's PemPrivateKey.toPEM(ByteBufAllocator, boolean, PrivateKey) is gone. If "
                        + "Netty changed how the OPENSSL provider encodes keys, gate 7.1's stated "
                        + "mechanism must be re-established before this test is deleted.");
    }

    @Test
    public void openSslKeyPathRejectsAKeyWithNoExtractableMaterial() throws Exception {
        final Method toPem = toPemForPrivateKey();

        try {
            toPem.invoke(null, UnpooledByteBufAllocator.DEFAULT, true, new NonExtractableKey());
            Assert.fail("Netty accepted a key whose getEncoded() is null. If this ever passes, the "
                    + "reason gate 7.1 gives for needing an extractable mTLS key no longer holds "
                    + "and the gate must be re-examined - this would be good news.");
        } catch (InvocationTargetException e) {
            Assert.assertTrue(
                    "expected an IllegalArgumentException from the key-encoding step, got: "
                            + e.getCause(),
                    e.getCause() instanceof IllegalArgumentException);
            Assert.assertTrue(
                    "the failure should name encoding as the problem, so it is not mistaken for a "
                            + "handshake or certificate fault; got: " + e.getCause().getMessage(),
                    e.getCause().getMessage().contains("does not support encoding"));
        }
    }

    /**
     * An extractable key traverses the same call without complaint, which is the control: the
     * rejection above is caused by the missing material and not by the stub being a stub.
     */
    @Test
    public void theSamePathAcceptsAnExtractableKey() throws Exception {
        final Method toPem = toPemForPrivateKey();
        final PrivateKey extractable =
                java.security.KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate();

        Assert.assertNotNull("a software key must expose its encoding", extractable.getEncoded());
        final Object pem =
                toPem.invoke(null, UnpooledByteBufAllocator.DEFAULT, true, extractable);
        Assert.assertNotNull("an extractable key must PEM-encode successfully", pem);
    }

    /**
     * Records why remedy "path A" (offloading the handshake's private-key operation to the HSM via
     * Netty's own callback) is not a configuration change in this repository.
     *
     * <p>Netty exposes {@code OpenSslContextOption.PRIVATE_KEY_METHOD} for exactly this, and its
     * javadoc restricts it to BoringSSL. Two things block it here: this repository pins the
     * {@code linux-x86_64-fedora} tcnative artifact, which is the OpenSSL variant rather than
     * BoringSSL; and, measured by this test, {@code OpenSslContextOption} does not exist at all in
     * {@code netty-handler-4.1.49.Final} — it arrived in a later 4.1.x. So path A needs a Netty
     * upgrade as well as a native-library swap, on a Quarkus 1.7.0 / Camel-Quarkus 1.0.0 stack from
     * 2020. That cost belongs in the gate rather than being discovered during implementation.
     */
    @Test
    public void openSslContextOptionIsAbsentSoThePrivateKeyCallbackIsNotAvailableHere() {
        try {
            Class.forName("io.netty.handler.ssl.OpenSslContextOption");
            Assert.fail("OpenSslContextOption now exists, so Netty was upgraded. Re-assess gate "
                    + "7.1 path A: the private-key callback may now be reachable, though it still "
                    + "requires the BoringSSL tcnative variant.");
        } catch (ClassNotFoundException expected) {
            // The documented state: the callback API is not on this classpath.
        }
    }
}
