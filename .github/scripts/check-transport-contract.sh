#!/usr/bin/env bash
#
# Transport-contract gate for the maintained CloudHSM teaching skeleton.
#
# WHAT THIS IS. A source-level assertion that the production CloudHSM route and the local
# simulator still declare the endpoint options the BCB DICT v2 transparent-proxy contract
# depends on. It is the companion to DictV2TransparentProxyContractTest: that test
# characterises what the options DO (by driving real routes and inspecting what arrives at the
# far end), while this gate pins the production endpoints to those same options. Neither is
# sufficient alone - a behavioural test on a hand-built route would not notice production
# drifting, and this gate alone would not notice the options meaning something different.
#
# WHAT THIS IS NOT. Text matching over source is weaker than execution. It cannot prove
# behaviour, it cannot prove TLS works, and it proves nothing whatsoever about BCB
# compatibility. It exists because the production route needs CloudHSM/HSM/AWS to start, so it
# cannot be exercised in CI - see CLOUDHSM_BCB_V2_HANDOFF.md.
#
# Run locally with:  bash .github/scripts/check-transport-contract.sh
set -euo pipefail

CLOUDHSM_ROUTE="proxy/cloudhsm/proxy/src/main/java/com/amazon/aws/pix/cloudhsm/proxy/PixCloudHSMProxyRouteBuilder.java"
SIMULATOR_ROUTE="proxy/test/src/main/java/com/amazon/aws/pix/proxy/test/PixProxyTestRouteBuilder.java"

rc=0

fail() {
    echo "::error file=$1::$2"
    rc=1
}

require() {
    local file="$1" needle="$2" why="$3"
    if [ ! -f "$file" ]; then
        fail "$file" "expected file is missing, so the transport contract cannot be checked"
        return
    fi
    if ! grep -qF -- "$needle" "$file"; then
        fail "$file" "missing '$needle' - $why"
    fi
}

echo "== CloudHSM production route: $CLOUDHSM_ROUTE"

# Consumer side. Without this, a request to /api/v2/entries/... is not routed at all: the
# consumer only matches its own exact URI. Proven by
# DictV2TransparentProxyContractTest#withoutMatchOnUriPrefixASubPathIsNotEvenAccepted.
require "$CLOUDHSM_ROUTE" ".matchOnUriPrefix(true)" \
    "the inbound endpoint must accept DICT v2 sub-paths, otherwise /api/v2/... returns 404 instead of being proxied"

# Producer side. NOTE, deliberately precise: bridgeEndpoint is NOT what preserves the path and
# query. That was measured - the netty-http consumer sets Exchange.HTTP_PATH/HTTP_QUERY and the
# producer appends them, and HTTP_URI arrives relative, so forwarding is identical with and
# without this option in a loopback harness. It is required for its documented purpose
# (ignoring an absolute HTTP_URI, and the associated Host handling) and is pinned here so the
# behaviour is not changed unexamined. See the contract test's javadoc for the measurement.
require "$CLOUDHSM_ROUTE" ".bridgeEndpoint(true)" \
    "the outbound endpoint must bridge rather than re-derive the target from an absolute HTTP_URI"

# BCB returns meaningful 4xx/5xx bodies (400/403/404/409/410/429/503). If Camel raises an
# exception on those instead of passing them through, the caller loses BCB's own error payload
# and the audit record loses the status code.
require "$CLOUDHSM_ROUTE" ".throwExceptionOnFailure(false)" \
    "BCB error responses must be passed through to the caller, not converted into exceptions"

require "$CLOUDHSM_ROUTE" ".ssl(true)" \
    "the BCB leg must be TLS"

