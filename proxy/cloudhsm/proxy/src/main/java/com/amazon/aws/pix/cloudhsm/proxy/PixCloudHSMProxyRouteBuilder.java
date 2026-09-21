package com.amazon.aws.pix.cloudhsm.proxy;

import com.amazon.aws.pix.cloudhsm.proxy.camel.netty.NettyHttpClientInitializerFactory;
import com.amazon.aws.pix.cloudhsm.proxy.camel.netty.PixHttpHeaderFilterStrategy;
import com.amazon.aws.pix.cloudhsm.proxy.camel.netty.NettySSLContextParameters;
import com.amazon.aws.pix.cloudhsm.proxy.processor.CaptureRequestProcessor;
import com.amazon.aws.pix.cloudhsm.proxy.processor.DecodeResponseProcessor;
import com.amazon.aws.pix.cloudhsm.proxy.processor.LogRequestResponseProcessor;
import com.amazon.aws.pix.cloudhsm.proxy.processor.RejectCompressedRequestProcessor;
import com.amazon.aws.pix.cloudhsm.proxy.processor.SignRequestProcessor;
import com.amazon.aws.pix.cloudhsm.proxy.processor.VerifyResponseProcessor;
import com.amazon.aws.pix.core.util.KeyStoreUtil;
import com.amazon.aws.pix.core.xml.Iso20022XmlSigner;
import com.amazon.aws.pix.core.xml.XmlSigner;
import com.cavium.cfm2.CFM2Exception;
import com.cavium.cfm2.LoginManager;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import lombok.AllArgsConstructor;
import org.apache.camel.builder.EndpointConsumerBuilder;
import org.apache.camel.builder.EndpointProducerBuilder;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.json.JSONObject;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.PutRecordBatchRequest;
import software.amazon.awssdk.services.firehose.model.PutRecordBatchResponse;
import software.amazon.awssdk.services.firehose.model.Record;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@lombok.extern.slf4j.Slf4j
@ApplicationScoped
public class PixCloudHSMProxyRouteBuilder extends EndpointRouteBuilder {

    @ConfigProperty(name = "aws.default.region")
    String awsDefaultRegion;

    @AllArgsConstructor
    enum Secret {
        CloudHSMSecret("HSM_USER", "HSM_PASSWORD");

        public final String user;
        public final String password;

        public String getSecretId() {
            return String.format("/pix/proxy/cloudhsm/%s", this.name());
        }
    }

    enum Param {
        CloudHSMClusterId,
        CloudHSMCustomerCA,
        MtlsKeyLabel,
        MtlsCertificate,
        SignatureKeyLabel,
        SignatureCertificate,
        BcbMtlsCertificate,
        BcbSignatureCertificate,
        BcbDictEndpoint,
        BcbSpiEndpoint,
        SpiAuditStream,
        DictAuditStream;

        public static final String PATH = "/pix/proxy/cloudhsm/";

        public String getParamName() {
            return String.format("%s%s", PATH, this.name());
        }
    }

    private Map<String, String> parameters;
    private KeyStore cloudHsmKeyStore;
    private SslContext sslContext;
    private XmlSigner xmlSigner;
    private Iso20022XmlSigner iso20022XmlSigner;
    private FirehoseClient firehoseClient;

    @PostConstruct
    void init() throws Exception {
        loadParameters();
        loadCloudHsmKeyStore();
        createSslContext();
        createXmlSigners();
        createFirehoseClient();
    }

    /**
     * Durable fallback for audit records Firehose refuses. The directory is overridable because a
     * spool on the container filesystem dies with the task; point it at a mounted volume in
     * production and ship the file.
     */
    private final com.amazon.aws.pix.core.audit.AuditSpool auditSpool =
            new com.amazon.aws.pix.core.audit.AuditSpool(java.nio.file.Paths.get(
                    System.getProperty("pix.audit.spool.dir", "/work")));

    /**
     * Queue depth for off-path audit delivery. Bounded on purpose: an unbounded queue would turn a
     * Firehose outage into heap exhaustion, trading a lost audit record for a total Pix outage.
     * Overflow spills to {@link #auditSpool} rather than blocking the caller or dropping silently.
     */
    private static final int AUDIT_QUEUE_CAPACITY =
            Integer.getInteger("pix.audit.queue.capacity", 10_000);

