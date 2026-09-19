package com.amazon.aws.pix.cloudhsm.proxy.processor;

import com.amazon.aws.pix.core.xml.XmlSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.Map;

import static com.amazon.aws.pix.core.util.PixConstants.PIX_HEADERS;
import static com.amazon.aws.pix.core.util.PixConstants.PIX_HEADER_SIGNATURE_VALID;
import static com.amazon.aws.pix.core.util.PixConstants.SIGNATURE_VALID_CERTIFICATE_ERROR;

@Slf4j
@RequiredArgsConstructor
public class VerifyResponseProcessor implements Processor {

    private final XmlSigner xmlSigner;

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> headers = exchange.getIn().getHeaders();
        headers.putAll(exchange.getProperty(PIX_HEADERS, Map.class));

        final String body = exchange.getIn().getBody(String.class);
        if (body != null && body.length() > 0) {
            int statusCode = (int) exchange.getIn().getHeader("CamelHttpResponseCode");
            if (200 <= statusCode && statusCode < 300) {
                try {
                    final Boolean valid = xmlSigner.verify(body);
                    headers.put(PIX_HEADER_SIGNATURE_VALID, valid.toString());
                    if (!valid) headers.put("CamelHttpResponseCode", 500);
                } catch (XmlSigner.CertificateValidityException e) {
                    // This exception must NOT escape the processor. This is the second-to-last
                    // step of the route; the audit write is the last one. Letting it propagate
                    // aborts the exchange, so LogRequestResponseProcessor never runs and the
                    // Firehose audit record is lost - for precisely the transactions a compliance
                    // reader would most want to see, since an expired BACEN certificate affects
                    // every message until someone rotates it.
                    //
                    // That would undo the fix for upstream issue #18 from the other direction:
                    // #18 stopped an audit failure from damaging a transaction, and this stops a
                    // verification failure from destroying the audit record.
                    //
                    // The transaction still fails with 500 (the response cannot be trusted), but
                    // the audit field says WHY, distinctly from a signature mismatch.
                    log.error("certificate validity problem while verifying the BACEN response - "
                            + "failing the transaction with 500 but still writing the audit record. "
                            + "Rotate the trusted certificate; this is NOT a signature mismatch.", e);
                    headers.put(PIX_HEADER_SIGNATURE_VALID, SIGNATURE_VALID_CERTIFICATE_ERROR);
                    headers.put("CamelHttpResponseCode", 500);
                }
            }
        }
    }
}
