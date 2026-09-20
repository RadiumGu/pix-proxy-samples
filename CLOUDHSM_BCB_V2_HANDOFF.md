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
   - Non-payment key-check uses a separate host **and port** on both stages:
     - Homologação: `dict-np-h.pi.rsfn.net.br:16532/api-np/v2/keys/check`
     - Production: `dict-np.pi.rsfn.net.br:16432/api-np/v2/keys/check`
3. BCB still documents mTLS, XML Digital Signature for DICT writes/changes, and mandatory response-signature validation. Query requests need not be signed.
4. BCB v2 has query-driven operations (`Cursor`, `IncludeStatistics`, `Status`, `ModifiedAfter`, `Limit`, repeated query values). The CloudHSM proxy must preserve path/query/header/body transparently.
5. The security-manual link on the API page (`cedsfn/Manual_de_Seguranca_PIX.pdf`) still returns **404**, while its sibling manuals under `pix/Regulamento_Pix/` return 200 — so it is access-restricted, not missing. The document **was** obtained from the Internet Archive snapshot of that exact BCB URL (digest unchanged 2025-07-16 → 2026-06-03) and its transport requirements are now recorded in 7.2 and in README-CloudHSM.md. SPI `MsgDefIdr` and XSD versions remain unverified — see 7.3 — and must not be guessed.
6. **Compression is expected, not exotic.** The API page recommends callers send `Accept-Encoding: gzip`; sending a compressed *request* is explicitly unsupported. Because the proxy forwards client headers transparently, BCB will answer compressed, and the body must be decoded before signature verification. Fixed 2026-09-20; see 3.2.
7. **Connection reuse is recommended.** The API page states the mTLS handshake cost is high in latency terms, recommends an HTTP connection pool, and returns a `Keep-Alive` header carrying a `timeout`. This skeleton does not configure or document a pool — open item, not a defect of correctness.
8. **DNS TTL must be respected.** The security manual states clients "devem sempre respeitar o TTL" of the DNS servers, warning that failing to do so can cause loss of access. This skeleton resolves configuration once at startup and has not been checked against that requirement — open item.
9. **DICT API version moved on.** Released version is **2.12.1**; **2.13.0_rc1** is in progress. Its header-value regex changes (`PI-RequestingParticipant` → `^(?i)[a-z0-9]{8}`, `PI-PayerId` → `^([0-9]{11}|[A-Z0-9]{12}[0-9]{2})$` for **alphanumeric CNPJ**, account number `^[A-Z0-9]{1,20}$`) do **not** affect this proxy, which forwards bytes and never parses business content; `DictV2RequestPolicy` checks header *presence* only. Also of note: `getBucketState`/`listBucketStates` moved off `dict-ratelimit.pi.rsfn.net.br` in 2.6.0 and the old host now returns **HTTP 410**; MED 2.0 Funds Recovery, Fraud Markers and Event Notifications endpoints exist but are out of scope per section 1.

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

### 3.2 Added 2026-09-20 — compressed BCB responses no longer look like signature failures

**A measured defect, not a hypothetical.** BCB's API page recommends callers send
`Accept-Encoding: gzip`. The proxy forwards client headers transparently, so that reaches BCB and
BCB answers compressed. The route then handed the body straight to `convertToString()` before
verifying the signature.

What that did, measured on this repository: the gzip magic `0x1f 0x8b` became `31, U+FFFD` — `0x8b`
is not a valid stand-alone UTF-8 sequence, so it was replaced and the payload was destroyed
**irreversibly**. `xmlSigner.verify()` then failed and the proxy answered **HTTP 500 with
"signature invalid" for a response BCB had signed correctly**. The trigger was a caller following
BCB's own documented recommendation.

| Claim | Evidence | Note |
|---|---|---|
| gzip/deflate bodies decode to the exact signed bytes; unknown/stacked encodings and corrupt streams are refused; decoded size is bounded | `HttpContentDecoder` + `HttpContentDecoderTest` (16 tests, proxy/core) | one test pins the root cause, asserting the `String` round trip is lossy |
| a gzip response reaches verification as the signed XML, `Content-Encoding` is stripped, the caller gets the whole body, an undecodable encoding is recorded rather than blamed on the signature | `DictV2CompressedResponseContractTest` (7 tests, proxy/test) | real Camel routes over loopback HTTP |
| the production route decodes **before** `convertToString()` | `check-transport-contract.sh` compares line numbers | ordering is the fix; presence alone is not enough |

