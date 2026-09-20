package com.amazon.aws.pix.core.test.http;

import com.amazon.aws.pix.core.http.CompressedRequestPolicy;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Pins the refusal of compressed request bodies, and the reason it has to be a refusal. */
public class CompressedRequestPolicyTest {

    @Test
    public void gzipAndDeflateRequestsAreRejected() {
        assertTrue(CompressedRequestPolicy.mustReject("gzip"));
        assertTrue(CompressedRequestPolicy.mustReject("GZIP"));
        assertTrue(CompressedRequestPolicy.mustReject("deflate"));
        assertTrue(CompressedRequestPolicy.mustReject(" gzip "));
        assertTrue(CompressedRequestPolicy.mustReject("gzip, deflate"));
    }

    /**
     * Negative control. Without this, a policy that rejected everything would satisfy the test
     * above while refusing every ordinary Pix request - which is a far worse failure than the one
     * being fixed.
     */
    @Test
    public void ordinaryUncompressedRequestsAreNotRejected() {
        assertFalse("absent header is the normal case", CompressedRequestPolicy.mustReject(null));
        assertFalse(CompressedRequestPolicy.mustReject(""));
        assertFalse(CompressedRequestPolicy.mustReject("  "));
        assertFalse("identity means no encoding", CompressedRequestPolicy.mustReject("identity"));
    }

    /** RFC 7231's status for an unsupported Content-Encoding. */
    @Test
    public void theStatusIs415() {
        assertEquals(415, CompressedRequestPolicy.REJECTION_STATUS);
    }

    /**
     * Root-cause regression for what rejecting prevents: a gzip body that reaches
     * {@code convertToString()} is destroyed irreversibly, and the old route then signed the
     * wreckage. This reproduces the corruption directly so the reason for the refusal cannot be
     * mistaken for fussiness.
     */
    @Test
    public void aGzipBodyIsIrreversiblyDestroyedByAStringConversion() throws Exception {
        final String original = "<Entry><Key>+5561999999999</Key></Entry>";

        final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(original.getBytes(StandardCharsets.UTF_8));
        }
        final byte[] gzipBytes = compressed.toByteArray();

        assertEquals("gzip magic byte 1", (byte) 0x1f, gzipBytes[0]);
        assertEquals("gzip magic byte 2", (byte) 0x8b, gzipBytes[1]);

        // What the route did before signing.
        final String mojibake = new String(gzipBytes, StandardCharsets.UTF_8);
        final byte[] roundTripped = mojibake.getBytes(StandardCharsets.UTF_8);

        assertNotEquals("the conversion must be shown to be lossy",
                gzipBytes.length, roundTripped.length);
        assertEquals("0x8b decodes to the replacement character, losing the original byte",
                '\uFFFD', mojibake.charAt(1));

        // And this is the request the policy refuses to sign.
        assertTrue(CompressedRequestPolicy.mustReject("gzip"));
    }

    /** The message must not echo raw attacker-controlled bytes back to the caller. */
    @Test
    public void theRejectionMessageIsSanitisedAndBounded() {
        final String nasty = "gzip\r\nX-Injected: 1";
        final String message = CompressedRequestPolicy.rejectionMessage(nasty);

        assertFalse("no CR may survive into a response body", message.contains("\r"));
        assertFalse("no LF may survive into a response body", message.contains("\n"));

        final StringBuilder longValue = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longValue.append('g');
        }
        assertTrue("an over-long value must be truncated",
                CompressedRequestPolicy.rejectionMessage(longValue.toString()).contains("..."));
    }
}