    /** One writer per delivery stream; the DICT and SPI routes must not share a queue. */
    private final Map<String, com.amazon.aws.pix.core.audit.AsyncAuditWriter> auditWriters =
            new HashMap<>();

    /**
     * Builds the off-path batch writer for one delivery stream.
     *
     * <p>Uses {@code PutRecordBatch} rather than one {@code PutRecord} per transaction: up to 500
     * records per call, which is what lets a single writer thread keep pace with a proxy that is
     * signing continuously. Firehose reports per-record failures <em>inside</em> a 200 response, so
     * {@code failedPutCount} is checked explicitly — treating HTTP 200 as success would lose exactly
     * the records that were rejected, which is the silent-loss shape this work exists to remove.
     */
    private com.amazon.aws.pix.core.audit.AsyncAuditWriter auditWriterFor(final String streamName) {
        return auditWriters.computeIfAbsent(streamName, stream -> {
            final com.amazon.aws.pix.core.audit.AsyncAuditWriter writer =
                    new com.amazon.aws.pix.core.audit.AsyncAuditWriter(
                            AUDIT_QUEUE_CAPACITY,
                            records -> {
                                final PutRecordBatchResponse response =
                                        firehoseClient.putRecordBatch(PutRecordBatchRequest.builder()
                                                .deliveryStreamName(stream)
                                                .records(records.stream()
                                                        .map(r -> Record.builder()
                                                                .data(SdkBytes.fromUtf8String(r))
                                                                .build())
                                                        .collect(Collectors.toList()))
                                                .build());
                                if (response.failedPutCount() != null
                                        && response.failedPutCount() > 0) {
                                    throw new IllegalStateException("Firehose rejected "
                                            + response.failedPutCount() + " of " + records.size()
                                            + " audit records inside a 200 response");
                                }
                            },
                            auditSpool::spool);
            writer.start();

            // Register with the Camel context so that Camel's own shutdown calls close().
            //
            // Without this the writer leaked records on EVERY container stop, and it was a
            // regression introduced by moving delivery off the request path: the worker is a daemon
            // thread, so the JVM exits without running it. MEASURED in a separate JVM - 200 records
            // accepted, 0 survived exit. Up to AUDIT_QUEUE_CAPACITY accepted-but-undelivered records
            // were discarded per deploy, scale-in or rollout. Before the async writer existed the
            // Firehose call was synchronous and inline, so a graceful stop lost nothing; the buffer
            // bought latency and silently created a standing data-loss window.
            //
            // Camel's Service contract is used rather than a JVM shutdown hook because Camel stops
            // its services BEFORE the JVM tears down, so the flush happens while the Firehose client
            // is still usable. A shutdown hook races the AWS SDK's own teardown.
            try {
                getContext().addService(new org.apache.camel.Service() {
                    @Override
                    public void start() {
                        // Already started above; starting twice is a no-op by design.
                    }

                    @Override
                    public void stop() {
                        log.info("flushing the audit writer for stream {} before shutdown", stream);
                        writer.close();
                        if (writer.lostCount() > 0) {
                            log.error("{} {} audit record(s) for stream {} were neither delivered "
                                    + "nor spooled during shutdown.",
                                    com.amazon.aws.pix.core.audit.AuditAlarmTokens
                                            .SPOOL_WRITE_FAILED,
                                    writer.lostCount(), stream);
                        }
                    }
                });
            } catch (Exception e) {
                // Must not be swallowed: without the service the flush never runs and records are
                // lost on every stop, which is precisely the regression this wiring exists to fix.
                throw new IllegalStateException("could not register the audit writer for stream "
                        + stream + " with the Camel context; refusing to run with a writer whose "
                        + "queued records would be discarded on shutdown", e);
            }
            return writer;
        });
    }

    /** Registry name of the custom initializer; referenced explicitly by bcbEndpoint(). */
    private static final String CLIENT_INITIALIZER_FACTORY = "nettyHttpClientInitializerFactory";

    /**
     * Registry name of the header filter that keeps BCB's caching directives. MEASURED: the stock
     * strategy filters out cache-control, so without this a getEntry response reaches the caller
     * with no directive bounding how long a key-ownership answer may be reused.
     */
    private static final String HEADER_FILTER_STRATEGY = "pixHttpHeaderFilterStrategy";

