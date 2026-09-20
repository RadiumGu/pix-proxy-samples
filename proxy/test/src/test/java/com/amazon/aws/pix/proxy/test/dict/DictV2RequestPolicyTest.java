package com.amazon.aws.pix.proxy.test.dict;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tests for the local simulator's DICT v2 request policy.
 *
 * <p>These assert <strong>simulator policy</strong>, deliberately not BCB behaviour. The value is
 * that the simulator now refuses requests the old stub accepted: it answered 200 for any path with
 * no header checks, so it could not detect the proxy dropping the v2 path or the BCB headers. A
 * simulator that cannot fail cannot be used as evidence of anything.
 *
 * <p>Passing these tests is <strong>not</strong> evidence of BCB homologação — see
 * {@code CLOUDHSM_BCB_V2_HANDOFF.md}.
 */
public class DictV2RequestPolicyTest {

    private static final String V2_PATH = "/api/v2/entries/+5561999999999";

    private Map<String, Object> validHeaders() {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(DictV2RequestPolicy.REQUESTING_PARTICIPANT, "12345678");
        headers.put(DictV2RequestPolicy.END_TO_END_ID, "E1234567820260920123400000000001");
        return headers;
    }

    // ------------------------------------------------------------------ happy path

    @Test
    public void acceptsAV2PathWithTheParticipantHeaders() {
        DictV2RequestPolicy.Decision decision =
                DictV2RequestPolicy.decide(V2_PATH, "IncludeStatistics=true", validHeaders());

        Assert.assertTrue("a well-formed v2 request must be accepted: " + decision, decision.isSuccess());
        Assert.assertEquals(200, decision.status());
    }

    /** The reason string carries the query, so a dropped query is visible in simulator logs. */
    @Test
    public void successReasonReportsThePreservedQuery() {
        DictV2RequestPolicy.Decision decision = DictV2RequestPolicy.decide(
                V2_PATH, "IncludeStatistics=true&Status=OPEN&Status=CLOSED", validHeaders());

        Assert.assertTrue("the query must be echoed in the reason so its loss is observable: " + decision,
                decision.reason().contains("Status=OPEN") && decision.reason().contains("Status=CLOSED"));
    }

    @Test
    public void headerLookupIsCaseInsensitive() {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("pi-requestingparticipant", "12345678");
        headers.put("PI-ENDTOENDID", "E1234567820260920123400000000001");

        Assert.assertTrue("HTTP header names are case-insensitive; the policy must not depend on casing",
                DictV2RequestPolicy.decide(V2_PATH, null, headers).isSuccess());
    }

    // ------------------------------------------------------------------ v2 path enforcement

    /** DICT v1 was fully disabled on 2024-02-04, so a v1-shaped path must not be answered 200. */
    @Test
    public void rejectsAV1Path() {
        DictV2RequestPolicy.Decision decision =
                DictV2RequestPolicy.decide("/api/v1/entries/+5561999999999", null, validHeaders());

        Assert.assertFalse("a v1 path must not be accepted", decision.isSuccess());
        Assert.assertEquals(404, decision.status());
        Assert.assertTrue("the reason should say why, for whoever reads the simulator log: " + decision,
                decision.reason().contains("2024-02-04"));
    }

    @Test
    public void rejectsAPathOutsideApiV2() {
        Assert.assertEquals(404,
                DictV2RequestPolicy.decide("/anything", null, validHeaders()).status());
    }

    @Test
    public void rejectsANullPath() {
        Assert.assertEquals(404,
                DictV2RequestPolicy.decide(null, null, validHeaders()).status());
    }

    // ------------------------------------------------------------------ header enforcement

    @Test
    public void rejectsAMissingRequestingParticipant() {
        Map<String, Object> headers = validHeaders();
        headers.remove(DictV2RequestPolicy.REQUESTING_PARTICIPANT);

        DictV2RequestPolicy.Decision decision = DictV2RequestPolicy.decide(V2_PATH, null, headers);
        Assert.assertEquals("a dropped participant header must be visible as a 400, not a silent 200",
                400, decision.status());
        Assert.assertTrue(decision.reason().contains(DictV2RequestPolicy.REQUESTING_PARTICIPANT));
    }

    @Test
    public void rejectsAMissingEndToEndId() {
        Map<String, Object> headers = validHeaders();
        headers.remove(DictV2RequestPolicy.END_TO_END_ID);

        Assert.assertEquals(400, DictV2RequestPolicy.decide(V2_PATH, null, headers).status());
    }

    @Test
    public void rejectsABlankParticipantHeader() {
        Map<String, Object> headers = validHeaders();
        headers.put(DictV2RequestPolicy.REQUESTING_PARTICIPANT, "   ");

        Assert.assertEquals("a whitespace-only header is as absent as a missing one",
                400, DictV2RequestPolicy.decide(V2_PATH, null, headers).status());
    }

