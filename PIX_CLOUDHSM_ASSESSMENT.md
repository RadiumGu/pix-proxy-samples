# Pix CloudHSM proxy — verification assessment

Audience: a decision-maker choosing how to sign and transport Pix messages with AWS CloudHSM.
This is not an engineering handoff — `CLOUDHSM_BCB_V2_HANDOFF.md` is that, and
`README-CloudHSM.md` is the architecture. This document says what was **verified on real
hardware**, what remains **unproven**, and what this repository deliberately **does not do**.

Everything below is labelled MEASURED (a command was run and its output read) or INFERRED
(a conclusion drawn from measurements). Where evidence could not be obtained, that is stated
as a gap rather than filled with a plausible answer.

---

## 1. What this repository is, and what it is not

It is a **teaching skeleton** for two things: signing Pix XML with a key that never leaves a
FIPS-validated HSM, and transporting the result to BCB over mutual TLS without altering the
message. It is transparent by design — the proxy does not interpret Pix business content.

It is **not a PSP**. Section 6 lists what a real participant must build on top, so that the
scope of this work is not mistaken for readiness to transact.

---

## 2. Verified on real CloudHSM hardware

Two throwaway clusters were provisioned, driven, and destroyed. Eight questions had been
identified as blocking an architecture decision; all eight now have answers.

| # | Question | Verdict | Evidence |
|---|---|---|---|
| 1 | Can the signing key be created non-extractable? | **Yes, but only explicitly** | MEASURED `extractable: false, never-extractable: true, always-sensitive: true, local: true`. SDK 5 defaults to `extractable=true`; the attribute must be passed. |
| 2 | Does the JCE provider expose private key bytes? | **No** | MEASURED `CloudHsmRsaPrivateCrtKey`, `getEncoded()=null`, `getFormat()=null`. |
| 3 | Does the HSM audit log prove a signature happened inside it? | **No — evidence unobtainable** | MEASURED: the audit log contains **no signing opcode at all** after ~10 private-key operations. See §4. |
| 4 | Does RSA signing work end to end? | **Yes** | MEASURED 256-byte RSA-2048 signature, `Verified OK`; CSR verifies OK. |
| 5 | Does mutual TLS complete with an HSM-held client key? | **Yes** | MEASURED server logged `depth=0 C=BR, O=pixpoc, CN=pix-client` and `1 server accepts that finished`. |
| 6 | Is the BCB-mandated minimum cipher negotiated? | **Yes** | MEASURED `Cipher is ECDHE-RSA-AES128-GCM-SHA256` on both peers. |
| 7 | Does TLS 1.3 work? | **Yes** | MEASURED `Protocol: TLSv1.3`, `TLS_AES_256_GCM_SHA384`. |
| 8 | Does the existing signing code work on SDK 5 unmodified? | **Yes** | MEASURED `SIGN_OK=true, length=1729, VERIFY=true` with a `never-extractable` key. |

### Why question 8 is the one that scopes the migration

`XmlSigner` runs **unmodified** on SDK 5, because JSR-105 routes the `Signature` operation to
the CloudHSM provider. So an SDK 3 → SDK 5 migration is **not** a rewrite of the signing
logic. The entire difficulty sits in the TLS half: Netty wants key *bytes*, and an HSM only
ever gives you a *handle*.

---

## 3. The recommended transport path, and why

Four ways to give Netty an HSM-held client key were considered. **Path D — a Java client that
hands the HSM's `PrivateKey` object to an SSL context using `SslProvider.JDK`** is recommended.

The deciding evidence is question 2. Because `getEncoded()` returns `null`, any path that
needs key bytes is not merely awkward — it is **impossible**, not a matter of effort:

- **OpenSSL engine path: eliminated by measurement.** `-keyform engine` fails with
  `ENGINE_load_private_key: no load function`. The engine implements **no load function**, so
  the only mechanism is a `reference-pem` file. An nginx `ssl_certificate_key engine:...`
  configuration would never have worked. This was a genuine unknown, and it is now closed.