    /**
     * Read-timeout bound for the BCB leg, in milliseconds. An operational safety limit rather than
     * a BCB protocol value; reconcile with the Manual de Tempos do Pix before homologação.
     */
    private static final long BCB_READ_TIMEOUT_MS = 30_000L;

    /**
     * Connection-pool bounds for the BCB leg. Operational values, not BCB protocol values.
     *
     * <p>{@code MIN_EVICTABLE_IDLE} is the one that bites: it must stay below the Keep-Alive timeout
     * BCB advertises, or the pool will hand out a connection the peer has already closed.
     */
    private static final int BCB_POOL_MAX_ACTIVE =
            Integer.getInteger("pix.bcb.pool.maxActive", 100);
    private static final int BCB_POOL_MIN_IDLE =
            Integer.getInteger("pix.bcb.pool.minIdle", 5);
    private static final long BCB_POOL_MIN_EVICTABLE_IDLE_MS =
            Long.getLong("pix.bcb.pool.minEvictableIdleMs", 20_000L);

    @Override
    public void configure() throws Exception {
        // Manual de Seguranca do Pix section 2: clients "devem sempre respeitar o TTL" published by
        // the DNS servers. The JVM does not honour a record's TTL at all - it applies its own fixed
        // cache - and defaults to caching FOREVER when a security manager is installed, so a stale
        // address can outlive a BCB endpoint move for the life of the process. Bound it explicitly.
        log.info(com.amazon.aws.pix.core.net.DnsCachePolicy.apply());

        getContext().getRegistry().bind(CLIENT_INITIALIZER_FACTORY, new NettyHttpClientInitializerFactory());
        getContext().getRegistry().bind(HEADER_FILTER_STRATEGY, new PixHttpHeaderFilterStrategy());

        configure(8080, xmlSigner, getParameter(Param.BcbDictEndpoint), getParameter(Param.DictAuditStream));
        configure(9090, iso20022XmlSigner, getParameter(Param.BcbSpiEndpoint), getParameter(Param.SpiAuditStream));

        from(checkEndpoint())
                // Was: .transform(constant("OK")) - which reported healthy unconditionally.
                //
                // That is worse than having no probe. A load balancer keeps routing Pix traffic to
                // a container whose HSM session has died, so the container looks healthy exactly
                // while every DICT write it receives fails signing. Weaker checks would not help:
                // a CloudHSM PrivateKey is a handle, so a non-null key and an open TCP connection
                // can both be true while the session behind them is gone. Signing a canary
                // document is what tells a live session from a stale handle.
                //
                // 503 rather than 500: load balancers and orchestrators treat "I am deliberately
                // unavailable" differently from "I crashed", and only the former reliably takes an
                // instance out of rotation without being counted as an application error.
                .process(exchange -> {
                    final com.amazon.aws.pix.core.health.HsmHealthProbe.Result result =
                            hsmHealthProbe.check();
                    exchange.getMessage().setHeader(org.apache.camel.Exchange.HTTP_RESPONSE_CODE,
                            result.isHealthy() ? 200 : 503);
                    exchange.getMessage().setBody(result.isHealthy()
                            ? "OK" : "UNHEALTHY: " + result.getDetail());
                    if (!result.isHealthy()) {
                        log.error("/check reporting UNHEALTHY - this container cannot sign: {}",
                                result.getDetail());
                    }
                });
    }

    /**
     * Canary document for the health probe. Deliberately minimal and constant: the probe exists to
     * exercise the HSM private key, not to test XML handling.
     */
    private static final String HEALTH_CANARY = "<healthcheck/>";

    /**
     * Probes whether this container can still sign, cached for a few seconds so that per-second
     * health polls do not each cost an HSM private-key operation.
     *
     * <p>It signs with the DICT signer because that is the credential the proxy's primary duty
     * depends on. A future refinement would probe both signers separately and report which one is
     * broken; today a single verdict is what the endpoint can express.
     */
    private final com.amazon.aws.pix.core.health.HsmHealthProbe hsmHealthProbe =
            new com.amazon.aws.pix.core.health.HsmHealthProbe(() -> xmlSigner.sign(HEALTH_CANARY));

