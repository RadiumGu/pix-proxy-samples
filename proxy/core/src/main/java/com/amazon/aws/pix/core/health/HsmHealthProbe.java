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

    /** One probe plus the credential name to report when it is the one that failed. */
    private static final class NamedProbe {
        private final String name;
        private final Probe probe;

        private NamedProbe(final String name, final Probe probe) {
            this.name = name;
            this.probe = probe;
        }
    }

    private final java.util.List<NamedProbe> probes = new java.util.ArrayList<>();
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
        this.probes.add(new NamedProbe("signing-key", probe));
        this.ttlMillis = ttl.toMillis();
        this.clock = clock;
    }

    /**
     * Registers an additional credential this container needs in order to serve Pix traffic.
     *
     * <p>Added because {@code /check} probed the signing key only, which left the mTLS client key
     * unverified — and that key is what every BCB handshake depends on. It is fetched ONCE at
     * startup and handed to the SSL context, so if its HSM session later dies, every request to BCB
     * fails at the handshake while {@code /check} keeps answering 200 because signing still works.
     * A load balancer has no reason to replace the container, and the outage persists.
     *
     * <p>Probes run in registration order and the first failure decides the verdict, naming the
     * credential that broke. All of them must pass for the container to be healthy: a container that
     * can sign but cannot complete mTLS is no more useful than one that can do neither.
     *
     * @param name  the credential name to report, for example {@code mtls-client-key}
     * @param probe an operation that fails if that credential is unusable
     */
    public HsmHealthProbe add(final String name, final Probe probe) {
        if (name == null || name.trim().isEmpty() || probe == null) {
            throw new IllegalArgumentException("name and probe are both required");
        }
        probes.add(new NamedProbe(name, probe));
        // Any registration invalidates a cached verdict, so a newly added credential cannot be
        // skipped for up to a TTL by an answer computed before it existed.
        cached.set(null);
        return this;
    }

    /** The credential names probed, in order. Lets a test assert WHAT is covered, not just that
     * something is. */
    public java.util.List<String> probedCredentials() {
        final java.util.List<String> names = new java.util.ArrayList<>();
        for (final NamedProbe p : probes) {
            names.add(p.name);
        }
        return names;
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

        Result fresh = new Result(true,
                "HSM credential checks succeeded for " + probedCredentials());
        for (final NamedProbe named : probes) {
            try {
                probeCount.incrementAndGet();
                named.probe.run();
            } catch (Throwable t) {
                // Throwable, not Exception: a dead JCE provider can surface as an Error - a
                // NoClassDefFoundError or UnsatisfiedLinkError from the native layer - and that is
                // still an unhealthy container rather than something to propagate.
                //
                // The credential name is in the message because "signing failed" sent an operator
                // to the wrong key: the signing key and the mTLS key are different objects with
                // different failure modes, and only one of them breaks the BCB handshake.
                fresh = new Result(false, "HSM credential '" + named.name + "' is UNUSABLE: "
                        + t.getClass().getName() + ": " + t.getMessage());
                break;
            }
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
