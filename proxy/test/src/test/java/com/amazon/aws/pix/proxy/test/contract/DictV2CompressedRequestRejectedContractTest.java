package com.amazon.aws.pix.proxy.test.contract;

import com.amazon.aws.pix.core.http.CompressedRequestPolicy;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives a real route to prove a compressed request is refused with 415 and, crucially, that
 * <b>nothing is signed and nothing is forwarded</b>.
 *
 * <p>The signing step is a stand-in that records whether it ran, because the assertion that matters
 * is not the status code — it is that the corrupted body never reached a signer. A 415 that still
 * signed and forwarded would look correct from the client's side while having already sent BCB a
 * signed corrupt document.
 */
public class DictV2CompressedRequestRejectedContractTest {

    private CamelContext camelContext;
    private int proxyPort;
    private int targetPort;

    /** Set if the signing step runs; it must not for a compressed request. */
    private final AtomicInteger signerInvocations = new AtomicInteger(0);
    /** What the stand-in BCB received; must stay null for a compressed request. */
    private final AtomicReference<String> forwarded = new AtomicReference<>(null);

    @Before
    public void setUp() throws Exception {
        proxyPort = freePort();
        targetPort = freePort();
        camelContext = new DefaultCamelContext();
    }

    @After
    public void tearDown() {
        if (camelContext != null) {
            camelContext.stop();
        }
    }

    /** A gzip request must be refused, unsigned and unforwarded. */
    @Test
    public void aCompressedRequestIsRefusedAndNeverSignedOrForwarded() throws Exception {
        startProxy();

        final HttpURLConnection connection = post("/api/v2/entries", gzip("<Entry/>"), "gzip");

        assertEquals("BCB does not accept compressed requests",
                CompressedRequestPolicy.REJECTION_STATUS, connection.getResponseCode());
        assertEquals("the corrupted body must never reach a signer", 0, signerInvocations.get());
        assertNull("nothing may be forwarded to BCB", forwarded.get());

        final String body = readError(connection);
        assertTrue("the refusal must explain itself: " + body,
                body.contains("Compressed request bodies are not supported"));
    }

    /**
     * Negative control, and the one that stops this from being a proxy that refuses everything: an
     * ordinary uncompressed request must still be signed and forwarded.
     */
    @Test
    public void anUncompressedRequestIsStillSignedAndForwarded() throws Exception {
        startProxy();

        final HttpURLConnection connection =
                post("/api/v2/entries", "<Entry/>".getBytes(StandardCharsets.UTF_8), null);

        assertEquals(200, connection.getResponseCode());
        assertEquals("the normal path must still sign", 1, signerInvocations.get());
        assertTrue("the normal path must still forward: " + forwarded.get(),
                String.valueOf(forwarded.get()).contains("SIGNED"));
    }

    /**
     * The bypass an independent review demonstrated, and the reason the header check alone was not
     * enough: a client that gzips the body and omits {@code Content-Encoding} used to sail through,
     * and the mojibake was signed under the PSP key and forwarded to BCB. That is the commit's own
     * stated catastrophe, reachable by an honest-but-broken client rather than an attacker.
     */
    @Test
    public void gzipBytesWithNoContentEncodingHeaderAreAlsoRefused() throws Exception {
        startProxy();

        final HttpURLConnection connection = post("/api/v2/entries", gzip("<Entry/>"), null);

        assertEquals("a compressed body must be refused however it is declared",
                CompressedRequestPolicy.REJECTION_STATUS, connection.getResponseCode());
        assertEquals("the undeclared case must never reach a signer either",
                0, signerInvocations.get());
        assertNull("and nothing may be forwarded to BCB", forwarded.get());
    }

    /**
     * Negative control for the sniffing, and the one that keeps it from becoming a liability: an
     * ordinary XML body whose bytes merely start with something unusual must still be accepted.
     * Over-rejecting a legitimate Pix request is the worse error of the two.
     */
    @Test
    public void anUncompressedBodyIsNotMistakenForGzip() throws Exception {
        startProxy();

        // 0x1f alone is not the gzip signature; only 0x1f 0x8b is.
        final byte[] body = new byte[] {0x1f, 0x3c, 'a', '/', '>'};
        final HttpURLConnection connection = post("/api/v2/entries", body, null);

        assertEquals("sniffing must not reject on a single coincidental byte",
                200, connection.getResponseCode());
        assertEquals(1, signerInvocations.get());
    }

    /** Mirrors the production ordering: reject, then convert, then sign, then forward. */
    private void startProxy() throws Exception {
        final String consumer = "netty-http:http://0.0.0.0:" + proxyPort + "?matchOnUriPrefix=true";
        final String producer = "netty-http:http://127.0.0.1:" + targetPort
                + "?bridgeEndpoint=true&throwExceptionOnFailure=false";
        final String target = "netty-http:http://0.0.0.0:" + targetPort + "?matchOnUriPrefix=true";

        final Processor rejectCompressed = exchange -> {
            final String encoding =
                    exchange.getIn().getHeader(Exchange.CONTENT_ENCODING, String.class);
            final byte[] raw = exchange.getIn().getBody(byte[].class);
            if (CompressedRequestPolicy.mustReject(encoding, raw)) {
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE,
                        CompressedRequestPolicy.REJECTION_STATUS);
                exchange.getMessage()
                        .setBody(CompressedRequestPolicy.rejectionMessage(encoding));
                exchange.getMessage().removeHeader(Exchange.CONTENT_ENCODING);
                exchange.setRouteStop(true);
            }
        };

        camelContext.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from(consumer)
                        .process(rejectCompressed)
                        .transform(body().convertToString())
                        .process(exchange -> {
                            signerInvocations.incrementAndGet();
                            exchange.getIn().setBody("SIGNED:" + exchange.getIn().getBody(String.class));
                        })
                        .to(producer);

                from(target)
                        .transform(body().convertToString())
                        .process(exchange -> forwarded.set(exchange.getIn().getBody(String.class)))
                        .setHeader("CamelHttpResponseCode", constant(200))
                        .setBody(constant("<Response/>"));
            }
        });
        camelContext.start();
    }

    private HttpURLConnection post(final String path, final byte[] body, final String encoding)
            throws Exception {
        final HttpURLConnection connection =
                (HttpURLConnection) new URL("http://127.0.0.1:" + proxyPort + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("Content-Type", "application/xml");
        if (encoding != null) {
            connection.setRequestProperty("Content-Encoding", encoding);
        }
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
        return connection;
    }

    private static byte[] gzip(final String text) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static String readError(final HttpURLConnection connection) throws Exception {
        final java.io.InputStream in = connection.getErrorStream() != null
                ? connection.getErrorStream() : connection.getInputStream();
        if (in == null) {
            return "";
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