    @Test
    public void toleratesNullHeaders() {
        Assert.assertEquals(400, DictV2RequestPolicy.decide(V2_PATH, null, null).status());
    }

    // ------------------------------------------------------------------ status simulation

    /** Every status the repository claims the simulator can produce must actually be producible. */
    @Test
    public void simulatesEveryDocumentedErrorStatus() {
        for (int status : new int[]{400, 403, 404, 409, 410, 429, 503}) {
            Map<String, Object> headers = validHeaders();
            headers.put(DictV2RequestPolicy.SIMULATE_STATUS_HEADER, String.valueOf(status));

            DictV2RequestPolicy.Decision decision = DictV2RequestPolicy.decide(V2_PATH, null, headers);
            Assert.assertEquals(
                    "the documentation says the simulator can produce " + status
                            + "; if it cannot, the documentation is wrong",
                    status, decision.status());
            Assert.assertFalse("a simulated error must not be reported as success", decision.isSuccess());
        }
    }

    /**
     * An unsupported value must NOT become a made-up status. Falling through to normal handling is
     * the safe behaviour: it keeps the simulator from inventing BCB semantics on a typo.
     */
    @Test
    public void ignoresAnUnsupportedSimulatedStatus() {
        Map<String, Object> headers = validHeaders();
        headers.put(DictV2RequestPolicy.SIMULATE_STATUS_HEADER, "418");

        Assert.assertTrue("an unsupported simulated status must fall through to normal handling "
                        + "rather than be echoed back as if BCB could return it",
                DictV2RequestPolicy.decide(V2_PATH, null, headers).isSuccess());
    }

    @Test
    public void ignoresANonNumericSimulatedStatus() {
        Map<String, Object> headers = validHeaders();
        headers.put(DictV2RequestPolicy.SIMULATE_STATUS_HEADER, "not-a-number");

        Assert.assertTrue(DictV2RequestPolicy.decide(V2_PATH, null, headers).isSuccess());
    }

    /** Status simulation is checked before path validity, so error paths can be exercised too. */
    @Test
    public void simulatedStatusAppliesEvenOnANonV2Path() {
        Map<String, Object> headers = validHeaders();
        headers.put(DictV2RequestPolicy.SIMULATE_STATUS_HEADER, "503");

        Assert.assertEquals(503, DictV2RequestPolicy.decide("/api/v1/x", null, headers).status());
    }

    /** The simulated-status reason must say it is a simulator affordance, not BCB behaviour. */
    @Test
    public void simulatedStatusReasonNamesItselfAsSimulatorOnly() {
        Map<String, Object> headers = validHeaders();
        headers.put(DictV2RequestPolicy.SIMULATE_STATUS_HEADER, "429");

        String reason = DictV2RequestPolicy.decide(V2_PATH, null, headers).reason();
        Assert.assertTrue("the reason must mark this as simulator-only so a reader cannot mistake it "
                        + "for BCB behaviour: " + reason,
                reason.contains("simulator affordance"));
    }

    // ------------------------------------------------------------------ scope guard

    /**
     * Scope assertion, not a behaviour test. The simulator must stay a transport/crypto test double;
     * if someone starts adding Pix business state machines (MED, Fraud Marker, Event Notifications,
     * refunds, settlement), this repository's stated scope has been broken. Kept as a test so the
     * boundary is executable rather than only prose in a README.
     */
    @Test
    public void policyDoesNotImplementPixBusinessSemantics() {
        Map<String, Object> headers = validHeaders();
        // A body/operation that a PSP would treat as a business action gets no special handling:
        // the policy decides purely on transport shape.
        DictV2RequestPolicy.Decision refundShaped =
                DictV2RequestPolicy.decide("/api/v2/refunds/SOMETHING", "x=1", headers);
        DictV2RequestPolicy.Decision entryShaped =
                DictV2RequestPolicy.decide("/api/v2/entries/+5561999999999", "x=1", headers);

        Assert.assertEquals(
                "the simulator must not branch on business resource type - it is a transport and "
                        + "crypto test double, and MED/Fraud/Event/refund state machines are "
                        + "deliberately out of scope for this repository",
                refundShaped.status(), entryShaped.status());
    }

    /** Guards the documented simulatable set against silent growth into invented semantics. */
    @Test
    public void onlyDocumentedStatusesAreSimulatable() {
        Map<String, Object> headers = new HashMap<>(validHeaders());
        for (int status : new int[]{200, 201, 301, 418, 500, 502}) {
            headers.put(DictV2RequestPolicy.SIMULATE_STATUS_HEADER, String.valueOf(status));
            Assert.assertTrue(
                    status + " is not in the documented simulatable set, so it must be ignored "
                            + "rather than produced",
                    DictV2RequestPolicy.decide(V2_PATH, null, headers).isSuccess());
        }
    }
}
