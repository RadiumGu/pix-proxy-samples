# CloudHSM + BCB DICT v2 continuation handoff

> **Purpose**: handoff for an agent/engineer with a working Java 11 + Maven environment to continue the **maintained CloudHSM teaching skeleton** in this fork.
>
> **Scope is deliberately narrow**: XML digital signatures, mTLS, CloudHSM client/container integration, and transparent HTTP proxying. **KMS is historical/unsupported: do not modify, build, test, or use it as an acceptance criterion.**

## 1. Non-goals — do not expand this repository into a PSP

Explicitly out of scope: payment initiation, inbound SPI asynchronous messages, settlement/reconciliation, refund business workflows, MED 2.0 / Funds Recovery, Fraud Markers, Event Notifications, Pix Automático, authorization, liquidity, fraud decisions, and operational SLAs.

The goal is only to make the **transport/cryptography teaching skeleton** compatible with the current BCB DICT v2 transport contract, and to document what only BCB homologação can prove.

## 2. Current external baseline (research completed 2026-09-20)

Official sources:

- [BCB DICT API v2.12.1](https://www.bcb.gov.br/content/estabilidadefinanceira/pix/API-DICT.html)
- [BCB DICT API changelog](https://bcb.gov.br/content/estabilidadefinanceira/pix/changelog.html)
- [BCB DICT v2 migration FAQ](https://www.bcb.gov.br/content/estabilidadefinanceira/pix/duvidas_comuns_api_v2.html)

Facts relevant to this skeleton:

1. DICT **v1 was fully disabled on 2024-02-04**. Production callers must send `/api/v2/...`.
2. BCB v2 bases shown by the official DICT page are:
   - Homologação: `dict-h.pi.rsfn.net.br:16522`, caller path begins `/api/v2/...`
   - Production: `dict.pi.rsfn.net.br:16422`, caller path begins `/api/v2/...`
   - Non-payment key-check uses separate `dict-np.pi.rsfn.net.br:16432/api-np/v2/keys/check`.
3. BCB still documents mTLS, XML Digital Signature for DICT writes/changes, and mandatory response-signature validation. Query requests need not be signed.
4. BCB v2 has query-driven operations (`Cursor`, `IncludeStatistics`, `Status`, `ModifiedAfter`, `Limit`, repeated query values). The CloudHSM proxy must preserve path/query/header/body transparently.
5. The current BCB security-manual direct link referenced by the API page returned 404 during research. Do **not** guess TLS 1.3, cipher suites, SPI `MsgDefIdr`, or certificates. Get current materials from BCB onboarding/support and validate them in homologação.

## 3. What is already fixed and verified

- XML `KeyInfo` uses certificate **Issuer DN**, not Subject DN (upstream issue #15), with a CA-issued regression fixture (`Subject != Issuer`).
- Audit schema includes `request_query` (upstream issue #16).
- CloudHSM wrapper configures all active HSMs, has bounded readiness, and `exec`s the JVM (upstream issue #17).
- CloudHSM audit delivery no longer fails an already-submitted transaction (upstream issue #18); see code comments for mandatory durable-fallback/compliance decision.
- Certificate validity is distinguishable from signature mismatch (upstream issue #19).
- `README-CloudHSM.md`, `README.md`, `README-KMS.md`, and `VERIFICATION.md` now define CloudHSM-only support scope.

Previous CI evidence is in `VERIFICATION.md`: `core`, `cloudhsm`, shellcheck and audit schema passed. KMS failure was an upstream dependency problem and KMS has now been removed from the maintained CI workflow.

### 3.1 Added 2026-09-20 — the v2 transport contract is now tested, not assumed

| What | Where | Evidence |
|---|---|---|
| DICT v2 transparent-proxy contract: `/api/v2/...` path, `IncludeStatistics=true`, **repeated** query params, `PI-RequestingParticipant` / `PI-PayerId` / `PI-EndToEndId`, XML body | `DictV2TransparentProxyContractTest` (11 tests) | drives real camel-netty-http routes; body checked by removing the inserted signature and requiring **byte equality** |
| Those assertions can actually fail | same class, 5 negative controls | dropping the path header, dropping both query headers, dropping the BCB headers, tampering with the body, removing `matchOnUriPrefix` |
| Production route keeps the options the contract depends on | `.github/scripts/check-transport-contract.sh` + `transport-contract` CI job | source assertion; verified it fails when `matchOnUriPrefix`, `TLSv1.2`, or the KMS scope guard are broken |
| Simulator can REJECT a malformed v2 request | `DictV2RequestPolicy` + `DictV2RequestPolicyTest` (17 tests) | non-v2 path → 404 citing the 2024-02-04 v1 shutdown; missing participant headers → 400; all 7 documented error statuses producible |
| Certificate expiry vs rotation are distinguishable | `XmlSignerExpiredCertificateTest` (2), `XmlSignerNotYetValidCertificateTest` (2) | expired → `CertificateExpiredException` cause; **not-yet-valid** → `CertificateNotYetValidException` cause (this branch was reachable but untested before) |
| Simulator certificate misuse is detectable | `WellKnownTestCertificatesTest` (3) | fingerprint match on the committed simulator certs, with a negative control so it cannot cry wolf |

**Two measured corrections worth keeping**, both found by negative controls rather than review:

1. `bridgeEndpoint=true` is **not** what preserves path/query. The consumer populates
   `Exchange.HTTP_PATH` / `HTTP_QUERY` and the producer appends them; `HTTP_URI` arrives
   *relative*, so forwarding is identical with and without the option in a loopback harness. The
   option is retained for its documented purpose (absolute `HTTP_URI`, Host handling), which that
   harness does not exercise — do not cite the contract test as proof of it.
2. Clearing `HTTP_QUERY` alone does not drop the query: `HTTP_RAW_QUERY` is also set and the
   producer falls back to it. Both must be cleared to simulate query loss.

**A CI structural note that matters more than it looks.** Before 2026-09-20 every job except `core`
passed `-DskipTests`, so a test added to `proxy/test` or `proxy/cloudhsm` would **never have run in
CI**. The `dict-v2-contract` job exists to close that. When adding a test here, confirm it actually
executes by reading the run's test count — not by seeing the run go green.

## 4. Required next work (CloudHSM only)

### A. Add CloudHSM v2 proxy contract tests — P0 — ✅ DONE 2026-09-20 (see 3.1)

The proxy uses Camel `matchOnUriPrefix(true)` and `bridgeEndpoint(true)`, which should preserve the incoming HTTP path/query/header/body. It is not enough to trust that intent.

Create a focused test that proves, for a DICT v2-style request, that the proxy preserves:

- `/api/v2/entries/{Key}` path;
- `IncludeStatistics=true` query;
- repeated query parameters (e.g. multiple `Status` values);
- required `PI-RequestingParticipant`, `PI-PayerId`, `PI-EndToEndId` headers;
- XML body untouched except the expected signature insertion.

Avoid requiring a real HSM for this test. Introduce an interface/factory seam around signing/TLS if necessary, or use a test-only signer and local Netty target. The test must fail if query/path/header are dropped.

### B. Strengthen the local simulator without pretending it is BCB — P0 — ✅ DONE 2026-09-20 (see 3.1 and 7.4)

`proxy/test` currently accepts every path on 8181/9191 and returns fixed success. Preserve it as a cryptographic smoke test, but add a v2 contract mode or explicit assertions for current `/api/v2` path/query/header cases. Keep the local simulator DNS (`test.pi.rsfn.net.br`) clearly separate from BCB homologação.

### C. TLS / certificate controls — P1 — partially done; the rest is a homologação gate (see 7.2)

Current CloudHSM code hardcodes `TLSv1.2`. Do not blindly replace it with TLS 1.3. Implement a narrowly scoped, default-preserving configuration only after confirming the exact Camel/Netty API and a BCB-approved protocol list:

- default remains the currently tested TLS 1.2;
- allow-list only BCB-approved protocols/ciphers;
- document hostname validation and certificate-chain behavior;
- add certificate expiry/rotation tests where feasible.

### D. Documentation / release gate — P0 — see section 7

Keep `README-CloudHSM.md` current and add a BCB homologação release checklist:

- current BCB endpoint/certificate/TLS policy verified;
- current DICT v2 OpenAPI/XSD version recorded;
- request and response signatures accepted/validated;
- 400/403/404/409/410/429/503 handling tested;
- no assertion that local simulator proves BCB compatibility.

## 5. Build and CI commands

Run only CloudHSM-relevant checks:

```bash
mvn -B -f proxy/pom.xml -pl core test
mvn -B -f proxy/pom.xml -pl core,test package -DskipTests
mvn -B -f proxy/pom.xml -pl core,cloudhsm/cavium,cloudhsm/proxy package -DskipTests
bash -n proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh
shellcheck -S warning proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh
```

Do **not** run or fix `-pl kms`; KMS is intentionally out of scope.

After each focused change:

```bash
git status --short
git add <specific-files>
git commit -m "..."
git push origin master
```

The intended remote is [RadiumGu/pix-proxy-samples](https://github.com/RadiumGu/pix-proxy-samples), branch `master`.

## 6. Acceptance criteria

Stop only when all are true:

1. CloudHSM-only CI is green (`core`, simulator, cloudhsm compile, shellcheck, audit-schema).
2. A focused v2 transparent-proxy contract test proves preservation of path/query/header/body.
3. README and `VERIFICATION.md` clearly distinguish local simulator from BCB homologação and explicitly list the unsupported PSP business layer.
4. Current source changes are committed and pushed to `master`.
5. Any unverified BCB security/TLS/SPI message details remain named homologação gates — never claimed as passed.

---

## 7. Homologação gates — open items, NOT solved

Everything in this section is **unverified by this repository** and must not be presented as
working. Each one needs material from BCB onboarding/support plus a homologação run. They are
listed as gates precisely so that "CI is green" is never mistaken for "BCB-ready".

### 7.1 mTLS private key must be EXTRACTABLE — not solved, external gate

The CloudHSM proxy cannot keep the mTLS private key inside the HSM. `PixCloudHSMProxyRouteBuilder`
builds the client TLS context with Netty's `SslProvider.OPENSSL` and
`keyManager(privateKey, certificates)`, which needs real private-key bytes, so the mTLS key has to
be generated **without** `-nex` (extractable). Consequence, stated plainly: the deployment does
**not** satisfy the strictest reading of "the private key is always under the institution's
exclusive control".

What is *not* affected: the **signing** key. That one is non-extractable and stays in the HSM, and
forging a transaction needs the signing key, not the mTLS key.

This is an **architectural gap, not a bug to patch here**. Closing it needs one of: a Netty/Camel
TLS path that accepts a `PrivateKey` handle backed by a JCE provider without exporting bytes; an
mTLS terminator outside the JVM that can use an HSM key; or BCB confirmation that an extractable
mTLS key is acceptable for the institution's risk posture. **Do not "fix" this by making the key
non-extractable and assuming it works — it will fail at TLS handshake time.** Record the decision
with whoever owns the institution's key policy.

### 7.2 TLS protocol and cipher policy — pinned to what is tested, not to what BCB requires

The BCB leg is pinned to `TLSv1.2`, which is the **only** protocol this repository has exercised.
The security-manual link on BCB's API page returned 404 during research (2026-09-20), so the
current approved protocol and cipher-suite list is **unknown to this repository**.

`.github/scripts/check-transport-contract.sh` now fails CI if `enabledProtocols("TLSv1.2")` is
changed, so raising it to TLS 1.3 cannot happen through a one-word code edit. Before changing it:
obtain BCB's current security manual, confirm the approved protocol/cipher list, then validate in
homologação. **This repository makes no claim that TLS 1.3 works against BCB.**

Also unverified: hostname validation is **not** enabled (mitigated only by explicitly trusting the
BCB certificate, i.e. certificate pinning), and BCB's certificate chain has not been validated
against a real endpoint.

### 7.3 SPI message definitions and XSD versions — not verified, do not guess

The SPI `MsgDefIdr` values and the exact ISO 20022 XSD versions BCB currently accepts are **not
established here**, and there is no XSD schema validation in the code at all. Do not infer them
from the sample messages in this repository. Obtain the current XSD set from BCB and validate in
homologação.

### 7.4 What the local simulator does and does not prove

`proxy/test` listens on `test.pi.rsfn.net.br:8181` / `:9191`. That hostname is a **local
convenience only** — it is not BCB homologação, which is `dict-h.pi.rsfn.net.br:16522`.

The simulator now enforces a v2 contract (`/api/v2/` prefix, participant headers) and can produce
400/403/404/409/410/429/503 on request, and `DictV2RequestPolicy` documents those rules as **this
repository's simulator policy, not BCB behaviour**. It is a cryptography and transport test double.

A green simulator run proves: XML signing and verification work end to end, mTLS handshakes against
a peer that requires client auth, and the proxy forwards what it should. It proves **nothing** about
BCB's real validation rules, its status codes, its required-header set per operation, its TLS policy,
or its message schemas.

### 7.5 PSP business capabilities — deliberately absent

Not implemented and not planned here: payment initiation, inbound SPI asynchronous messages,
settlement, reconciliation, liquidity, refund business workflows, MED 2.0 / Funds Recovery, Fraud
Markers, Event Notifications, Pix Automático, authorization, fraud decisioning, and operational
SLAs. `DictV2RequestPolicyTest` keeps this boundary **executable** rather than merely written down:
it asserts the simulator decides a refund-shaped path and an entry-shaped path identically, so
business state machines cannot be introduced quietly.

### 7.6 Tier-2 items this repository cannot reach at all

- Real BACEN round-trip in homologação.
- Multi-HSM failover: needs a cluster with at least two HSMs, replacing one while running.
- Verification against BCB's own published signed sample messages.
