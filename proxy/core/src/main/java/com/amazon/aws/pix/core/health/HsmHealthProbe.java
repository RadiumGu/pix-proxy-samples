package com.amazon.aws.pix.core.health;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Liveness probe that answers "can this container still sign?" by actually trying to sign, with the
 * result cached for a short window.
 *
 * <h2>Why a real operation is the only useful probe</h2>
 *
 * <p>The {@code /check} endpoint used to return the constant {@code "OK"}. That is worse than
 * having no probe at all: a load balancer keeps routing Pix traffic to a container whose HSM
 * session has died, and every DICT write it receives fails signing. The container looks healthy
 * precisely while it is useless.
 *
 * <p>Weaker checks do not help either. A non-null keystore, a non-null {@code PrivateKey} or a TCP
 * connection to the HSM can all be true while the session behind them is gone, because a CloudHSM
 * {@code PrivateKey} is a handle whose validity is only discovered when it is used. Performing one
 * signature is what distinguishes a live session from a stale handle.
 *
 * <h2>Why the result is cached</h2>
 *
 * <p>Health checks arrive every few seconds per container, and each probe is a real private-key
 * operation on a device with finite throughput that is also serving production traffic. Caching for
 * a short TTL keeps the probe honest while bounding the load it adds; the cost of the TTL is that
 * an outage is noticed up to {@code ttl} late, which is why the default is deliberately short.
 *
 * <p>This class is intentionally free of any CloudHSM dependency so it can be tested without an
 * HSM: the caller supplies the probe. It is safe for concurrent use.
 */
public class HsmHealthProbe {

    /** Short on purpose: it bounds how late an HSM outage is noticed. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(5);

    /** The operation whose success means "this container can still sign". */
    public interface Probe {
        void run() throws Exception;
    }

    private final Probe probe;
    private final long ttlMillis;
    private final Clock clock;

    private final AtomicReference<Result> cached = new AtomicReference<>(null);
    private final AtomicLong cachedAt = new AtomicLong(0);
    private final AtomicLong probeCount = new AtomicLong(0);

    /** Injectable so the TTL can be tested without sleeping. */
    public interface Clock {
        long millis();
    }

    public HsmHealthProbe(final Probe probe) {
        this(probe, DEFAULT_TTL, System::currentTimeMillis);
    }

    public HsmHealthProbe(final Probe probe, final Duration ttl, final Clock clock) {
        if (probe == null || ttl == null || clock == null) {
            throw new IllegalArgumentException("probe, ttl and clock are all required");
        }
        this.probe = probe;
        this.ttlMillis = ttl.toMillis();
        this.clock = clock;
    }

    /** The outcome of a probe: healthy, or unhealthy with the reason to report. */
    public static final class Result {
        private final boolean healthy;
        private final String detail;

        private Result(final boolean healthy, final String detail) {
            this.healthy = healthy;
            this.detail = detail;
        }

        public boolean isHealthy() {
            return healthy;
        }

        /** Human-readable reason; safe to log, and returned in the unhealthy response body. */
        public String getDetail() {
            return detail;
        }
    }

    /**
     * Returns the current health, re-probing only when the cached answer has aged past the TTL.
     *
     * <p>Never throws: a probe failure is the answer, not an error. Letting it throw would turn the
     * health endpoint into a 500, which many load balancers treat differently from a considered
     * "unhealthy" response.
     */
    public Result check() {
        final long now = clock.millis();
        final Result previous = cached.get();
        if (previous != null && now - cachedAt.get() < ttlMillis) {
            return previous;
        }

        Result fresh;
        try {
            probeCount.incrementAndGet();
            probe.run();
            fresh = new Result(true, "HSM signing operation succeeded");
        } catch (Throwable t) {
            // Throwable, not Exception: a dead JCE provider can surface as an Error - a
            // NoClassDefFoundError or UnsatisfiedLinkError from the native layer - and that is
            // still an unhealthy container rather than something to propagate.
            fresh = new Result(false, "HSM signing operation FAILED: "
                    + t.getClass().getName() + ": " + t.getMessage());
        }

        cached.set(fresh);
        cachedAt.set(now);
        return fresh;
    }

    /** How many times the underlying probe actually ran; used to assert the cache works. */
    public long probeCount() {
        return probeCount.get();
    }
}