- **Path B (`SslProvider.OPENSSL` with an HSM key): requires `SslProvider.JDK` instead**, for
  the same reason — the native provider needs bytes.
- **Path D works** because JSSE accepts a `PrivateKey` whose operations are delegated to a
  provider, without ever reading its material.

The cost of path D, stated plainly: it forgoes the native OpenSSL engine, so TLS throughput is
JSSE's rather than tcnative's. For Pix message rates that is a trade worth making; for a
different workload it might not be.

---

## 4. Where the evidence does not reach

**An operator cannot prove from the audit log that a given signature was produced inside the
HSM.** This was expected to be provable and is not. MEASURED: the audit log records
`CN_GENERATE_KEY_PAIR`×1, `CN_LOGIN`×29, `CN_CREATE_USER`×3, `CN_APP_FINALIZE`×19,
`CN_ENCRYPT_SESSION_V2`×53 — and **no signing opcode**, despite roughly ten private-key
operations having occurred. CloudHSM audits **management** commands, not data-plane crypto.

The replacement argument is deductive, and stronger than a log line would have been: the key
is `local: true` (anchored by the logged `CN_GENERATE_KEY_PAIR`, so it was generated in the
HSM), it is `never-extractable: true` (so it has never left), and a signature verifies against
its public key. A signature that verifies, produced by a key that was born inside the HSM and
cannot leave it, **must** have been computed inside the HSM. There is no other possibility.

This matters for homologação: if BCB asks for per-transaction cryptographic audit evidence,
**the HSM log will not supply it**, and the answer must be the deduction above plus
application-level audit records.

---

## 5. High availability: the finding that changes sizing

A two-HSM cluster across two Availability Zones was built, one HSM was deleted while signing
continuously, and the HSM was then restored. The result contradicts the intuitive reading of
"two HSMs in two AZs = highly available".

| Condition | Signing | Latency |
|---|---|---|
| 2 HSMs, key-availability check enabled | **Succeeds** | 0.39–0.52 s (MEASURED) |
| 1 HSM (one deleted), check enabled | **Fails entirely** | 87.2 s before erroring (MEASURED ×3, spread 0.04 s) |
| 2nd HSM restored | **Recovers automatically** | 0.45 s (MEASURED) |

The error is explicit: `Cannot perform the requested key operation as the key must be
available on at least 2 HSMs`.

**So two HSMs is not a redundant configuration — it is the minimum at which the check passes.**
Losing one drops the cluster below quorum and signing stops completely. To tolerate the loss
of one HSM while keeping the check enabled, **three HSMs are required**.

### Three configurations, and one of them is strictly dominated

An earlier draft of this document said `--disable-key-availability-check` "should not be used
in production". That was too strong and is corrected here. The flag gates **use**, not
replication: AWS documents key synchronisation in SDK 5 as "a fully automatic process", and the
quorum is a separate runtime condition checked per operation. Disabling it therefore does not
make keys less replicated — it stops the cluster refusing to use a key it already holds.

The AWS documentation matches the measurement above verbatim, including the failure text:
*"any attempt to create or use a token key will fail … The key must be available on at least 2
HSMs before being used."* Note **create or use** — the quorum is re-evaluated against current
cluster state on every operation, which is why an existing, fully replicated key stopped working
the moment the cluster dropped to one HSM.

| | HSMs | Quorum | Loses 1 HSM | Monthly, sa-east-1 |
|---|---|---|---|---|
| **A** | 3 | enabled | Signing continues (2 remain) | $5,957 |
| **B** | 2 | disabled | Signing continues (MEASURED 5/5, 0.38-0.46 s) | $3,971 |
| **C** | 2 | enabled | **Total signing outage** | $3,971 |

**C costs exactly what B costs and is strictly worse.** Whatever else is decided, the current
two-HSM-with-quorum shape should not be what runs in production.

A versus B is a real trade, not a formality:

- **A buys enforcement, not just headroom.** The cluster will refuse to use a key that is not
  replicated, so an unreplicated key cannot be used by accident. That is a safety property no
  operational rule can fully replace, and it needs no discipline from anyone.