A decode failure is recorded as `SIGNATURE_VALID_CONTENT_ENCODING_ERROR`, a fourth value for
`pix-signature-valid` beside `true`, `false` and `certificate-validity-error`. The reasoning is the
same as for the certificate case: a transport fault must not be filed as a cryptographic one. It is
carried as an exchange property rather than thrown so the exchange survives to the Firehose audit
write. No Glue schema change is needed — `response_signature_valid` is typed STRING.

Negative control (a permanent test): `withoutDecodingTheBodyReachingVerificationIsDestroyed`
asserts that with the decode step removed the body reaching verification is **not** the signed XML
and does contain U+FFFD.

**Seam worth knowing.** `DecodeResponseProcessor` lives in `proxy/cloudhsm/proxy`, which
`proxy/test` deliberately does not depend on (that module needs the CloudHSM JCE rpm and is
`continue-on-error` in CI). The contract test therefore exercises the same `HttpContentDecoder` call
the processor makes, while the gate pins the production wiring. Test proves the mechanism, gate
proves the wiring; neither alone suffices.

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

### C. TLS / certificate controls — P1 — protocol/cipher policy DONE 2026-09-20 (see 7.2); chain + hostname verification still open

The protocol question is settled: the *Manual de Segurança do Pix* v3.7 requires "TLS versão 1.2 ou
superior" with `ECDHE-RSA-AES-128-GCM-SHA256` (0xc02f) as the minimum suite, so both legs now pin
`enabledProtocols("TLSv1.2,TLSv1.3")` and `TlsProtocolNegotiationTest` covers it. See 7.2 for the
quotation, how the manual was retrieved, and what remains unproven.

Still open under this item:

