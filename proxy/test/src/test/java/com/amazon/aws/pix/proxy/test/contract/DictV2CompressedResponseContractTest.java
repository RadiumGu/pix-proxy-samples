package com.amazon.aws.pix.proxy.test.contract;

import com.amazon.aws.pix.core.http.HttpContentDecoder;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

/**
 * BCB DICT v2 <strong>compressed-response contract</strong>.
 *
 * <p>BCB's API page recommends that clients ask for compression: "É recomendável também que se
 * utilize compressão... adicione nas requisições o header {@code Accept-Encoding: gzip}". The proxy
 * forwards client headers transparently, so that header reaches BCB and BCB answers with a
 * gzip-compressed body. The XML Digital Signature is over the XML <em>document</em>, so the body
 * has to be decoded before verification or verification cannot possibly succeed.
 *
 * <p>Before the fix this was not a theoretical concern: the route handed the response straight to
 * {@code convertToString()}, the gzip magic {@code 0x1f 0x8b} became {@code 31, U+FFFD}, and the
 * proxy answered HTTP 500 with "signature invalid" for a response BCB had signed correctly.
 *
 * <h2>What this proves, and the seam it cannot cross</h2>
 *
 * <p><strong>Proves</strong>: that a camel-netty-http hop which decodes before converting to a
 * String recovers exactly the bytes the far end compressed, strips {@code Content-Encoding} so the
 * caller does not try to inflate plaintext, and delivers the full body to the caller.
 *
 * <p><strong>Seam</strong>: {@code DecodeResponseProcessor} itself lives in
 * {@code proxy/cloudhsm/proxy}, which this module deliberately does NOT depend on - that module
 * needs the CloudHSM JCE rpm and is {@code continue-on-error} in CI. So this test exercises the
 * same {@link HttpContentDecoder} call the production processor makes, and
 * {@code .github/scripts/check-transport-contract.sh} separately pins that the production route
 * really does call {@code DecodeResponseProcessor} <em>before</em> its {@code convertToString()}.
 * Test proves the mechanism; gate proves the wiring. Neither alone is enough.
 *
 * <p>Runs over plain HTTP on loopback, so it says nothing about TLS, mTLS or BCB homologação.
 */
public class DictV2CompressedResponseContractTest {

    private static final String V2_PATH = "/api/v2/entries/11122233300";

    /** A signed-looking DICT response with an accented name, so charset damage is visible. */
    private static final String SIGNED_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<GetEntryResponse><Signature>c2lnbmF0dXJl</Signature>"
                    + "<Entry><Key>11122233300</Key><KeyType>CPF</KeyType>"
                    + "<Owner><Name>João Silva</Name></Owner></Entry>"
                    + "</GetEntryResponse>";

    private CamelContext camelContext;
    private int proxyPort;
    private int targetPort;

    /** What the verification stage would have been handed. */
    private final AtomicReference<String> bodyAtVerification = new AtomicReference<>();
    /** The Content-Encoding still present when verification runs. */
    private final AtomicReference<String> encodingAtVerification = new AtomicReference<>();
    /** Set when decoding failed, standing in for the production exchange property. */
    private final AtomicReference<String> decodeError = new AtomicReference<>();

    @Before
    public void setUp() throws Exception {
        proxyPort = freePort();
        targetPort = freePort();
        camelContext = new DefaultCamelContext();
    }

    @After
    public void tearDown() throws Exception {
        if (camelContext != null) {
            camelContext.stop();
        }
    }

    // ---------------------------------------------------------------- positive contract

    @Test
    public void gzipResponseReachesVerificationAsTheExactSignedXml() throws Exception {
        start(gzipOf(SIGNED_XML), "gzip", /* decode */ true);
        get();

        Assert.assertEquals("verification must see the XML BCB signed, not the compressed octets",
                SIGNED_XML, bodyAtVerification.get());
        Assert.assertNull("decoding must not have failed", decodeError.get());
    }

    @Test
    public void contentEncodingIsStrippedSoTheCallerDoesNotInflatePlaintext() throws Exception {
        start(gzipOf(SIGNED_XML), "gzip", true);
        get();

        Assert.assertNull("Content-Encoding must be removed once the body is decoded",
                encodingAtVerification.get());
    }

    @Test
    public void theCallerReceivesTheCompleteDecodedBody() throws Exception {
        start(gzipOf(SIGNED_XML), "gzip", true);

        Assert.assertEquals("a stale Content-Length would truncate the response",
                SIGNED_XML, get());
    }

    @Test
    public void plainResponsesAreUnaffected() throws Exception {
        start(SIGNED_XML.getBytes(StandardCharsets.UTF_8), null, true);
        get();

        Assert.assertEquals(SIGNED_XML, bodyAtVerification.get());
    }

    @Test
    public void identityEncodingIsTreatedAsPlaintext() throws Exception {
        start(SIGNED_XML.getBytes(StandardCharsets.UTF_8), "identity", true);
        get();

        Assert.assertEquals(SIGNED_XML, bodyAtVerification.get());
    }