- **B needs exactly one operational rule, and it is not the obvious one.** I first wrote that
  rule as "verify `cluster-coverage: full` after any key creation". Measurement showed that check
  **cannot fail**: a key created while the cluster was degraded to one HSM also reports
  `cluster-coverage: "full"`, because `full` means *present on every HSM currently in the
  cluster* — and there was one. Coverage is relative to current membership, not a durability
  measure. The rule that works is: **verify at least two ACTIVE HSMs before creating or importing
  a key** — count HSMs, do not read a coverage string. For this workload that is easy to keep:
  the PSP signing key and the mTLS client key are generated once at provisioning and again only
  at rotation, both planned activities. The exposure B accepts is a key created during a degraded
  window, plus AWS's stated 24-hour window between automatic backups (additional backups are
  taken on cluster lifecycle events such as adding or removing an HSM).
- **B is thinner against a double failure.** If the surviving HSM fails before a replacement has
  synchronised, recovery is from backup. For a static signing key that loses nothing; for keys
  created since the last backup it loses them.

**Availability Zone placement is part of choosing A.** sa-east-1 has three AZs
(`sa-east-1a`, `1b`, `1c`), so three HSMs can each sit in their own. Three HSMs across only two
AZs does **not** tolerate an AZ failure: losing the AZ holding two of them leaves one, which is
below quorum, and the outage is the same as configuration C.

### Cost, at list price

On-demand list prices for `hsm2m.medium`, retrieved from the AWS Pricing API (service code
`CloudHSM`), at 730 hours per month. HSM charges only — no EC2, data transfer, or backup, and no
separate non-production or homologação cluster.

| Region | 2 HSMs | 3 HSMs | Third HSM costs |
|---|---|---|---|
| sa-east-1 (São Paulo) — $2.72/HSM/hour | $3,971/mo · $47,654/yr | $5,957/mo · $71,482/yr | **+$1,986/mo · +$23,827/yr** |
| us-east-1 (N. Virginia) — $1.60/HSM/hour | $2,336/mo · $28,032/yr | $3,504/mo · $42,048/yr | +$1,168/mo · +$14,016/yr |

São Paulo is **70% more expensive per HSM** than N. Virginia, which is the practical region for
Pix given RSFN connectivity. Whether BCB *requires* the HSM to be in Brazil is not something
this work established — treat region choice as an open question to confirm, not a settled
constraint.

Use the AWS Pricing Calculator for an authoritative estimate; the figures above are list prices
for sizing a decision, not a quote.

### Configuration B has now been measured

A third cluster was built specifically to test it: two HSMs across two AZs, quorum disabled, a
PSP-attributed key generated while healthy (`cluster-coverage: full`, `never-extractable: true`,
`sign: true`), a five-signature baseline, then one HSM deleted.

```
baseline, 2 HSMs   5/5 ok   0.46-0.52 s
degraded, 1 HSM    5/5 ok   0.38-0.46 s   <- the existing key still signs
```

**Configuration B's availability claim holds**, with no configuration change and no restart. The
same deletion under configuration C failed 3/3 at 87.2 s each. A key generated *while* degraded
was also usable — and reported `cluster-coverage: "full"`, which is what invalidated the naive
safety check described above.

What remains unmeasured is configuration **A**: a three-HSM cluster losing one HSM. It follows
directly from the quorum rule (two remain, so the quorum is met) and from configuration B's
demonstration that a single surviving HSM serves signatures at full speed, but it was not run.
The 3-AZ placement requirement was not measured either; it is deduced from the same rule.

Three further operational findings:

1. **Failure is slow, not fast.** 87 seconds is a fixed internal retry budget, not network
   jitter — three measurements agreed to within 0.04 s. On a synchronous Pix request path,
   requests will pile up rather than fail fast. Client-side timeouts must be set accordingly.
2. **Recovery needs no configuration change.** MEASURED: signing recovered with the
   replacement HSM's new IP absent from the client config, so the client discovers cluster
   members by itself. Note the replacement arrives on a **new** ENI IP.
