package com.amazon.aws.pix.cloudhsm.proxy.processor;

import com.amazon.aws.pix.core.http.CompressedRequestPolicy;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Refuses a compressed inbound request with 415 instead of signing corrupted bytes.
 *
 * <p>Must run <b>before</b> {@code convertToString()}. That ordering is the whole point: once a gzip
 * body has been through a charset decode it is destroyed irreversibly, and the previous route then
 * produced a valid XML signature over the wreckage and sent it to BCB under the PSP's key. The
 * reasoning, including why decoding the request instead would be wrong, is in
 * {@link CompressedRequestPolicy}.
 *
 * <p>The route is stopped rather than allowed to continue, so nothing is signed and nothing is sent.
 */
@Slf4j
public class RejectCompressedRequestProcessor implements Processor {

    @Override
    public void process(final Exchange exchange) {
        final String contentEncoding =
                exchange.getIn().getHeader(Exchange.CONTENT_ENCODING, String.class);

        // Read as bytes, BEFORE any string conversion, so the gzip signature is still intact. The
        // body must be sniffed and not merely the header trusted: a client that gzips the body and
        // omits Content-Encoding was measured passing straight through, with the mojibake signed
        // under the PSP key and forwarded to BCB.
        final byte[] body = exchange.getIn().getBody(byte[].class);

        if (!CompressedRequestPolicy.mustReject(contentEncoding, body)) {
            return;
        }

        final boolean undeclared = CompressedRequestPolicy.looksCompressed(body)
                && !CompressedRequestPolicy.mustReject(contentEncoding);

        log.warn("Refusing a compressed request body (Content-Encoding={}, gzip signature in body={})."
                + " BCB does not accept compressed requests, and signing it would mean signing a "
                + "corrupted document.{}", contentEncoding, undeclared,
                undeclared ? " The body was compressed WITHOUT declaring it - a broken client." : "");

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE,
                CompressedRequestPolicy.REJECTION_STATUS);
        exchange.getMessage().setBody(CompressedRequestPolicy.rejectionMessage(contentEncoding));

        // Drop the header so the 415 body itself is not advertised as compressed.
        exchange.getMessage().removeHeader(Exchange.CONTENT_ENCODING);

        exchange.setRouteStop(true);
    }
}
