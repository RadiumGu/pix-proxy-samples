package com.amazon.aws.pix.cloudhsm.proxy.processor;

import com.amazon.aws.pix.core.http.HttpContentDecoder;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import static com.amazon.aws.pix.core.util.PixConstants.PIX_CONTENT_ENCODING_ERROR;

/**
 * Undoes the HTTP {@code Content-Encoding} of a BCB response so the next step verifies the XML
 * that BCB actually signed.
 * <p>
 * This processor must sit between {@code to(bcbEndpoint)} and the {@code convertToString()} that
 * precedes {@link VerifyResponseProcessor}. Its position is the whole point: a gzip body converted
 * to a {@code String} first is destroyed irreversibly (the magic byte {@code 0x8b} becomes
 * U+FFFD), so no later step can recover it. See {@link HttpContentDecoder} for the measurement.
 * <p>
 * On success the body is replaced with the decoded octets and {@code Content-Encoding} is removed,
 * because the body handed downstream is no longer encoded - leaving the header in place would tell
 * the PSP's own HTTP client to inflate plaintext. {@code Content-Length} is dropped too so the
 * server side recomputes it from the new body rather than advertising the compressed length and
 * truncating the response.
 * <p>
 * A failure is recorded as an exchange property instead of being thrown. Throwing here would abort
 * the exchange, so {@code LogRequestResponseProcessor} would never run and the Firehose audit
 * record would be lost - the same reasoning that keeps
 * {@code XmlSigner.CertificateValidityException} inside {@link VerifyResponseProcessor}. The
 * transaction still fails with 500, but the audit says the body could not be decoded rather than
 * blaming the signature.
 */
@Slf4j
public class DecodeResponseProcessor implements Processor {

    @Override
    public void process(Exchange exchange) {
        final String contentEncoding = exchange.getIn().getHeader("Content-Encoding", String.class);
        if (!HttpContentDecoder.isEncoded(contentEncoding)) {
            return;
        }

        final byte[] encoded = exchange.getIn().getBody(byte[].class);
        if (encoded == null || encoded.length == 0) {
            // Nothing to decode; still drop the header so the claim matches the body.
            exchange.getIn().removeHeader("Content-Encoding");
            return;
        }

        try {
            final byte[] decoded = HttpContentDecoder.decode(encoded, contentEncoding);
            exchange.getIn().setBody(decoded);
            exchange.getIn().removeHeader("Content-Encoding");
            exchange.getIn().removeHeader("Content-Length");
            log.debug("decoded a '{}' BCB response body, {} -> {} bytes",
                    contentEncoding, encoded.length, decoded.length);
        } catch (HttpContentDecoder.UnsupportedContentEncodingException e) {
            // Leave the body alone: it is undecodable either way, and replacing it would discard
            // evidence a reader of the audit record may need.
            log.error("cannot decode the BCB response body (Content-Encoding: {}) - failing the "
                    + "transaction with 500 but still writing the audit record. This is NOT a "
                    + "signature mismatch.", contentEncoding, e);
            exchange.setProperty(PIX_CONTENT_ENCODING_ERROR, e.getMessage());
        }
    }
}
