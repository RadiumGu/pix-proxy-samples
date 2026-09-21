package com.amazon.aws.pix.core.test.health;

import com.amazon.aws.pix.core.health.HsmHealthProbe;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers {@code /check} verifying EVERY credential the proxy needs, not just the signing key.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>The probe ran {@code xmlSigner.sign(...)} only. The mTLS client key was fetched once at startup,
 * handed to the SSL context, and never revisited — so if its HSM session died, every BCB handshake
 * failed while {@code /check} kept answering 200 because signing still worked. A load balancer had no
 * reason to replace the container and the outage persisted for as long as the container lived.
 *
 * <p>The verdict also has to name the credential. "Signing failed" sends an operator to the PSP
 * document-signing key, which is a different object with different failure modes, and only one of the
 * two breaks the handshake.
 */
public class HsmHealthProbeMultiCredentialTest {

    private static final Duration NO_CACHE = Duration.ofMillis(0);

    /** A test asserting WHAT is covered. A probe that exists but covers the wrong key is the defect. */
    @Test
    public void bothTheSigningKeyAndTheMtlsKeyAreProbed() {
        final HsmHealthProbe probe = new HsmHealthProbe(() -> { }, NO_CACHE, () -> 0L)
                .add("mtls-client-key", () -> { });

        assertEquals("the probe must cover the signing key AND the mTLS client key; covering only "
                        + "the signer is what let a dead mTLS handle report healthy",
                java.util.Arrays.asList("signing-key", "mtls-client-key"),
                probe.probedCredentials());
    }

    /**
     * The case that used to return 200: signing works, mTLS does not. Every BCB request fails, so the
     * container must be reported unhealthy.
     */
    @Test
    public void aWorkingSignerWithADeadMtlsKeyIsUnhealthy() {
        final AtomicBoolean signerRan = new AtomicBoolean(false);
        final HsmHealthProbe probe = new HsmHealthProbe(() -> signerRan.set(true), NO_CACHE, () -> 0L)
                .add("mtls-client-key", () -> {
                    throw new java.security.KeyException("CloudHSM session closed");
                });

        final HsmHealthProbe.Result result = probe.check();

        assertTrue("the signer must actually have been exercised, or the test proves nothing about "
                + "the combination", signerRan.get());
        assertFalse("a container that can sign but cannot complete mTLS serves no Pix traffic and "
                + "must not report healthy. detail=" + result.getDetail(), result.isHealthy());
        assertTrue("the verdict must NAME the broken credential so an operator does not go looking "
                        + "at the signing key. detail=" + result.getDetail(),
                result.getDetail().contains("mtls-client-key"));
    }

    /**
     * Negative control for the test above: with both credentials working the same probe must report
     * healthy. Without this, "unhealthy" could be the answer for any reason at all.
     */
    @Test
    public void bothCredentialsWorkingIsHealthy() {
        final HsmHealthProbe probe = new HsmHealthProbe(() -> { }, NO_CACHE, () -> 0L)
                .add("mtls-client-key", () -> { });

        final HsmHealthProbe.Result result = probe.check();

        assertTrue("detail=" + result.getDetail(), result.isHealthy());
        assertTrue("the healthy detail should say what was checked. detail=" + result.getDetail(),
                result.getDetail().contains("mtls-client-key"));
    }

    /** A dead signer must still be caught, and must name the signer rather than the mTLS key. */
    @Test
    public void aDeadSignerNamesTheSigningKey() {
        final HsmHealthProbe probe = new HsmHealthProbe(() -> {
            throw new IllegalStateException("provider unloaded");
        }, NO_CACHE, () -> 0L).add("mtls-client-key", () -> { });

        final HsmHealthProbe.Result result = probe.check();

        assertFalse(result.isHealthy());
        assertTrue("detail=" + result.getDetail(), result.getDetail().contains("signing-key"));
    }

    /**
     * Registering a credential must invalidate any cached verdict. Otherwise a container could report
     * the answer computed before the mTLS probe existed, for up to a full TTL — a healthy verdict for
     * a credential nothing had checked.
     */
    @Test
    public void addingACredentialInvalidatesTheCachedVerdict() {
        final AtomicInteger clock = new AtomicInteger(0);
        final HsmHealthProbe probe =
                new HsmHealthProbe(() -> { }, Duration.ofMinutes(10), clock::get);

        assertTrue("precondition: healthy with the signer alone", probe.check().isHealthy());

        probe.add("mtls-client-key", () -> {
            throw new java.security.KeyException("CloudHSM session closed");
        });

        // The clock has NOT moved, so a stale cache would still be inside its TTL.
        assertFalse("the cached healthy verdict predates the mTLS probe and must not be reused",
                probe.check().isHealthy());
    }

    /** A dead credential must not be masked by the Error/Exception split, as elsewhere in this repo. */
    @Test
    public void anErrorFromTheNativeLayerIsAlsoUnhealthy() {
        final HsmHealthProbe probe = new HsmHealthProbe(() -> { }, NO_CACHE, () -> 0L)
                .add("mtls-client-key", () -> {
                    throw new UnsatisfiedLinkError("libcloudhsm gone");
                });

        final HsmHealthProbe.Result result = probe.check();

        assertFalse("an Error from the native layer is still an unhealthy container",
                result.isHealthy());
        assertTrue("detail=" + result.getDetail(),
                result.getDetail().contains("UnsatisfiedLinkError"));
    }
}