- hostname validation is absent and is a real defect, not an unknown (7.2 item 3);
- BCB's ICP-Brasil v10 chain has never been validated against a real endpoint;
- participant signing-certificate requirements (`padrão SPB`) need the *Manual de Segurança do SFN*;
- certificate expiry/rotation now has tests (`XmlSignerExpiredCertificateTest`,
  `XmlSignerNotYetValidCertificateTest`) but not against BCB-issued material.

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
mvn -B -f proxy/pom.xml -pl core test                       # 38 tests as of 2026-09-20
mvn -B -f proxy/pom.xml -pl core,test test                 # + 38 in proxy/test, same date
mvn -B -f proxy/pom.xml -pl core,test package -DskipTests
mvn -B -f proxy/pom.xml -pl core,cloudhsm/cavium,cloudhsm/proxy package -DskipTests
bash -n proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh
shellcheck -S warning proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh
bash .github/scripts/check-transport-contract.sh
```

Only `core` and `test` execute tests; `simulator` and `cloudhsm` run with `-DskipTests`, so a test
added to them would silently never run. After adding a test, confirm the **count** in the CI run
rather than trusting a green tick.

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

### 7.1 mTLS private key must be EXTRACTABLE — mechanism now measured; remedies exist and are ranked

**Updated 2026-09-20.** This item previously called the gap "architectural, not a bug to patch
here" and left the remedy vague. The mechanism is now measured and the remedies are ranked, so what
remains is a decision rather than an unknown.

#### The mechanism, measured rather than asserted

`PixCloudHSMProxyRouteBuilder` builds the BCB leg with `SslProvider.OPENSSL` and
`keyManager(privateKey, certificates)` (lines 204–205), so the mTLS key must be generated
**without** `-nex`, i.e. extractable.

The README gives the reason as *"Cavium has JCE, but not JSSE"*. That is an architectural statement,
it was true of Client SDK 3, and it is **not the mechanism that actually fails**. The difference
matters because the two point at different remedies.

Measured against `netty-handler-4.1.49.Final` bytecode: with the OPENSSL provider Netty hands the
key to a native TLS library by PEM-encoding it. `PemPrivateKey.toPEM(ByteBufAllocator, boolean,
PrivateKey)` calls `PrivateKey.getEncoded()`, throws `IllegalArgumentException` with the message
`"<class> does not support encoding"` when it is `null`, and its **only** caller is
`ReferenceCountedOpenSslContext` — the OPENSSL path itself. Per the JCA contract a key whose
material cannot leave its device returns `null` there.

Executable evidence: `MtlsNonExtractableKeyTest` (proxy/test, 3 tests, runs in CI) drives that exact
call with a stub key whose `getEncoded()` is `null` and asserts the rejection, with an extractable
software key as the control.

So the blocker is a property of **this Netty API**, not of HSMs in general. That is what opens up
remedies that do not require waiting for a JSSE integration.

**What is *not* affected:** the **signing** key. It is non-extractable and stays in the HSM, and
forging a transaction needs the signing key, not the mTLS key. The mTLS key only establishes the
channel. This gap is therefore about **completeness of the compliance argument** — being able to say
"no exceptions" rather than "one exception with compensating controls" — not about a high-severity
hole. Do not let it displace higher-severity work.

#### Verified on real hardware, 2026-09-20 (hsm2m.medium, FIPS, Client SDK 5.18.0)

A throwaway cluster was stood up in us-east-1 to settle what a stub could not. Results:

**A non-extractable SDK 5 key returns `null` from `getEncoded()` — MEASURED.** Generated with
`cloudhsm-cli key generate-asymmetric-pair rsa --private-attributes extractable=false sign=true`,
whose attributes read back `"extractable": false, "never-extractable": true,
"always-sensitive": true`. Loaded through the SDK 5 JCE provider:

```
keyClass = com.amazonaws.cloudhsm.jce.provider.CloudHsmRsaPrivateCrtKey
getFormat  = null
getEncoded = null
```

This confirms the stub in `MtlsNonExtractableKeyTest` behaves like the real thing, and it settles a
question this section previously left open: **path B must use `SslProvider.JDK`, not `OPENSSL`.**
Because `getEncoded()` is `null` on SDK 5 exactly as on SDK 3, Netty's OPENSSL path
(`PemPrivateKey.toPEM` → `IllegalArgumentException: does not support encoding`) fails the same way
after the migration. Migrating to SDK 5 does **not** by itself make the current Netty code work.

**`extractable=false` must be set explicitly.** SDK 5 defaults to extractable, so a migration that
simply ports the key-generation step will silently produce an *exportable* mTLS key — the opposite of
the intent. MEASURED: omitting the attribute is accepted without warning.

**Four SDK 3 → 5 migration facts this repository does not yet record**, all MEASURED:

1. `hsm2m.medium` requires a `Mode` argument on `CreateCluster` (`FIPS` or `NON_FIPS`);
   `hsm1.medium` did not. Omitting it fails with
   `CloudHsmInvalidRequestException: Mode is a required argument for this hsm type.`
2. SDK 5 enforces a **key-availability quorum of 2 HSMs by default**. On a single-HSM cluster every
   key operation fails with *"the key must be available on at least 2 HSMs"* until the check is
   disabled. This is a sound production default and a trap for anyone running one HSM.
3. SDK 5 configures **each component separately** — `configure-cli`, `configure-jce` and
   `configure-dyn` are three different binaries with three different config files. Configuring the
   CLI alone leaves the JCE holding the literal placeholder `%%HSM_IP_ADDRESS%%` and it fails with
   `Config key hostname has invalid value`. The container entrypoint currently configures one thing;
   under SDK 5 it must configure each component it uses.
4. `cloudhsm-cli key generate-asymmetric-pair rsa` requires `--public-exponent`; it has no default.

#### Path D's core mechanism is PROVEN on hardware — MEASURED 2026-09-20

Tested on the same cluster, with the non-extractable key from above
(`extractable=false`, `never-extractable=true`):

```
openssl engine -t -c cloudhsm
  (cloudhsm) CloudHSM OpenSSL Engine   [RSA, EC]   [ available ]

cloudhsm-cli key generate-file --encoding reference-pem --path hsmkey.pem \
    --filter attr.label=pix-mtls-priv
openssl dgst -engine cloudhsm -sha256 -sign hsmkey.pem -out sig.bin data.txt
  Engine "cloudhsm" set.        -> sig.bin, 256 bytes (RSA-2048)

openssl dgst -verify pub.pem -signature sig.bin -sha256 data.txt
  Verified OK

openssl req -new -engine cloudhsm -key hsmkey.pem -out client.csr -subj '...'
  Certificate request self-signature verify OK
```

So the engine performs a genuine private-key operation on a key that cannot leave the HSM, and a
client certificate can be issued for that key. That is the load-bearing capability path D needs.

**The unknown this section flagged largely dissolves, and for an instructive reason.** The worry was
that AWS documents the engine for a *server* directive (`ssl_certificate_key`) while this proxy needs
a *client* one (`proxy_ssl_certificate_key`), and that the client directive might not route through
the engine. Measured: there is no engine-specific key syntax to route at all. An `engine:name:id`
reference **fails outright** —

```
openssl dgst -engine cloudhsm -keyform engine -sign pix-mtls-priv ...
  Could not find private key from org.openssl.engine:cloudhsm:pix-mtls-priv
  error:1300007D:engine routines:ENGINE_load_private_key:no load function
