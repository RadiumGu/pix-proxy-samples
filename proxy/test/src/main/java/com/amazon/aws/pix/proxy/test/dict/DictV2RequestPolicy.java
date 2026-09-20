package com.amazon.aws.pix.proxy.test.dict;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Request policy for the <strong>local BCB DICT v2 simulator</strong>.
 *
 * <p><strong>Read this before trusting anything this class decides.</strong> These rules are
 * <em>simulator policy chosen by this repository</em>, not BCB behaviour. BCB's real per-operation
 * validation, its required-header set per endpoint, and its exact status codes for each failure mode
 * are only obtainable from BCB's current API materials and a homologação run. This class exists so
 * the teaching skeleton can be tested repeatably against a <em>stable, documented</em> contract —
 * not so anyone can claim BCB compatibility. See {@code CLOUDHSM_BCB_V2_HANDOFF.md}.
 *
 * <p>What it deliberately does <strong>not</strong> do: any Pix business logic. There is no payment
 * initiation, no settlement or reconciliation, no refund workflow, no MED 2.0 / Funds Recovery, no
 * Fraud Marker, no Event Notification, no Pix Automático, and no authorization or liquidity
 * decision. Those are out of scope for this repository by design; a simulator that faked them would
 * invite exactly the false confidence this class's javadoc is trying to prevent.
 *
 * <p>Two things it DOES check, both anchored to facts the repository can cite:
 *
 * <ul>
 *   <li><strong>The path must be under {@code /api/v2/}.</strong> DICT v1 was fully disabled on
 *       2024-02-04, so a v1-shaped call is a caller defect worth surfacing loudly rather than
 *       quietly answering 200 as the old stub did.</li>
 *   <li><strong>The BCB participant/correlation headers must be present.</strong> The proxy's whole
 *       job is to forward them untouched, so a simulator that answers 200 without them cannot
 *       detect the proxy dropping them.</li>
 * </ul>
 *
 * <p>Status simulation is driven by the {@value #SIMULATE_STATUS_HEADER} header so that error
 * handling (400/403/404/409/410/429/503) can be exercised end to end. That header is a
 * <strong>simulator affordance and must never be sent to real BCB</strong>.
 */
public final class DictV2RequestPolicy {

    /** Simulator-only header asking for a specific HTTP status. Never send this to real BCB. */
    public static final String SIMULATE_STATUS_HEADER = "PI-Simulate-Status";

    /** Required prefix for every DICT v2 call; v1 was disabled on 2024-02-04. */
    public static final String V2_PREFIX = "/api/v2/";

    /** Headers the proxy must forward, so the simulator refuses a request without them. */
    public static final String REQUESTING_PARTICIPANT = "PI-RequestingParticipant";
    public static final String END_TO_END_ID = "PI-EndToEndId";

    /**
     * Statuses this simulator is willing to produce on request. Restricted to the set the
     * repository documents as exercised, so a typo cannot silently become a made-up status.
     */
    private static final int[] SIMULATABLE = {400, 403, 404, 409, 410, 429, 503};

    private DictV2RequestPolicy() {
    }

    /** The simulator's decision: an HTTP status plus a human reason for the logs and tests. */
    public static final class Decision {
        private final int status;
        private final String reason;

        Decision(int status, String reason) {
            this.status = status;
            this.reason = reason;
        }

        public int status() {
            return status;
        }

        public String reason() {
            return reason;
        }

        /** True when the request should be answered normally (signed DICT response). */
        public boolean isSuccess() {
            return status == 200;
        }

        @Override
        public String toString() {
            return status + " " + reason;
        }
    }

    /**
     * Decides how the simulator should answer.
     *
     * @param path    the request path as received, e.g. {@code /api/v2/entries/+5561999999999}
     * @param query   the raw query string, may be {@code null}
     * @param headers request headers; lookups are case-insensitive
     */
    public static Decision decide(String path, String query, Map<String, ?> headers) {
        Integer requested = requestedStatus(headers);
        if (requested != null) {
            return new Decision(requested,
                    "simulated on request via " + SIMULATE_STATUS_HEADER
                            + " (simulator affordance; not BCB behaviour)");
        }

        if (path == null || !path.startsWith(V2_PREFIX)) {
            return new Decision(404,
                    "path must start with " + V2_PREFIX + " - DICT v1 was fully disabled on "
                            + "2024-02-04, so a non-v2 path is a caller defect. Received: " + path);
        }

        if (isBlank(header(headers, REQUESTING_PARTICIPANT))) {
            return new Decision(400, "missing " + REQUESTING_PARTICIPANT
                    + " - the proxy must forward it, so its absence means it was dropped");
        }

        if (isBlank(header(headers, END_TO_END_ID))) {
            return new Decision(400, "missing " + END_TO_END_ID
                    + " - the proxy must forward it, so its absence means it was dropped");
        }

        return new Decision(200, "accepted: v2 path and participant headers present"
                + (isBlank(query) ? ", no query" : ", query preserved: " + query));
    }

    /** @return the requested simulated status, or {@code null} when none/unsupported was asked for */
    private static Integer requestedStatus(Map<String, ?> headers) {
        String raw = header(headers, SIMULATE_STATUS_HEADER);
        if (isBlank(raw)) {
            return null;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        for (int allowed : SIMULATABLE) {
            if (allowed == parsed) {
                return parsed;
            }
        }
        return null;
    }

    /** Case-insensitive header lookup: HTTP header names are not case sensitive. */
    private static String header(Map<String, ?> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, ?> entry : headers.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return Objects.toString(entry.getValue(), null);
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
