package com.amazon.aws.pix.cloudhsm.proxy.processor;

import com.amazon.aws.pix.core.audit.AuditLog;
import com.amazon.aws.pix.core.audit.AsyncAuditWriter;
import com.amazon.aws.pix.core.audit.AuditAlarmTokens;
import com.amazon.aws.pix.core.audit.AuditSpool;
import com.amazon.aws.pix.core.util.PixConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.Map;
import java.util.stream.Collectors;

import static com.amazon.aws.pix.cloudhsm.proxy.processor.CaptureRequestProcessor.REQUEST_LOG_PROPERTY;

@Slf4j
@RequiredArgsConstructor
public class LogRequestResponseProcessor implements Processor {

    private final String streamName;
    /**
     * Off-path delivery. The Firehose call happens on a background thread in batches, so neither
     * its latency nor its failures land on the exchange that serves the caller.
     *
     * <p>The audit write must never fail the transaction: failing an exchange for a message BACEN
     * may already have accepted is "downstream thinks it failed, upstream already settled", the
     * worst inconsistency for a payment proxy (upstream issue #18). Handing the record to a queue
     * that neither blocks nor throws is a stronger guarantee of that than a try/catch was.
     */
    private final AsyncAuditWriter auditWriter;
    /** Durable fallback, used when the queue is full or delivery fails; see {@link AuditSpool}. */
    private final AuditSpool auditSpool;

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> headers = exchange.getIn().getHeaders();

        AuditLog auditLog = (AuditLog) exchange.getProperty(REQUEST_LOG_PROPERTY);

        // Reachable only if the exchange failed before CaptureRequestProcessor ran - a signing
        // failure, for instance. There is genuinely nothing to audit, but staying silent would
        // make a lost record look like a message that was never sent, so say so explicitly.
        if (auditLog == null) {
            log.error(AuditAlarmTokens.NO_RECORD + " NO AUDIT RECORD EXISTS for an exchange on "
                    + "stream {} - it failed before the "
                    + "request was captured. Nothing was sent to the BCB. Alarm on this line.",
                    streamName, exchange.getException());
            return;
        }

        // A transport or TLS failure on the BCB leg leaves no response at all. Without this the
        // record would be indistinguishable from a successful exchange that carried no status
        // code, which is the difference between "settled" and "outcome unknown".
        final Throwable failure = transportFailure(exchange);
        if (failure != null) {
            auditLog.setTransportFailure(failure.getClass().getName() + ": " + failure.getMessage());
        }

        auditLog.setResponseStatusCode(headers.get("CamelHttpResponseCode"));
        auditLog.setResponseSignatureValid(headers.get(PixConstants.PIX_HEADER_SIGNATURE_VALID));
        auditLog.setResponseBody(exchange.getIn().getBody(String.class));
        auditLog.setResponseHeader(
                headers.entrySet().stream()
                        .filter(e -> !e.getKey().startsWith("Camel"))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );

        final String auditJson = auditLog.toJson();

        // Hand off and return. The Firehose call now happens on a background thread in batches, so
        // neither its latency nor its failures sit on the path that serves the caller. A full queue
        // spills to the durable spool rather than blocking or dropping - see AsyncAuditWriter.
        if (!auditWriter.submit(auditJson)) {
            log.error(AuditAlarmTokens.QUEUE_FULL + " " + AuditAlarmTokens.SPOOLED
                    + " audit queue full for stream {} - the record went to the local spool "
                    + "instead ({}). Delivery is not keeping up; alarm on this line. A spool on a "
                    + "container filesystem does NOT survive the task.",
                    streamName, auditSpool.status());
        }
    }

    /**
     * Returns the failure that aborted the BCB leg, or {@code null} for a normal exchange.
     *
     * <p>Both sources are checked on purpose. {@code getException()} holds a failure that is still
     * live, while Camel moves a failure that has been handled to the {@code EXCEPTION_CAUGHT}
     * property - so consulting only the first would silently miss a handled transport failure,
     * which is the very case this method exists for.
     */
    private static Throwable transportFailure(final Exchange exchange) {
        final Throwable live = exchange.getException();
        if (live != null) {
            return live;
        }
        return exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
    }
}