```

— because the CloudHSM engine implements **no `load_private_key` function**. The only mechanism is
the `reference-pem` file, which is an ordinary file on disk that merely *looks* like a private key
and contains no key material. Any directive that takes a key **file path** therefore accepts it, and
server-side versus client-side stops being a meaningful distinction. This also means nginx's
`ssl_certificate_key engine:...` syntax would not have worked either way.

**Still to confirm:** a full mTLS handshake driven by stunnel/nginx in client mode, that the
mandatory suite 0xc02f negotiates with this key, TLS 1.3 behaviour, and — the real acceptance
criterion — a CloudHSM **audit-log** entry proving the handshake's private-key operation executed
inside the HSM rather than merely that the handshake succeeded.

#### Remedies, ranked

| | Approach | Requires SDK 3 → 5 migration? | Java code change | Main risk |
|---|---|---|---|---|
| **D** | mTLS terminated **outside the JVM**: a native sidecar (nginx / stunnel / Envoy / HAProxy) using the CloudHSM **OpenSSL Dynamic Engine**; the app talks loopback plaintext to it | **No** — the engine exists for SDK 3 too | **None** | whether the sidecar's *outbound* client-certificate directive accepts an engine-backed key |
| **A** | Netty's own private-key offload, `OpenSslContextOption.PRIVATE_KEY_METHOD` | Recommended, not strictly required | Moderate | needs BoringSSL **and** a Netty upgrade — see below |
| **B** | SDK 5 JCE + `keystoreType="CLOUDHSM"` KeyStore, `SslProvider.JDK` | **Yes, hard dependency** (and therefore JDK 17+) | Small | client-side authentication with this KeyStore is not documented by AWS |
| **C** | Load the CloudHSM OpenSSL engine into `netty-tcnative` | — | Large | no public API; needs a custom tcnative build or JNI. **Do not attempt.** |

**Path A costs more here than it appears, measured:** Netty's `PRIVATE_KEY_METHOD` is documented as
BoringSSL-only, and this repository pins the `linux-x86_64-fedora` tcnative artifact, which is the
OpenSSL variant. Beyond that swap, `OpenSslContextOption` **does not exist at all** in
`netty-handler-4.1.49.Final` (it arrived in a later 4.1.x) — asserted by
`MtlsNonExtractableKeyTest#openSslContextOptionIsAbsentSoThePrivateKeyCallbackIsNotAvailableHere`.
So path A needs a Netty upgrade as well, on a Quarkus 1.7.0 / Camel-Quarkus 1.0.0 stack from 2020.

**Why path D is worth validating first:** it is the only option that does not bind this gap to the
SDK 5 migration (which 7.6 and section 5 treat as a separate project), it leaves the signing path
untouched so the signature tests need no re-validation, and it uses an AWS-documented configuration
rather than a Netty `@UnstableApi`. It would also incidentally address three items this repository
tracks elsewhere: absent TLS hostname verification (7.2 item 3), the unset endpoint timeout, and the
HSM client sharing a container with the application.

**Path D's honest costs:** a loopback segment carries plaintext containing CPF, account and amount.
It crosses no network boundary and stays inside one task's network namespace, but it is a new
discussion point under a strict zero-trust review and needs recording in the risk register. It also
adds a component to maintain (version, CVEs, configuration).

#### What is still NOT established

1. **The load-bearing unknown for path D.** AWS documents the engine with nginx as a **server**
   (`ssl_certificate_key` + `ssl_engine cloudhsm`). This proxy needs nginx as a **client**
   (`proxy_ssl_certificate_key`), and whether that directive also goes through the engine is not
   covered by AWS documentation. `stunnel` in `client = yes` mode is the most direct alternative and
   documents `engine` / `engineId` explicitly. **Validate this before committing to path D.**
2. **A real CloudHSM key has not been tested, and on new infrastructure it CANNOT be.** The
   evidence above uses a stub. Testing the real thing would mean a Cavium SDK 3 key, and SDK 3 only
   works with `hsm1.medium` — which is no longer creatable. Measured against the live API in
   `us-east-1` on 2026-09-20: `CreateCluster` with `hsm1.medium` returns
   `CloudHsmInvalidRequestException: Provided HsmType is not supported.` So the SDK 3 code path in
   this repository can no longer be stood up at all, and confirming `getEncoded() == null` on a real
   Cavium key is **permanently unverifiable** rather than merely pending a cluster. What a cluster
   CAN still verify is the *remedies* below, on `hsm2m.medium` with Client SDK 5.
