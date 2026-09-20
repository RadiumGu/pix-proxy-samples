package com.amazon.aws.pix.core.test.http;

import com.amazon.aws.pix.core.http.HttpContentDecoder;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link HttpContentDecoder}.
 * <p>
 * The defect these guard against was measured, not theorised: a client that follows BCB's own
 * documented recommendation and sends {@code Accept-Encoding: gzip} caused BCB to return a
 * gzip-compressed body, which the route converted straight to a {@code String} before verifying
 * the XML signature. The conversion destroyed the payload and the proxy reported "signature
 * invalid" plus HTTP 500 for a response BCB had signed correctly.
 */
public class HttpContentDecoderTest {

    /** A realistic signed DICT response; the accents matter for the charset regression below. */
    private static final String SIGNED_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<GetEntryResponse><Signature>c2ln</Signature>"
                    + "<Entry><Key>11122233300</Key><KeyType>CPF</KeyType>"
                    + "<Owner><Name>Jo\u00e3o Silva</Name></Owner></Entry>"
                    + "</GetEntryResponse>";

    private static byte[] plain() {
        return SIGNED_XML.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
            gz.finish();
            return out.toByteArray();
        }
    }

    private static byte[] zlibDeflate(byte[] raw) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DeflaterOutputStream df = new DeflaterOutputStream(out)) {
            df.write(raw);
            df.finish();
            return out.toByteArray();
        }
    }

    private static byte[] rawDeflate(byte[] raw) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DeflaterOutputStream df = new DeflaterOutputStream(
                     out, new java.util.zip.Deflater(-1, true))) {
            df.write(raw);
            df.finish();
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------- pass-through

    @Test
    public void absentEncodingReturnsTheBodyUntouched() throws Exception {
        byte[] body = plain();
        assertSame("no encoding must not copy or alter the body", body,
                HttpContentDecoder.decode(body, null));
    }

    @Test
    public void identityEncodingReturnsTheBodyUntouched() throws Exception {
        byte[] body = plain();
        assertSame(body, HttpContentDecoder.decode(body, "identity"));
        assertSame(body, HttpContentDecoder.decode(body, "  IDENTITY  "));
        assertSame(body, HttpContentDecoder.decode(body, ""));
    }

    @Test
    public void nullAndEmptyBodiesSurviveAnyEncoding() throws Exception {
        assertNull(HttpContentDecoder.decode(null, "gzip"));
        assertEquals(0, HttpContentDecoder.decode(new byte[0], "gzip").length);
    }

    // ---------------------------------------------------------------- gzip

    @Test
    public void gzipRoundTripsToTheExactSignedBytes() throws Exception {
        byte[] decoded = HttpContentDecoder.decode(gzip(plain()), "gzip");
        assertArrayEquals("verification must see byte-for-byte what BCB signed",
                plain(), decoded);
    }

    @Test
    public void gzipIsRecognisedCaseInsensitivelyAndWithWhitespace() throws Exception {
        assertArrayEquals(plain(), HttpContentDecoder.decode(gzip(plain()), "GZIP"));
        assertArrayEquals(plain(), HttpContentDecoder.decode(gzip(plain()), "  gzip "));
    }

    @Test
    public void xGzipAliasIsSupported() throws Exception {
        assertArrayEquals(plain(), HttpContentDecoder.decode(gzip(plain()), "x-gzip"));
    }

    @Test
    public void identityAlongsideGzipIsIgnoredRatherThanRefused() throws Exception {
        // "gzip, identity" is effectively just gzip; refusing it would fail a decodable body.
        assertArrayEquals(plain(), HttpContentDecoder.decode(gzip(plain()), "gzip, identity"));
    }

    // ---------------------------------------------------------------- deflate

    @Test
    public void zlibWrappedDeflateIsDecoded() throws Exception {
        assertArrayEquals(plain(), HttpContentDecoder.decode(zlibDeflate(plain()), "deflate"));
    }

    @Test
    public void rawDeflateFallsBackRatherThanFailing() throws Exception {
        assertArrayEquals("a raw deflate stream is still readable and must not be rejected",
                plain(), HttpContentDecoder.decode(rawDeflate(plain()), "deflate"));
    }

    // ---------------------------------------------------------------- refusals

    @Test
    public void unknownEncodingIsRefusedNotPassedThrough() throws Exception {
        try {
            HttpContentDecoder.decode(gzip(plain()), "br");
            fail("an undecodable encoding must not reach signature verification");
        } catch (HttpContentDecoder.UnsupportedContentEncodingException expected) {
            assertTrue(expected.getMessage().contains("br"));
        }
    }

    @Test
    public void stackedEncodingIsRefused() throws Exception {
        try {
            HttpContentDecoder.decode(gzip(plain()), "gzip, deflate");
            fail("stacked encodings must be refused rather than guessed at");
        } catch (HttpContentDecoder.UnsupportedContentEncodingException expected) {
            assertTrue(expected.getMessage().contains("stacked"));
        }
    }

    @Test
    public void corruptGzipIsRefusedNotSilentlyMangled() throws Exception {
        byte[] corrupt = gzip(plain());
        corrupt[8] ^= 0x7f;
        corrupt[corrupt.length - 3] ^= 0x7f;
        try {
            HttpContentDecoder.decode(corrupt, "gzip");
            fail("a corrupt stream must be reported, not handed on as a bad signature");
        } catch (HttpContentDecoder.UnsupportedContentEncodingException expected) {
            assertTrue(expected.getMessage().contains("gzip"));
        }
    }

    @Test
    public void plaintextLabelledGzipIsRefused() throws Exception {
        // Mislabelled body: refusing beats verifying something we cannot trust.
        try {
            HttpContentDecoder.decode(plain(), "gzip");
            fail("plaintext labelled gzip must be refused");
        } catch (HttpContentDecoder.UnsupportedContentEncodingException expected) {
            assertTrue(expected.getMessage().contains("gzip"));
        }
    }

    @Test
    public void decompressionBombIsBounded() throws Exception {
        final byte[] oneMb = new byte[1024 * 1024];
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            for (int i = 0; i < (HttpContentDecoder.MAX_DECODED_BYTES / oneMb.length) + 2; i++) {
                gz.write(oneMb);
            }
            gz.finish();
        }
        try {
            HttpContentDecoder.decode(out.toByteArray(), "gzip");
            fail("an unbounded expansion must be stopped by MAX_DECODED_BYTES");
        } catch (HttpContentDecoder.UnsupportedContentEncodingException expected) {
            assertTrue(expected.getMessage().contains("exceeds"));
        }
    }

    // ---------------------------------------------------------------- isEncoded

    @Test
    public void isEncodedDistinguishesPlaintextFromCompressed() {
        assertFalse(HttpContentDecoder.isEncoded(null));
        assertFalse(HttpContentDecoder.isEncoded(""));
        assertFalse(HttpContentDecoder.isEncoded("identity"));
        assertFalse(HttpContentDecoder.isEncoded(" , identity , "));
        assertTrue(HttpContentDecoder.isEncoded("gzip"));
        assertTrue(HttpContentDecoder.isEncoded("GZIP"));
        assertTrue(HttpContentDecoder.isEncoded("deflate"));
        assertTrue(HttpContentDecoder.isEncoded("gzip, identity"));
    }

    // ---------------------------------------------------------------- root cause

    /**
     * Pins the measured root cause: converting a gzip body to a {@code String} destroys it
     * irreversibly, so decoding cannot be moved after the conversion.
     * <p>
     * The gzip magic is {@code 0x1f 0x8b}. {@code 0x8b} is not a valid stand-alone UTF-8 sequence,
     * so it becomes U+FFFD and the original octet is unrecoverable - re-encoding the string yields
     * different bytes. This is why {@link HttpContentDecoder} takes {@code byte[]}.
     */
    @Test
    public void stringConversionDestroysGzipWhichIsWhyDecodingComesFirst() throws Exception {
        final byte[] compressed = gzip(plain());

        final String viaString = new String(compressed, StandardCharsets.UTF_8);
        final byte[] roundTripped = viaString.getBytes(StandardCharsets.UTF_8);

        assertEquals("gzip magic byte 0 survives", 0x1f, compressed[0] & 0xff);
        assertEquals("gzip magic byte 1 is 0x8b", 0x8b, compressed[1] & 0xff);
        assertEquals("0x8b becomes the replacement character", '\ufffd', viaString.charAt(1));
        assertNotEquals("the round trip is lossy, so the body cannot be recovered later",
                compressed.length, roundTripped.length);

        // Decoding first, then converting, is lossless - the order the route must use.
        final byte[] decodedFirst = HttpContentDecoder.decode(compressed, "gzip");
        assertEquals(SIGNED_XML, new String(decodedFirst, StandardCharsets.UTF_8));
    }
}