# The floor comes from BCB's own security manual, which is now quoted rather than guessed at.
# Manual de Segurança do Pix v3.7, section 2 "Comunicação segura": "utilizando criptografia TLS
# versão 1.2 ou superior, com autenticação mútua obrigatória". So 1.2 is the FLOOR and 1.3 is
# permitted; offering both is what "ou superior" means, and TLS version negotiation keeps a
# 1.2-only BCB endpoint reachable (measured in TlsProtocolNegotiationTest). The list stays pinned
# because Corretto 11 still enables TLS 1.1 and 1.0 by default, which are BELOW that floor.
# Dropping below 1.2, or leaving the list to the JVM, is what this gate is here to catch.
require "$CLOUDHSM_ROUTE" '.enabledProtocols("TLSv1.2,TLSv1.3")' \
    "the manual's floor is TLS 1.2 'ou superior'; the pinned pair keeps 1.1/1.0 out while allowing 1.3"

# Response decoding. BCB's API page recommends clients send Accept-Encoding: gzip, and this proxy
# forwards client headers transparently, so a compressed response is the EXPECTED case. The XML
# signature is over the XML document, not over the compressed octets, so the body must be decoded
# before verification. Ordering is the whole point and is why this is not just a presence check: a
# gzip body converted to a String first has its magic byte 0x8b replaced by U+FFFD and is destroyed
# irreversibly, after which verification can only report a bogus signature mismatch.
require "$CLOUDHSM_ROUTE" "new DecodeResponseProcessor()" \
    "the BCB response must be Content-Encoding-decoded before its XML signature is verified"

decode_line=$(grep -n "new DecodeResponseProcessor()" "$CLOUDHSM_ROUTE" | head -1 | cut -d: -f1)
convert_line=$(grep -n "\.transform(body()\.convertToString())" "$CLOUDHSM_ROUTE" | tail -1 | cut -d: -f1)
verify_line=$(grep -n "new VerifyResponseProcessor(" "$CLOUDHSM_ROUTE" | head -1 | cut -d: -f1)
if [ -n "$decode_line" ] && [ -n "$convert_line" ] && [ -n "$verify_line" ]; then
    if [ "$decode_line" -ge "$convert_line" ] || [ "$decode_line" -ge "$verify_line" ]; then
        fail "$CLOUDHSM_ROUTE" \
            "DecodeResponseProcessor (line $decode_line) must come BEFORE the response convertToString (line $convert_line) and VerifyResponseProcessor (line $verify_line); decoding after the String conversion cannot work, the bytes are already destroyed"
    fi
else
    fail "$CLOUDHSM_ROUTE" \
        "could not locate the decode/convert/verify steps, so their ordering cannot be checked"
fi

# Client initializer wiring. MEASURED trap: binding the factory into the Camel registry does NOT
# wire it - Camel does not autowire clientInitializerFactory, so without an explicit #reference the
# STOCK HttpClientInitializerFactory runs instead. Since the custom factory is the only code that
# reads NettySSLContextParameters.getSslContext(), losing it means the CloudHSM mTLS client key and
# the pinned BCB trust anchor never reach TLS - against a BCB that mandates mutual auth. Pinned by
# NettyClientInitializerFactoryWiringTest as well.
require "$CLOUDHSM_ROUTE" '.clientInitializerFactory("#" + CLIENT_INITIALIZER_FACTORY)' \
    "the custom netty initializer must be referenced explicitly, or the CloudHSM SslContext is silently unused"

# Read timeout. The custom factory installs a ReadTimeoutHandler only when requestTimeout > 0, and
# Camel's default is 0 = unbounded. A BCB that handshakes then stalls would hold a worker forever.
require "$CLOUDHSM_ROUTE" ".requestTimeout(BCB_READ_TIMEOUT_MS)" \
    "the BCB leg must have a bounded read timeout"

# The io.netty SslContext pins its own protocol list, and the custom factory applies the endpoint's
# enabledProtocols ONLY when sslContextParameters is null - which it is not here. So this builder is
# what the handshake actually offers; pinning only TLSv1.2 here would silently drop 1.3.
require "$CLOUDHSM_ROUTE" '.protocols("TLSv1.2", "TLSv1.3")' \
    "the SslContext must offer the same protocol pair as the endpoint, or one of them is dead config"

