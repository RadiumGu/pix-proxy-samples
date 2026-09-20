package com.amazon.aws.pix.cloudhsm.proxy.processor;

import com.amazon.aws.pix.core.audit.AuditLog;
import com.amazon.aws.pix.core.audit.AuditSpool;
import com.amazon.aws.pix.core.util.PixConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.PutRecordRequest;
import software.amazon.awssdk.services.firehose.model.PutRecordResponse;

import java.util.Map;
import java.util.stream.Collectors;

import static com.amazon.aws.pix.cloudhsm.proxy.processor.CaptureRequestProcessor.REQUEST_LOG_PROPERTY;

@Slf4j
@RequiredArgsConstructor
public class LogRequestResponseProcessor implements Processor {

    private final FirehoseClient firehoseClient;
    private final String streamName;
    /** Durable fallback for records Firehose refused; see {@link AuditSpool}. */
    private final AuditSpool auditSpool;

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> headers = exchange.getIn().getHeaders();

        AuditLog auditLog = (AuditLog) exchange.getProperty(REQUEST_LOG_PROPERTY);
        auditLog.setResponseStatusCode(headers.get("CamelHttpResponseCode"));
        auditLog.setResponseSignatureValid(headers.get(PixConstants.PIX_HEADER_SIGNATURE_VALID));
        auditLog.setResponseBody(exchange.getIn().getBody(String.class));
        auditLog.setResponseHeader(
                headers.entrySet().stream()
                        .filter(e -> !e.getKey().startsWith("Camel"))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );

        final String auditJson = auditLog.toJson();

        PutRecordRequest putRecordRequest = PutRecordRequest.builder()
                .deliveryStreamName(streamName)
                .record(builder -> builder.data(SdkBytes.fromUtf8String(auditJson)))
                .build();

        // The audit write is the LAST step of the route. Letting it throw fails the whole
        // exchange, so the caller receives an error for a message BACEN may have already
        // accepted - "downstream thinks it failed, upstream already settled", the worst
        // inconsistency for a payment proxy. Audit delivery must therefore not fail the
        // transaction. See upstream issue #18.
        //
        // Swallowing the failure traded a correctness problem for a compliance one - a lost
        // audit record. Item 1 below is now DONE: the record is written to a local spool
        // instead of vanishing. Items 2 and 3 remain open and still need a decision:
        //   1. DONE - durable fallback, see AuditSpool. Note its limit: a spool on a
        //      container filesystem dies with the task, so mount a volume and ship the file.
        //   2. STILL OPEN - alarm on AuditSpool.spooledCount() being non-zero, and on the
        //      Firehose errorOutputPrefix. An unaudited transaction is a compliance event
        //      and nothing in this repository watches for one.
        //   3. STILL OPEN - move the write off the critical path (bounded queue +
        //      background PutRecordBatch).
        // Also still open, and NOT solved by the spool: a transport or TLS failure on the BCB
        // leg aborts the exchange before this processor runs at all, so no record exists to
        // spool. Fixing that needs the audit write moved into an onCompletion block.
        try {
            PutRecordResponse putRecordResponse = firehoseClient.putRecord(putRecordRequest);
            if (log.isDebugEnabled()) {
                log.debug("audit record delivered, recordId={}", putRecordResponse.recordId());
            }
        } catch (Exception e) {
            final boolean spooled = auditSpool.spool(auditJson);
            log.error("AUDIT DELIVERY FAILED for stream {} - transaction was NOT failed. "
                    + "This is a compliance event: alarm on this line. Record {} to the local "
                    + "spool ({}). A spool on a container filesystem does NOT survive the task.",
                    streamName, spooled ? "WAS written" : "COULD NOT be written",
                    auditSpool.status(), e);
        }
    }
}
