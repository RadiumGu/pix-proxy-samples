package com.amazon.aws.pix.core.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Decodes an HTTP {@code Content-Encoding} body back to the bytes the sender actually signed.
 * <p>
 * <b>Why this exists.</b> The BCB DICT API page recommends that clients ask for compression
 * ("&Eacute; recomend&aacute;vel tamb&eacute;m que se utilize compress&atilde;o... adicione nas
 * requisi&ccedil;&otilde;es o header {@code Accept-Encoding: gzip}"). The proxy forwards client
 * headers transparently, so that {@code Accept-Encoding} reaches BCB and BCB answers with a
 * gzip-compressed body. The XML Digital Signature, however, is over the <em>XML document</em> -
 * compression is a transport encoding applied afterwards. Verifying the compressed octets can
 * therefore never succeed.
 * <p>
 * <b>Why it must work on bytes.</b> The route used to hand the response straight to
 * {@code body().convertToString()}. Measured on this repo: a gzip body arrives, charset
 * conversion maps the gzip magic {@code 0x1f 0x8b} to {@code 31, U+FFFD} - the {@code 0x8b} is
 * replaced by the Unicode replacement character and the data is destroyed <em>irreversibly</em>,
 * so no later step can recover it. Decoding must happen before any {@code String} conversion,
 * which is why this class takes and returns {@code byte[]} and never touches a charset.
 * <p>
 * <b>Why unknown encodings are an error, not a pass-through.</b> Returning the body untouched for
 * an encoding we cannot decode would hand undecodable octets to signature verification, which
 * would then report a signature mismatch - a transport fault wearing a cryptographic fault's
 * clothing. That is the exact confusion {@code SIGNATURE_VALID_CERTIFICATE_ERROR} was introduced
 * to avoid, so this class refuses loudly instead.
 * <p>
 * This class is deliberately free of Camel and of any HTTP client, so the decoding rules can be
 * unit-tested in {@code proxy/core} - one of the only two modules whose tests actually execute in
 * CI.
 */
public final class HttpContentDecoder {

    /**
     * Upper bound on the decoded size, as a guard against a decompression bomb.
     * <p>
     * The inbound server side is already bounded: Camel's {@code chunkedMaxContentLength} defaults
     * to 1 MB and {@code HttpServerInitializerFactory} installs an {@code HttpObjectAggregator}
     * with it. Nothing bounded the <em>decoded</em> size of a response, and a compressed stream
     * expands without limit by construction, so an explicit ceiling is cheaper than an OOM. DICT
     * responses are small - the largest realistic payload is a 200-element list - so 16 MB is
     * several orders of magnitude of headroom rather than a functional limit.
     */
    public static final int MAX_DECODED_BYTES = 16 * 1024 * 1024;

    private static final int COPY_BUFFER_BYTES = 8 * 1024;

    private HttpContentDecoder() {
    }

    /**
     * Raised when the body cannot be decoded. Callers must record this distinctly from a signature
     * mismatch; see the class javadoc.
     */
    public static class UnsupportedContentEncodingException extends Exception {
        public UnsupportedContentEncodingException(String message) {
            super(message);
        }

        public UnsupportedContentEncodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * @return {@code true} when {@code contentEncoding} names an encoding that actually has to be
     *         undone. Absent, blank and {@code identity} all mean "the body is already plaintext".
     */
    public static boolean isEncoded(String contentEncoding) {
        return !effectiveTokens(contentEncoding).isEmpty();
    }

    /**
     * Decodes {@code body} according to {@code contentEncoding}.
     *
     * @param body            the raw octets as received; {@code null} is returned unchanged
     * @param contentEncoding the {@code Content-Encoding} header value, possibly {@code null}
     * @return the decoded octets, or {@code body} itself when no decoding is needed
     * @throws UnsupportedContentEncodingException if the encoding is unknown, is a multi-step
     *                                            encoding, the stream is corrupt, or the decoded
     *                                            size exceeds {@link #MAX_DECODED_BYTES}
     */
    public static byte[] decode(byte[] body, String contentEncoding)
            throws UnsupportedContentEncodingException {

        final List<String> tokens = effectiveTokens(contentEncoding);
        if (tokens.isEmpty() || body == null || body.length == 0) {
            return body;
        }
        if (tokens.size() > 1) {
            // Legal HTTP, but stacked encodings do not occur on DICT and guessing the order is a
            // good way to corrupt a body silently. Refuse, so the caller records it as a transport
            // fault rather than letting it surface as a bad signature.
            throw new UnsupportedContentEncodingException(
                    "stacked Content-Encoding is not supported: '" + contentEncoding + "'");
        }

        final String token = tokens.get(0);
        switch (token) {
            case "gzip":
            case "x-gzip":
                return inflate(body, token, /* raw */ false, /* gzip */ true);
            case "deflate":
                // RFC 7230's "deflate" means zlib-wrapped. Some servers emit a raw deflate stream
                // instead, so fall back to nowrap rather than failing a body we can in fact read.
                try {
                    return inflate(body, token, false, false);
                } catch (UnsupportedContentEncodingException zlibFailed) {
                    return inflate(body, token, true, false);
                }
            default:
                throw new UnsupportedContentEncodingException(
                        "unsupported Content-Encoding '" + contentEncoding + "'");
        }
    }

    private static byte[] inflate(byte[] body, String token, boolean raw, boolean gzip)
            throws UnsupportedContentEncodingException {

        try (InputStream source = new ByteArrayInputStream(body);
             InputStream in = gzip
                     ? new GZIPInputStream(source)
                     : new InflaterInputStream(source, new java.util.zip.Inflater(raw));
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(body.length * 3, 64))) {

            final byte[] buffer = new byte[COPY_BUFFER_BYTES];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DECODED_BYTES) {
                    throw new UnsupportedContentEncodingException(
                            "decoded body exceeds " + MAX_DECODED_BYTES + " bytes for encoding '"
                                    + token + "' - refusing to buffer further");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (UnsupportedContentEncodingException e) {
            throw e;
        } catch (IOException e) {
            throw new UnsupportedContentEncodingException(
                    "corrupt '" + token + "' stream: " + e.getMessage(), e);
        }
    }

    /**
     * Splits the header value and drops {@code identity}, which is a no-op by definition.
     */
    private static List<String> effectiveTokens(String contentEncoding) {
        final List<String> tokens = new ArrayList<>();
        if (contentEncoding == null) {
            return tokens;
        }
        for (String part : contentEncoding.split(",")) {
            final String token = part.trim().toLowerCase();
            if (!token.isEmpty() && !"identity".equals(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
