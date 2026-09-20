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

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This library is licensed under the MIT-0 License. See the LICENSE file.