echo "== CloudHSM local simulator: $SIMULATOR_ROUTE"

require "$SIMULATOR_ROUTE" ".matchOnUriPrefix(true)" \
    "the simulator must accept DICT v2 sub-paths so it can assert on /api/v2/... requests"

require "$SIMULATOR_ROUTE" ".needClientAuth(true)" \
    "the simulator's value is that it exercises mTLS; without client auth it stops testing that"

require "$SIMULATOR_ROUTE" '.enabledProtocols("TLSv1.2,TLSv1.3")' \
    "the simulator must match the protocol pair the production leg is pinned to"

# KMS is historical/unsupported in this fork. Keep it out of the maintained CI surface: a
# reference to it here would quietly make the maintained path depend on it again.
echo "== scope guard: KMS must not re-enter the maintained CI workflow"
if grep -nE -- '-pl[^#]*\bkms\b|proxy/kms' .github/workflows/build.yml; then
    fail ".github/workflows/build.yml" \
        "the maintained CI workflow references KMS, which is historical/unsupported in this fork"
fi

if [ "$rc" -eq 0 ]; then
    
# ---------------------------------------------------------------------------
# Security-override pins.
#
# quarkus-bom 1.7.0.Final would otherwise supply Netty 4.1.49.Final
# (CVE-2021-43797, header-injection request smuggling - acutely relevant to a
# proxy) and jackson-databind 2.11.2 (CVE-2020-25649, XXE in a process that also
# handles signed XML). The overrides only work because of two placement facts
# that are easy to undo by accident, so both are pinned here.
# ---------------------------------------------------------------------------
PARENT_POM=proxy/pom.xml

for pin in \
    '<artifactId>netty-bom</artifactId>' \
    '<artifactId>jackson-bom</artifactId>' \
    '<netty.version>' \
    '<jackson.version>' \
    '<netty-tcnative.version>'; do
  if ! grep -qF -- "$pin" "$PARENT_POM"; then
    echo "ERROR: $PARENT_POM no longer pins $pin - the Netty/jackson CVE overrides are gone." >&2
    exit 1
  fi
done

# The imported security BOMs must precede quarkus-bom. For imported BOMs Maven
# honours the FIRST declaration that manages an artifact, so moving netty-bom
# below quarkus-bom silently restores the vulnerable versions while the build
# still succeeds and the pom still "mentions" a pinned version.
NETTY_BOM_LINE=$(grep -n '<artifactId>netty-bom</artifactId>' "$PARENT_POM" | head -1 | cut -d: -f1)
JACKSON_BOM_LINE=$(grep -n '<artifactId>jackson-bom</artifactId>' "$PARENT_POM" | head -1 | cut -d: -f1)
QUARKUS_BOM_LINE=$(grep -n '<artifactId>quarkus-bom</artifactId>' "$PARENT_POM" | head -1 | cut -d: -f1)

if [ -z "$NETTY_BOM_LINE" ] || [ -z "$JACKSON_BOM_LINE" ] || [ -z "$QUARKUS_BOM_LINE" ]; then
  echo "ERROR: could not locate the BOM imports in $PARENT_POM to check their order." >&2
  exit 1
fi
if [ "$NETTY_BOM_LINE" -ge "$QUARKUS_BOM_LINE" ] || [ "$JACKSON_BOM_LINE" -ge "$QUARKUS_BOM_LINE" ]; then
  echo "ERROR: netty-bom (line $NETTY_BOM_LINE) and jackson-bom (line $JACKSON_BOM_LINE) must both" >&2
  echo "       appear BEFORE quarkus-bom (line $QUARKUS_BOM_LINE), or the CVE overrides do nothing." >&2
  exit 1
fi

