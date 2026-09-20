package com.amazon.aws.pix.proxy.test.audit;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Proves that a transport failure on the BCB leg still produces an audit record, and that the
 * previous placement genuinely lost it.
 *
 * <h2>The defect</h2>
 *
 * <p>The audit write used to be the last {@code .process()} of the route. A transport-level failure
 * on the BCB leg - connection refused, TLS handshake rejected, read timeout - aborts the exchange,
 * and a trailing step simply never runs. So a request that was signed and actually sent left no
 * record whatsoever: the state an operator most needs to see, "sent, outcome unknown", was the one
 * state that produced nothing at all.
 *
 * <p>{@code throwExceptionOnFailure(false)} does not cover this. That option suppresses HTTP error
 * <em>statuses</em>, which arrive as a well-formed response. These failures happen below HTTP,
 * where there is no response to carry a status.
 *
 * <h2>Why both tests are needed</h2>
 *
 * <p>The first test asserts the onCompletion write runs when the route fails. On its own that does
 * not establish it was ever a problem. The second runs the identical failing route with the write
 * in the old trailing position and asserts it does NOT run - that is the defect, reproduced. The
 * pair is what distinguishes a real fix from a change that merely looks safer.
 *
 * <p>These tests use a plain {@code DefaultCamelContext} and a deliberately unroutable endpoint
 * rather than the production route, because {@code proxy/cloudhsm} is built with {@code -skipTests}
 * in CI: a test placed beside the production route would never execute. What is asserted here is
 * the Camel behaviour the production route depends on.
 */
public class OnCompletionAuditSurvivesTransportFailureTest {

    /**
     * A port chosen to be closed, so the send fails at the transport layer rather than returning an
     * HTTP status. netty-http is the same producer component the production route uses, so the
     * failure mode reproduced here is the real one.
     */
    private static final String DEAD_ENDPOINT = "netty-http:http://127.0.0.1:1/bcb";

    private static final long ASSERT_TIMEOUT_MS = 10_000;

    /** The fix: an onCompletion block runs even though the exchange failed. */
    @Test
    public void auditRunsWhenTheBcbLegFailsAtTransportLevel() throws Exception {
        final List<String> audited = new CopyOnWriteArrayList<>();

        runFailingRoute(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:in")
                        .onCompletion()
                            .process(e -> audited.add("audited"))
                        .end()
                        .to(DEAD_ENDPOINT);
            }
        });

        assertEquals("the audit record must survive a transport failure", 1, audited.size());
    }

    /**
     * Negative control, and the defect reproduced: with the write in its old trailing position the
     * identical failure produces no record at all.
     */
    @Test
    public void aTrailingProcessorIsSkippedByTheSameFailure() throws Exception {
        final List<String> audited = new CopyOnWriteArrayList<>();

        runFailingRoute(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:in")
                        .to(DEAD_ENDPOINT)
                        .process(e -> audited.add("audited"));
            }
        });

        assertTrue("a trailing audit step is skipped when the send fails - this is the defect",
                audited.isEmpty());
    }

    /**
     * Sends one message through a route that is expected to fail, and returns once the exchange has
     * settled. The send throwing is the expected outcome, not a test failure.
     */
    private void runFailingRoute(final RouteBuilder route) throws Exception {
        final DefaultCamelContext context = new DefaultCamelContext();
        try {
            context.addRoutes(route);
            context.start();
            try {
                context.createProducerTemplate()
                        .requestBody("direct:in", "<payload/>", String.class);
            } catch (Exception expected) {
                // The endpoint is unreachable on purpose; the assertions are about the audit step.
            }
        } finally {
            context.stop();
        }
    }
}
