package com.amazon.aws.pix.core.test.health;

import com.amazon.aws.pix.core.health.HsmHealthProbe;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the behaviour of the {@code /check} probe, which previously returned a constant {@code "OK"}.
 *
 * <p>The constant was worse than no probe: a load balancer keeps sending Pix traffic to a container
 * whose HSM session has died, so it looks healthy exactly while it cannot sign anything.
 */
public class HsmHealthProbeTest {

    /** A working signer means healthy. */
    @Test
    public void aSucceedingProbeReportsHealthy() {
        final HsmHealthProbe probe = new HsmHealthProbe(() -> { });

        final HsmHealthProbe.Result result = probe.check();

        assertTrue(result.isHealthy());
        assertTrue(result.getDetail(), result.getDetail().contains("succeeded"));
    }

    /** The case the constant "OK" hid: signing is broken, so the container must be taken out. */
    @Test
    public void aFailingProbeReportsUnhealthyAndSaysWhy() {
        final HsmHealthProbe probe = new HsmHealthProbe(() -> {
            throw new IllegalStateException("session closed");
        });

        final HsmHealthProbe.Result result = probe.check();

        assertFalse("a container that cannot sign must not report healthy", result.isHealthy());
        assertTrue(result.getDetail(), result.getDetail().contains("IllegalStateException"));
        assertTrue(result.getDetail(), result.getDetail().contains("session closed"));
    }

    /**
     * A dead native provider can surface as an Error rather than an Exception. That is still an
     * unhealthy container, and it must not escape as a 500.
     */
    @Test
    public void anErrorFromTheNativeLayerIsAlsoJustUnhealthy() {
        final HsmHealthProbe probe = new HsmHealthProbe(() -> {
            throw new UnsatisfiedLinkError("libcloudhsm gone");
        });

        final HsmHealthProbe.Result result = probe.check();

        assertFalse(result.isHealthy());
        assertTrue(result.getDetail(), result.getDetail().contains("UnsatisfiedLinkError"));
    }

    /** Within the TTL the HSM is spared: repeated polls reuse one answer. */
    @Test
    public void repeatedChecksInsideTheTtlProbeTheHsmOnlyOnce() {
        final AtomicLong now = new AtomicLong(1_000);
        final HsmHealthProbe probe =
                new HsmHealthProbe(() -> { }, Duration.ofSeconds(5), now::get);

        for (int i = 0; i < 20; i++) {
            assertTrue(probe.check().isHealthy());
        }

        assertEquals("20 polls inside the TTL must cost one HSM operation", 1, probe.probeCount());
    }

    /**
     * Negative control for the cache: once the TTL has passed the probe must run again. Without
     * this, a cache that never expired would satisfy the test above while reporting a long-dead
     * HSM as healthy forever.
     */
    @Test
    public void afterTheTtlTheProbeRunsAgain() {
        final AtomicLong now = new AtomicLong(1_000);
        final HsmHealthProbe probe =
                new HsmHealthProbe(() -> { }, Duration.ofSeconds(5), now::get);

        probe.check();
        now.addAndGet(5_001);
        probe.check();

        assertEquals("the answer must be re-derived after the TTL", 2, probe.probeCount());
    }

    /** Recovery must be reported, not latched: a healed HSM has to return to healthy. */
    @Test
    public void aRecoveredHsmIsReportedHealthyAgain() {
        final AtomicBoolean broken = new AtomicBoolean(true);
        final AtomicLong now = new AtomicLong(1_000);
        final HsmHealthProbe probe = new HsmHealthProbe(() -> {
            if (broken.get()) {
                throw new IllegalStateException("session closed");
            }
        }, Duration.ofSeconds(5), now::get);

        assertFalse(probe.check().isHealthy());

        broken.set(false);
        now.addAndGet(5_001);

        assertTrue("a healed HSM must not stay marked unhealthy", probe.check().isHealthy());
    }
}