# A version declared in a module's own <dependencies> beats dependencyManagement
# outright. netty-tcnative carried a hardcoded 2.0.31.Final here, which defeated
# the parent's pin in complete silence - Netty moved to 4.1.118 while tcnative
# stayed on 2.0.31, a mismatched JNI pair.
if awk '/<artifactId>netty-tcnative<\/artifactId>/,/<\/dependency>/' \
     proxy/cloudhsm/proxy/pom.xml | grep -q '<version>'; then
  echo "ERROR: proxy/cloudhsm/proxy/pom.xml hardcodes a netty-tcnative version. A module-level" >&2
  echo "       version overrides dependencyManagement, so this silently unpins tcnative from" >&2
  echo "       Netty. Remove the <version> and let the parent supply it." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Hostname verification on the outbound client.
#
# SNI and endpoint identification are easy to confuse and do unrelated jobs: SNI
# only tells the server which name the client wants, while endpoint
# identification is what compares the certificate the server actually presented
# against the host that was dialled. The client set the former and not the
# latter, so any chain the trust store accepted was accepted for ANY hostname.
# ---------------------------------------------------------------------------
CLIENT_FACTORY=proxy/cloudhsm/proxy/src/main/java/com/amazon/aws/pix/cloudhsm/proxy/camel/netty/NettyHttpClientInitializerFactory.java

# Matches the CALL to the shared configurer, not a string inside it. The behaviour itself
# is now executed by NettyHostnameVerificationTest through a real Netty handshake; this
# assertion only has to catch the factory ceasing to call it.
if ! grep -qF 'PixTlsEngineConfigurer.configureClient(engine, uri.getHost())' "$CLIENT_FACTORY"; then
  echo "ERROR: $CLIENT_FACTORY no longer enables hostname verification." >&2
  echo "       setSSLParameters with an SNI name alone does NOT check the server certificate" >&2
  echo "       against the host that was dialled. See TlsHostnameVerificationTest, whose" >&2
  echo "       negative control shows the mismatch is accepted without this call." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# The audit write must stay inside an onCompletion block.
#
# As a trailing .process() it is simply skipped when the BCB leg fails at the
# transport layer - connection refused, handshake rejected, read timeout - so a
# request that WAS signed and WAS sent leaves no record at all. Moving it back to
# the end of the route would compile, pass every happy-path test, and silently
# restore that gap, so the position is pinned by line number rather than by
# presence. throwExceptionOnFailure(false) does not cover this: it suppresses HTTP
# error statuses, which arrive as a well-formed response, while these failures
# happen below HTTP. See OnCompletionAuditSurvivesTransportFailureTest, whose
# negative control reproduces the loss.
# ---------------------------------------------------------------------------
if ! grep -q '\.onCompletion()' "$CLOUDHSM_ROUTE"; then
  echo "ERROR: $CLOUDHSM_ROUTE no longer wraps the audit write in an onCompletion block, so a transport" >&2
  echo "       failure on the BCB leg will lose the audit record entirely." >&2
  exit 1
fi

AUDIT_LINE=$(grep -n 'new LogRequestResponseProcessor(' "$CLOUDHSM_ROUTE" | head -1 | cut -d: -f1)
SEND_LINE=$(grep -n 'to(bcbEndpoint(' "$CLOUDHSM_ROUTE" | head -1 | cut -d: -f1)

if [ -z "$AUDIT_LINE" ] || [ -z "$SEND_LINE" ]; then
  echo "ERROR: could not locate the audit write and the BCB send in $CLOUDHSM_ROUTE to check their order." >&2
  exit 1
fi
if [ "$AUDIT_LINE" -ge "$SEND_LINE" ]; then
  echo "ERROR: the audit write (line $AUDIT_LINE) must be registered BEFORE the BCB send" >&2
  echo "       (line $SEND_LINE), which is what the onCompletion form does. Sitting after the" >&2
  echo "       send means it is skipped whenever the send fails at transport level." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# /check must actually probe the HSM.