3. **Control-plane state lags the data plane.** Activation returned success while
   `describe-clusters` still read `INITIALIZED` for ~80 s, and `CreateHsm` was refused during
   that window. Deletion showed the same lag: both HSMs reported deleted while the cluster
   still listed 2 for ~120 s. Automation that trusts the immediate response will break.

### Audit coverage of cluster lifecycle is split across two sources

MEASURED: a replacement HSM's audit stream opens with `CN_RESTORE_BEGIN`,
`PARTITION_BACKUP_RESTORE_LOG`, `CERT_AUTH/RSA/KEK` — key replication into a joining HSM **is**
auditable. But **removal is not recorded as an event**: the deleted HSM's stream simply ends
with `END_MARKER_OPCODE`. From the CloudHSM log alone, a deliberate deletion and a crash are
indistinguishable — both look like a stream that stopped.

The lifecycle lives in **CloudTrail** instead (MEASURED: `DeleteHsm`, `CreateHsm`×4,
`InitializeCluster`, all attributed). CloudTrail also records **rejected** attempts, which is
useful for forensics. A PSP audit trail must therefore collect **both** sources; collecting
only the HSM log loses "who removed an HSM".

---

## 6. Deliberately not implemented

This repository covers signing, mTLS, the CloudHSM client/container, and transparent HTTP
proxying. A participant must still build, and none of the following is present or attempted:

- Payment initiation and the full DICT entry lifecycle beyond transport
- Inbound SPI asynchronous message handling
- Settlement, reconciliation, and refund state machines
- MED 2.0 / Funds Recovery, Fraud Markers, Event Notifications
- Pix Automático
- Authorization, liquidity management, and fraud decisioning
- Operational SLAs and the capacity model behind them

Absence here is a scope decision, not an oversight.

---

## 7. Open gates before production

Ordered by how much they can hurt.

1. **Cluster sizing.** Two HSMs with the quorum enabled is a total outage on one HSM failure
   and must not ship (§5). Either three HSMs with the quorum enabled (+$23,827/yr at São Paulo
   list price, and they must be in three separate AZs), or two with the quorum disabled plus the
   rule that keys are never created while degraded. The second option has not been measured.
2. **Certificate revocation checking ships disabled.** The switch exists and is tested, but a
   revoked BCB certificate would currently be accepted. Enabling it requires two facts this
   repository cannot establish: that the trust parameter holds the ICP-Brasil CA rather than a
   pinned leaf (with a pinned leaf, no revocation check can occur at all), and that CRL/OCSP
   endpoints are reachable from RSFN.
3. **Alarms are defined and tested but not deployed.** The CDK app synthesises and its
   assertions run in CI; no stack has been pushed to an account.
4. **tcnative is pinned to `linux-x86_64-fedora`.** Because `SslProvider.OPENSSL` is requested
   explicitly, a host without that native library fails at startup. TLS behaviour has only been
   exercised on that architecture.
5. **Three security-manual requirements rest on an Internet Archive retrieval.** The Manual de
   Segurança do Pix v3.7 returns 404 on the BCB site while sibling manuals return 200 (verified
   with a positive control). The TLS-version, DNS-TTL and ICP-Brasil chain requirements quoted
   in the architecture docs come from that archived copy. Re-obtain them through BCB onboarding
   rather than treating them as settled.
6. **Per-transaction HSM audit evidence is unobtainable** (§4). If homologação demands it, the
   deductive argument plus application-level audit is the answer, and that should be agreed in
   advance rather than discovered during certification.

---

## 8. How to check these claims

Every verdict above traces to a command and its output. The measurement methods, the traps
encountered, and the negative controls are recorded in `CLOUDHSM_BCB_V2_HANDOFF.md` and
`README-CloudHSM.md`. Contract assertions in CI each have a negative control that was confirmed
to fail for the right reason, and CI is verified by reading the test count rather than the
status tick — a green job with zero tests has already happened once in this repository and is
now guarded against explicitly.

All AWS resources created for this verification have been destroyed and confirmed at zero.
