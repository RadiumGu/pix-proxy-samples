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

# TLS 1.2 is the currently TESTED behaviour. Raising it to 1.3 requires evidence from BCB's
# current security manual plus a homologação run; this repository must not claim TLS 1.3
# support it has not demonstrated. See CLOUDHSM_BCB_V2_HANDOFF.md section 4C.
require "$CLOUDHSM_ROUTE" '.enabledProtocols("TLSv1.2")' \
    "TLS 1.2 is the tested default; changing it needs BCB security-manual evidence and a homologação run, not a code edit"

echo "== CloudHSM local simulator: $SIMULATOR_ROUTE"

require "$SIMULATOR_ROUTE" ".matchOnUriPrefix(true)" \
    "the simulator must accept DICT v2 sub-paths so it can assert on /api/v2/... requests"

require "$SIMULATOR_ROUTE" ".needClientAuth(true)" \
    "the simulator's value is that it exercises mTLS; without client auth it stops testing that"

require "$SIMULATOR_ROUTE" '.enabledProtocols("TLSv1.2")' \
    "the simulator must match the protocol the production leg is pinned to"

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