#
# It used to return the constant "OK", which is worse than having no probe: a load
# balancer keeps routing Pix traffic to a container whose HSM session has died, so
# the container reports healthy exactly while every DICT write it receives fails
# signing. Reverting to a constant would be a one-line change that no test could
# catch, because the endpoint would still answer 200.
# ---------------------------------------------------------------------------
# Comment lines are stripped first. The route legitimately DESCRIBES the old
# behaviour in a comment explaining why it changed, and matching that text would fail
# the build for documenting the fix - which is exactly what happened on the first
# attempt at this check.
CHECK_ROUTE_CODE=$(grep -vE '^[[:space:]]*(//|\*|/\*)' "$CLOUDHSM_ROUTE")

if printf '%s' "$CHECK_ROUTE_CODE" | grep -q 'transform(constant("OK"))'; then
  echo "ERROR: $CLOUDHSM_ROUTE reports /check healthy unconditionally again. A constant \"OK\"" >&2
  echo "       keeps a container with a dead HSM session in the load balancer's rotation." >&2
  echo "       See HsmHealthProbe and HsmHealthProbeTest." >&2
  exit 1
fi

# Matches the CALL, not the type name. An earlier version grepped for
# "HsmHealthProbe", which a renamed-but-unused "HsmHealthProbeGone" still satisfies as
# a substring - the assertion passed while the probe was gone.
if ! printf '%s' "$CHECK_ROUTE_CODE" | grep -q 'hsmHealthProbe\.check()'; then
  echo "ERROR: $CLOUDHSM_ROUTE no longer calls hsmHealthProbe.check() on /check, so the endpoint" >&2
  echo "       is not proving the container can still sign." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# The revocation-checking path must remain reachable.
#
# Netty's default trust manager performs NO revocation checking, so a revoked BCB
# certificate is accepted. The capability is off by default because enabling it needs
# two deployment facts this repository cannot check - the trust parameter holding the
# ICP-Brasil CA rather than the BCB leaf, and CRL/OCSP reachability from RSFN - but the
# wiring must not be quietly deleted, or the switch becomes undocumented dead config.
# ---------------------------------------------------------------------------
if ! printf '%s' "$CHECK_ROUTE_CODE" | grep -q 'RevocationAwareTrustManagers\.create('; then
  echo "ERROR: $CLOUDHSM_ROUTE no longer wires RevocationAwareTrustManagers into the BCB TLS" >&2
  echo "       context, so pix.tls.revocation.enabled can no longer do anything. Netty's default" >&2
  echo "       trust manager checks no revocation at all." >&2
  exit 1
fi

if ! printf '%s' "$CHECK_ROUTE_CODE" | grep -q 'pix.tls.revocation.enabled'; then
  echo "ERROR: $CLOUDHSM_ROUTE no longer reads pix.tls.revocation.enabled, so revocation" >&2
  echo "       checking cannot be switched on in a deployment." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# A compressed request must be refused BEFORE the body becomes a String.
#
# BCB does not accept compressed requests. Once gzip bytes go through a charset
# decode they are destroyed irreversibly, and the route then produced a valid PSP
# signature over the wreckage and sent it to BCB. Presence alone is not enough here -
# the refusal is only effective if it runs before convertToString(), so the order is
# pinned by line number.
# ---------------------------------------------------------------------------
if ! printf '%s' "$CHECK_ROUTE_CODE" | grep -q 'new RejectCompressedRequestProcessor()'; then
  echo "ERROR: $CLOUDHSM_ROUTE no longer refuses compressed request bodies, so a gzip request" >&2
  echo "       would be charset-decoded into mojibake and then SIGNED with the PSP key." >&2
  exit 1
fi

REJECT_LINE=$(grep -n 'new RejectCompressedRequestProcessor()' "$CLOUDHSM_ROUTE" | head -1 | cut -d: -f1)
CONVERT_LINE=$(grep -n 'transform(body()\.convertToString())' "$CLOUDHSM_ROUTE" | head -1 | cut -d: -f1)