    @Test
    public void undecodableEncodingIsRecordedInsteadOfBlamingTheSignature() throws Exception {
        start(gzipOf(SIGNED_XML), "br", true);
        get();

        Assert.assertNotNull(
                "an undecodable body must be recorded as a transport fault, so the audit does not "
                        + "call it a signature mismatch", decodeError.get());
        Assert.assertTrue("the recorded reason should name the offending encoding",
                decodeError.get().contains("br"));
    }

    // ---------------------------------------------------------------- negative control

    /**
     * The control that makes the assertions above mean something: with the decode step removed,
     * the body reaching verification is NOT the signed XML. If this ever starts seeing clean XML,
     * the positive tests have stopped testing anything.
     */
    @Test
    public void withoutDecodingTheBodyReachingVerificationIsDestroyed() throws Exception {
        start(gzipOf(SIGNED_XML), "gzip", /* decode */ false);
        get();

        final String seen = bodyAtVerification.get();
        Assert.assertNotEquals("without decoding, verification cannot see the signed XML",
                SIGNED_XML, seen);
        Assert.assertTrue("the replacement character is the signature of the lossy conversion",
                seen != null && seen.indexOf('\ufffd') >= 0);
    }

    // ---------------------------------------------------------------- harness

    /**
     * @param responseBody    what the BCB stand-in returns
     * @param contentEncoding the Content-Encoding it claims, or {@code null}
     * @param decode          whether the proxy decodes before converting to a String, mirroring
     *                        the production route's {@code DecodeResponseProcessor} step
     */
    private void start(byte[] responseBody, String contentEncoding, boolean decode)
            throws Exception {

        final String consumer = "netty-http:http://0.0.0.0:" + proxyPort + "?matchOnUriPrefix=true";
        final String producer = "netty-http:http://127.0.0.1:" + targetPort
                + "?bridgeEndpoint=true&throwExceptionOnFailure=false";
        final String target = "netty-http:http://0.0.0.0:" + targetPort + "?matchOnUriPrefix=true";

        camelContext.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                org.apache.camel.model.ProcessorDefinition<?> route = from(consumer).to(producer);
                if (decode) {
                    route = route.process(new DecodingProcessor());
                }
                route.transform(body().convertToString())
                        .process(new VerificationSpy());

                from(target).process(exchange -> {
                    exchange.getIn().setHeader("CamelHttpResponseCode", 200);
                    exchange.getIn().setHeader("Content-Type", "application/xml;charset=utf-8");
                    if (contentEncoding != null) {
                        exchange.getIn().setHeader("Content-Encoding", contentEncoding);
                    }
                    exchange.getIn().setBody(responseBody);
                });
            }
        });
        camelContext.start();
    }

    /** Mirrors production's {@code DecodeResponseProcessor}; see the class javadoc on the seam. */
    private class DecodingProcessor implements Processor {
        @Override
        public void process(Exchange exchange) {
            final String encoding = exchange.getIn().getHeader("Content-Encoding", String.class);
            if (!HttpContentDecoder.isEncoded(encoding)) {
                return;
            }
            final byte[] encoded = exchange.getIn().getBody(byte[].class);
            try {
                exchange.getIn().setBody(HttpContentDecoder.decode(encoded, encoding));
                exchange.getIn().removeHeader("Content-Encoding");
                exchange.getIn().removeHeader("Content-Length");
            } catch (HttpContentDecoder.UnsupportedContentEncodingException e) {
                decodeError.set(e.getMessage());
            }
        }
    }

    /** Stands where {@code VerifyResponseProcessor} stands, recording what it would have verified. */
    private class VerificationSpy implements Processor {
        @Override
        public void process(Exchange exchange) {
            bodyAtVerification.set(exchange.getIn().getBody(String.class));
            final Object encoding = exchange.getIn().getHeader("Content-Encoding");
            encodingAtVerification.set(encoding == null ? null : encoding.toString());
        }
    }

    private static byte[] gzipOf(String xml) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(xml.getBytes(StandardCharsets.UTF_8));
            gz.finish();
            return out.toByteArray();
        }
    }

    /** @return the body the caller actually received */
    private String get() throws Exception {
        final URL url = new URL("http://127.0.0.1:" + proxyPort + V2_PATH);
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        // Exactly what BCB's API page tells clients to send.
        connection.setRequestProperty("Accept-Encoding", "gzip");
        connection.setRequestProperty("PI-RequestingParticipant", "12345678");
        connection.setRequestProperty("PI-PayerId", "12345678901");
        connection.setRequestProperty("PI-EndToEndId", "E1234567820260920123400000000001");
        try {
            connection.getResponseCode();
            return read(connection);
        } finally {
            connection.disconnect();
        }
    }

    private String read(HttpURLConnection connection) {
        InputStream stream;
        try {
            stream = connection.getInputStream();
        } catch (IOException e) {
            stream = connection.getErrorStream();
        }
        if (stream == null) {
            return null;
        }
        try (InputStream in = stream) {
            final ByteArrayOutputStream sink = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                sink.write(buffer, 0, read);
            }
            return new String(sink.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
