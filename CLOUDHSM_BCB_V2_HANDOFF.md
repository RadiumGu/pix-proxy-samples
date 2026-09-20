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

## 4. Required next work (CloudHSM only)

### A. Add CloudHSM v2 proxy contract tests — P0

The proxy uses Camel `matchOnUriPrefix(true)` and `bridgeEndpoint(true)`, which should preserve the incoming HTTP path/query/header/body. It is not enough to trust that intent.

Create a focused test that proves, for a DICT v2-style request, that the proxy preserves:

- `/api/v2/entries/{Key}` path;
- `IncludeStatistics=true` query;
- repeated query parameters (e.g. multiple `Status` values);
- required `PI-RequestingParticipant`, `PI-PayerId`, `PI-EndToEndId` headers;
- XML body untouched except the expected signature insertion.

Avoid requiring a real HSM for this test. Introduce an interface/factory seam around signing/TLS if necessary, or use a test-only signer and local Netty target. The test must fail if query/path/header are dropped.

### B. Strengthen the local simulator without pretending it is BCB — P0

`proxy/test` currently accepts every path on 8181/9191 and returns fixed success. Preserve it as a cryptographic smoke test, but add a v2 contract mode or explicit assertions for current `/api/v2` path/query/header cases. Keep the local simulator DNS (`test.pi.rsfn.net.br`) clearly separate from BCB homologação.

### C. TLS / certificate controls — P1, gated by BCB

Current CloudHSM code hardcodes `TLSv1.2`. Do not blindly replace it with TLS 1.3. Implement a narrowly scoped, default-preserving configuration only after confirming the exact Camel/Netty API and a BCB-approved protocol list:

- default remains the currently tested TLS 1.2;
- allow-list only BCB-approved protocols/ciphers;
- document hostname validation and certificate-chain behavior;
- add certificate expiry/rotation tests where feasible.

### D. Documentation / release gate — P0

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
