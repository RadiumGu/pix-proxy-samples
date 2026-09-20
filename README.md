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

### Where things live

| Path | Purpose |
|---|---|
| `proxy/core` | Signature, TLS and content-decoding logic — **its tests run in CI** |
| `proxy/test` | Local BCB simulator and the DICT v2 transport contract tests — **its tests run in CI** |
| `proxy/cloudhsm` | The CloudHSM proxy itself. Compiles in CI, but its tests do not run there (it needs the CloudHSM JCE rpm) |
| `proxy/kms` | Historical, unsupported, kept out of CI by an explicit guard |
| `.github/scripts/check-transport-contract.sh` | Source-level gate pinning the production route's endpoint options |
| `tools/generate_architecture_diagram.py` | Regenerates the architecture diagrams from the official AWS icon set |

Only `proxy/core` and `proxy/test` execute tests. `proxy/cloudhsm` and the simulator build with
`-DskipTests`, so a test added to them would silently never run — after adding one, confirm the test
**count** in the CI run rather than trusting a green tick.

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This library is licensed under the MIT-0 License. See the LICENSE file.
