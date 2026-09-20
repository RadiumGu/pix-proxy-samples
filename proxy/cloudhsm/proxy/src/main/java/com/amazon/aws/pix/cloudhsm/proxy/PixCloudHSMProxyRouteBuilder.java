package com.amazon.aws.pix.cloudhsm.proxy;

import com.amazon.aws.pix.cloudhsm.proxy.camel.netty.NettyHttpClientInitializerFactory;
import com.amazon.aws.pix.cloudhsm.proxy.camel.netty.NettySSLContextParameters;
import com.amazon.aws.pix.cloudhsm.proxy.processor.CaptureRequestProcessor;
import com.amazon.aws.pix.cloudhsm.proxy.processor.DecodeResponseProcessor;
import com.amazon.aws.pix.cloudhsm.proxy.processor.LogRequestResponseProcessor;
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

    /** Registry name of the custom initializer; referenced explicitly by bcbEndpoint(). */
    private static final String CLIENT_INITIALIZER_FACTORY = "nettyHttpClientInitializerFactory";

    /**
     * Read-timeout bound for the BCB leg, in milliseconds. An operational safety limit rather than
     * a BCB protocol value; reconcile with the Manual de Tempos do Pix before homologação.
     */
    private static final long BCB_READ_TIMEOUT_MS = 30_000L;

    @Override
    public void configure() throws Exception {
        getContext().getRegistry().bind(CLIENT_INITIALIZER_FACTORY, new NettyHttpClientInitializerFactory());

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
                    .process(new LogRequestResponseProcessor(firehoseClient, streamName, auditSpool))
                .end()
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
                .advanced().nativeTransport(true);
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
                .advanced()
                .nativeTransport(true)
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
