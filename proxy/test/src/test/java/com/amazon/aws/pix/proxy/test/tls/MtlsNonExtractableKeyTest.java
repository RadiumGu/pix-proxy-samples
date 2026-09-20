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
     * Records where remedy "path A" (offloading the handshake's private-key operation to the HSM
     * via Netty's own callback) actually stands, and this changed when the CVE overrides moved
     * Netty from 4.1.49.Final to 4.1.118.Final.
     *
     * <p>On 4.1.49 this test asserted the opposite: {@code OpenSslContextOption} did not exist at
     * all, so path A needed a Netty upgrade *and* a native-library swap. The upgrade has now
     * happened as a side effect of fixing CVE-2021-43797, and this test failed loudly on the bump
     * exactly as it was designed to. Measured on 4.1.118.Final, the API half of path A is present:
     * {@code OpenSslContextOption.PRIVATE_KEY_METHOD} and {@code ASYNC_PRIVATE_KEY_METHOD} both
     * exist, and {@code OpenSslPrivateKeyMethod} is the interface an HSM-backed signer would
     * implement.
     *
     * <p>One blocker remains, and it is not an API one: {@code PRIVATE_KEY_METHOD} is restricted to
     * BoringSSL, while this repository pins the {@code linux-x86_64-fedora} tcnative artifact,
     * which is the dynamically-linked OpenSSL build. Path A is therefore now a native-library swap
     * rather than a framework upgrade — materially cheaper than before, but still not a
     * configuration change, and still not the recommended remedy. {@code OpenSsl.isBoringSSL()} is
     * package-private so it cannot be asserted from here, and the native library cannot be loaded
     * on an aarch64 build host anyway, so this test deliberately checks only the API surface.
     */
    @Test
    public void openSslPrivateKeyCallbackApiIsNowPresentAfterTheNettyUpgrade() throws Exception {
        final ClassLoader cl = getClass().getClassLoader();

        // initialize=false on purpose. Measured: initializing OpenSslPrivateKeyMethod throws
        // NoClassDefFoundError for io.netty.internal.tcnative.SSLPrivateKeyMethod, because the
        // callback API's own static setup reaches into netty-tcnative-classes. That is a real
        // constraint on path A worth recording - the option is not usable from netty-handler
        // alone - but it is not what this test is asserting, so the lookup avoids triggering it.
        final Class<?> option =
                Class.forName("io.netty.handler.ssl.OpenSslContextOption", false, cl);

        Assert.assertEquals("PRIVATE_KEY_METHOD must be typed as the callback option",
                "io.netty.handler.ssl.OpenSslPrivateKeyMethod",
                genericOptionArgument(option.getField("PRIVATE_KEY_METHOD")));
        Assert.assertNotNull("the async variant exists too",
                option.getField("ASYNC_PRIVATE_KEY_METHOD"));

        final Class<?> method =
                Class.forName("io.netty.handler.ssl.OpenSslPrivateKeyMethod", false, cl);
        Assert.assertTrue("an HSM signer would implement this interface", method.isInterface());
    }

    /** Reads the type argument of an {@code OpenSslContextOption<T>} field without initializing it. */
    private static String genericOptionArgument(final java.lang.reflect.Field field) {
        final java.lang.reflect.Type generic = field.getGenericType();
        Assert.assertTrue("expected a parameterised option field",
                generic instanceof java.lang.reflect.ParameterizedType);
        return ((java.lang.reflect.ParameterizedType) generic).getActualTypeArguments()[0]
                .getTypeName();
    }

    /**
     * Negative control for the assertion above: a class that has never existed in Netty must still
     * be reported as absent. Without this, {@code Class.forName} succeeding for any reason at all
     * would look like evidence that the callback API had arrived.
     */
    @Test
    public void theSameLookupStillReportsAGenuinelyAbsentClass() {
        try {
            Class.forName("io.netty.handler.ssl.OpenSslContextOptionThatDoesNotExist", false,
                    getClass().getClassLoader());
            Assert.fail("a fabricated Netty class must not resolve");
        } catch (ClassNotFoundException expected) {
            // Correct: the lookup above is capable of reporting absence.
        }
    }
}
