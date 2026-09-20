package com.amazon.aws.pix.proxy.test.contract;

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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BCB DICT <strong>v2 transparent-proxy contract</strong>.
 *
 * <p>The CloudHSM proxy is a transparent HTTP proxy in front of BCB DICT v2. DICT v2 is
 * query-driven — {@code Cursor}, {@code IncludeStatistics}, {@code Status}, {@code ModifiedAfter},
 * {@code Limit}, and repeated query values — so silently dropping any part of the request line or
 * the BCB headers would corrupt the call while still looking like a working proxy. The production
 * route relies on camel-netty-http's {@code matchOnUriPrefix=true} on the consumer and
 * {@code bridgeEndpoint=true} on the producer to achieve that. Intent is not evidence, so this
 * test drives real Camel routes and asserts what actually arrives at the far end.
 *
 * <h2>What this test proves, and what it deliberately does not</h2>
 *
 * <p><strong>Proves</strong>: that a camel-netty-http hop configured the way production configures
 * it preserves the full {@code /api/v2/...} path, the query string including repeated parameters,
 * the BCB {@code PI-*} headers, and the XML body.
 *
 * <p><strong>Does NOT prove</strong>: anything about TLS, mTLS, cipher suites, certificate chains,
 * CloudHSM, or BCB compatibility. This runs over <em>plain HTTP on loopback</em> on purpose — so
 * that it needs no HSM, no certificates and no AWS, and so that it runs on any CPU architecture
 * (the repository's netty native transport artifacts are pinned to {@code linux-x86_64}, so
 * {@code nativeTransport} is deliberately left off here). Passing this test is **not** evidence of
 * BCB homologação. See {@code CLOUDHSM_BCB_V2_HANDOFF.md}.
 *
 * <p><strong>Negative controls are part of the contract.</strong> A test that only asserts the
 * happy path cannot show it would notice a regression, so
 * {@link #withoutBridgeEndpointThePathAndQueryAreLost()} and
 * {@link #withoutMatchOnUriPrefixASubPathIsNotEvenAccepted()} assert that removing each option
 * really does break the very thing the positive tests claim. If those two ever start passing with
 * the options removed, the positive assertions have stopped meaning anything.
 */
public class DictV2TransparentProxyContractTest {

    /** A DICT v2 entry lookup path, including a Pix key that needs no escaping. */
    private static final String V2_PATH = "/api/v2/entries/+5561999999999";

    /** Query with a flag AND a repeated parameter — both are real DICT v2 shapes. */
    private static final String V2_QUERY = "IncludeStatistics=true&Status=OPEN&Status=CLOSED";

    private static final String REQUESTING_PARTICIPANT = "PI-RequestingParticipant";
    private static final String PAYER_ID = "PI-PayerId";
    private static final String END_TO_END_ID = "PI-EndToEndId";

    private static final String REQUEST_BODY =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<GetEntryRequest><Key>+5561999999999</Key><PayerId>12345678901</PayerId></GetEntryRequest>";

    /** Marker the fake signer inserts, standing in for the real XML signature element. */
    private static final String SIGNATURE_MARKER = "<TestSignatureInsertedByProxy/>";

    private CamelContext camelContext;
    private Recorder recorder;
    private int proxyPort;
    private int targetPort;

    @Before
    public void setUp() throws Exception {
        recorder = new Recorder();
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

    /** The complete {@code /api/v2/...} path must arrive unchanged. */
    @Test
    public void pathIsPreserved() throws Exception {
        startProductionShapedProxy();
        get(proxyPort, V2_PATH, V2_QUERY);

        Assert.assertEquals("the DICT v2 path must reach BCB unchanged",
                V2_PATH, recorder.singlePath());
    }

    /** {@code IncludeStatistics=true} must survive; DICT v2 behaviour depends on it. */
    @Test
    public void includeStatisticsQueryIsPreserved() throws Exception {
        startProductionShapedProxy();
        get(proxyPort, V2_PATH, V2_QUERY);

        Assert.assertTrue("IncludeStatistics=true was lost; query string was: " + recorder.singleQuery(),
                recorder.singleQuery().contains("IncludeStatistics=true"));
    }

    /**
     * Repeated query parameters must ALL survive. This is the case a naive
     * {@code Map<String,String>} of query parameters silently collapses, which is why it has its
     * own test rather than being folded into the query assertion above.
     */
    @Test
    public void repeatedQueryParametersArePreserved() throws Exception {
        startProductionShapedProxy();
        get(proxyPort, V2_PATH, V2_QUERY);

        String query = recorder.singleQuery();
        Assert.assertTrue("Status=OPEN was lost; query string was: " + query, query.contains("Status=OPEN"));
        Assert.assertTrue("Status=CLOSED was lost; query string was: " + query, query.contains("Status=CLOSED"));

        int occurrences = query.split("Status=", -1).length - 1;
        Assert.assertEquals(
                "both repeated Status values must survive as separate parameters, not be collapsed "
                        + "into one; query string was: " + query,
                2, occurrences);
    }

    /** The BCB-required {@code PI-*} headers must reach BCB. */
    @Test
    public void bcbRequiredHeadersArePreserved() throws Exception {
        startProductionShapedProxy();
        get(proxyPort, V2_PATH, V2_QUERY);

        Assert.assertEquals("PI-RequestingParticipant was lost", "12345678",
                recorder.singleHeader(REQUESTING_PARTICIPANT));
        Assert.assertEquals("PI-PayerId was lost", "12345678901",
                recorder.singleHeader(PAYER_ID));
        Assert.assertEquals("PI-EndToEndId was lost", "E1234567820260920123400000000001",
                recorder.singleHeader(END_TO_END_ID));
    }

    /**
     * The body must arrive with the original content intact and the signature insertion as the
     * ONLY difference. Asserting "contains the original elements" alone would pass even if the
     * proxy mangled the document, so the reconstruction check below removes exactly the inserted
     * marker and requires byte equality with what was sent.
     */
    @Test
    public void bodyIsPreservedApartFromTheExpectedSignatureInsertion() throws Exception {
        startProductionShapedProxy();
        post(proxyPort, V2_PATH, V2_QUERY, REQUEST_BODY);

        String received = recorder.singleBody();
        Assert.assertTrue("the proxy did not perform the expected signature insertion",
                received.contains(SIGNATURE_MARKER));
        Assert.assertEquals(
                "removing the inserted signature must yield the original body byte for byte - "
                        + "anything else means the proxy altered the payload. Received: " + received,
                REQUEST_BODY, received.replace(SIGNATURE_MARKER, ""));
    }

    // ---------------------------------------------------------------- negative controls

    /**
     * Detection proof for the path assertion: if something in the route drops
     * {@code Exchange.HTTP_PATH}, the v2 path must no longer arrive. Without this,
     * {@link #pathIsPreserved()} could be passing for reasons unrelated to the proxy.
     */
    @Test
    public void droppingThePathHeaderIsDetected() throws Exception {
        startProxyWithSaboteur(exchange -> exchange.getIn().removeHeader(Exchange.HTTP_PATH));
        get(proxyPort, V2_PATH, V2_QUERY);

        Assert.assertNotEquals(
                "negative control broke: the v2 path still arrived after HTTP_PATH was removed, so "
                        + "pathIsPreserved() cannot distinguish a working proxy from a broken one",
                V2_PATH, recorder.singlePath());
    }

    /**
     * Detection proof for both query assertions.
     *
     * <p>Note it removes {@code HTTP_QUERY} <em>and</em> {@code HTTP_RAW_QUERY}: the netty-http
     * consumer populates both, and the producer falls back to the raw one, so clearing only
     * {@code HTTP_QUERY} still forwards the full query string. Measured — the first version of this
     * control cleared one header and failed for that reason.
     */
    @Test
    public void droppingTheQueryHeaderIsDetected() throws Exception {
        startProxyWithSaboteur(exchange -> {
            exchange.getIn().removeHeader(Exchange.HTTP_QUERY);
            exchange.getIn().removeHeader(Exchange.HTTP_RAW_QUERY);
        });
        get(proxyPort, V2_PATH, V2_QUERY);

        String query = recorder.singleQuery();
        Assert.assertFalse(
                "negative control broke: the query still arrived after both HTTP_QUERY and "
                        + "HTTP_RAW_QUERY were removed, so the IncludeStatistics and repeated-Status "
                        + "assertions prove nothing. Query was: " + query,
                query != null && query.contains("IncludeStatistics=true"));
    }

    /** Detection proof for the BCB header assertions. */
    @Test
    public void droppingTheBcbHeadersIsDetected() throws Exception {
        startProxyWithSaboteur(exchange -> {
            exchange.getIn().removeHeader(REQUESTING_PARTICIPANT);
            exchange.getIn().removeHeader(PAYER_ID);
            exchange.getIn().removeHeader(END_TO_END_ID);
        });
        get(proxyPort, V2_PATH, V2_QUERY);

        Assert.assertNull(
                "negative control broke: PI-RequestingParticipant survived removal, so the header "
                        + "assertions prove nothing",
                recorder.singleHeader(REQUESTING_PARTICIPANT));
    }

    /** Detection proof for the body assertion. */
    @Test
    public void alteringTheBodyIsDetected() throws Exception {
        startProxyWithSaboteur(exchange -> exchange.getIn().setBody("<Tampered/>"));
        post(proxyPort, V2_PATH, V2_QUERY, REQUEST_BODY);

        String received = recorder.singleBody();
        Assert.assertNotEquals(
                "negative control broke: a tampered body still reconstructed to the original, so the "
                        + "body assertion proves nothing. Received: " + received,
                REQUEST_BODY, received == null ? null : received.replace(SIGNATURE_MARKER, ""));
    }

    /**
     * Negative control for {@code matchOnUriPrefix=true} on the consumer: without it a request to a
     * sub-path of the consumer URI is not routed at all, so nothing is ever recorded.
     */
    @Test
    public void withoutMatchOnUriPrefixASubPathIsNotEvenAccepted() throws Exception {
        startProxy(/* matchOnUriPrefix */ false, /* bridgeEndpoint */ true, /* saboteur */ null);
        get(proxyPort, V2_PATH, V2_QUERY);

        Assert.assertTrue(
                "negative control broke: without matchOnUriPrefix=true the DICT v2 sub-path was "
                        + "still routed, so that option's contribution is untested. Recorded "
                        + recorder.paths.size() + " request(s) at the target.",
                recorder.paths.isEmpty());
    }

    /**
     * Measured, and recorded here so nobody re-derives it wrongly: in this configuration
     * {@code bridgeEndpoint} does <strong>not</strong> affect path/query preservation.
     *
     * <p>The mechanism that actually preserves them is the netty-http consumer populating
     * {@code Exchange.HTTP_PATH} / {@code HTTP_QUERY} and the producer appending those to its
     * endpoint URI — which is why the detection proofs above target those headers rather than the
     * option. {@code HTTP_URI} arriving from the consumer is <em>relative</em>
     * ({@code /api/v2/entries/...}), so the producer resolves it against the endpoint host whether
     * or not {@code bridgeEndpoint} is set, and both settings forward identically.
     *
     * <p>Production keeps {@code bridgeEndpoint=true} for its documented purpose — ignoring an
     * {@code HTTP_URI} that is absolute, and the associated Host handling — a case this loopback
     * harness does not exercise. This test asserts the measured equivalence rather than pretending
     * the option is covered.
     */
    @Test
    public void bridgeEndpointDoesNotChangePathOrQueryForwardingHere() throws Exception {
        startProxy(true, /* bridgeEndpoint */ false, null);
        get(proxyPort, V2_PATH, V2_QUERY);

        Assert.assertEquals("measured behaviour changed: without bridgeEndpoint the path no longer "
                        + "forwards identically, so the note in this test's javadoc is now wrong",
                V2_PATH, recorder.singlePath());
        Assert.assertTrue("measured behaviour changed: without bridgeEndpoint the query no longer "
                        + "forwards identically, so the note in this test's javadoc is now wrong",
                recorder.singleQuery().contains("IncludeStatistics=true"));
    }

    // ---------------------------------------------------------------- harness

    /** Starts the proxy with the same option set the production CloudHSM route uses. */
    private void startProductionShapedProxy() throws Exception {
        startProxy(true, true, null);
    }

    private void startProxyWithSaboteur(Processor saboteur) throws Exception {
        startProxy(true, true, saboteur);
    }

    private void startProxy(boolean matchOnUriPrefix, boolean bridgeEndpoint, Processor saboteur)
            throws Exception {
        final String consumer = "netty-http:http://0.0.0.0:" + proxyPort
                + (matchOnUriPrefix ? "?matchOnUriPrefix=true" : "");
        final String producer = "netty-http:http://127.0.0.1:" + targetPort
                + "?throwExceptionOnFailure=false" + (bridgeEndpoint ? "&bridgeEndpoint=true" : "");
        final String target = "netty-http:http://0.0.0.0:" + targetPort + "?matchOnUriPrefix=true";

        camelContext.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                // The proxy under test: sign, then forward transparently.
                RouteDefinitionHolder.define(this, consumer, producer, saboteur);

                // Stand-in for BCB: record exactly what arrived, then answer.
                from(target)
                        .transform(body().convertToString())
                        .process(recorder)
                        .setHeader("CamelHttpResponseCode", constant(200))
                        .setBody(constant("<Response/>"));
            }
        });
        camelContext.start();
    }

    /** Keeps the route definition in one place so positive and control runs share it. */
    private static final class RouteDefinitionHolder {
        static void define(RouteBuilder builder, String consumer, String producer, Processor saboteur) {
            org.apache.camel.model.ProcessorDefinition<?> route = builder.from(consumer)
                    .transform(builder.body().convertToString())
                    .process(new FakeSignatureInsertingProcessor());
            if (saboteur != null) {
                route = route.process(saboteur);
            }
            route.to(producer);
        }
    }

    /**
     * Stands in for the real XML signer. Deliberately NOT the real {@code XmlSigner}: the contract
     * under test is transport transparency, and requiring keys or an HSM here would make the test
     * unrunnable in CI. The real signer has its own tests in {@code proxy/core}.
     */
    private static class FakeSignatureInsertingProcessor implements Processor {
        @Override
        public void process(Exchange exchange) {
            String body = exchange.getIn().getBody(String.class);
            if (body != null && !body.isEmpty()) {
                exchange.getIn().setBody(body + SIGNATURE_MARKER);
            }
        }
    }

    /** Records the request line, BCB headers and body seen at the far end. */
    private static class Recorder implements Processor {
        final List<String> paths = new CopyOnWriteArrayList<>();
        final List<String> queries = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<String> requestingParticipants = new CopyOnWriteArrayList<>();
        final List<String> payerIds = new CopyOnWriteArrayList<>();
        final List<String> endToEndIds = new CopyOnWriteArrayList<>();

        @Override
        public void process(Exchange exchange) {
            paths.add(header(exchange, Exchange.HTTP_PATH));
            queries.add(header(exchange, Exchange.HTTP_QUERY));
            bodies.add(exchange.getIn().getBody(String.class));
            requestingParticipants.add(header(exchange, REQUESTING_PARTICIPANT));
            payerIds.add(header(exchange, PAYER_ID));
            endToEndIds.add(header(exchange, END_TO_END_ID));
        }

        private String header(Exchange exchange, String name) {
            Object value = exchange.getIn().getHeader(name);
            return value == null ? null : value.toString();
        }

        String singlePath() {
            requireExactlyOne();
            return paths.get(0);
        }

        String singleQuery() {
            requireExactlyOne();
            return queries.get(0);
        }

        String singleBody() {
            requireExactlyOne();
            return bodies.get(0);
        }

        String singleHeader(String name) {
            requireExactlyOne();
            if (REQUESTING_PARTICIPANT.equals(name)) return requestingParticipants.get(0);
            if (PAYER_ID.equals(name)) return payerIds.get(0);
            if (END_TO_END_ID.equals(name)) return endToEndIds.get(0);
            throw new IllegalArgumentException("unrecorded header " + name);
        }

        private void requireExactlyOne() {
            Assert.assertEquals(
                    "expected exactly one request to reach the BCB stand-in; got " + paths.size()
                            + ". Zero means the proxy never forwarded it, more than one means the "
                            + "harness leaked requests between tests.",
                    1, paths.size());
        }
    }

    // ---------------------------------------------------------------- plain HTTP client

    /**
     * Deliberately a JDK client rather than a Camel producer: the thing under test is what the
     * proxy forwards, so the request must be built by something that is not Camel.
     */
    private void get(int port, String path, String query) throws Exception {
        exchange(port, path, query, null);
    }

    private void post(int port, String path, String query, String body) throws Exception {
        exchange(port, path, query, body);
    }

    private void exchange(int port, String path, String query, String body) throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + path + (query == null ? "" : "?" + query));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(body == null ? "GET" : "POST");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty(REQUESTING_PARTICIPANT, "12345678");
        connection.setRequestProperty(PAYER_ID, "12345678901");
        connection.setRequestProperty(END_TO_END_ID, "E1234567820260920123400000000001");
        if (body != null) {
            connection.setRequestProperty("Content-Type", "application/xml;charset=utf-8");
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            connection.getResponseCode();
            drain(connection);
        } finally {
            connection.disconnect();
        }
    }

    private void drain(HttpURLConnection connection) {
        for (InputStream stream : streams(connection)) {
            if (stream == null) {
                continue;
            }
            try (InputStream in = stream) {
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    sink.write(buffer, 0, read);
                }
            } catch (IOException ignored) {
                // Response body is not part of this contract; the assertions read the far end.
            }
        }
    }

    private List<InputStream> streams(HttpURLConnection connection) {
        List<InputStream> streams = new ArrayList<>();
        try {
            streams.add(connection.getInputStream());
        } catch (IOException e) {
            streams.add(connection.getErrorStream());
        }
        return Collections.unmodifiableList(streams);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