3. **Proof that the handshake's private-key operation happens inside the HSM.** Whichever remedy is
   chosen, the acceptance criterion is a CloudHSM **audit-log** entry for the handshake operation —
   not merely a successful handshake. Build that check into the POC.
4. **BCB's own position.** It remains valid to close this by having BCB accept an extractable mTLS
   key for the institution's risk posture. Record that decision with whoever owns key policy.

**Do not** "fix" this by making the key non-extractable and assuming it works — measured above, it
fails at TLS context construction with `does not support encoding`.

> Provenance: the four-path analysis and the SDK 5 / JSSE finding come from an external research
> note supplied 2026-09-20. Its claims about this repository were re-verified here (README wording,
> the two route lines, the tcnative classifier) and all three checked out; the Netty 4.1.49
> `OpenSslContextOption` absence and the executable rejection test were added by this repository.
> One claim in that note is **not** verified here: a 2026-03-01 compliance deadline, for which no
> primary source was seen — do not cite it without one.

### 7.2 TLS protocol and cipher policy — requirement now known; real-endpoint proof still a gate

**Resolved 2026-09-20.** This item previously said the approved protocol and cipher list was
"unknown to this repository". It is now known, quoted from the primary source.

**Manual de Segurança do Pix, v3.7** (PDF created 2025-06-06), section 2 *"Comunicação segura"*:

> "O participante deve se conectar às APIs disponíveis no Pix exclusivamente por meio do protocolo
> HTTP versão 1.1 utilizando criptografia **TLS versão 1.2 ou superior**, com autenticação mútua
> obrigatória no estabelecimento da conexão. Deve ser suportada, **no mínimo, a Cipher Suite
> ECDHE-RSA-AES-128-GCM-SHA256 (0xc02f)**"

Section 5.4.3 adds that the BC uses **ICP-Brasil chain v10** SSL certificates for connection
authentication and encryption, and that participants sign with ICP-Brasil **padrão SPB**
certificates whose specification lives in the *Manual de Segurança do SFN*.

**Retrieval, stated because it bears on how much to trust this.** The URL the API page links to
404s; the sibling manuals under `pix/Regulamento_Pix/` return 200, which was confirmed by
enumerating that directory with the four retrievable manuals as a positive control that the
enumeration method works. The PDF was then fetched from the Internet Archive snapshot of that exact
BCB URL, digest unchanged across snapshots from 2025-07-16 to 2026-06-03. It is BCB's own file via
an archive, not a third-party restatement. **Re-confirm the current version through BCB
onboarding/support before a homologação run.**

**What changed in the code.** Both legs now pin `enabledProtocols("TLSv1.2,TLSv1.3")` — 1.2 is the
floor and `ou superior` permits 1.3. The list stays pinned rather than left to the JVM because
Corretto 11 still enables TLS 1.1 and 1.0 by default, below the manual's floor; Corretto 17 does
not. `TlsProtocolNegotiationTest` (proxy/core, 6 tests) proves a peer offering 1.2+1.3 still
negotiates 1.2 against a 1.2-only server, that 0xc02f actually negotiates rather than merely being
listed, and that the pin excludes 1.1/1.0. The mandatory suite is supported **and enabled by
default** on both Corretto 11.0.32 and 17.0.20 — measured.

**Still a gate, and not closed by any of the above:**

1. **No proof against BCB's real endpoint.** `dict.pi.rsfn.net.br` has no public A record — RSFN is
   a private network — so the handshake cannot be probed from outside it. Everything above is
   loopback JSSE plus a document.
2. **BCB's certificate chain is unvalidated.** ICP-Brasil v10 has not been exercised here.
3. **Hostname verification is absent.** Mitigated only by explicitly trusting the BCB certificate
   (i.e. pinning). This one does **not** depend on the manual — hostname verification is a general
   TLS requirement — so it is a genuine open defect rather than an unknown, tracked here because
   changing it affects the simulator fixture's certificate subject.
4. **Signing-certificate requirements** (`padrão SPB`, per the *Manual de Segurança do SFN*) are not
   verified; that manual has not been obtained.

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
