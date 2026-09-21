# CloudHSM teaching architecture for secure Pix message transport

[![build](https://github.com/RadiumGu/pix-proxy-samples/actions/workflows/build.yml/badge.svg)](https://github.com/RadiumGu/pix-proxy-samples/actions/workflows/build.yml)
[![License: MIT-0](https://img.shields.io/badge/License-MIT--0-blue.svg)](LICENSE)

**[中文版本 / Chinese version](README.zh-CN.md)**

> ## ⚠️ Maintained scope in this fork
>
> This fork maintains **only the AWS CloudHSM path**: XML digital signatures, mTLS, CloudHSM client/container integration, and transparent HTTP proxying. It is a teaching skeleton for the transport and cryptography layer of a Pix integration — **not a complete Pix PSP implementation**.
>
> Explicitly out of scope: payment initiation, inbound SPI messages, settlement/reconciliation, MED 2.0 / Funds Recovery, Fraud Markers, Event Notifications, Pix Automático, refund business workflows, authorization, liquidity, fraud decisions, and operational SLAs. Those are separate PSP domain services governed by current BCB rules.
>
> **The `proxy/kms` architecture is historical and unsupported in this fork.** It remains in the tree for reference only. See [`README-CloudHSM.md`](README-CloudHSM.md) for the maintained BCB DICT v2 boundary and [`VERIFICATION.md`](VERIFICATION.md) for tested fixes and remaining limitations.

### ***You can clone, change, execute it, but *it should not be used as a basis for building the final integration* of the Financial Institution with PIX (SPI and DICT).***

This project contains source code and supporting files to exemplify digital signature and secure message transmission to the Brazilian Instant Payment System (PIX). The maintained CloudHSM architecture represents a **proxy** for communication with Brazilian Central Bank (BACEN). The idea of the proxy is to use **AWS CloudHSM** as a direct and mandatory path for every transaction, with the following objectives:

```bash
- Establish the TLS tunnel with mutual authentication (mTLS).
- Signature of XML messages.
- Sending the request log to the datastream.
```

AWS CloudHSM — **maintained teaching path** | AWS KMS — **historical / unsupported** |
:-:|:-:|
<img src="/images/hsm.jpg" width="100" height="100">|<img src="/images/kms.jpg" width="100" height="100">|
[CloudHSM scope and deployment](README-CloudHSM.md)|[Historical reference only](README-KMS.md)|

## Which document do I read?

This repository has several markdown files that grew up at different times and are easy to confuse.
Here is the whole set and what each one is *for*, so you do not have to open all of them to find out.

| Document | What it is | Read it when |
|---|---|---|
| **`README.md`** (this file) | Entry point and scope statement: what this fork maintains and what it deliberately does not. | Start here. |
| [`README-CloudHSM.md`](README-CloudHSM.md) | **The maintained path.** Architecture walkthrough, the BCB / TLS / JDK / CloudHSM version requirements, full AWS deployment steps, and how to run every check. | You are deploying, or you need to know what BCB requires on the wire and which versions satisfy it. |
| [`README-KMS.md`](README-KMS.md) | **Historical / unsupported.** The older AWS KMS variant, kept for reference only. | Only for historical context. It is not maintained, not in CI, and must not be used as a baseline. |
| [`PIX_CLOUDHSM_ASSESSMENT.md`](PIX_CLOUDHSM_ASSESSMENT.md) | Verification assessment for a decision-maker: what was measured on real CloudHSM hardware, the recommended transport path and why the alternatives were eliminated, the two-HSM availability finding, and the open gates before production. | You are deciding whether and how to adopt this approach, rather than implementing it. |
| [`VERIFICATION.md`](VERIFICATION.md) | Independent verification record: which defects were reproduced, which were fixed, what each fix was tested with, and what remains a limitation. | You want the evidence for a claim this repository makes, rather than the claim itself. |
| [`CLOUDHSM_BCB_V2_HANDOFF.md`](CLOUDHSM_BCB_V2_HANDOFF.md) · [中文](CLOUDHSM_BCB_V2_HANDOFF.zh-CN.md) | Engineering handoff: the external BCB baseline, the evidence table for the transport contract, required next work, and the **homologação gates that are explicitly NOT solved**. | You are picking this work up, or you need the honest list of what is unproven. |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Contribution and security-reporting process. | Reporting an issue or opening a PR. |
| [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) | The project's code of conduct. | Participating in the project. |

**If you read only one thing beyond this page,** read section 7 of
[`CLOUDHSM_BCB_V2_HANDOFF.md`](CLOUDHSM_BCB_V2_HANDOFF.md). It lists what this repository has *not*
demonstrated — including that the CloudHSM path **cannot be deployed as written**, because the code
targets CloudHSM Client SDK 3 while `hsm1.medium` reached end of support on 2026-03-31 and the only
creatable instance type requires SDK 5.9.0+, which in turn requires JDK 17+. Passing the test suite
is **not** evidence of BCB homologação.

## How it works, and why "transparent" is the whole design

A Pix request from the institution's application does not go to BCB directly. It goes to this
proxy, which is the mandatory path for every transaction. What the proxy does to it, in order —
this is the actual production route:

```
  institution's application
        │  plain HTTP, inside the VPC
        ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │ 1. onCompletion audit hook registered   ← runs even if the      │
  │                                            BCB leg fails        │
  │ 2. reject a compressed request body (415)                       │
  │ 3. convert the body to a string                                 │
  │ 4. SIGN the XML  ── private key stays inside CloudHSM ──────────┼──▶ CloudHSM
  │ 5. capture the request for the audit record                     │
  │ 6. send to BCB  ── mTLS, client key also inside CloudHSM ───────┼──▶ BCB / RSFN
  │ 7. decode the response (gzip/deflate) then convert to string    │
  │ 8. VERIFY BCB's signature on the response                       │
  └─────────────────────────────────────────────────────────────────┘
        │
        ▼  audit record → Firehose (async, with an fsync'd on-disk fallback)
```

**The private key never leaves the HSM.** The proxy holds a *handle*, not key material — measured:
the JCE object is a `CloudHsmRsaPrivateCrtKey` whose `getEncoded()` returns `null`. Signing happens
inside the FIPS boundary; the proxy only asks for it.

<p align="center">
  <img src="/images/proxy-cloudhsm-arch.png" width="620" alt="CloudHSM proxy architecture: the application reaches BCB only through the proxy, which signs with a key held in a CloudHSM cluster and streams audit records to Firehose">
</p>

**Transparency is a correctness requirement, not a nicety.** An XML signature covers the document,
so *any* mutation of the message between signing and BCB invalidates it — and a mutation on the
inbound side means signing the wrong bytes. This is where most of the defects found in this
repository came from, and it is why a source-level CI gate pins the route's endpoint options rather
than trusting them to stay put. Things that must survive the proxy untouched, each with a test:

- the request path, the query string, and **repeated** query parameters
- headers BCB sets, including `Cache-Control` — a stock header filter silently dropped it, which
  for `getEntry` bounds how stale a key-ownership answer may be, and a stale one means paying the
  wrong account
- the body, byte for byte — a compressed request body destroyed by string conversion would be
  signed as wreckage, so it is refused with 415 instead
- `ETag`, and the response status including `410` and the anti-scanning behaviour

Two measured corrections worth knowing if you touch this code: `bridgeEndpoint=true` does **not**
by itself preserve path and query, and clearing `HTTP_QUERY` alone does not drop the query because
`HTTP_RAW_QUERY` is a second source.

## Versions: what is pinned, and the one constraint that blocks deployment

Read from `proxy/pom.xml` and `.github/workflows/build.yml`, not from prose — these are the values
the build actually uses.

| Component | Pinned | Why it is pinned there |
|---|---|---|
| Java | **11** (`temurin` in CI) | What CloudHSM Client SDK 3 supports |
| Quarkus | 1.7.0.Final | The generation `camel-quarkus` 1.0.0 targets |
| Camel Quarkus | 1.0.0 | Brings `camel-netty-http`, which speaks HTTP/1.1 as BCB requires |
| Netty | **4.1.138.Final** | Earlier pins carried request-smuggling advisories (CWE-444); 4.1.118 still had CVE-2025-58056 |
| netty-tcnative | 2.0.84.Final, `linux-x86_64-fedora` | Paired with that Netty; **x86_64 only**, so another architecture fails at startup |
| Jackson | 2.15.4 | Security floor; both BOMs are imported **before** `quarkus-bom` so they win |
| CloudHSM SDK 3 | **3.4.4-1** rpm, SHA-256 verified | The version this code targets |
| Node (alarms app only) | 22 | For the CDK alarm app, outside the Maven build |

**The blocking constraint.** The code targets CloudHSM Client **SDK 3**, but `hsm1.medium` reached
end of support on **2026-03-31**, and the only creatable instance type needs **SDK 5.9.0+**, which
in turn needs **JDK 17+**. So this cannot be deployed as written. The good news, measured on real
hardware: `XmlSigner` runs **unmodified** on SDK 5 because JSR-105 routes the `Signature` operation
to the CloudHSM provider, so the migration is confined entirely to the TLS half — Netty wants key
*bytes* and an HSM only ever gives you a *handle*. The recommended way out is in
[`PIX_CLOUDHSM_ASSESSMENT.md`](PIX_CLOUDHSM_ASSESSMENT.md) section 3.

## The audit path, and the four signals that mean records are at risk

A signed request that reached BCB with no record of it is the worst outcome this proxy can produce,
so the audit path is built to fail loudly rather than quietly.

The write is registered as an `onCompletion` hook, which runs on **success and failure alike**. That
placement matters: a transport or TLS failure on the BCB leg aborts the exchange, so a final
processing step would simply never execute — and the request that *was* signed and *was* sent would
leave no trace. Note `throwExceptionOnFailure(false)` does not cover this; it suppresses HTTP error
statuses, while these failures happen below HTTP.

Delivery is asynchronous to keep Firehose off the caller's latency path, with a bounded queue and a
durable fallback. The fallback is opened with `DSYNC`, because a "durable" spool that returns once
the bytes are in the page cache is no fallback at all for the crash it exists to survive.

Four stable log tokens mark every way a record can be lost, and the CDK app in `alarms/` turns each
into a CloudWatch alarm:

| Token | Meaning |
|---|---|
| `PIX_AUDIT_SPOOLED` | Delivery failed and the record went to the on-disk fallback. Recoverable, but ship and truncate the spool. |
| `PIX_AUDIT_QUEUE_FULL` | The async queue was full. Overflow spilled to the spool rather than blocking the caller. |
| `PIX_AUDIT_NO_RECORD` | An exchange produced no audit record at all. |
| `PIX_AUDIT_SPOOL_WRITE_FAILED` | **Audit data lost** — neither delivered nor persisted. Any non-zero value is a real loss. |

The alarms also watch Firehose itself with `treatMissingData: BREACHING`, deliberately: "no data" on
a stream that should always be carrying audit records *is* the outage — the container died, delivery
stopped, or the stream was deleted. The metric-filter alarms take the opposite setting for the
opposite reason, since those metrics only produce data points when something is already wrong.

**The alarms are defined, tested and synthesisable, but not deployed.** No stack has been pushed to
an account. That is an explicit gap, not an implied capability.

## Before you size the cluster: two HSMs is not a redundant configuration

This is on the front page because it is an early architecture and cost decision, it is
counter-intuitive, and getting it wrong produces a total outage rather than a degradation.

CloudHSM Client SDK 5 enforces a **key availability quorum**: a key must exist on **at least two
HSMs** before it may be used, and the check is re-evaluated on every operation. So a two-HSM
cluster is not redundant — it is the *minimum* at which the check passes. Measured on real
hardware:

| | HSMs | Quorum | One HSM lost | Measured |
|---|---|---|---|---|
| **A** | 3 | enabled (default) | Signing continues — two remain | follows from the quorum rule |
| **B** | 2 | disabled | **Signing continues** at full speed | 5/5 signatures, 0.38–0.46 s |
| **C** | 2 | enabled (default) | Fresh-process clients fail; a running session keeps signing until it restarts | 3/3 CLI failures at 87.2 s; 279/279 JVM signatures OK |

> **MEASURED: a long-lived JVM session is NOT interrupted, and this falsifies the row above for
> production.** A purpose-built 2-HSM cluster, quorum at its **default (enabled)**, key generated
> while healthy (`cluster-coverage: full`, `never-extractable: true`). A JVM installed the CloudHSM
> JCE provider once, loaded the keystore once, and resolved the `PrivateKey` handle once — then
> signed in a loop. One HSM was deleted mid-run. Result: **279 signatures, 0 failures**, continuing
> for **475 seconds** after the deletion, at **1.9–2.2 ms** each throughout.
>
> So the quorum is enforced when a client **establishes** its session and when keys are created or
> listed — not on every private-key operation against an already-resolved handle. AWS's
> troubleshooting page points the same way: it lists key generation, `key list`, and *"a new
> instance of the SDK was started"* as the triggers, and notes that *"OpenSSL frequently forks new
> instances of the SDK"*.
>
> **Why the earlier measurement said otherwise.** Every configuration-C failure was measured with
> `cloudhsm-cli`, which starts a **fresh process per invocation** — so each attempt was a new SDK
> instance, which is itself a trigger. The 87.2 s failures are real and they are what a
> fresh-process client sees; they are **not** what a long-lived proxy sees.
>
> **What this changes, and what it does not.** Configuration C is not the instant total outage
> stated above: a running proxy keeps signing on one surviving HSM. It is still the configuration
> to avoid, for reasons that remain measured — a **restart** during the degraded window cannot
> re-establish a session, so any deploy, crash, scale-out or container replacement turns the
> degradation into an outage, and key creation and rotation fail throughout. Sizing for three HSMs
> is still AWS's own documented recommendation. The difference is that the failure is
> **restart-triggered rather than immediate**, which changes the incident shape from "Pix stops
> now" to "Pix stops at the next restart" — and the second is easy to mistake for having survived
> the failure.

**Configuration C costs exactly what B costs and is strictly worse.** It is also the shape you get
by following the obvious path, so it is the one to avoid. The failure is slow rather than fast —
87 seconds before the error surfaces — so requests pile up instead of failing quickly, and
client-side timeouts must be set accordingly whichever configuration you pick.

Two traps worth knowing before reading further:

- **`cluster-coverage: "full"` is not a durability measure.** It means *present on every HSM
  currently in the cluster*, so a key that exists on a single HSM also reports `full`. To check
  whether a key is safely replicated, **count ACTIVE HSMs** — do not read that string.
- **Two different things are called "quorum".** The key availability quorum counts *HSMs*;
  quorum authentication (M of N) counts *people*. They are unrelated and appear in the same
  command output.

Full detail, including the join/synchronisation mechanism, AZ placement, audit coverage and the
measured operational sequencing traps, is in
[Cluster high availability](README-CloudHSM.md#cluster-high-availability-how-to-size-it-and-the-setting-that-decides-everything)
and [Two different things are called "quorum"](README-CloudHSM.md#two-different-things-are-called-quorum-and-conflating-them-is-a-real-hazard).
The cost comparison for two versus three HSMs is in
[`PIX_CLOUDHSM_ASSESSMENT.md`](PIX_CLOUDHSM_ASSESSMENT.md).

### Where things live

| Path | Purpose |
|---|---|
| `proxy/core` | Signature, TLS and content-decoding logic — **its tests run in CI** |
| `proxy/test` | Local BCB simulator and the DICT v2 transport contract tests — **its tests run in CI** |
| `proxy/cloudhsm` | The CloudHSM proxy itself. Compiles in CI, but its tests do not run there (it needs the CloudHSM JCE rpm) |
| `proxy/kms` | Historical, unsupported, kept out of CI by an explicit guard |
| `alarms/` | CDK app for the audit alarms. Deliberately **outside** the Maven reactor so the Java build gains no Node dependency; its `cdk synth` and template assertions are a gating CI job |
| `.github/scripts/check-transport-contract.sh` | Source-level gate pinning the production route's endpoint options |
| `tools/generate_architecture_diagram.py` | Regenerates the architecture diagrams from the official AWS icon set |

Only `proxy/core` and `proxy/test` execute tests. `proxy/cloudhsm` and the simulator build with
`-DskipTests`, so a test added to them would silently never run — after adding one, confirm the test
**count** in the CI run rather than trusting a green tick.

## Trying it locally

Nothing here needs an AWS account or an HSM — the signature logic and the whole DICT v2 transport
contract run against a local BCB simulator and committed certificate fixtures.

```bash
export JAVA_HOME=/path/to/jdk11          # Java 11; see the version table above

# Everything that executes: signature logic + the transparent-proxy contract
mvn -f proxy/pom.xml clean test

# The source-level gate that pins the production route's endpoint options
bash .github/scripts/check-transport-contract.sh

# The CDK alarm app (Node 22, outside the Maven build)
cd alarms && npm ci && npx jest && npx cdk synth
```

The Maven reactor root is `proxy/pom.xml`, so module builds are
`mvn -f proxy/pom.xml -pl <module>`. Building `proxy/cloudhsm` needs the CloudHSM JCE rpm installed
locally; CI compiles it without running its tests.

**Read the test count, not the green tick.** Only `proxy/core` and `proxy/test` execute tests — the
other modules build with `-DskipTests`, so a test added to them silently never runs. This has
already bitten: a `.gitignore` pattern once excluded a jest config, CI fell back to a transform that
could not parse TypeScript, and the job reported `Tests: 0 total` while passing locally. The alarms
job now asserts the assertion *count* for exactly that reason.

CI runs **8 jobs**: the two that execute tests (`core`, `dict-v2-contract`), two compile-only builds
(`simulator`, `cloudhsm`), a shellcheck of the container entrypoint, the transport-contract and KMS
scope gate, the audit-schema check, and the gating CDK alarms job.

**Every contract assertion here has a negative control** — the guarded thing is removed and the
check is confirmed to fail *for the right reason*. That habit exists because three gates in this
repository once passed while the thing they guarded was gone: `grep 'Foo'` happily matched a renamed
`FooGone`. Assert on *usage*, not on a name fragment.

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This library is licensed under the MIT-0 License. See the LICENSE file.
