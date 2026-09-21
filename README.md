# CloudHSM teaching architecture for secure Pix message transport

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
| [`CLOUDHSM_BCB_V2_HANDOFF.md`](CLOUDHSM_BCB_V2_HANDOFF.md) | Engineering handoff: the external BCB baseline, the evidence table for the transport contract, required next work, and the **homologação gates that are explicitly NOT solved**. | You are picking this work up, or you need the honest list of what is unproven. |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Contribution and security-reporting process. | Reporting an issue or opening a PR. |
| [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) | The project's code of conduct. | Participating in the project. |

**If you read only one thing beyond this page,** read section 7 of
[`CLOUDHSM_BCB_V2_HANDOFF.md`](CLOUDHSM_BCB_V2_HANDOFF.md). It lists what this repository has *not*
demonstrated — including that the CloudHSM path **cannot be deployed as written**, because the code
targets CloudHSM Client SDK 3 while `hsm1.medium` reached end of support on 2026-03-31 and the only
creatable instance type requires SDK 5.9.0+, which in turn requires JDK 17+. Passing the test suite
is **not** evidence of BCB homologação.

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
| **C** | 2 | enabled (default) | **Total signing outage** | 3/3 failures, 87.2 s each |

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

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This library is licensed under the MIT-0 License. See the LICENSE file.