if [ -z "$REJECT_LINE" ] || [ -z "$CONVERT_LINE" ]; then
  echo "ERROR: could not locate the compressed-request refusal and the string conversion in" >&2
  echo "       $CLOUDHSM_ROUTE to check their order." >&2
  exit 1
fi
if [ "$REJECT_LINE" -ge "$CONVERT_LINE" ]; then
  echo "ERROR: the compressed-request refusal (line $REJECT_LINE) must come BEFORE the string" >&2
  echo "       conversion (line $CONVERT_LINE). After it, the body is already destroyed and the" >&2
  echo "       refusal cannot prevent a signature over corrupted bytes." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Connection reuse and DNS TTL, both required by BCB documentation.
#
# The DICT API page recommends an HTTP connection pool because the mTLS handshake is
# expensive - and here every handshake's client-side private-key operation runs inside
# the HSM, so a non-reusing proxy pays an HSM round trip per request. The Manual de
# Seguranca do Pix section 2 requires respecting DNS TTL; the JVM applies a fixed cache
# instead of the record's TTL, and caches FOREVER when a security manager is installed.
# ---------------------------------------------------------------------------
for pin in 'keepAlive(true)' 'producerPoolMaxActive(' 'producerPoolMinEvictableIdle('; do
  if ! printf '%s' "$CHECK_ROUTE_CODE" | grep -qF -- "$pin"; then
    echo "ERROR: $CLOUDHSM_ROUTE no longer configures $pin, so the BCB leg stops reusing" >&2
    echo "       connections and pays a full mutual-TLS handshake - including an HSM" >&2
    echo "       private-key operation - on every request." >&2
    exit 1
  fi
done

if ! printf '%s' "$CHECK_ROUTE_CODE" | grep -q 'DnsCachePolicy\.apply()'; then
  echo "ERROR: $CLOUDHSM_ROUTE no longer bounds the JVM DNS cache. The Manual requires respecting" >&2
  echo "       DNS TTL, and the JVM default is to cache forever when a security manager is" >&2
  echo "       installed - a stale address then outlives a BCB endpoint move for the whole" >&2
  echo "       process lifetime." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# BCB's caching directives must not be filtered away.
#
# MEASURED: camel-netty-http 3.4.2's stock NettyHttpHeaderFilterStrategy carries an
# out-filter list containing cache-control, so a getEntry response reaches the caller
# with NO Cache-Control while neighbouring headers such as ETag pass through. The DICT
# API page requires clients to follow that directive, and it is the only bound on how
# long a key-ownership answer may be reused - a stale one means paying an account that
# no longer owns the key. Both legs must reference the custom strategy.
# ---------------------------------------------------------------------------
if ! printf '%s' "$CHECK_ROUTE_CODE" | grep -q 'new PixHttpHeaderFilterStrategy()'; then
  echo "ERROR: $CLOUDHSM_ROUTE no longer binds PixHttpHeaderFilterStrategy, so BCB's" >&2
  echo "       Cache-Control directive is filtered out of responses silently." >&2
  exit 1
fi

HFS_REFS=$(printf '%s' "$CHECK_ROUTE_CODE" | grep -c 'headerFilterStrategy("#" + HEADER_FILTER_STRATEGY)' || true)
if [ "$HFS_REFS" -lt 2 ]; then
  echo "ERROR: $CLOUDHSM_ROUTE references the header filter strategy on $HFS_REFS endpoint(s); both" >&2
  echo "       the BCB producer and the caller-facing consumer need it. The producer governs the" >&2
  echo "       response the proxy receives, the consumer governs what is written back." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# The audit writer's queue must be flushed on shutdown.