    private void configure(int port, XmlSigner xmlSigner, String endpoint, String streamName) {
        from(proxyEndpoint(port))
                // The audit write lives here, not at the end of the route, and that placement is
                // the whole point. A transport or TLS failure on the BCB leg - connection refused,
                // handshake rejected, read timeout - aborts the exchange, and a final .process()
                // step simply never executes, so the request that WAS signed and WAS sent left no
                // record at all. Note that throwExceptionOnFailure(false) does not cover this: it
                // suppresses HTTP error statuses, while these failures happen below HTTP.
                //
                // onCompletion runs on both success and failure, which makes "signed and sent,
                // outcome unknown" an auditable state instead of an invisible one. It also runs
                // after the response has been returned to the caller, so the audit write is off
                // the caller's latency path as a side benefit.
                .onCompletion()
                    .process(new LogRequestResponseProcessor(streamName, auditWriterFor(streamName), auditSpool))
                .end()
                // Must precede convertToString(). A compressed request body that reaches the
                // string conversion is destroyed irreversibly, and the route would then sign the
                // wreckage - a valid PSP signature over a corrupted document. BCB does not accept
                // compressed requests at all, so this refuses with 415 and stops the route.
                .process(new RejectCompressedRequestProcessor())
                .transform(body().convertToString())
                .process(new SignRequestProcessor(xmlSigner))
                .process(new CaptureRequestProcessor())
                .to(bcbEndpoint(endpoint))
                // Must precede convertToString(): a gzip body turned into a String is destroyed
                // irreversibly, so decoding cannot happen after this point.
                .process(new DecodeResponseProcessor())
                .transform(body().convertToString())
                .process(new VerifyResponseProcessor(xmlSigner));

    }

    private EndpointConsumerBuilder proxyEndpoint(int port) {
        return nettyHttp(String.format("http://0.0.0.0:%d", port))
                .matchOnUriPrefix(true)
                .advanced()
                .nativeTransport(true)
                // Both legs need it: the producer governs the response the proxy receives, the
                // consumer governs what is written back to the caller.
                .headerFilterStrategy("#" + HEADER_FILTER_STRATEGY);
    }

    private EndpointProducerBuilder bcbEndpoint(String endpoint) {
        NettySSLContextParameters nettySSLContextParameters = new NettySSLContextParameters();
        nettySSLContextParameters.setSslContext(sslContext);

        return nettyHttp("https://" + endpoint)
                .bridgeEndpoint(true)
                .throwExceptionOnFailure(false)
                .ssl(true)
                // Manual de Seguranca do Pix v3.7, section 2: "TLS versao 1.2 ou superior, com
                // autenticacao mutua obrigatoria". 1.2 is the FLOOR, so 1.3 is offered too and TLS
                // version negotiation settles on the highest both peers support - a 1.2-only BCB
                // endpoint still connects (proven by TlsProtocolNegotiationTest). The list stays
                // pinned rather than left to the JVM because Corretto 11 still enables TLS 1.1 and
                // 1.0 by default, which are below the manual's floor.
                .enabledProtocols("TLSv1.2,TLSv1.3")
                .sslContextParameters(nettySSLContextParameters)
                // The custom factory only installs a ReadTimeoutHandler when requestTimeout > 0,
                // and its default is 0 = disabled. Unbounded reads mean a BCB that completes the
                // handshake then stalls holds a netty worker and a pooled connection forever. This
                // bound is an operational safety limit, NOT a BCB protocol value - reconcile it
                // with the Manual de Tempos do Pix before homologação.
                .requestTimeout(BCB_READ_TIMEOUT_MS)
                // The DICT API page states the mTLS handshake is expensive in latency terms and
                // recommends an HTTP connection pool, and BCB returns Keep-Alive with a timeout.
                // Reuse matters more here than for an ordinary client: every new connection is a
                // full mutual-TLS handshake whose client-side private-key operation happens inside
                // the HSM, so a non-reusing proxy pays an HSM round trip per request.
                .keepAlive(true)
                .advanced()
                .nativeTransport(true)
                // Keeps Cache-Control (and Pragma/Warning) on the way back. The stock strategy
                // filters them out; see PixHttpHeaderFilterStrategy for the measurement and for why
                // the hop-by-hop headers stay filtered. Also lives under advanced().
                .headerFilterStrategy("#" + HEADER_FILTER_STRATEGY)
                // Pool options live under advanced() in camel-netty-http 3.4.2.
                .producerPoolMaxActive(BCB_POOL_MAX_ACTIVE)
                .producerPoolMinIdle(BCB_POOL_MIN_IDLE)
                // Must stay BELOW the Keep-Alive timeout BCB advertises. If the pool holds an idle
                // connection longer than the peer does, the peer closes it first and the next
                // request goes into a half-closed socket - surfacing as sporadic, load-dependent
                // failures that look like network flakiness rather than misconfiguration.
                // Reconcile with the timeout in BCB's Keep-Alive response header before
                // homologação: this is a conservative guess, NOT a BCB value.
                .producerPoolMinEvictableIdle(BCB_POOL_MIN_EVICTABLE_IDLE_MS)
                // MEASURED, and the reason this line exists: binding the factory into the registry
                // is NOT enough. Camel does not autowire clientInitializerFactory - without this
                // explicit reference it instantiates the STOCK HttpClientInitializerFactory, and
                // NettyHttpClientInitializerFactory (the only code that reads
                // NettySSLContextParameters.getSslContext()) never runs. The CloudHSM mTLS client
                // key and the pinned BCB trust anchor would then never reach TLS at all.
                .clientInitializerFactory("#" + CLIENT_INITIALIZER_FACTORY);
    }

