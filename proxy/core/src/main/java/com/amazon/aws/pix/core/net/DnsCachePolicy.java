package com.amazon.aws.pix.core.net;

import java.security.Security;

/**
 * Bounds the JVM's DNS cache so the proxy can honour BCB's DNS TTL requirement.
 *
 * <h2>What the Manual requires, and why the JVM default is not enough</h2>
 *
 * <p><em>Manual de Segurança do Pix</em> v3.7, section 2, requires that clients "devem sempre
 * respeitar o TTL" published by the DNS servers, and warns that failing to do so can cause loss of
 * access. The reason is operational rather than pedantic: BCB moves endpoints between addresses, and
 * a client pinned to a stale address keeps dialling a host that has stopped serving it.
 *
 * <h2>Three traps, all of which this class exists to avoid</h2>
 *
 * <p><b>The JVM does not honour the record's TTL at all.</b> It applies its own fixed cache duration
 * to every lookup regardless of what the DNS response said. So "respect the TTL" cannot be satisfied
 * by doing nothing; it can only be approximated by keeping the JVM's cache short enough that a
 * changed record is picked up promptly.
 *
 * <p><b>The value lives in a java.security property, not a system property.</b>
 * {@code networkaddress.cache.ttl} is read via {@link Security}. The frequently-cited
 * {@code -Dsun.net.inetaddr.ttl} is a legacy fallback that is only consulted when the security
 * property is unset, so a deployment that sets only the {@code -D} flag may silently have no effect.
 * This class therefore sets the security property.
 *
 * <p><b>The dangerous default is conditional.</b> With no security manager installed the default is
 * 30 seconds, which is fine. With one installed it is <b>-1, meaning cache forever</b> — a
 * successful lookup is then never re-resolved for the life of the process, which is precisely the
 * "loss of access" the Manual warns about. Whether a security manager is present is not something
 * this repository controls, so the value is set explicitly rather than inherited.
 *
 * <p>The negative cache matters too, and in the opposite direction: a long negative TTL means a
 * lookup that failed during a brief DNS blip stays failed. It is kept very short.
 */
public final class DnsCachePolicy {

    /** java.security property that actually governs the positive DNS cache. */
    public static final String TTL_PROPERTY = "networkaddress.cache.ttl";

    /** java.security property for failed lookups. */
    public static final String NEGATIVE_TTL_PROPERTY = "networkaddress.cache.negative.ttl";

    /** Seconds. Short enough to follow a BCB address change promptly without re-resolving per call. */
    public static final int DEFAULT_TTL_SECONDS = 30;

    /** Seconds. Deliberately tiny: a failure cached for long outlives the blip that caused it. */
    public static final int DEFAULT_NEGATIVE_TTL_SECONDS = 1;

    private DnsCachePolicy() {
    }

    /**
     * Applies bounded DNS caching using the defaults.
     *
     * @return a description of what was applied AND what actually took effect, for logging at startup
     */
    public static String apply() {
        return apply(DEFAULT_TTL_SECONDS, DEFAULT_NEGATIVE_TTL_SECONDS);
    }

    /**
     * System property the JDK consults as a FALLBACK when the security property is unset. Unlike the
     * security property it can be set on the command line, which is the only way to get a value in
     * before the first name resolution — see {@link #apply(int, int)}.
     */
    public static final String LEGACY_SYSTEM_PROPERTY = "sun.net.inetaddr.ttl";

    /**
     * @param ttlSeconds         positive cache duration; must be {@code >= 0}. Passing a negative
     *                           value would mean "cache forever", which is the failure being fixed,
     *                           so it is refused rather than honoured.
     * @param negativeTtlSeconds failed-lookup cache duration; must be {@code >= 0}
     */
    public static String apply(final int ttlSeconds, final int negativeTtlSeconds) {
        if (ttlSeconds < 0 || negativeTtlSeconds < 0) {
            throw new IllegalArgumentException("DNS cache TTLs must be >= 0; a negative value means "
                    + "cache forever, which is what breaks access when BCB moves an endpoint");
        }

        final String previous = Security.getProperty(TTL_PROPERTY);
        Security.setProperty(TTL_PROPERTY, Integer.toString(ttlSeconds));
        Security.setProperty(NEGATIVE_TTL_PROPERTY, Integer.toString(negativeTtlSeconds));

        // Report what took effect, not what was requested. MEASURED: the JDK reads this property
        // ONCE, in the static initializer of sun.net.InetAddressCachePolicy, and thereafter returns
        // the frozen value. Any name resolution - an SSM call, a Secrets Manager call, building an
        // AWS client - loads that class, so a write from application startup code afterwards sets the
        // property and changes nothing. An earlier version of this method logged success in exactly
        // that situation, which is worse than doing nothing: it makes an ineffective configuration
        // look verified. Measured: with a lookup first, the property reads 7 while the effective
        // policy stays 30.
        final Integer effective = effectivePolicySeconds();
        final String verdict;
        if (effective == null) {
            verdict = " EFFECTIVE VALUE UNVERIFIED (sun.net is not reflectively accessible here) - "
                    + "confirm " + LEGACY_SYSTEM_PROPERTY + " is set on the command line.";
        } else if (effective == ttlSeconds) {
            verdict = " Effective policy confirmed as " + effective + "s.";
        } else {
            verdict = " *** INEFFECTIVE: the JVM is still caching for " + effective + "s. This call "
                    + "ran after the first name resolution had already frozen the policy. Set -D"
                    + LEGACY_SYSTEM_PROPERTY + "=" + ttlSeconds + " on the command line instead; a "
                    + "security-property write from application code cannot fix this. ***";
        }

        return String.format(
                "DNS cache requested: %s=%d (was %s), %s=%d. Manual de Seguranca do Pix section 2 "
                        + "requires respecting DNS TTL; the JVM applies a fixed cache rather than the "
                        + "record's own TTL, and defaults to -1 (forever) when a security manager is "
                        + "installed.%s",
                TTL_PROPERTY, ttlSeconds, previous == null ? "unset" : previous,
                NEGATIVE_TTL_PROPERTY, negativeTtlSeconds, verdict);
    }

    /**
     * The cache duration the JVM is actually applying, or {@code null} if it cannot be read.
     *
     * <p>Read reflectively from {@code sun.net.InetAddressCachePolicy} because there is no public API
     * for it. That package is not exported by {@code java.base}, so this returns {@code null} unless
     * the JVM was started with {@code --add-exports java.base/sun.net=ALL-UNNAMED}. Returning
     * {@code null} rather than throwing is deliberate: an unverifiable reading must not take the
     * proxy down, but it must also not be reported as a confirmation.
     */
    public static Integer effectivePolicySeconds() {
        try {
            final Class<?> policy = Class.forName("sun.net.InetAddressCachePolicy");
            final java.lang.reflect.Method get = policy.getDeclaredMethod("get");
            get.setAccessible(true);
            return (Integer) get.invoke(null);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** The value actually in effect, so a startup log can state it rather than assume it. */
    public static String effectiveTtl() {
        final String value = Security.getProperty(TTL_PROPERTY);
        return value == null ? "unset" : value;
    }
}