#
# The worker is a daemon thread, so the JVM exits without running it. MEASURED in a
# separate JVM: 200 records accepted, 0 survived exit. Without a lifecycle
# registration, every deploy / scale-in / rollout silently discards up to the queue
# capacity of accepted-but-undelivered audit records - a regression introduced by
# moving delivery off the request path, since the previous synchronous write lost
# nothing on a graceful stop. The existing gate pins onCompletion and the audit-write
# ordering but nothing guarded the writer's lifecycle.
# ---------------------------------------------------------------------------
if ! printf '%s' "$CHECK_ROUTE_CODE" | grep -q 'getContext()\.addService('; then
  echo "ERROR: $CLOUDHSM_ROUTE no longer registers the audit writer with the Camel context, so" >&2
  echo "       nothing calls close() and every queued audit record is discarded on shutdown." >&2
  exit 1
fi

if ! printf '%s' "$CHECK_ROUTE_CODE" | grep -q 'writer\.close()'; then
  echo "ERROR: $CLOUDHSM_ROUTE registers a service that does not close the audit writer, so the" >&2
  echo "       queue is still discarded on shutdown." >&2
  exit 1
fi

# The refusal must inspect the BODY, not only the declared Content-Encoding. Measured:
# a client that gzips the body and omits the header passed straight through, and the
# mojibake was signed under the PSP key and forwarded to BCB - the exact catastrophe
# the header check was added to prevent, reachable by a broken client.
if ! grep -q 'mustReject(contentEncoding, body)' \
     proxy/cloudhsm/proxy/src/main/java/com/amazon/aws/pix/cloudhsm/proxy/processor/RejectCompressedRequestProcessor.java; then
  echo "ERROR: RejectCompressedRequestProcessor no longer sniffs the body, so a gzip body sent" >&2
  echo "       WITHOUT a Content-Encoding header would be signed as mojibake and forwarded." >&2
  exit 1
fi

# The DNS TTL bound must come from the LAUNCH, not from application code. Measured: the
# JDK reads networkaddress.cache.ttl once, in a static initializer, and the proxy
# resolves names during @PostConstruct (SSM, Secrets Manager, Firehose) before Camel
# configures its routes - so a Security.setProperty call from the route builder sets the
# property and changes nothing while logging success.
# Matches the ASSIGNMENT, not the bare name. A prefix match is satisfied by a renamed
# 'sun.net.inetaddr.ttl.gone', which is how the first version of this control passed
# while the flag was gone - the third time a substring assertion has done that here.
if ! grep -qF -- '-Dsun.net.inetaddr.ttl=' proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh; then
  echo "ERROR: wrapper_script.sh no longer sets -Dsun.net.inetaddr.ttl, so the JVM keeps its" >&2
  echo "       default DNS cache (30s, or FOREVER under a security manager) regardless of what" >&2
  echo "       DnsCachePolicy.apply() reports. See CLOUDHSM_BCB_V2_HANDOFF.md." >&2
  exit 1
fi

# /check must probe the mTLS client key, not only the document signer. That key is fetched
# once at startup and handed to the SSL context, so a dead HSM session for it fails every BCB
# handshake while signing still works - and /check answered 200 throughout, giving the load
# balancer no reason to replace the container.
#
# Matches the REGISTRATION CALL, not the string "mtls". Three gates in this repository have
# passed while the guarded thing was gone, because a name fragment matched a renamed symbol.
if ! grep -qF '.add("mtls-client-key", this::probeMtlsClientKey)' "$CLOUDHSM_ROUTE"; then
    echo "ERROR: $CLOUDHSM_ROUTE no longer registers the mTLS client key with the health probe." >&2
    echo "  /check would report healthy while every BCB handshake fails." >&2
    exit 1
fi

# The probe has to perform a real private-key operation. An empty or stubbed body would satisfy
# the registration check above while proving nothing about the key.
if ! grep -qF 'signature.initSign(key)' "$CLOUDHSM_ROUTE"; then
    echo "ERROR: $CLOUDHSM_ROUTE probeMtlsClientKey no longer signs with the mTLS key." >&2
    echo "  Registration without a real private-key operation is a probe that cannot fail." >&2
    exit 1
fi

echo "OK: transport contract options present, and KMS stays out of maintained CI"
fi
exit "$rc"
