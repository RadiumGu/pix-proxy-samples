# AWS CloudHSM architecture to exemplify digital signature and secure message transmission to the Brazilian Instant Payment System

<p align="center">
  <img src="/images/proxy-cloudhsm.png">
</p>

> ## ⚠️ Maintained scope and BCB currency boundary
>
> This fork maintains **only** the CloudHSM teaching skeleton: **XML digital signatures, mTLS, CloudHSM client/container integration, and transparent HTTP proxying**. It is a transport/cryptography reference, **not** a complete Pix PSP implementation.
>
> **Explicitly out of scope (not a backlog):** payment initiation; inbound SPI asynchronous messages; settlement/reconciliation; refund business workflows; MED 2.0 / Funds Recovery; Fraud Markers; Event Notifications; Pix Automático; authorization; liquidity; fraud decisions; and operational SLAs. A real PSP must implement these in separate domain services under current BCB rules.
>
> **DICT v2 boundary:** DICT API v1 was fully disabled on 2024-02-04. Production callers must send `/api/v2/...`; the local `test.pi.rsfn.net.br` simulator below is not BCB homologação. Before every homologação/production release, obtain the current BCB OpenAPI, Security Manual, endpoint bases, certificate chain and allowed TLS policy: [DICT API](https://www.bcb.gov.br/content/estabilidadefinanceira/pix/API-DICT.html) · [changelog](https://bcb.gov.br/content/estabilidadefinanceira/pix/changelog.html).
>
> See [`VERIFICATION.md`](VERIFICATION.md) for tested CloudHSM fixes and [`CLOUDHSM_BCB_V2_HANDOFF.md`](CLOUDHSM_BCB_V2_HANDOFF.md) for the remaining CloudHSM-only work.

## BCB homologação release checklist

**What CI proves and what it does not.** The maintained CI proves the transport contract
mechanically: the DICT v2 path, query string (including repeated parameters), the BCB `PI-*`
headers and the XML body survive the proxy hop; XML signing/verification works; an expired versus
a not-yet-valid trusted certificate are distinguishable; and the local simulator refuses a
malformed v2 request instead of answering 200. **None of that is evidence of BCB compatibility.**
The local simulator on `test.pi.rsfn.net.br:8181/:9191` is a loopback test double — BCB homologação
is `dict-h.pi.rsfn.net.br:16522`.

Work through this before any homologação or production release. Every unchecked box is a reason not
to release, not a nice-to-have.

- [ ] **Current BCB materials obtained** — DICT v2 OpenAPI, the Security Manual, endpoint bases,
      certificate chain and allowed TLS policy, taken from BCB onboarding/support rather than from
      this repository. The Security Manual link on BCB's API page returned 404 during research on
      2026-09-20, so this cannot be short-cut from here.
- [ ] **DICT v2 OpenAPI / XSD version recorded** in your release notes, with the exact version you
      validated against. This repository performs **no XSD schema validation at all** and its
      sample messages must not be treated as a version reference.
- [ ] **TLS policy confirmed against BCB's current manual.** The BCB leg is pinned to `TLSv1.2`
      because that is the only protocol exercised here; CI fails if it is changed
      (`.github/scripts/check-transport-contract.sh`). Do not raise it to TLS 1.3 on the strength
      of this repository — confirm the approved protocol and cipher list, then prove it in
      homologação.
- [ ] **Certificate chain validated against a real BCB endpoint**, and hostname validation
      decided deliberately: it is **not** enabled here, mitigated only by explicitly trusting the
      BCB certificate (certificate pinning).
- [ ] **Both BCB trust certificates replaced** with the current BCB-provided chain.
      `BcbSignatureCertificate` and `BcbMtlsCertificate` are the same parameters the simulator
      uses, so switching from the simulator is not merely changing an endpoint. The application
      logs an ERROR naming a known simulator certificate when one is trusted
      (`WellKnownTestCertificates`) — alarm on that line outside local simulation.
- [ ] **Certificate expiry and rotation monitoring in place.** Configuration is read **once at
      startup**, so rotating a certificate requires a redeploy. There is no expiry monitoring in
      this repository.
- [ ] **Request and response signatures accepted by BCB in homologação**, including a round trip
      against BCB's own published signed sample messages. The local simulator reuses *our* signer,
      so a green simulator run only proves we agree with ourselves.
- [ ] **Error handling exercised for 400 / 403 / 404 / 409 / 410 / 429 / 503.** The simulator can
      produce all seven on request via the `PI-Simulate-Status` header — a **simulator affordance
      that must never be sent to real BCB**. Confirm your caller and your audit records handle
      each, then confirm the real codes and conditions with BCB, since the simulator's mapping is
      this repository's policy and not BCB behaviour.
- [ ] **mTLS private-key policy decided in writing.** The mTLS key must be **extractable** for the
      current Netty TLS path, so this deployment does not satisfy the strictest reading of
      "the private key never leaves the institution's control". The signing key is unaffected and
      non-extractable. See `CLOUDHSM_BCB_V2_HANDOFF.md` §7.1 — and note that simply making the key
      non-extractable fails at handshake time.
- [ ] **Audit durability decision made**, per the code comments in `LogRequestResponseProcessor`:
      audit delivery deliberately does not fail a transaction, which trades a correctness problem
      for a compliance one. A durable fallback sink and an alarm on `AUDIT DELIVERY FAILED` are
      required, and whether an audit failure should reject a transaction is a compliance call.
- [ ] **LGPD handling for the audit log.** Records contain full message bodies — names, CPF,
      account numbers, amounts — with no redaction in this repository.
- [ ] **HSM generation and session handling reviewed.** This code targets **CloudHSM Client SDK 3**
      and `hsm1.medium`, which can no longer be created and reached end of support on 2026-03-31;
      `hsm2m.medium` requires Client SDK 5.9.0+. There is also no HSM session reconnect. See
      `VERIFICATION.md`.
- [ ] **Multi-HSM failover tested** on a cluster with at least two HSMs, replacing one while
      running. A single-HSM environment cannot exercise this.
- [ ] **No claim made anywhere that the local simulator proves BCB compatibility.**

This project contains source code and supporting files that includes the following folders:

- `proxy/cloudhsm` - Proxy that uses AWS CloudHSM.
- `proxy/core` - Sign XML messages.
- `proxy/test` - BACEN simulator.

The main code of application uses several AWS resources, including AWS CloudHSM and an AWS Fargate. The audit part of solution use other AWS resources, including [Amazon Data Firehose](https://aws.amazon.com/firehose/) (formerly **Amazon Kinesis Data Firehose**), [Amazon Athena](https://aws.amazon.com/athena/?nc1=h_ls&whats-new-cards.sort-by=item.additionalFields.postDateTime&whats-new-cards.sort-order=desc), [Amazon S3](https://aws.amazon.com/s3/?nc1=h_ls) and [AWS Glue](https://docs.aws.amazon.com/glue/latest/dg/components-overview.html).


## Version requirements: BCB Pix, TLS, JDK and CloudHSM

Everything in this section is quoted from a primary source or measured. Where a requirement could
not be verified it says so instead of guessing.

### What BCB requires on the wire

From the **Manual de Segurança do Pix, v3.7** (PDF created 2025-06-06), section 2
*"Comunicação segura"*:

> "O participante deve se conectar às APIs disponíveis no Pix exclusivamente por meio do protocolo
> **HTTP versão 1.1** utilizando criptografia **TLS versão 1.2 ou superior**, com **autenticação
> mútua obrigatória** no estabelecimento da conexão. Deve ser suportada, **no mínimo, a Cipher
> Suite ECDHE-RSA-AES-128-GCM-SHA256 (0xc02f)**"

| Requirement | Value | Where this repository stands |
|---|---|---|
| HTTP version | 1.1 | camel-netty-http speaks 1.1 |
| TLS version | **1.2 or higher** (`ou superior`) | route pins `enabledProtocols("TLSv1.2,TLSv1.3")` |
| Mutual authentication | mandatory | `needClientAuth` on the simulator; mTLS keystore from SSM in production |
| Cipher suite | **at minimum** `ECDHE-RSA-AES-128-GCM-SHA256` (0xc02f) | supported **and enabled by default** on Corretto 11 and 17 — measured, not assumed |
| Signature | XMLDSig; `<Signature>` at the XML root for DICT | `XmlSigner` / `Iso20022XmlSigner` |
| Signing certificates | ICP-Brasil **padrão SPB** (spec in *Manual de Segurança do SFN*) | not verified here — homologação gate |
| BC's connection certificates | ICP-Brasil **chain v10** SSL | not verified here — homologação gate |
| DNS | clients "devem sempre respeitar o TTL" of the DNS servers | **not verified** — this skeleton reads config once at startup |

Note that 1.2 is the **floor, not the ceiling**. `ou superior` permits TLS 1.3, which is why both
are offered. The list is pinned rather than left to the JVM default because Corretto 11 still
enables TLS 1.1 and 1.0, which are *below* that floor:

```
Corretto 11.0.32  default enabled: [TLSv1.3, TLSv1.2, TLSv1.1, TLSv1]   <- 1.1/1.0 must be excluded
Corretto 17.0.20  default enabled: [TLSv1.3, TLSv1.2]
```

**How the manual was obtained.** The URL the DICT API page links to
(`/content/estabilidadefinanceira/cedsfn/Manual_de_Seguranca_PIX.pdf`) returns **404**, while its
sibling manuals under `pix/Regulamento_Pix/` return 200. The document here was retrieved from the
Internet Archive snapshot of that exact BCB URL; its content digest is unchanged across snapshots
from 2025-07-16 to 2026-06-03. It is BCB's own file reached through an archive. Confirm the current
version through BCB onboarding/support before relying on it for a homologação run.

### JDK versions

TLS is **not** what constrains the JDK choice — both JDKs below satisfy the manual:

| | 0xc02f supported / enabled by default | TLS 1.3 |
|---|---|---|
| Corretto 11.0.32 | yes / yes | yes |
| Corretto 17.0.20 | yes / yes | yes |

The mandatory cipher suite has been available since **JDK 8u161**, and TLS 1.3 since **JDK 11**
(back-ported to 8u261). What actually forces a JDK upgrade is CloudHSM, below.

### CloudHSM versions — this is the blocking constraint

| Item | State |
|---|---|
| This code targets | Client **SDK 3** (`com.cavium.cfm2`, `PARTITION_1`, `key_mgmt_util`) |
| `hsm1.medium` | **cannot be created** — measured against the live API in `us-east-1` on 2026-09-20: `CreateCluster` with `hsm1.medium` returns `CloudHsmInvalidRequestException: Provided HsmType is not supported.` End of support was **2026-03-31** (past) |
| `hsm2m.medium` | the only creatable type; requires Client SDK **5.9.0+** |
| SDK 5 JCE provider | supports **OpenJDK 17 / 21 / 25** only |

So the CloudHSM path in this repository **cannot be deployed as written**, and the required
migration to SDK 5 also forces JDK 17+. That is a consequence of CloudHSM lifecycle, not of any
BCB requirement. On JDK 17 this repository's XMLDSig path additionally needs two `--add-exports`
flags. See `CLOUDHSM_BCB_V2_HANDOFF.md` section 7.

### Response compression

BCB's API page recommends clients send `Accept-Encoding: gzip`. This proxy forwards client headers
transparently, so a compressed response is the **expected** case. Because the XML signature covers
the XML document and not the compressed octets, the route decodes the body *before* verifying it
(`DecodeResponseProcessor`, then `convertToString()`, then `VerifyResponseProcessor`). Reversing
that order does not merely fail — a gzip body converted to a `String` first has the magic byte
`0x8b` replaced by U+FFFD and is destroyed irreversibly. Sending a compressed **request** is not
supported by BCB at all.

## How to verify all of this

Only `core` and the `test` module run tests. `simulator` and `cloudhsm` build with `-DskipTests`,
so a test placed in them would silently never run.

```bash
export JAVA_HOME=~/.local/opt/jdk11
export PATH=$JAVA_HOME/bin:$PATH

# 1. Signature, TLS, content-decoding, audit-spool, health-probe and revocation unit tests
#    (core: 79 tests, read from CI run 35525201219 on 2026-09-20 - counts change, so verify)
mvn -B -f proxy/pom.xml -pl core test

# 2. Everything that executes, including the DICT v2 transport contract
#    (core 79 + proxy/test 48 = 127, read from CI run 35525201219 on 2026-09-20)
mvn -B -f proxy/pom.xml -pl core,test test

# 3. Simulator build
mvn -B -f proxy/pom.xml -pl core,test package -DskipTests

# 4. CloudHSM build (needs the CloudHSM JCE rpm installed locally)
mvn -B -f proxy/pom.xml -pl core,cloudhsm/cavium,cloudhsm/proxy package -DskipTests

# 5. Container entrypoint
bash -n proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh
shellcheck -S warning proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh

# 6. Source-level transport contract + KMS scope guard
bash .github/scripts/check-transport-contract.sh
```

### Which test covers which claim

| Claim | Test / check |
|---|---|
| TLS 1.2 floor, 1.3 permitted, 1.2-only peer still reachable | `TlsProtocolNegotiationTest` (proxy/core) |
| Mandatory suite 0xc02f negotiates, not merely listed | `TlsProtocolNegotiationTest` |
| TLS 1.1/1.0 excluded by the pin | `TlsProtocolNegotiationTest` |
| gzip/deflate decoding, refusals, bomb bound, root cause | `HttpContentDecoderTest` (proxy/core) |
| gzip response reaches verification as the signed XML | `DictV2CompressedResponseContractTest` (proxy/test) |
| path / query / repeated query / headers / body preserved | `DictV2TransparentProxyContractTest` (proxy/test) |
| simulator request policy | `DictV2RequestPolicyTest` (proxy/test) |
| why the mTLS key must currently be extractable, and why Netty's private-key callback is not available here | `MtlsNonExtractableKeyTest` (proxy/test) |
| production route still declares the pinned options, decode precedes verify | `check-transport-contract.sh` |

The counts above are dated on purpose. They move whenever a test is added, so treat the **CI run's
own output** as authoritative rather than this page — and when you add a test, confirm the count in
that output instead of trusting a green tick.

### Negative controls

Every contract assertion here has a paired negative control, because a test that only passes cannot
show it would notice a regression. Two are permanent tests
(`withoutBridgeEndpointThePathAndQueryAreLost`,
`withoutDecodingTheBodyReachingVerificationIsDestroyed`); the rest are run by hand by breaking the
thing and confirming the check goes red. To reproduce a gate control:

```bash
cp .github/scripts/check-transport-contract.sh /tmp/gate.bak   # restore from a FILE copy
# then e.g. move DecodeResponseProcessor after convertToString in the route, and re-run:
bash .github/scripts/check-transport-contract.sh               # must exit non-zero
```

Restore from a file copy and verify with `cmp`. Do **not** rely on `git checkout --` to undo a
mutation: for an untracked file it silently does nothing, and for a tracked file it reverts to the
last *commit* rather than to your uncommitted work. Both failure modes produced misleading
"all green" runs while building this.

### What none of this proves

These checks run over **plain HTTP on loopback** with a stub signer, no HSM, no certificates and no
AWS. They say nothing about TLS against BCB, about BCB's certificate chain, or about CloudHSM.
`dict.pi.rsfn.net.br` has no public A record — RSFN is a private network — so the real endpoint
cannot be probed from outside it. Passing these is **not** evidence of BCB homologação.

## Cluster high availability: how to size it, and the setting that decides everything

All of the following was **measured on real CloudHSM hardware** — two clusters, HSMs deleted
while signing, then restored — not reasoned from the documentation. Timings are from
`hsm2m.medium` clusters in FIPS mode driven by `cloudhsm-cli` 5.18.0.

### The one thing to get right

SDK 5 enforces a **key availability quorum**: by default a key must exist on **at least two
HSMs** before an application may use it. AWS documents the consequence as *"any attempt to
create **or use** a token key will fail"* — note *use*, not just *create*. The quorum is
re-evaluated against current cluster membership on **every operation**, so a key that is already
fully replicated stops working the moment the cluster drops below two HSMs.

That produces three configurations, and one of them should never ship:

| | HSMs | Quorum | One HSM lost | Measured |
|---|---|---|---|---|
| **A** | 3 | enabled (default) | Signing continues — two remain | inferred from the quorum rule |
| **B** | 2 | disabled | **Signing continues** at full speed | MEASURED 5/5 OK, 0.38–0.46 s |
| **C** | 2 | enabled (default) | **Total signing outage** for short-lived clients (see below) | MEASURED 3/3 fail, 87.2 s each |

> **What the configuration-C measurement does and does not establish.** Every measurement used `cloudhsm-cli`, which starts a **fresh process** per invocation. AWS's troubleshooting page for this error lists *"a new instance of the SDK was started"* among the operations that trigger it, alongside key generation and `key list` — so these measurements **cannot separate** the quorum blocking the sign operation itself from the quorum firing at SDK startup. The proxy holds a long-lived JVM session with an already-resolved key handle, and **whether that keeps signing after one HSM of two is lost is NOT established here.** The durability documentation says "create **or use**" a token key fails, which points one way; the troubleshooting page's trigger list does not mention signing with an open handle, which points the other. Treat "total signing outage" as measured for short-lived clients and unproven for a long-lived one.

AWS's own troubleshooting guidance for this error reaches the same sizing conclusion independently: its listed resolutions are to disable the check, to avoid the triggering operations outside initialisation code on a two-HSM cluster, or to *"increase the amount of HSMs in your cluster to at least three"*.

**Configuration C is strictly dominated.** It costs exactly what B costs and loses all signing
capability when one HSM goes away. Two HSMs with the default quorum is the shape you get by
following the obvious path, and it is the one to avoid.

### What was measured, in order

Healthy two-HSM cluster, quorum disabled, key generated with the attributes a PSP signing key
needs (`extractable=false`, `never-extractable=true`, `sign=true`, `cluster-coverage: full`):

```
{"phase":"baseline_2hsm","n":1,"dur":0.515,"ok":true}   ... 5/5 ok, 0.46-0.52 s
```

Then one HSM of the two was deleted and the same key was used again:

```
{"phase":"degraded_1hsm_existing_key","n":1,"dur":0.393,"ok":true}   ... 5/5 ok, 0.38-0.46 s
```

**Signing continued at full speed with no configuration change and no restart.** The same
deletion against configuration C produced, three times over:

```
error_code: 1 — Cannot perform the requested key operation as the key must be
available on at least 2 HSMs
```

each after **87.2 seconds** (three measurements, spread 0.04 s — a fixed internal retry budget,
not network jitter).

Restoring a second HSM recovered configuration C automatically, in 0.45 s, **without** the
replacement's new ENI IP being added to the client config — the client discovers cluster members
by itself. Note the replacement arrives on a **new** IP.

### How a joining HSM is synchronised, and what keeps it in sync afterwards

These are **two different mechanisms**, and conflating them leads to the wrong operational
expectations.

**At join time: a full snapshot, not an incremental catch-up.** AWS documents five events, of
which only the first is yours:

1. You call `create-hsm`.
2. CloudHSM takes a backup of an **existing** HSM in the cluster.
3. It **restores that backup onto the new HSM**, which is what puts it in sync.
4. The existing HSMs **notify the client** that a new HSM exists.
5. The client connects to the new HSM.

The new HSM is therefore not "catching up" — it is overwritten wholesale with a copy of a peer's
state. AWS is explicit that the restore "overwrites all other data that might have been on the
HSM prior to restoration". A backup carries all users (CO, CU, AU), all key material and
certificates, and the HSM configuration and policies.

Step 4 explains a measured result: after a replacement HSM joined on a **new** ENI IP, signing
worked with that IP absent from the client config. The cluster **pushes** membership to the
client; the client does not poll or re-read configuration. Re-running `configure-cli` is only
needed to give a *fresh* client host an initial contact point.

**The source HSM is read, not modified.** Nothing in the documented flow changes the existing
HSM — it is the backup source. Cluster expansion is described as cloning "all users, keys, and
policies from another HSM in the cluster. No additional steps are required on your part."

**After join: token keys are synchronised continuously, and you manage none of it.**

| Key kind | Synchronised across the cluster? |
|---|---|
| **Token keys** — persistent, created by generate / import / unwrap | **Yes.** Client-side synchronisation clones them as they are created; server-side synchronisation periodically clones keys to every HSM as a fallback. Requires no management. |
| **Session keys** — ephemeral, scoped to one session | **No.** They exist on a single HSM and are never replicated. |

The proxy's signing key and mTLS key are token keys addressed by label, so they are covered. Any
use of session keys would not be.

### The window right after creating a key, and the quorum's second purpose

There is a race here that is easy to miss and that AWS documents plainly: a call using a
**newly created** key "can get routed to any available HSM in the cluster. **If the call you
route to an HSM without the key, then the call fails.**" The documented mitigation is
application-level **retry** on calls made immediately after key creation, because synchronisation
time varies with cluster workload.

This reframes what the key availability quorum is for. It is not only a durability control — it
also **removes this race**, by refusing to use a key until it exists on two HSMs. A random
"routed to an HSM that lacks the key" failure becomes a deterministic wait instead.

So configuration B (quorum disabled) re-accepts that race for newly created keys, in addition to
the durability exposure already described. For this workload the window is small and confined to
provisioning and rotation — the signing key and the mTLS key are created once and then used for
months — but it means configuration B's operational rule has two parts, not one:

> 1. Verify at least two ACTIVE HSMs **before** creating or importing a key.
> 2. After creating a key, confirm it is usable (and retry) **before** relying on it, rather than
>    assuming the next call will succeed.

Under configuration A the quorum enforces both of these, which is the substance of "A buys
enforcement, not merely headroom".

**SDK 3 caveat, for anyone still on it:** `cloudhsm_mgmt_util` talks to HSMs directly, bypassing
the client daemon, and its configuration is **not** updated dynamically when HSMs are added. User
management performed with it while the cluster membership changes can leave users unsynchronised.
Do not add HSMs while it is running. SDK 5's `cloudhsm-cli` does not have this problem, and the
client reconfigures itself as HSMs come and go — which is what was measured here.

### A key created while a new HSM is joining

The join restores a **snapshot**. A key created after that snapshot was taken is not in it, so
something else has to carry it across. That something is **server-side synchronisation**, which
AWS describes as periodically cloning keys to every HSM in the cluster and requiring no
management. It exists precisely as the fallback for this case — client-side synchronisation
cannot help, because it clones at creation time to the HSMs that are in the cluster *then*.

What actually happens depends on the cluster size and the quorum setting:

| Expansion | Quorum | Creating a key mid-join |
|---|---|---|
| 1 → 2 HSMs | enabled | **Creation fails.** One ACTIVE HSM cannot satisfy a quorum of two. The situation is prevented, not handled. |
| 1 → 2 HSMs | disabled | Creation succeeds on the old HSM, and the key exists on **one HSM only** until server-side synchronisation runs. Losing that HSM in the window loses the key beyond the last backup. |
| 2 → 3 HSMs | enabled | Creation succeeds — the two existing HSMs satisfy the quorum and client-side synchronisation puts the key on both. The key is on 2 of 3 until server-side synchronisation reaches the third. |

In the 2 → 3 case the quorum is satisfied throughout, so the key is never *blocked*. But a
quorum of two is not the same as "the HSM this call is routed to has the key", and AWS documents
that a call routed to an HSM without the key **fails**. So a call can still fail during the
window, and the documented mitigation is application-level retry. (That composition of two
documented behaviours is our reading; it was not measured.)

**The server-side synchronisation interval is not documented**, so catch-up time cannot be bounded
from the documentation — AWS says only that it "can vary, depending on the workload of your
cluster and other intangibles", and points at CloudWatch to determine the timing an application
should use.

**How to detect that a key did not propagate.** `HsmKeysTokenOccupied` in the `AWS/CloudHSM`
namespace reports token keys in use per **HSM instance** as well as per cluster. AWS's own
monitoring best practices recommend alarming on *"differences in HSM user or key count to
identify synchronization issues"* — so comparing that metric across the HSM IDs of one cluster is
the supported way to see divergence. A persistent difference means keys exist on some HSMs and
not others. Note `HsmUsersAvailable` gives the same handle for user divergence, which is the
other thing a join has to carry.

This completes the operational rule for configuration B: count ACTIVE HSMs before creating a key,
confirm the new key is usable before relying on it, and alarm on per-HSM key-count divergence so
a synchronisation failure is visible rather than discovered by a failing signature.

### `cluster-coverage: full` does not mean what it looks like

This is the trap in this whole area, and it invalidates the obvious safety check.

A key created **while the cluster was degraded to one HSM** reports:

```
"cluster-coverage": "full"
```

`full` means *present on every HSM currently in the cluster* — and there was only one. So a key
that exists on a single HSM, and would be lost with that HSM, reports exactly the same coverage
string as a key safely replicated across two. **Coverage is relative to current cluster
membership; it is not a durability measure.**

Therefore, if you run configuration B, the operational rule is **not** "check
`cluster-coverage` after creating a key" — that check cannot fail. The rule is:

> **Verify the cluster has at least two ACTIVE HSMs before creating or importing any key.**
> Count HSMs (`describe-clusters`, or `cloudhsm-cli cluster hsm-info`), do not read a coverage
> string.

For this workload the rule is easy to keep: the PSP signing key and the mTLS client key are
generated once at provisioning and again only at rotation, both planned activities.

### Choosing between A and B

- **A buys enforcement, not merely headroom.** The cluster refuses to use an under-replicated
  key, so it cannot happen by accident. No human discipline is required. Cost: one more HSM.
- **B buys the same availability for two HSMs' worth of money**, at the price of one operational
  rule and a thinner margin: if the surviving HSM fails before a replacement synchronises,
  recovery is from backup. AWS takes daily automatic backups plus additional backups on cluster
  lifecycle events such as adding or removing an HSM, so for a static signing key that loses
  nothing, and for keys created since the last backup it loses them.

**Availability Zone placement is part of choosing A.** Three HSMs spread across only **two** AZs
does not tolerate an AZ failure: losing the AZ holding two of them leaves one, which is below
quorum, and the outage is identical to configuration C. Put each HSM in its own AZ.

### Client timeouts must be set deliberately

The degraded failure in configuration C is **slow, not fast** — 87 seconds before the error
surfaces. On a synchronous Pix request path that means requests pile up rather than failing
quickly, so a client-side timeout below the Pix response budget is required regardless of which
configuration is chosen. The proxy's BCB endpoint sets `requestTimeout(30_000L)` for this
reason.

### Audit coverage of HSM add/remove is split across two sources

A joining HSM's audit stream opens with `CN_RESTORE_BEGIN`, `PARTITION_BACKUP_RESTORE_LOG`,
`CERT_AUTH/RSA/KEK` — key replication into a new HSM **is** auditable. **Removal is not recorded
as an event**: the deleted HSM's stream simply ends with `END_MARKER_OPCODE`. From the CloudHSM
log alone, a deliberate deletion and a crash are indistinguishable.

The lifecycle lives in **CloudTrail** (`CreateHsm`, `DeleteHsm`, `InitializeCluster`, attributed
to a principal), which also records **rejected** attempts. An audit trail must collect **both**
sources; the HSM log alone cannot answer "who removed an HSM".

### Operational sequencing traps, all measured

1. **A second HSM cannot be added until the cluster is fully ACTIVATED**, not merely
   initialized: `CreateHsm` is refused with *"already contains an HSM but has not yet been fully
   activated"*. Activation means logging in and setting the admin password with the client.
2. **The control plane lags the data plane.** `cluster activate` returned success while
   `describe-clusters` still read `INITIALIZED` for ~80 s, and `CreateHsm` was refused for that
   whole window. On teardown, both HSM deletions were accepted while the cluster still reported
   2 HSMs for ~120 s, so a `delete-cluster` issued on the API response alone fails. Automation
   must poll state, not trust the immediate reply.
3. **The client cannot reach an HSM until its security group allows it.** The HSM ENI carries
   only the cluster's own security group, which admits intra-group traffic, so an instance in a
   different group gets nothing on port 2223. The symptom is misleading: activation fails with
   *"Failed to initialize hsm1 context"*, which reads like a cluster-type mismatch and is pure
   network isolation. Attach the cluster security group to the client instance.
4. **`configure-cli` in 5.18.0 takes options with no subcommand.** The documented
   `configure-cli update -a <ip>` form is from an older SDK 5 and is rejected with *"unrecognized
   subcommand 'update'"*. Also `-a <HSM ENI IP>...` is **variadic** — both addresses go in one
   `-a`, and repeating the flag fails.
5. **`rpm -E %rhel` returns the literal `%rhel` on Amazon Linux 2023.** Discover the EL version
   by trying candidates rather than trusting the macro.
6. **Default key attributes are wrong for a PSP signing key.** Generated without explicit
   attributes, the private key comes back `extractable: true`, `never-extractable: false`,
   `sign: false` — extractable and unable to sign. Pass
   `--private-attributes sign=true extractable=false` explicitly. The same gap exists on the JCE
   path, where SDK 5 defaults `extractable` to true.

## Two different things are called "quorum", and conflating them is a real hazard

CloudHSM uses the word *quorum* for two unrelated mechanisms. One counts **HSMs**, the other
counts **people**. They appear in the same command output, which is how they get confused.

| | **Key availability quorum** | **Quorum authentication (M of N)** |
|---|---|---|
| Counts | **HSMs** holding the key | **users** approving the operation |
| Purpose | key durability, and removing the post-creation routing race | multi-person control, separation of duties |
| Enforced | client config plus current cluster membership | inside the HSM, verified against registered public keys |
| Configured with | `configure-cli --disable-key-availability-check` | `cloudhsm-cli` quorum commands; per-key values set **at key generation** |
| Default | **enabled** — minimum two HSMs | **off** — quorum values are 0 |
| Blocks | creating or using an under-replicated key | an operation without enough approvals |

Both are visible in one `key list --verbose` response. `cluster-coverage` and the availability
behaviour belong to the first; `key-quorum-values` belongs to the second:

```
"key-quorum-values": {
  "manage-key-quorum-value": 0,      <- M of N for key MANAGEMENT
  "use-key-quorum-value": 0          <- M of N for key USE (signing)
},
"cluster-coverage": "full"           <- unrelated: availability, not approvals
```

The first mechanism is covered above. This section is about the second.

### What M of N can control

No single user can perform a quorum-controlled operation; a minimum number of users — between
**2 and 8** — must cooperate. The controlled operations are grouped into *services*, and the
grouping is what matters for a payment workload:

| Service | Role | Operations |
|---|---|---|
| `user` | Admin | `user create`, `user delete`, `user change-password`, `user change-mfa` |
| `quorum` | Admin | `quorum token-sign set-quorum-value` |
| `cluster` | Admin | `cluster mtls register-trust-anchor`, `deregister-trust-anchor`, `set-enforcement` — **hsm2m.medium only** |
| `key-management` | Crypto User | `key wrap`, `key unwrap`, `key share`, `key unshare`, `key set-attribute` |
| `key-usage` | Crypto User | `key sign` |
| `registration` | either | registering a public key for quorum authentication |

### The approval flow

1. Each user generates an **RSA-2048 signing key pair outside the HSM** and protects it
   themselves — the HSM never holds it.
2. Each user logs in and registers their **public** key:
   `user change-quorum token-sign register --public-key <pub.pem> --signed-token <tokenfile>`.
3. A user wanting a controlled operation obtains a token:
   `quorum token-sign generate --service key-management --token <path> --filter attr.label=<label>`.
   For key services the token is **bound to a specific key** by that filter.
4. Approvers sign the token's `token` field — a SHA-256 digest of `approval_data` — **outside the
   HSM**, for example with `openssl pkeyutl -sign -pkeyopt digest:sha256`, and their base64
   signatures are pasted into the token file's `signatures` array with their username and role.
5. The requester runs the operation with `--approval <token file>`.
6. The HSM verifies each signature against the registered public keys and only then performs the
   operation.

Signing happens outside the HSM and verification happens inside it, so an approver's private key
never enters the HSM, and forging an approval requires that private key.

### Measured: the two key quorum values cannot be separated

An earlier version of this section recommended setting `manage-private-key-quorum-value` while
leaving `use-private-key-quorum-value` alone, so that key management needed approvals and signing
did not. **That was measured and it is not implementable.** Three attempts at key generation:

| `--manage-private-key-quorum-value` | `--use-private-key-quorum-value` | Result |
|---|---|---|
| 2 | 0 | **rejected** — `The manage key and use key quorum values must be set to a value greater than 1` |
| 2 | 2 | accepted |
| 0 | 0 | **rejected** — same error |

Supplying one flag without the other is a CLI error, and both values must be greater than 1. The
only way to have no key quorum is to omit both flags entirely, which leaves the values at 0. So
key-level quorum authentication is **all or nothing per key**: gating management necessarily gates
signing.

The consequence, measured end to end on a key with `manage=2, use=2`:

```
sign, no approval          -> error_code 1  "Quorum Failed"
sign, two approvals        -> error_code 0  signature returned
sign, same token reused    -> error_code 1  "Invalid username-signature pair for quorum authorization"
sign, one approval only    -> error_code 1  "Too few quorum approvals: currently 1 approvals, but 2 approvals required"
set-attribute, 2 approvals -> error_code 0  "Attribute set successfully"
```

Tokens are single-use and M is enforced exactly. So **key-level quorum is unusable for an
automated Pix signing key**: every signature would need a freshly generated token plus a fresh
round of human approvals.

### What protects the signing key instead

The threat key-level quorum would have covered is someone exporting or weakening the key. Measured
on a key with **no** quorum at all, that threat is already closed by attributes:

```
key set-attribute --name extractable       --value true
  -> "Attribute extractable cannot be set to the value true by the user for this operation"
key set-attribute --name never-extractable  --value false
  -> "Attribute never-extractable cannot be set to the value false by the user for this operation"
```

Attributes were unchanged afterwards. A key generated with `extractable=false` and
`never-extractable=true` cannot be made extractable later, so it cannot be wrapped out, with or
without quorum. That removes most of what key-level quorum was wanted for here.

**Where quorum authentication IS worth using** is the admin side, whose values are independently
settable with `quorum token-sign set-quorum-value --service <user|quorum|cluster>` — note that
command accepts only those three services, never the key ones:

- **`user`** — no single admin can create a crypto-user alone. This matters, because a new
  crypto-user can generate its own key and sign with it.
- **`quorum`** — no single admin can lower the quorum values.
- **`cluster`** — on `hsm2m.medium`, no single admin can change client-to-HSM mTLS enforcement or
  its trust anchors.

None of these touch the signing path.

### Two token formats, and the documentation shows only one

`cloudhsm-cli` 5.18.0 emits **different token structures** depending on the service, which matters
before writing any automation against it:

```
registration service:
  { "version": "2.0",
    "tokens":     [ { "approval_data": "...", "unsigned": "<b64 sha256>", "signed": "" } ],
    "signatures": [ { "username": "...", "role": "...", "signature": "" } ] }

key-usage / key-management:
  { "version": "2.0", "service": "key-usage", "key_reference": "0x...",
    "approval_data": "...", "token": "<b64 sha256>", "signatures": [] }
```

The AWS documentation shows the **flat** key-service shape only. There is no `token` field in a
registration token — the value to sign is `tokens[].unsigned` — so applying the documented shape to
a registration token yields an empty signature and `InvalidQuorumSignature`. Applying the
registration shape to a key token fails the other way, with `No token signatures provided`. A
signing helper has to handle both.

In both cases the signed value is a 32-byte SHA-256 digest, signed as-is rather than re-hashed:

```
openssl pkeyutl -sign -inkey <approver.key> -pkeyopt digest:sha256 -keyform PEM \
  -in <decoded digest> -out <sig>
```

Registration is itself a quorum-token operation, which looks circular and is not: the token comes
from the `registration` service and is signed by the registering user's **own** private key, which
is exactly the proof of possession the HSM needs before it will trust that public key.

### The lock-out hazard

AWS's own guidance is to keep **at least two more admins than the M value**, so that if one is
locked out the others can still reset passwords. Deleting users is the dangerous operation: if the
number of available approvers falls below M, **you can no longer create users or authorise any
operation, and you lose the ability to administer the cluster**. The documented recovery is to
restore a backup into a **new cluster**.

Token housekeeping details worth knowing: tokens expire **ten minutes** after creation by default;
an HSM stores up to **1,024** tokens and purges an expired one when full; a user may sign their
own token, which counts as one of the required approvals; and the "one active token per user per
service" limit applies to the `user` and `quorum` services but **not** to key services. When MFA
is enabled, the **same key** serves both MFA and quorum authentication.

## Following is the proposed architecture

The architecture presented here can be part of a more complete, [event-based solution](https://aws.amazon.com/en/event-driven-architecture/), which can cover the entire payment message transmission flow, from the banking core. For example, the complete solution of the Financial Institution (paying or receiving), could contain other complementary architectures such as **Authorization**, **Undo** (based on the [SAGA model](https://docs.aws.amazon.com/whitepapers/latest/microservices-on-aws/distributed-data-management.html)), **Effectiveness**, **Communication with on-premises** environment ([hybrid environment](https://aws.amazon.com/en/hybrid/)), etc., using other services such as [Amazon EventBridge](https://aws.amazon.com/en/eventbridge/), Amazon Simple Notification Service ([SNS](https://aws.amazon.com/en/sns/?whats-new-cards.sort-by=item.additionalFields.postDateTime&whats-new-cards.sort-order=desc)), Amazon Simple Queue Service ([SQS](https://aws.amazon.com/en/sqs/)), [AWS Step Functions](https://aws.amazon.com/en/step-functions/), [Amazon ElastiCache](https://aws.amazon.com/en/elasticache/), [Amazon DynamoDB](https://aws.amazon.com/en/dynamodb/).

<p align="center">
  <img src="/images/proxy-cloudhsm-arch.png" width="600" height="600">

> This diagram is **generated**, not hand-drawn. Its source is
> [`tools/generate_architecture_diagram.py`](tools/generate_architecture_diagram.py), which composes
> the official [AWS Architecture Icons](https://aws.amazon.com/architecture/icons/) (release
> `07312026`). Re-run it when AWS ships a new icon release or renames a service — the script fails
> loudly if an icon it expects is no longer in the package, which is how the renames of
> *Kinesis Data Firehose → Data Firehose* and *QuickSight → Quick* were caught. The icon set itself
> is deliberately not committed here; download it from the link above and pass `--icons`.
</p>


1. Store login and password in [AWS Secrets Manager](https://aws.amazon.com/en/secrets-manager/), to communicate with AWS CloudHSM.
2. Store or import the private key on [AWS CloudHSM](https://aws.amazon.com/cloudhsm/?nc1=h_ls).
3. Store the three certificates in the [AWS Systems Manager Parameter Store](https://docs.aws.amazon.com/systems-manager/latest/userguide/systems-manager-parameter-store.html): generated key certificate for signature, certificate generated for mTLS, CloudHSM certificate (customer CA).
4. Service/Application sends transaction request in XML format.
5. [ELB](https://aws.amazon.com/en/elasticloadbalancing/) balances requests among [AWS Fargate containers](https://aws.amazon.com/en/fargate/).
6. Application (AWS Fargate) uses AWS CloudHSM for digital signature of XML.
7. Application (AWS Fargate) uses AWS CloudHSM to establish mTLS and transmit XML to [BACEN](https://www.bcb.gov.br/en/financialstability/instantpayments).
8. Application (AWS Fargate) receives the response from BACEN and, if necessary, validates the digital signature of the received XML.
9. Application (AWS Fargate) logs the request log by sending it directly to [Amazon Data Firehose](https://aws.amazon.com/en/kinesis/data-firehose/).
10. The reply message is sent to the ELB.
11. The reply message is received by the Service/Application.
12. Amazon Data Firehose uses the [AWS Glue Data Catalog](https://aws.amazon.com/en/glue/?whats-new-cards.sort-by=item.additionalFields.postDateTime&whats-new-cards.sort-order=desc) to convert the logs to parquet format.
13. Amazon Data Firehose sends the logs to [Amazon S3](https://aws.amazon.com/en/s3/), already partitioned into “folders” (/year/month/day/hour/).
14. [Amazon Athena](https://docs.aws.amazon.com/athena/latest/ug/glue-athena.html) uses the AWS Glue Data Catalog as a central place to store and retrieve table metadata.
15. [AWS Glue crawlers](https://docs.aws.amazon.com/glue/latest/dg/add-crawler.html) automatically update the metadata repository every hour.
16. You can immediately query the data directly on Amazon S3 using serverless analytics services, such as [Amazon Athena](https://aws.amazon.com/en/athena/?whats-new-cards.sort-by=item.additionalFields.postDateTime&whats-new-cards.sort-order=desc) (ad hoc with standard SQL) and optionally the [Amazon Quick](https://aws.amazon.com/quicksight/) (formerly **Amazon QuickSight**).


## How to deploy?

### AWS CloudHSM

Here are the resources you’ll need in order to follow along with both architectures:

- An Amazon Virtual Private Cloud (Amazon VPC) with the following components:

Private subnet in Availability Zone to be used for the HSM’s elastic network interface (ENI).
A public subnet that contains a network address translation (NAT) gateway.
A private subnet with a route table that routes internet traffic (0.0.0.0/0) to the NAT gateway. You’ll use this subnet to run the AWS Fargate application. The NAT gateway allows you to connect to the AWS CloudHSM, [AWS Systems Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/setup-create-vpc.html), and [AWS Secrets Manager endpoints](https://docs.aws.amazon.com/secretsmanager/latest/userguide/vpc-endpoint-overview.html#vpc-endpoint).

 **Note**: For high availability, you can add multiple instances of the public and private subnets. For more information about how to create an Amazon VPC with public and private subnets as well as a NAT gateway, refer to the [Amazon VPC user guide](https://docs.aws.amazon.com/vpc/latest/userguide/VPC_Scenarios.html).

- An **active AWS CloudHSM cluster** with at least one active HSM. The HSMs should be created in the private subnets. You can follow the Getting Started with [AWS CloudHSM guide](https://docs.aws.amazon.com/cloudhsm/latest/userguide/create-cluster.html) to create and initialize the CloudHSM cluster.

> ### ⚠️ This prerequisite can no longer be satisfied as written
>
> This sample's code is hard-wired to **CloudHSM Client SDK 3** (`com.cavium.cfm2.LoginManager`,
> `PARTITION_1`, `key_mgmt_util`, `cloudhsm-client-jce-latest.el7.x86_64.rpm`), which only works with
> the `hsm1.medium` HSM type. Per AWS's own
> [deprecation notice](https://docs.aws.amazon.com/cloudhsm/latest/userguide/compliance-dep-notif.html)
> and [HSM types page](https://docs.aws.amazon.com/cloudhsm/latest/userguide/hsm-types.html):
>
> * **new `hsm1.medium` clusters cannot be created since April 2025**;
> * `hsm1.medium` **reached end of support on 2026-03-31**;
> * existing `hsm1.medium` clusters have been auto-migrated to `hsm2m.medium` since January 2026;
> * `hsm2m.medium` **requires Client SDK 5.9.0 or later**.
>
> So the old HSM type can no longer be created and this code cannot talk to the new one. Making the
> CloudHSM path work again requires **porting SDK 3 to SDK 5** (a different API) *and* moving to
> JDK 17/21 (SDK 5's JCE supports OpenJDK 17/21/25 only) *and* adding the two `--add-exports` flags,
> because this project throws `IllegalAccessError` at runtime on JDK 17 without them. Those three
> changes are one package.
>
> **The KMS architecture in [README-KMS.md](README-KMS.md) is unaffected** and is the path to use if
> you just want to run the sample. See [VERIFICATION.md](VERIFICATION.md) for the measured evidence.

- The **AWS CloudHSM client** installed and configured to connect to the CloudHSM cluster. Optionally, you can use an Amazon Linux 2 EC2 instance with the CloudHSM client installed and configured. The client instance should be launched in the public subnet. You can again refer to [Getting Started With AWS CloudHSM](https://docs.aws.amazon.com/cloudhsm/latest/userguide/getting-started.html) to configure and connect the client instance. Also, install the [AWS CloudHSM Dynamic Engine for OpenSSL](https://docs.aws.amazon.com/cloudhsm/latest/userguide/openssl-library-install.html).

- The **CO** and **CU credentials** created: CO (crypto officer) and CU (crypto user) by following the steps in the [user guide](https://docs.aws.amazon.com/cloudhsm/latest/userguide/manage-hsm-users.html#create-user).

#### Generate keys and certificate to digital signature

You can generate or import a [private key using Open SSL](https://docs.aws.amazon.com/cloudhsm/latest/userguide/ssl-offload-import-or-generate-private-key-and-certificate.html). 
We recommend that private key should be non-extractable.

- Generating a [NON-EXTRACTABLE](https://docs.aws.amazon.com/cloudhsm/latest/userguide/key_mgmt_util-genRSAKeyPair.html) private key:

Launch the key management util:
```
$ /opt/cloudhsm/bin/key_mgmt_util
```

Login:
```
Command: loginHSM -u CU -s <HSM_USER> -p <HSM_PASSWORD>
```

Generate the key pair:
```
Command: genRSAKeyPair -m 2048 -e 65537 -l <LABEL> -nex 

Cfm3GenerateKeyPair:    public key handle: <X>    private key handle: <Y>
```

Exit
```
Command: exit
```

Launch the CloudHSM management util:
```
$ /opt/cloudhsm/bin/cloudhsm_mgmt_util /opt/cloudhsm/etc/cloudhsm_mgmt_util.cfg
```

Login:
```
aws-cloudhsm> loginHSM CU <HSM_USER> <HSM_PASSWORD>
```

Check that private key is not extractable:
```
aws-cloudhsm> getAttribute <Y> 354

OBJ_ATTR_EXTRACTABLE
0x00000000
```

Note that the public key is always extractable:
```
aws-cloudhsm> getAttribute <X> 354

OBJ_ATTR_EXTRACTABLE
0x00000001
```

Check the private key label:
```
aws-cloudhsm> getAttribute <Y> 3

OBJ_ATTR_LABEL
<LABEL>
```

Check the public key label:
```
aws-cloudhsm> getAttribute <X> 3

OBJ_ATTR_LABEL
<LABEL>
```

Change the public key label:
```
aws-cloudhsm> setAttribute <X> 3 <LABEL:PUBLIC>
```

Check the public key label:
```
aws-cloudhsm> getAttribute <X> 3

OBJ_ATTR_LABEL
<LABEL:PUBLIC>
```

Exit
```
aws-cloudhsm> quit
```

Launch the key management util:
```
$ /opt/cloudhsm/bin/key_mgmt_util
```
Login:
```
Command: loginHSM -u CU -s <HSM_USER> -p <HSM_PASSWORD>
```

Export the fake private key:
```
Command: getCaviumPrivKey -k <Y> -out <LABEL>.key
```

Exit
```
Command: exit
```

Export the HSM_USER and HSM_PASSWORD to use with OpenSSL:
```
$ export n3fips_password=<HSM_USER>:<HSM_PASSWORD>
```

Generate the CSR:
```
$ openssl req -engine cloudhsm -new -key <LABEL>.key -out <LABEL>.csr
```

Generate a self-signed certificate (ONLY FOR TEST):
```
$ openssl x509 -engine cloudhsm -req -days <DAYS> -in <LABEL>.csr -signkey <LABEL>.key -out <LABEL>.cer
```

#### Generate keys and certificate to mTLS

- Generating an extractable key for mTLS (Cavium has JCE, but not JSSE):

Launch the key management util:
```
$ /opt/cloudhsm/bin/key_mgmt_util
```

Login:
```
Command: loginHSM -u CU -s <HSM_USER> -p <HSM_PASSWORD>
```

Generate a key pair:
```
Command: genRSAKeyPair -m 2048 -e 65537 -l <LABEL>

Cfm3GenerateKeyPair:    public key handle: <X>    private key handle: <Y>
```

Exit
```
Command: exit
```

Launch the CloudHSM management util:
```
$ /opt/cloudhsm/bin/cloudhsm_mgmt_util /opt/cloudhsm/etc/cloudhsm_mgmt_util.cfg
```

Login:
```
aws-cloudhsm> loginHSM CU <HSM_USER> <HSM_PASSWORD>
```

Check that private key is extractable:
```
aws-cloudhsm> getAttribute <Y> 354

OBJ_ATTR_EXTRACTABLE
0x00000001
```

Public key is always extractable:
```
aws-cloudhsm> getAttribute <X> 354

OBJ_ATTR_EXTRACTABLE
0x00000001
```

Check the private key label:
```
aws-cloudhsm> getAttribute <Y> 3

OBJ_ATTR_LABEL
<LABEL>
```

Check the public key label:
```
aws-cloudhsm> getAttribute <X> 3

OBJ_ATTR_LABEL
<LABEL>
```

Change the public key label:
```
aws-cloudhsm> setAttribute <X> 3 <LABEL:PUBLIC>
```

Check again the public key label:
```
aws-cloudhsm> getAttribute <X> 3

OBJ_ATTR_LABEL
<LABEL:PUBLIC>
```

Exit
```
aws-cloudhsm> quit
```

Launch the key management util:
```
$ /opt/cloudhsm/bin/key_mgmt_util
```

Login:
```
Command: loginHSM -u CU -s <HSM_USER> -p <HSM_PASSWORD>
```

Export the fake private key:
```
Command: getCaviumPrivKey -k <Y> -out <LABEL>.key
```

Exit
```
Command: exit
```

Export the HSM_USER and HSM_PASSWORD to use with OpenSSL:
```
$ export n3fips_password=<HSM_USER>:<HSM_PASSWORD>
```

Generate the CSR:
```
$ openssl req -engine cloudhsm -new -key <LABEL>.key -out <LABEL>.csr
```

Generate a self-signed certificate (ONLY FOR TEST):
```
$ openssl x509 -engine cloudhsm -req -days <DAYS> -in <LABEL>.csr -signkey <LABEL>.key -out <LABEL>.cer
```

### AWS Secrets Manager

[Create](https://docs.aws.amazon.com/secretsmanager/latest/userguide/tutorials_basic.html) a secret with name `/pix/proxy/cloudhsm/CloudHSMSecret` and value:
```
{
  "HSM_USER": "<HSM_USER>",
  "HSM_PASSWORD": "<HSM_PASSWORD>"
}
```

### Register (log audit)

1. Before you can upload data to Amazon S3, you must [create a bucket](https://docs.aws.amazon.com/AmazonS3/latest/user-guide/create-bucket.html) in one of the AWS Regions to store your data. After you create a bucket, you can upload an unlimited number of data objects to the bucket

2. The AWS Glue Data Catalog contains references to data that is used as sources and targets of your extract, transform, and load (ETL) jobs in AWS Glue. Information in the Data Catalog is stored as metadata tables, where each table specifies a [single data store](https://docs.aws.amazon.com/glue/latest/dg/populate-data-catalog.html).

3. [Define a database](https://docs.aws.amazon.com/glue/latest/dg/populate-data-catalog.html) in your Data Catalog.

4. [Define two tables](https://docs.aws.amazon.com/glue/latest/dg/tables-described.html): SPI and DICT. Both tables need to have the [Columns Structure](https://docs.aws.amazon.com/glue/latest/dg/aws-glue-api-catalog-tables.html#aws-glue-api-catalog-tables-Column) and [Partition Keys](https://docs.aws.amazon.com/glue/latest/dg/tables-described.html#tables-partition), like example below:

```
columns: [
    {name: 'request_date', type: glue.Schema.STRING},
    {name: 'request_method', type: glue.Schema.STRING},
    {name: 'request_path', type: glue.Schema.STRING},
    {name: 'request_query', type: glue.Schema.STRING},
    {name: 'request_header', type: glue.Schema.STRING},
    {name: 'request_body', type: glue.Schema.STRING},
    {name: 'response_status_code', type: glue.Schema.INTEGER},
    {name: 'response_signature_valid', type: glue.Schema.STRING},
    {name: 'response_header', type: glue.Schema.STRING},
    {name: 'response_body', type: glue.Schema.STRING},
    {name: 'transport_failure', type: glue.Schema.STRING}
],
            
partitionKeys: [
    {name: 'year', type: glue.Schema.STRING},
    {name: 'month', type: glue.Schema.STRING},
    {name: 'day', type: glue.Schema.STRING},
    {name: 'hour', type: glue.Schema.STRING}
]
```

The DICT table must be pointed to the S3 bucket that you created and must have the prefix `log/dict`.
<br/>
The SPI table must be pointed to the S3 bucket that you created and must have the prefix `log/spi`.

5. [Create a crawler](https://docs.aws.amazon.com/glue/latest/dg/add-crawler.html) with target to the created tables (SPI and DICT).

6. Create two Amazon Data Firehose delivery streams: (SPI and DICT) in the AWS Glue Data Catalog to make the conversion in parquet format. Thus, we have the following prefixes and the destination S3 bucket:

  * DICT develivery stream:
    * Deliver to S3 Bucket created
    * Use the Glue DICT table to convert to PARQUET
    * Specify:
```
prefix: log/dict/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/hour=!{timestamp:HH}/
errorOutputPrefix: error/dict/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/hour=!{timestamp:HH}/!{firehose:error-output-type}
```

   * SPI develivery stream:
      * Deliver to S3 Bucket created
      * Use the Glue SPI table to convert to PARQUET
      * Specify:
```
prefix: log/spi/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/hour=!{timestamp:HH}/
errorOutputPrefix: error/spi/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/hour=!{timestamp:HH}/!{firehose:error-output-type}
```

### Alarms on unaudited transactions

An unaudited Pix transaction is a compliance event, and until now nothing here watched for one. The
`errorOutputPrefix` below is configured, so records Firehose cannot deliver are written to S3 — but
a prefix that nobody looks at is not a control. These are the alarms that make the existing plumbing
observable.

**Alarm on the log tokens, not on the log wording.** The proxy emits stable tokens next to each
human sentence, declared in `AuditAlarmTokens`. A metric filter that matched prose would silently
stop firing the first time someone improved a message — and an alarm that has gone quiet reads
exactly like "nothing is wrong". **Changing one of those strings breaks a deployed alarm; treat them
as a published interface.**

| Token | Means | Severity |
|---|---|---|
| `PIX_AUDIT_SPOOLED` | A record could not be delivered and went to the local spool | High — recoverable, but only if the spool is shipped |
| `PIX_AUDIT_QUEUE_FULL` | Off-path delivery is not keeping up with traffic | High — sustained means records are spooling continuously |
| `PIX_AUDIT_NO_RECORD` | An exchange produced no audit record at all | High — nothing was sent to BCB, but the gap is unexplained |
| `PIX_AUDIT_SPOOL_WRITE_FAILED` | Neither delivered nor persisted | **Critical — the record is genuinely lost** |

```typescript
// One metric filter per token; treat PIX_AUDIT_SPOOL_WRITE_FAILED as page-worthy.
const auditLost = new logs.MetricFilter(this, 'AuditRecordLost', {
    logGroup: proxyLogGroup,
    filterPattern: logs.FilterPattern.literal('"PIX_AUDIT_SPOOL_WRITE_FAILED"'),
    metricNamespace: 'Pix/Audit',
    metricName: 'AuditRecordLost',
    metricValue: '1',
    defaultValue: 0,          // REQUIRED: without it the metric has no datapoints while healthy,
});                           // and the alarm sits in INSUFFICIENT_DATA rather than OK

new cloudwatch.Alarm(this, 'AuditRecordLostAlarm', {
    metric: auditLost.metric({period: cdk.Duration.minutes(1), statistic: 'Sum'}),
    threshold: 0,
    comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
    evaluationPeriods: 1,     // a single lost audit record is already reportable
    treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
});
```

**Also alarm on the Firehose side**, because the tokens above cannot see a failure that happens after
the proxy has handed the record over:

```typescript
// Records Firehose accepted but could not deliver - these land under errorOutputPrefix.
new cloudwatch.Alarm(this, 'AuditDeliveryFailedAlarm', {
    metric: new cloudwatch.Metric({
        namespace: 'AWS/Firehose',
        metricName: 'DeliveryToS3.Success',
        dimensionsMap: {DeliveryStreamName: dictAuditStream.deliveryStreamName!},
        period: cdk.Duration.minutes(5),
        statistic: 'Average',
    }),
    threshold: 1,             // Average < 1 means some puts are failing
    comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
    evaluationPeriods: 1,
    treatMissingData: cloudwatch.TreatMissingData.BREACHING,   // no data = not delivering
});

// Freshness catches a stream that is silently stalled rather than erroring.
new cloudwatch.Alarm(this, 'AuditDataFreshnessAlarm', {
    metric: new cloudwatch.Metric({
        namespace: 'AWS/Firehose',
        metricName: 'DeliveryToS3.DataFreshness',
        dimensionsMap: {DeliveryStreamName: dictAuditStream.deliveryStreamName!},
        period: cdk.Duration.minutes(5),
        statistic: 'Maximum',
    }),
    threshold: 900,          // reconcile with the buffering hint actually configured
    comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
    evaluationPeriods: 2,
});
```

Note on `treatMissingData`: the two Firehose alarms use **BREACHING** on purpose. A delivery stream
that has stopped receiving anything publishes no datapoints, so `NOT_BREACHING` would leave a totally
dead audit pipeline sitting in `OK`. That is the failure mode most worth catching and the easiest to
configure wrongly.

Not yet done, and deliberately not claimed: none of this is wired in CDK in this repository, because
there is no CDK app here to wire it into — the infrastructure is documented rather than deployed. What
the code now guarantees is that every one of these conditions emits a stable, greppable token.

### AWS Systems Manager Parameter Store

1. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/cloudhsm/CloudHSMClusterId` and value:
```
<CLOUDHSM_CLUSTER_ID>
```
To find out the CloudHSM Cluster Id is simple. In the AWS console, type CloudHSM and you will find your cluster. In the CloudHSM clusters list you will see the Cluster Id in the format "cluster-xxxxxxxxxxx".

<p align="center">
  <img src="/images/cloudhsm-id.jpg">
</p>


2. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/cloudhsm/CloudHSMCustomerCA` and value:
```
-----BEGIN CERTIFICATE-----
<CLOUDHSM_CUSTOMER_CA_CERTIFICATE>
-----END CERTIFICATE-----
```

3. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/cloudhsm/SignatureKeyLabel` and value:
```
<SIGNATURE_LABEL>
```

4. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/cloudhsm/SignatureCertificate` and value:
```
-----BEGIN CERTIFICATE-----
<SIGNATURE_CERTIFICATE>
-----END CERTIFICATE-----
```

5. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/cloudhsm/MtlsKeyLabel` and value:
```
<MTLS_KEY_LABEL>
```

6. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/cloudhsm/MtlsCertificate` and value:
```
-----BEGIN CERTIFICATE-----
<SIGNATURE_CERTIFICATE>
-----END CERTIFICATE-----
```

7. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/cloudhsm/DictAuditStream` and value:
```
<FIREHOSE_DICT_DELIVERY_STREAM_NAME>
```

8. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/cloudhsm/SpiAuditStream` and value:
```
<FIREHOSE_SPI_DELIVERY_STREAM_NAME>
```

9. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/cloudhsm/BcbDictEndpoint`.

> **Value format:** hostname plus port only — do **not** include `https://`, a trailing slash, or `/api/v2`. The CloudHSM proxy uses Camel `bridgeEndpoint(true)` and preserves the incoming request path/query string. The calling application must therefore send the BCB v2 path (for example `/api/v2/entries/{Key}`) to the proxy.
>
> **BCB DICT v2 baseline (verify with BCB before use):**
> - Homologação: `dict-h.pi.rsfn.net.br:16522`
> - Production: `dict.pi.rsfn.net.br:16422`
> - API v1 was fully disabled on 2024-02-04. Do not configure or call `/v1/` paths.
>
> These are BCB endpoint bases, not a substitute for BCB participant onboarding, current certificates, Security Manual requirements, or homologação tests.

```
<DICT_V2_HOST_AND_PORT>
```

> **⚠️ Local simulator certificate warning.** The simulator certificate has a PUBLIC private key. It ships in this repository at `proxy/test/src/main/docker/ssl/` next to `sig.key` / `mtls.key`, and its subject is BACEN's real production domain (`O=BCB, OU=PIX, CN=*.pi.rsfn.net.br`, valid until 2030-07-03). Anyone who reads this repository can forge a response accepted by a proxy trusting that certificate.
>
> The `BcbSignatureCertificate` parameter is the same trust mechanism used in production. Switching from the simulator to BCB is not merely changing the endpoint: replace **both** BCB trust certificates with the current BCB-provided chain. The application logs an ERROR naming this known simulator certificate when it is trusted (`WellKnownTestCertificates`); alarm on that line outside local simulation.

**Local simulator only — not a BCB endpoint:**
```
test.pi.rsfn.net.br:8181
```

10. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/cloudhsm/BcbSpiEndpoint`.

> **Value format:** hostname plus port only. The caller supplies the SPI message path. The sample `pacs.008.spi.1.4` fixture and the local simulator are historical teaching material; obtain the current SPI Message Definition, XSD, endpoint, certificate chain and TLS policy from BCB before homologação/production use. Do not infer a production SPI endpoint or message version from this repository.

```
<SPI_HOST_AND_PORT_FROM_CURRENT_BCB_ONBOARDING>
```

**Local simulator only — not a BCB endpoint:**
```
test.pi.rsfn.net.br:9191
```

11. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/cloudhsm/BcbSignatureCertificate` and value:
```
-----BEGIN CERTIFICATE-----
<BACEN_SIGNATURE_CERTIFICATE>
-----END CERTIFICATE-----
```

TO USE THE TEST - SIMULATOR, use:
```
-----BEGIN CERTIFICATE-----
MIIDnDCCAoSgAwIBAgIEaLkRBjANBgkqhkiG9w0BAQsFADBkMQswCQYDVQQGEwJC
UjELMAkGA1UECBMCREYxETAPBgNVBAcTCEJyYXNpbGlhMQwwCgYDVQQKEwNCQ0Ix
DDAKBgNVBAsTA1BJWDEZMBcGA1UEAwwQKi5waS5yc2ZuLm5ldC5icjAeFw0yMDA3
MDUxMzUwMzVaFw0zMDA3MDMxMzUwMzVaMGQxCzAJBgNVBAYTAkJSMQswCQYDVQQI
EwJERjERMA8GA1UEBxMIQnJhc2lsaWExDDAKBgNVBAoTA0JDQjEMMAoGA1UECxMD
UElYMRkwFwYDVQQDDBAqLnBpLnJzZm4ubmV0LmJyMIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAy38YHSwphFKHH49rFbl/caqP/ugD0vD3n6lrGzC9xukG
q81bVYXKBzbVtn8gxCOsUCIktMoZNe6QCUeTGshreohIFKdzV/ZH70eZcCOcGoZX
3evPJuRYIpjjxp0CJbj71EubylavUNpgGjj9v02ezlto94oQN87YR77sDBPBPGeW
CwaYPN8KY0tW8CqrmJXkMsA+pd/1tv3QbBpkUbEgbTvrVTz+9qEUpAg6SeytIulg
icLQrklYPv/Jex4KKcZxAp6SGBrMYmuCViw40qd1SriWk5HYfmMzXSy6DJ7HO5Im
0F1g43XdEWr0hUmUpsFu2JTIO5qGgx9OcQ6Tw74mgQIDAQABo1YwVDAdBgNVHQ4E
FgQUMswECZ5M0yc1aMkxWdNucYsKjU8wMwYDVR0RAQH/BCkwJ4IJbG9jYWxob3N0
ghRob3N0LmRvY2tlci5pbnRlcm5hbIcEfwAAATANBgkqhkiG9w0BAQsFAAOCAQEA
varWSOwcE2A5sIsJbPHczsDXiVOObfJjVol/JBXPH00A8uZ6hbsWDCNp7XZHjheW
snw9acXzKvi+NY/kCYaSegsUr9O+2BBcGhCN4LI5uITE9s3YZKyl+2rqk93P7EDB
RSitjPXeRm9ANPZCR90h+amZQLNbfiK0Povrv61isFqLfdGXnk9B6tfLB+baeS8f
HhxEM22sd+5yo9rUZOdAGI72SMzgaMT1AJZbVbb3z2ymDByJkgTAsVdkkSNkqwEi
Y3lg6cJ5thj5NdaXWc8wCzG6L85uAVV/7eh0SMJ2ITMJwkrrqtrX47LeNPrtCTy/
B8Um+Ao1f9w4nbxP53d+6w==
-----END CERTIFICATE-----
```

12. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/cloudhsm/BcbMtlsCertificate` and value:
```
-----BEGIN CERTIFICATE-----
<BACEN_MTLS_CERTIFICATE>
-----END CERTIFICATE-----
```

TO USE THE TEST - SIMULATOR, use:
```
-----BEGIN CERTIFICATE-----
MIIDnDCCAoSgAwIBAgIEBR5HdTANBgkqhkiG9w0BAQsFADBkMQswCQYDVQQGEwJC
UjELMAkGA1UECBMCREYxETAPBgNVBAcTCEJyYXNpbGlhMQwwCgYDVQQKEwNCQ0Ix
DDAKBgNVBAsTA1BJWDEZMBcGA1UEAwwQKi5waS5yc2ZuLm5ldC5icjAeFw0yMDA3
MDUxMzUxMTFaFw0zMDA3MDMxMzUxMTFaMGQxCzAJBgNVBAYTAkJSMQswCQYDVQQI
EwJERjERMA8GA1UEBxMIQnJhc2lsaWExDDAKBgNVBAoTA0JDQjEMMAoGA1UECxMD
UElYMRkwFwYDVQQDDBAqLnBpLnJzZm4ubmV0LmJyMIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAztOPl4NGjpvf/d07FHHkbJKC7xRwoBhvTpTQ/vQp9E3v
hUI4fIgvvsXAzaknifMysdtk1BRS3Urk6tiL9ZCKEVcqfTPTdawcBi2AABrBvWYx
jDTk5dK1o8wPcUyWRDMRXiWv7grODR75u5a+s3bZTOWLIDxGpY2cuDSRWK0bT8Zh
of+8cn4yML03A83mqrfri1rahH/WpGzwOPk6+pv2m/VKv6GTS1ADD5xTExO5Zotg
wYAuU/zUVZ007CvHGGVoJ87hbUr8EmW1DsgxcPGWeKZ0SzCZkV88eLaD6sedyg0q
5w1ACRPSK0fRXHpxLkqckJ72hyinr/S/axOvM9bijQIDAQABo1YwVDAdBgNVHQ4E
FgQU+hfJS2Fp0POVlnuOxCsgquNqJocwMwYDVR0RAQH/BCkwJ4IJbG9jYWxob3N0
ghRob3N0LmRvY2tlci5pbnRlcm5hbIcEfwAAATANBgkqhkiG9w0BAQsFAAOCAQEA
hjIm6Cj35JR1cbKsIlUFlFVfN7/D9Rx2GOq7JtD4SbzTbDyJ3zm/usVMFFNDNuSs
mJhqLpTKmPX9Akp55RSdnLDEs2tDs7rN5Fy5BODwHblnnyflN0oSihnGop0TtEtv
Gw+zWXms4Pm9Vyi3l+UQA3ENJICP3H7iiPyxj0kThjhFnoIn4kqd+/xSp/BBR6JB
1UofthomxU4qcYcb4gWBYdgaGzoUIk3W3iMBzQDQmmiqAgKYhEA24SrOgkwWw0/o
3RNL7mYI5L3tivyrc0/K3/aE0yPhDAMHpC8V7taUwnb4k7rClB0bqF5GmCnWzBis
NAoejbjou87yzYUTY8nRnw==
-----END CERTIFICATE-----
```

### AWS Fargate (PROXY)

1. To configure the Amazon ECS using Fargate, use this [procedure](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/getting-started-fargate.html). You can use the dockerfile `proxy/cloudhsm/proxy/src/main/docker/Dockerfile`. You also need configure the following [permissions](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-iam-roles.html) to:

- Read the secret (AWS Secrets Manager).
- Read the parameters (AWS Systems Manager Parameter Store).
- **Describe the CloudHSM cluster (`cloudhsmv2:DescribeClusters`).** `wrapper_script.sh` calls
  `aws cloudhsmv2 describe-clusters` at container start to discover the ACTIVE HSM IPs. This is an
  **IAM** permission and is separate from the security-group rule below — without it the container
  exits before the JVM is ever launched.
- If any parameter above is created as a **SecureString**, also allow `kms:Decrypt` on that
  parameter's KMS key. The application requests decryption unconditionally, which is ignored for
  plain `String` parameters.
- Put data (log) into deliver streams (Amazon Data Firehose).
- [Connect](https://docs.aws.amazon.com/cloudhsm/latest/userguide/configure-sg.html) to the AWS CloudHSM cluster.

You have to expose the service using **INTERNAL** [Application Load Balancer](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/create-application-load-balancer.html).

### AWS Fargate (TEST - SIMULATOR)

1. To configure the Amazon ECS using Fargate for testing, use this [procedure](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/getting-started-fargate.html). You can use the test dockerfile `/proxy/test/src/main/docker/Dockerfile`. You also need configure the following [permissions](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-iam-roles.html) to:

- Read the parameters (AWS Systems Manager Parameter Store).

You have to expose the service using the **INTERNAL** [Network Load Balancer](https://docs.aws.amazon.com/elasticloadbalancing/latest/network/create-network-load-balancer.html).

2. You have to configure a [private hosted zone](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/hosted-zone-private-creating.html) with domain name `rsfn.net.br`. Also, use this [procedure](https://aws.amazon.com/premiumsupport/knowledge-center/route-53-create-alias-records/) to configure an A record for the name `test.pi.rsfn.net.br` and specify the alias for the TEST Network Load Balancer.

### Amazon Athena and Amazon Quick

1. Use this [procedure](https://docs.aws.amazon.com/athena/latest/ug/getting-started.html) to use Amazon Athena to query data in the S3 bucket created previously. 

2. Optionally, you can use [Amazon Quick](https://docs.aws.amazon.com/quicksight/latest/user/setup-new-quicksight-account.html) that lets you easily create and publish interactive dashboards that include ML Insights. Dashboards can then be accessed from any device, and embedded into your applications, portals, and website.