    private EndpointConsumerBuilder checkEndpoint() {
        return nettyHttp("http://0.0.0.0:7070/check")
                .advanced().nativeTransport(true);
    }

    private void loadParameters() {
        SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsDefaultRegion))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        parameters = new HashMap<>();
        String nextToken = null;
        do {
            GetParametersByPathResponse response = ssmClient.getParametersByPath(GetParametersByPathRequest.builder()
                    .nextToken(nextToken).path(Param.PATH).recursive(true)
                    // withDecryption is required for SecureString parameters and ignored for
                    // plain String ones. Without it, a SecureString parameter returns ciphertext,
                    // which then reaches CertificateFactory and fails startup with an error that
                    // says nothing about the parameter type. Many organisations mandate
                    // SecureString for anything certificate-adjacent, so accept both. NOTE: using
                    // SecureString also needs kms:Decrypt on the parameter key in the task role.
                    .withDecryption(true).build());
            parameters.putAll(response.parameters().stream().collect(Collectors.toMap(Parameter::name, Parameter::value)));
            nextToken = response.nextToken();
        } while (nextToken != null);
    }

    private void loadCloudHsmKeyStore() throws IOException, CFM2Exception, KeyStoreException, CertificateException, NoSuchAlgorithmException {

        SecretsManagerClient secretsManagerClient = SecretsManagerClient.builder()
                .region(Region.of(awsDefaultRegion))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        GetSecretValueResponse secretValue = secretsManagerClient.getSecretValue(builder -> builder.secretId(Secret.CloudHSMSecret.getSecretId()));
        JSONObject secret = new JSONObject(secretValue.secretString());
        String hsmUser = secret.getString(Secret.CloudHSMSecret.user);
        String hsmPassword = secret.getString(Secret.CloudHSMSecret.password);

        Security.addProvider(new com.cavium.provider.CaviumProvider());
        LoginManager.getInstance().login("PARTITION_1", hsmUser, hsmPassword);
        cloudHsmKeyStore = KeyStore.getInstance("CloudHSM");
        cloudHsmKeyStore.load(null, null);
    }

    /**
     * Whether to validate the BCB certificate path with revocation checking instead of the default
     * trust manager, which performs none at all.
     *
     * <p>Default OFF, and that is a considered position rather than timidity. Two preconditions are
     * deployment facts this repository cannot check, and getting either wrong is worse than the
     * status quo:
     *
     * <ol>
     *   <li>The trust parameter must hold the ICP-Brasil <b>CA</b>, not the BCB leaf. With a pinned
     *       leaf, revocation checking is structurally impossible - PKIX does not revocation-check a
     *       trust anchor - so switching it on would be a pure no-op that merely looks secure.
     *       Proven by RevocationAwareTrustManagersTest.</li>
     *   <li>The container must be able to reach ICP-Brasil's CRL or OCSP endpoints from inside
     *       RSFN. If it cannot, hard fail takes the proxy down completely; and soft fail only
     *       rescues an <em>unreachable</em> endpoint, not a certificate with no distribution point
     *       at all - also measured in that test.</li>
     * </ol>
     *
     * <p>So the switch exists, is tested, and is off until someone confirms both facts. The startup
     * log states which mode is active so the answer is never assumed.
     */
    private static final boolean TLS_REVOCATION_ENABLED =
            Boolean.parseBoolean(System.getProperty("pix.tls.revocation.enabled", "false"));

    /** Soft fail tolerates an unreachable CRL/OCSP endpoint; hard fail treats it as fatal. */
    private static final boolean TLS_REVOCATION_SOFT_FAIL =
            Boolean.parseBoolean(System.getProperty("pix.tls.revocation.softfail", "true"));

    private void createSslContext() throws Exception {
        PrivateKey signatureKey = (PrivateKey) cloudHsmKeyStore.getKey(getParameter(Param.MtlsKeyLabel), null);
        Collection<X509Certificate> certificates = KeyStoreUtil.getCertificates(getParameter(Param.MtlsCertificate));
        Collection<X509Certificate> trustCertificates = KeyStoreUtil.getCertificates(getParameter(Param.BcbMtlsCertificate));

        SslContextBuilder builder = SslContextBuilder.forClient()
                .sslProvider(SslProvider.OPENSSL)
                .keyManager(signatureKey, certificates)
                // Must match the endpoint's enabledProtocols. The custom initializer applies
                // enabledProtocols ONLY when sslContextParameters is null, and here it is not, so
                // whatever is pinned on THIS builder is what the handshake offers. Leaving
                // "TLSv1.2" here would silently drop TLS 1.3 despite the endpoint asking for it.
                // Manual de Seguranca do Pix v3.7 section 2: "TLS versao 1.2 ou superior".
                .protocols("TLSv1.2", "TLSv1.3");

        if (TLS_REVOCATION_ENABLED) {
            log.warn("BCB TLS: revocation checking ENABLED (softFail={}). This requires the trust "
                    + "parameter to hold the ICP-Brasil CA rather than the BCB leaf - with a pinned "
                    + "leaf no revocation check can occur - and requires CRL/OCSP reachability from "
                    + "RSFN.", TLS_REVOCATION_SOFT_FAIL);
            builder.trustManager(
                    com.amazon.aws.pix.core.tls.RevocationAwareTrustManagers.create(trustCertificates, TLS_REVOCATION_SOFT_FAIL));
        } else {
            log.warn("BCB TLS: revocation checking is DISABLED - a revoked BCB certificate would be "
                    + "accepted. Set -Dpix.tls.revocation.enabled=true once the trust parameter "
                    + "holds the ICP-Brasil CA and CRL/OCSP reachability from RSFN is confirmed. "
                    + "See CLOUDHSM_BCB_V2_HANDOFF.md.");
            builder.trustManager(trustCertificates);
        }

        sslContext = builder.build();
    }

    private void createXmlSigners() throws UnrecoverableKeyException, NoSuchAlgorithmException, KeyStoreException {
        PrivateKey signatureKey = (PrivateKey) cloudHsmKeyStore.getKey(getParameter(Param.SignatureKeyLabel), null);
        X509Certificate signatureKeyCertificate = KeyStoreUtil.getCertificate(getParameter(Param.SignatureCertificate));
        KeyStore trustStore = KeyStoreUtil.generateTrustStore("bcb", getParameter(Param.BcbSignatureCertificate));

        xmlSigner = new XmlSigner(signatureKey, signatureKeyCertificate, trustStore);
        iso20022XmlSigner = new Iso20022XmlSigner(signatureKey, signatureKeyCertificate, trustStore);
    }

    private void createFirehoseClient() {
        firehoseClient = FirehoseClient.builder()
                .region(Region.of(awsDefaultRegion))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private String getParameter(Param param) {
        return Optional.ofNullable(parameters.get(param.getParamName()))
                .orElseThrow(() -> new IllegalStateException(String.format("Parameter %s not found!", param.getParamName())));
    }

}
