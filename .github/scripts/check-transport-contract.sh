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
    echo "OK: transport contract options present, and KMS stays out of maintained CI"
fi
exit "$rc"
