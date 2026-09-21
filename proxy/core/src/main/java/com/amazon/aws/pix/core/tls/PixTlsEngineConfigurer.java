package com.amazon.aws.pix.core.tls;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.util.Collections;

/**
 * Configures a client {@link SSLEngine} for the BCB leg: SNI plus hostname verification.
 *
 * <h2>Why this is a separate class rather than three lines in the Netty factory</h2>
 *
 * <p>It used to be inline in {@code NettyHttpClientInitializerFactory}, and an independent review
 * showed the consequence: deleting the hostname-verification line left the whole test suite green,
 * because no test could reach the production code. The only thing guarding it was a CI text-grep,
 * while the commit message claimed a test pinned the behaviour. Pulling the configuration out gives
 * the tests something to actually execute.
 *
 * <p>It lives in {@code proxy/core} deliberately. The engine configuration is pure JSSE — no Netty
 * type appears in this signature — so {@code core} gains no framework dependency, and the module
 * that CI actually runs tests in can exercise it. {@code proxy/cloudhsm} is built with
 * {@code -DskipTests}, so a test placed beside the factory would never execute.
 *
 * <h2>SNI and endpoint identification are different things</h2>
 *
 * <p>Both are set here because setting only the first looks like security and is not. SNI is a
 * <em>request</em>: a plaintext hint in the ClientHello naming which host the client wants. Endpoint
 * identification is the <em>check</em>: it compares the certificate the server actually presented
 * against the host that was dialled. With SNI alone, JSSE validates the chain and then accepts that
 * chain for any hostname at all.
 */
public final class PixTlsEngineConfigurer {

    /** RFC 2818 hostname matching. The standard algorithm name; "LDAPS" is the only other. */
    public static final String ENDPOINT_IDENTIFICATION_ALGORITHM = "HTTPS";

    private PixTlsEngineConfigurer() {
    }

    /**
     * Puts the engine into client mode with SNI and hostname verification enabled.
     *
     * @param engine the engine to configure, already created for the target host
     * @param host   the hostname that was dialled; the certificate must match it
     */
    public static void configureClient(final SSLEngine engine, final String host) {
        if (engine == null) {
            throw new IllegalArgumentException("engine is required");
        }

        engine.setUseClientMode(true);

        final SSLParameters parameters = engine.getSSLParameters();

        // An IP literal is not a legal SNI server name, and offering one makes some servers abort
        // the handshake. Hostname verification still applies either way - it falls back to matching
        // the IP against the certificate's iPAddress SAN.
        if (isSniEligible(host)) {
            parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
        }

        parameters.setEndpointIdentificationAlgorithm(ENDPOINT_IDENTIFICATION_ALGORITHM);

        engine.setSSLParameters(parameters);
    }

    /**
     * SNI carries DNS names only, per RFC 6066: an IPv4 or IPv6 literal must not be sent as a
     * server_name. A bare hostname with no dot is also rejected, since it cannot be a valid FQDN.
     */
    static boolean isSniEligible(final String host) {
        if (host == null || host.isEmpty() || !host.contains(".")) {
            return false;
        }
        // An IPv4 literal is all digits and dots; IPv6 contains a colon.
        return !host.matches("[0-9.]+") && !host.contains(":");
    }
}
