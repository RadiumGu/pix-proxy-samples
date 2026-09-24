# VERIFICATION — English rendering

> ## Read this first — what this document is, and who it is for
>
> **This is an audit trail, not user documentation.** It records how each claim this fork makes was
> tested: what was reproduced, what was measured, what turned out to be wrong, and what was left
> unfixed. It is written for someone **checking this work**, not for someone using the code.
>
> **If you just want to use or deploy this project, you are in the wrong file.** Go to
> [`README-CloudHSM.md`](README-CloudHSM.md) — it has the architecture, the BCB / TLS / JDK /
> CloudHSM version requirements, the deployment steps, and how to run the checks. For the list of
> things that are still unproven, see section 7 of
> [`CLOUDHSM_BCB_V2_HANDOFF.md`](CLOUDHSM_BCB_V2_HANDOFF.md).
>
> **Two warnings that apply however you use this repository:**
> 1. The **CloudHSM path cannot be deployed as written** — the code targets Client SDK 3, while
>    `hsm1.medium` reached end of support on 2026-03-31 and the only creatable instance type needs
>    SDK 5.9.0+, which needs JDK 17+. See the ⚠️ section below.
> 2. **Passing the test suite is not evidence of BCB homologação.** The local simulator is not BCB.
>
> **Language note — read this before you cite anything from this file.** This is the **English
> rendering**. The **authoritative original is [`VERIFICATION.md`](VERIFICATION.md)**, whose body is
> Chinese because it was written as a working record, and that is the copy to correct if you find an
> error here. The two are held together mechanically, not by good intentions: CI runs
> `.github/scripts/check-doc-parity.py`, which fails the build if the headings, the code
> blocks, or any measured number diverge between them — because an audit trail that exists in two
> copies is an audit trail that can quietly disagree with itself.
>
> The rest of the repository's documentation is in English; the front page also has a Chinese
> version at [`README.zh-CN.md`](README.zh-CN.md).


This document is for **the person receiving this code**: how to independently verify which fixes this fork made relative to the official AWS sample, and **that those fixes actually work**.

- **Upstream repository**: https://github.com/aws-samples/pix-proxy-samples
- **This fork's baseline commit**: `fa20042d19d4c0b78f6898f3053b4cd0729938d0` (2024-10-17, = tip of upstream `master`)
- **Corresponding upstream issues**: [#15](https://github.com/aws-samples/pix-proxy-samples/issues/15) · [#16](https://github.com/aws-samples/pix-proxy-samples/issues/16) · [#17](https://github.com/aws-samples/pix-proxy-samples/issues/17) · [#18](https://github.com/aws-samples/pix-proxy-samples/issues/18) · [#19](https://github.com/aws-samples/pix-proxy-samples/issues/19)

---

## 0. CI measured results (2026-09-19, for direct citation)

**Current status (2026-09-20): all 7 jobs of the CloudHSM-only CI on `master` are green.** The workflow has been changed to maintain only the CloudHSM path — `kms` no longer participates in CI (kept for history, unsupported). The `kms + simulator - compile` job in the table below **no longer exists**; the record is retained below only to explain the origin story of that red mark. **Do not read it as the current state.**

The current 7 jobs: `core` · `CloudHSM simulator - compile` · `DICT v2 transparent-proxy contract test` · `cloudhsm - compile` (best effort) · `wrapper_script.sh - shellcheck` · `transport contract - production route options + KMS scope guard` · `audit schema`.

The BCB DICT v2 transport contract test and gate added on 2026-09-20, together with the **unresolved homologação gate checklist**, are in [`CLOUDHSM_BCB_V2_HANDOFF.md`](CLOUDHSM_BCB_V2_HANDOFF.md) sections 3.1 and 7. The key point, restated because it governs how this document is to be cited: **passing the local simulator ≠ passing BCB homologação.**

**2026-09-20 update — two statements originally in this section have been overturned by evidence; do not cite the old versions anymore:**

- **TLS and cipher are no longer "unverified".** The *Manual de Segurança do Pix* **v3.7** has been obtained; §2 requires "TLS versão **1.2 ou superior**" and a minimum suite of **ECDHE-RSA-AES-128-GCM-SHA256 (0xc02f)**. Both routes have been changed to `enabledProtocols("TLSv1.2,TLSv1.3")`, with `TlsProtocolNegotiationTest` covering it. See HANDOFF **§7.2**. **What is still unverified**: the handshake against BCB's real endpoint (`dict.pi.rsfn.net.br` has no public A record and cannot be probed), BCB's ICP-Brasil v10 certificate chain, and the **missing hostname validation** — the latter has been reclassified as a real defect rather than an unknown.
- **The "must be extractable" mechanism for the mTLS private key has been measured.** It is no longer an inference: Netty's `PemPrivateKey.toPEM` calls `getEncoded()`, throws `does not support encoding` when it is null, and its only caller is the OPENSSL path. `MtlsNonExtractableKeyTest` fixes this fact in CI; HANDOFF **§7.1** gives four remediation paths and their ordering.
- Still **unverified** and do not guess: SPI `MsgDefIdr` and XSD version (HANDOFF §7.3).

### History: first all-green and the kms red mark (2026-09-19)

Status at that time: [run 35458232223](https://github.com/RadiumGu/pix-proxy-samples/actions/runs/35458232223) (commit `50e61bc`) had all 5 jobs green; since fixing the only long-standing failing `kms + simulator` job at `13349dc` ([first all-green run 35456018649](https://github.com/RadiumGu/pix-proxy-samples/actions/runs/35456018649)), 9 consecutive pushes were 5/5 success. That job was subsequently moved out of the maintained CI along with KMS.

The test count for `proxy/core` rose from **5 to 14** in the independent re-review of 2026-09-19 (`XmlSignerSecureValidationTest` 4, `XmlSignerExpiredCertificateTest` 2, `WellKnownTestCertificatesTest` 3, see section 7), and rose again to **16** on 2026-09-20 (`XmlSignerNotYetValidCertificateTest` 2, covering the previously reachable but untested certificate-rotation branch). There are also 28 tests in `proxy/test` (**out of date**: currently core 38 + proxy/test 38, see the update note below) (11 transparent-proxy contract + 17 simulator v2 policy), executed by the newly added `dict-v2-contract` job. All 7 reactor modules compile.

The table below is the very first [Actions run](https://github.com/RadiumGu/pix-proxy-samples/actions/runs/35454878877) (triggered by pushing `fixes/p0-production-hardening`), retained because it records the original scene of the `kms` red mark:

| Job | Result | Note |
|---|---|---|
| **`core - build + test`** (signing logic) | ✅ **success** | Fixes for defects 1 and 5 plus `XmlSignerCaIssuedCertificateTest` compile and all pass |
| `wrapper_script.sh - shellcheck` | ✅ success | Script changes for defect 3 |
| `audit schema` (field/column alignment gate) | ✅ success | Defect 2 |
| `cloudhsm - compile` | ✅ success | Modules touched by defects 3 and 4 |
| `kms + simulator - compile` | ❌ **failure** (that run) | ⚠️ **unrelated to this change**; **fixed on 2026-09-19**, see below |

**The `kms + simulator - compile` failure is unrelated to this fix**: the error is
```
Could not find artifact software.amazon.awssdk:kms-jce-provider:jar:1.0.0 in central
```
This coordinate lives in **`proxy/kms/pom.xml`** (a file not touched by this change) and is a pre-existing upstream problem — `kms-jce-provider` comes from the `aws-samples/aws-kms-jce` project and was never published to Maven Central. Upstream itself never ran CI over this module (see section 6), so the defect went undiscovered until now. **It is independent of items 1–5 and out of scope for this fork's fixes.**

**There is only one fix path: local install.** What this document previously wrote — "point at the actual published coordinate of `aws-kms-jce`" — was wrong: **no such coordinate exists** (measured, see below). The coordinate that `aws-kms-jce` builds from source is exactly what `proxy/kms/pom.xml` needs, so a single local `mvn install` suffices:
```bash
git clone https://github.com/aws-samples/aws-kms-jce.git
mvn -f aws-kms-jce/pom.xml install -DskipTests     # produces software.amazon.awssdk:kms-jce-provider:1.0.0
mvn -B -f proxy/pom.xml -pl core,kms,test package -DskipTests   # passes now
```

Measured (2026-09-19):
- Querying `search.maven.org` for `a:kms-jce-provider` → `numFound: 0` (**no** version, no groupId at all); directly probing `repo.maven.apache.org` for the three paths `1.0.0` / `1.0.1` / `1.1.0` all returned **HTTP 404**; `aws-samples/aws-kms-jce` has a GitHub **release count of 0**.
- After a local `mvn install`, re-running the third command above: `AWS PIX Core` / `Pix KMS Proxy Sync` / `PIX Proxy Test` **all three modules SUCCESS**. That is, this red mark is **purely an artifact-resolution problem; the source itself compiles**.

**CI has been fixed accordingly (2026-09-19)**: the `kms-and-simulator` job in `.github/workflows/build.yml` gained a step that first builds and installs `kms-jce-provider` from source (pinned to commit `6f7f179`, not following the branch, to guarantee reproducibility), then compiles `core,kms,test`. The former red mark was **permanent** and carried no information about source quality; now this job either really proves those two modules compile, or goes red for a meaningful reason. **Measured to be in effect**: in [run 35456018649](https://github.com/RadiumGu/pix-proxy-samples/actions/runs/35456018649) that job is **success** and all 5 jobs are green.

## ⚠️ Read this first: what this code is, and is not

**The upstream disclaimer still applies in full and was not invalidated by these fixes:**

> *"You can clone, change, execute it, but **it should not be used as a basis for building the final integration** of the Financial Institution with PIX (SPI and DICT)."*

This fork fixes **5 specific defects**; it does not turn the sample into a production system. The major gaps that remain are in [section 5](#5-what-this-fork-did-not-fix-required-reading) — including that CloudHSM Client SDK 3 is a previous generation, HSM session invalidation has no reconnect, the mTLS private key must be extractable, there is no XSD validation, the audit log contains personal data with no LGPD handling, and so on.

### ⚠️ The CloudHSM path can no longer be deployed (found in the 2026-09-19 re-review, not previously mentioned in this document)

**Follow `README-CloudHSM.md` and you will get stuck at step one — creating the cluster.** This is not a code defect; the external timeline has moved past it. Three facts stack up to close this path:

| Fact | Source |
|---|---|
| **Cannot create a `hsm1.medium` cluster since April 2025** | [AWS deprecation notice](https://docs.aws.amazon.com/cloudhsm/latest/userguide/compliance-dep-notif.html) |
| `hsm1.medium` **reached end of support on 2026-03-31** (that date has passed) | same |
| The only creatable type `hsm2m.medium` **requires Client SDK 5.9.0+** | [HSM types page](https://docs.aws.amazon.com/cloudhsm/latest/userguide/hsm-types.html) |

And this repository's code is **hard-bound to SDK 3** (`com.cavium.cfm2.LoginManager`, `PARTITION_1`, `key_mgmt_util`, `cloudhsm-client-jce-latest.el7.x86_64.rpm`). So it is not "runs but on an old SDK", it is: **the old instance type can no longer be created, and this code cannot connect to the new one.** AWS's migration guidance also states plainly that migrating to hsm2m "requires upgrading to the latest client SDK".

To actually get the CloudHSM half running requires **rewriting SDK 3 into SDK 5** (a completely different API), and at the same time raising the JDK to 17/21 (SDK 5's JCE supports only OpenJDK 17/21/25) — and section 4.1 has already measured that under JDK 17 this project **fails at runtime**, unless those two `--add-exports` are added. These three things are one package and cannot be done separately.

**The KMS half is unaffected** (`README-KMS.md`, no CloudHSM dependency), at the cost of the signing key living in KMS rather than in an institution-exclusive HSM partition. If you only want to see how this sample runs, take the KMS path.

**When you hand this code to anyone, deliver it together with section 5 of this document.**

---

## 1. What was fixed (5 items)

| # | Defect | Change location | Severity |
|---|---|---|---|
| **1** | `KeyInfo` used the certificate's **Subject DN** as the **Issuer DN**. With self-signed certificates the two are identical so it is invisible; with a real CA-issued certificate the issuer information sent to BACEN is wrong, and **the sample's own signature verification also fails** | `proxy/core/.../xml/XmlSigner.java` | 🔴 Will cause problems when integrating with BACEN |
| **2** | The audit field `request_query` was silently dropped (`AuditLog` emits 10 keys, the README's Glue table declares only 9 columns, and Firehose discards unknown fields without error). The KMS version is worse — **it does not collect the query string at all** | Two READMEs + `proxy/kms/.../service/Logger.java` | 🔴 Audit gap |
| **3** | The startup script configured only `Hsms[0]`, making multi-AZ HA nominal; the readiness wait had no timeout and could hang forever; `java` not being PID 1 could break graceful shutdown | `proxy/cloudhsm/.../docker/wrapper_script.sh` | 🔴 Availability |
| **4** | The audit write was the routing's **last step and synchronous**; a Firehose failure would make a transaction that "BACEN already accepted" appear as a failure to the caller | `LogRequestResponseProcessor.java` + KMS `Logger.java` | 🔴 Reconciliation risk |
| **5** | When a BACEN certificate expired, the exception was swallowed into "signature verification failed", turning **every transaction into a 500** that looked like an attack; also fixed the conditional that made the signature-verification diagnostic log fail to print reference detail in exactly the most common failure case | `proxy/core/.../xml/XmlSigner.java` | 🔴 Full outage and hard to locate |

**Incidental fix**: the RSA public exponent in `README-CloudHSM.md`, `-e 65541` → **`-e 65537`**. 65541 = 3 × 21847 is composite and diverges from every mainstream implementation. An external researcher submitted [PR #4](https://github.com/aws-samples/pix-proxy-samples/pull/4) to fix this back in 2021; after hanging for 3 years and 2 months it was closed unmerged with zero comments on 2024-10-17, so the upstream README still reads 65541.

> ⚠️ **The public exponent is an intrinsic property of the key and cannot be changed after the fact.** If you have already generated a key with `-e 65541`, you must regenerate it.

**Also added** (not in upstream):
- `proxy/core/src/test/java/.../XmlSignerCaIssuedCertificateTest.java` — the regression test that proves defect 1
- `proxy/core/src/test/resources/security/ca-signed-chain.p12` — a two-level certificate chain test fixture (Subject ≠ Issuer)
- `.github/workflows/build.yml` — CI (upstream has no CI at all)

---

## 2. Three tiers of verification, from least to most effort

### Tier 0 — zero install, 1 minute

Look at CI status and the diff:

```bash
git clone https://github.com/RadiumGu/pix-proxy-samples.git
cd pix-proxy-samples
git remote add upstream https://github.com/aws-samples/pix-proxy-samples.git
git fetch upstream

# What this fork changed relative to upstream, at a glance
git diff --stat upstream/master
# Line by line
git diff upstream/master
```

Directly on GitHub: the repository home page's **Actions** tab should have a green `build` run; if the `core` job is green, **the signing logic compiles and the regression tests are all green**.

### Tier 1 — local build verification, about 5 minutes ★ recommended ★

**Prerequisites**: JDK **11** (the project `pom.xml` sets `java.version=11`, and `proxy/core` depends on `--add-exports` for two `java.xml.crypto` internal packages; using a different major JDK is not verifying what this project actually ships) + Maven.

```bash
mvn -f proxy/pom.xml -pl core test
```

Expected: **all pass**, including
- `Iso20022XmlSignerTest` (upstream, pre-existing)
- `XmlSignerCaIssuedCertificateTest` (added by this fork, 3 test methods)

### ★★ The key step of Tier 1: prove the test is not an empty test ★★

**A test that merely passes may test nothing. To verify the fix is real, revert the fix and see whether the test goes red.**

```bash
# Manually revert defect 1 back to the upstream wrong form
sed -i.bak 's/getIssuerX500Principal()/getSubjectX500Principal()/' \
    proxy/core/src/main/java/com/amazon/aws/pix/core/xml/XmlSigner.java

mvn -f proxy/pom.xml -pl core test
```

**Expected: `XmlSignerCaIssuedCertificateTest` fails**, and the failure message should point out

- `keyInfoMustCarryIssuerDnNotSubjectDn` — the emitted `<ds:X509IssuerName>` is the leaf certificate's Subject DN, not the Issuer DN;
- `signerMustVerifyItsOwnSignatureWithACaIssuedCertificate` — the signer **cannot even verify the signature it produced**. This is because `X509IssuerSerialKeySelector` looks up the certificate in the trust store by "issuer DN + serial number", and the wrong issuer DN matches no certificate.

Meanwhile `Iso20022XmlSignerTest` (upstream, pre-existing, using a self-signed certificate) **still passes** — which is exactly why this bug could stay invisible upstream for so long.

Revert back:
```bash
git checkout -- proxy/core/src/main/java/com/amazon/aws/pix/core/xml/XmlSigner.java
rm -f proxy/core/src/main/java/com/amazon/aws/pix/core/xml/XmlSigner.java.bak
mvn -f proxy/pom.xml -pl core test   # should be all green again
```

> ⚠️ **Do not restore with `mv …XmlSigner.java.bak XmlSigner.java`** (this document previously gave exactly that command; it has been corrected). The `.bak` produced by `sed -i.bak` preserves the **original file's mtime**; moving it back yields a source file **older** than `target/classes/…/XmlSigner.class`. The `maven-compiler-plugin`'s incremental check therefore decides no recompilation is needed, so this step still tests the **mutated** class and `XmlSignerCaIssuedCertificateTest` goes red again — a **false red** caused purely by the restore method.
>
> Measured evidence (2026-09-19, local JDK 11.0.32 / Maven 3.9.9): after `mv` restore, `git status` is clean and line 218 of the source is indeed `getIssuerX500Principal()`, but
> ```
> javap -p -c target/classes/com/amazon/aws/pix/core/xml/XmlSigner.class | grep -oE 'get(Subject|Issuer)X500Principal'
> →  getSubjectX500Principal      # the class is still the mutated version
> ```
> Using `git checkout --` (which writes a current-time mtime), or adding a `touch`, or switching to `mvn clean test`, all avoid this.

> If you do only one verification, do this one. It simultaneously proves: ① the defect is real; ② the fix works; ③ the test has detection power.

### Tier 2 — end to end (needs an AWS account + a CloudHSM cluster)

Deploy per `README-CloudHSM.md` and use `proxy/test`'s BACEN simulator for end to end. Note the simulator's limitation: it reuses the **same** `Iso20022XmlSigner`, so a green simulator only proves "we are consistent with ourselves", **not "we are consistent with BACEN"**. Real verification must:
1. Use a certificate **issued by a real CA** (Subject ≠ Issuer);
2. Run our `verify()` against a BACEN-official signed sample message;
3. Do a real round trip in BACEN's **homologação** environment.

---

## 3. Per-item verification methods

### Defect 1 — KeyInfo Issuer DN

```bash
grep -n 'newX509IssuerSerial' proxy/core/src/main/java/com/amazon/aws/pix/core/xml/XmlSigner.java
```
Expected: `getIssuerX500Principal()`. Automated verification is in Tier 1.

The fixture itself can also be checked independently (Subject must be ≠ Issuer):
```bash
openssl pkcs12 -in proxy/core/src/test/resources/security/ca-signed-chain.p12 \
        -passin pass:secret -nokeys -clcerts 2>/dev/null \
  | openssl x509 -noout -subject -issuer -serial
```
Expected: `subject` is `O=Test PSP, CN=pix-signature-test`, `issuer` is `O=Test PIX Issuing CA`, and the two differ.

### Defect 2 — `request_query`

```bash
# Keys emitted by AuditLog
grep -o 'put("[a-z_]*"' proxy/core/src/main/java/com/amazon/aws/pix/core/audit/AuditLog.java | sort -u
# request_query must appear in the columns declared by both READMEs
grep -c "name: 'request_query'" README-CloudHSM.md README-KMS.md   # each should be 1
# The KMS version now collects the query string
grep -n 'setRequestQuery' proxy/kms/src/main/java/com/amazon/aws/pix/kms/proxy/service/Logger.java
```

The `audit-schema` job in CI turns this into a **build gate**: any field emitted by `AuditLog` but not declared in the README fails the build. You can deliberately delete the `request_query` line in a README, push once, and see whether CI goes red.

### Defect 3 — startup script

```bash
bash -n proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh    # syntax
shellcheck -S warning proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh
grep -n "Hsms\[?State=='ACTIVE'\]" proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh  # configure all HSMs
grep -n 'READY_TIMEOUT_SECS'  proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh        # readiness wait has a timeout
grep -n '^exec java'          proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh        # JVM is PID 1
```

> Note: the **real** verification of multi-HSM failover needs a cluster with ≥2 HSMs and replacing one of them while running. A single-HSM test environment **will not** expose the original defect, which is exactly why it is easy to overlook.

### Defect 4 — the audit write no longer fails the transaction

```bash
grep -n -A3 'try {' proxy/cloudhsm/proxy/src/main/java/com/amazon/aws/pix/cloudhsm/proxy/processor/LogRequestResponseProcessor.java
```
Expected: `firehoseClient.putRecord` is inside the `try`, and the `catch` logs `AUDIT DELIVERY FAILED`.

> ⚠️ **This fix is a trade-off, not a pure improvement.** It exchanges a "correctness problem" for a "compliance problem" — the audit record may be lost. The code comments spell out the three things that must be added before production (a durable fallback, an alarm on that log line, moving the write off the critical path), and that **compliance must rule in writing on "does an audit failure reject the transaction"**. Please do not skip this decision.

### Defect 5 — an expired certificate is distinguishable

```bash
grep -n 'findCertificateValidityProblem\|CertificateValidityException' \
     proxy/core/src/main/java/com/amazon/aws/pix/core/xml/XmlSigner.java
```
Expected: there is logic that walks the cause chain looking for `CertificateExpiredException` / `CertificateNotYetValidException` and, on a hit, throws a distinct `CertificateValidityException` instead of returning `false`.

> The reason for walking the chain rather than catching by type is that this exception is **thrown lazily** by `KeySelectorResult#getKey()` (during `validate()`), and by the time it reaches the caller it may already be wrapped several layers deep.

**Runtime verification** (optional): temporarily replace `BcbSignatureCertificate` with an expired certificate, and expect to see the log `trusted certificate is outside its validity period ... NOT a signature mismatch`, rather than an undifferentiated `failed to verify signature`.

---

## 4. An honest account of local-machine limitations

The machine that generated these fixes **had no JDK and no Maven** (`~/.m2` was empty), therefore:

- ✅ `wrapper_script.sh` had its syntax verified locally with `bash -n`;
- ✅ the test fixture had Subject ≠ Issuer verified locally with openssl;
- ⚠️ **compilation and testing of the Java changes were done by GitHub Actions, not locally**. Treat the Actions `core` job result as authoritative. If that job is red, the change has a compilation or test problem, and **you should not use this code at that point** — open an issue or contact directly.

> **This section describes the state "when these fixes were generated" and is no longer the only evidence.** On 2026-09-19 a local re-review was completed on a separate machine (with JDK 11 + Maven actually installed); the result is in **section 4.1** — including the mutation test, the negative control on the gate, and an item-by-item comparison against the upstream defect state.

Incidentally: CI answers an open question — whether `proxy/core` also needs `--add-exports` at **runtime** (`pom.xml` declares it only at compile time, while the `Dockerfile`'s `java -jar` has no corresponding argument). If the `core` job's tests pass, then JDK 11 runtime access to those two internal packages is fine; if it reports `IllegalAccessError`, then this upgrade hazard is empirically confirmed.

**This question was empirically settled in the independent re-review on 2026-09-19 (see section 4.1): JDK 11 runtime does not need it, JDK 17 blows up.**

## 4.1 Independent re-review measured results (2026-09-19, not CI, on-machine aarch64)

On an Amazon Linux 2023 / **aarch64** machine **unrelated to the one that generated the fixes**, using user-level installs of **Corretto 11.0.32 + Maven 3.9.9 + ShellCheck 0.10.0** (no root, no Docker), all locally verifiable items from sections 2 and 3 were re-run in full:

| Verification item | Result |
|---|---|
| Baseline commit == tip of upstream `master` | ✅ `fa20042…`, `merge-base` consistent |
| Set of changed files in `git diff --stat upstream/master` | ✅ 10 files, **no changes beyond what is claimed** |
| `mvn -pl core test` | ✅ **5 tests / 0 failures** (`XmlSignerCaIssuedCertificateTest` 3 + `XmlSignerTest` 1 + `Iso20022XmlSignerTest` 1) |
| **Mutation test** (defect 1 reverted to upstream form) | ✅ `XmlSignerCaIssuedCertificateTest` **2 methods go red**, the assertion message precisely stating `expected:<…O=Test PIX Issuing CA…> but was:<…O=Test PSP…>`; `Iso20022XmlSignerTest` (self-signed) **still green** |
| Fixture Subject ≠ Issuer | ✅ `O=Test PSP, CN=pix-signature-test` vs `O=Test PIX Issuing CA` |
| Defect 2: `AuditLog` keys vs README columns | ✅ emits 10 keys, both READMEs declare `request_query` once each; upstream measured at **10 keys vs 9 columns in the Glue `Columns` block** (the other 4 `name:` are partition keys), so this document's 10-vs-9 statement is accurate |
| `audit-schema` gate + **negative control** | ✅ currently exit 0; after deleting the `request_query` line in `README-KMS.md`, exit 1 naming the missing column — the gate **has detection power** |
| Defect 3: `bash -n` / `shellcheck -S warning` / three greps | ✅ all pass, `shellcheck` **zero warnings**; the upstream version measured as `Hsms[0]`, `while true` with no timeout, `java -jar` not `exec` |
| Defect 4: `putRecord` inside `try`, `catch` logs `AUDIT DELIVERY FAILED` | ✅ `LogRequestResponseProcessor.java:59,63,64`; the upstream version has **no try/catch at all** on that call |
| Defect 5: `findCertificateValidityProblem` / `CertificateValidityException` | ✅ `XmlSigner.java:150,154,168,188`; the upstream version is `catch (Exception)` → `return false` |
| RSA exponent | ✅ this fork `65537` in 2 places (lines 82, 202), upstream `65541` in the same two places |
| `kms + simulator` red-mark attribution | ✅ the **same error reproduced locally**; `proxy/kms/pom.xml` has an **empty diff** relative to upstream, confirming it is unrelated to items 1–5 (see section 0) |

**Runtime behaviour of the JDK internal packages (the formerly open question, now measured):**

- **JDK 11: no `--add-exports` needed at runtime.** `surefire` has no `argLine` and the tests are still all green — because the default `--illegal-access=permit` in JDK 9–15 opens the JDK-8-era packages to the unnamed module.
- **JDK 17: fails outright at runtime.** The same code run under Corretto 17.0.20 **compiles** (the `pom.xml`'s `compilerArgs` still apply) but **3 tests error at runtime**:
  ```
  IllegalAccessError: class com.amazon.aws.pix.core.xml.Iso20022URIDereferencer (in unnamed module)
  cannot access class com.sun.org.apache.xml.internal.security.signature.XMLSignatureInput
  (in module java.xml.crypto) because module java.xml.crypto does not export
  com.sun.org.apache.xml.internal.security.signature to unnamed module
  ```
- Therefore section 5's "depends on two JDK internal packages (upgrading the JDK is high-risk)" is **no longer an inference but a measured fact**, and the failure form is **silent pass at compile time, blows up on every signature at runtime** — the kind hardest to catch before go-live.
- **There is no active defect right now**: both `Dockerfile`s are `FROM amazoncorretto:11`. But whoever bumps the base image to 17/21 (for example to adopt CloudHSM SDK 5, whose JCE is compatible only with OpenJDK 17/21/25) **must at the same time** add those two `--add-exports` to `wrapper_script.sh`'s `exec java` (possibly via `JAVA_OPTS`) and to `surefire`'s `argLine`, or CI stays all green while production goes all down.

**Correction (an error in the re-review itself)**: the previous version here wrote "the `cloudhsm` module's compilation is not covered, because `cavium` needs an x86_64 rpm and this machine is aarch64 with no root". **That was a conclusion drawn without trying; measured, it is wrong.** All **7 reactor modules compile on aarch64, with no root and no Docker**:

```
AWS PIX (Brazilian Instant Payment System) ......... SUCCESS
AWS PIX Core ....................................... SUCCESS
Pix CloudHSM Parent POM ............................ SUCCESS
CloudHSM Cavium JCE ................................ SUCCESS
PIX CloudHSM Proxy ................................. SUCCESS
Pix KMS Proxy Sync ................................. SUCCESS
PIX Proxy Test ..................................... SUCCESS
BUILD SUCCESS
```

Three reasons make it hold, all worth recording: `rpm` 4.16.1.3 ships with Amazon Linux 2023 (unpacking needs no root); the only thing actually consumed from that `.el7.x86_64.rpm` is `/opt/cloudhsm/java/cloudhsm-<ver>.jar`, and that **jar has 0 `.so`/native entries** — it is pure Java and architecture-independent; the `netty-tcnative` `linux-x86_64-fedora` jar is also downloaded and participates in compilation as usual. **So the architecture constraint is at runtime, not at build time** — `mvn package` succeeds on Graviton, and only at run time does the native library turn up missing. This green-then-blows-up order is exactly why it is easy to miss.

Incidentally this yields a measured anchor for A13 (build not reproducible): **on 2026-09-19 `latest` resolved to `cloudhsm 3.4.4`** (`[echo] cloudhsm version: 3.4.4`). Building on a different day may give a different version, while the consumer uses the open range `[3.0.0,)`.

**Not covered by this re-review** (the real hard constraints): Tier 2 end to end (needs an AWS account + a CloudHSM cluster), and multi-HSM failover (needs a ≥2-HSM cluster and replacing one of them while running).

---

## 5. What this fork did NOT fix (required reading)

These are **all still present** and must be stated when handing this off:

| Category | Problem that remains |
|---|---|
| **Keys and compliance** | The mTLS private key **must be extractable** to be usable (Netty's `SslProvider.OPENSSL` + `keyManager(PrivateKey,…)` needs the real key bytes), so it does not meet the strictest requirement that "the private key is always under the institution's exclusive control". The signing private key is unaffected (non-extractable). **Note: the mTLS private key cannot forge transactions; forging transactions requires the signing private key** |
| **SDK generation (upgraded to a blocking item)** | Uses **CloudHSM Client SDK 3** (`com.cavium`, `key_mgmt_util`, `PARTITION_1`, `cloudhsm-client-jce-latest.el7` rpm). The current one is **SDK 5**, with a completely different API. SDK 5's JCE is compatible only with OpenJDK 17/21/25 — **which conflicts with this project's JDK 11 and those two internal packages** (see section 4.1), so upgrading the SDK and upgrading the JDK must be done together, and the latter has already been measured to blow up |
| **HSM session** | Session invalidation has **no reconnect mechanism** (it logs in once at startup, `R:175`). The symptom is "after a few days all signatures fail, a restart fixes it". SDK 5 has built-in improved login-state management |
| **HSM instance type (originally severely understated in this document)** | The original text only said `hsm1.medium`'s FIPS certificate #4218 was moved to the CMVP historical list on 2026-01-04 and one should switch to `hsm2m.medium`. **That statement is not wrong but far from sufficient** — per AWS's own [deprecation notice](https://docs.aws.amazon.com/cloudhsm/latest/userguide/compliance-dep-notif.html) and [types page](https://docs.aws.amazon.com/cloudhsm/latest/userguide/hsm-types.html): ① **you cannot create a `hsm1.medium` cluster since April 2025**; ② `hsm1.medium` **reached end of support on 2026-03-31** (that date has passed); ③ from January 2026 AWS began **auto-migrating** existing hsm1 clusters to `hsm2m.medium`; ④ `hsm2m.medium` **requires Client SDK 5.9.0 and above**. `hsm2m.medium`'s certificate is [#4703](https://csrc.nist.gov/projects/cryptographic-module-validation-program/certificate/4703) (FIPS 140-3 L3). **The net conclusion is in "⚠️ The CloudHSM path can no longer be deployed" above** |
| **Input validation** | No ISO 20022 **XSD schema validation** (zero `SchemaFactory` / `setSchema` / `.xsd` validation code in the whole repo). The message size limit is **not explicitly configured** — but it is **not unbounded**: what actually takes effect is Camel netty-http's default `chunkedMaxContentLength=1048576` (1 MB), imposed by the `HttpObjectAggregator` in the server-side pipeline (empirically from the bytecode of `camel-netty-http-3.4.2`'s `HttpServerInitializerFactory`). So the risk is not "can OOM" but that **this limit is implicit**: it is neither in config nor in docs, so anyone who raises it or changes the endpoint config will not realise they are widening the attack surface |
| **TLS** | Hostname validation is not enabled (mitigated by explicitly trusting the BACEN certificate, i.e. certificate pinning); `bcbEndpoint` has no explicitly configured timeout |
| **Certificate lifecycle** | No expiry monitoring; config is read only at startup, so **changing the certificate requires a redeploy** |
| **Audit and privacy** | The audit log contains the full message text, i.e. names, CPF, account, amount — sensitive data under **LGPD**, with zero handling in the sample (`AuditLog.java:34-35,50-51` store `request_body` / `response_body` verbatim; the test messages shipped with the repo contain `<Nm>Fulano da Silva</Nm>` and an 11-digit `<Id>`). S3 has no encryption / Object Lock / versioning configured (**Object Lock can only be enabled at bucket creation**). **Correction**: `errorOutputPrefix` is actually **configured on both streams in both READMEs** (`README-CloudHSM.md:363,372`, `README-KMS.md:140,149`); this document previously wrote "not configured", which was wrong. What is really missing is **an alarm on that already-existing `error/` prefix** — there is zero CloudWatch alarm config in the whole repo, so failed-delivery records pile up quietly under `error/` with nobody knowing |
| **Observability** | No metrics, no tracing, no alarms. Signature-verification failure does not distinguish "signature mismatch" (a security event) from "certificate expired/misconfiguration" (an operational fault) |
| **Architecture** | The HSM client and the application are in the same container (`Dockerfile:4-9` runs `yum install` for three CloudHSM rpms in the same image that runs the application); it depends on two **JDK internal packages** — **now measured**: usable at JDK 11 runtime (thanks to the default `--illegal-access=permit`), but at **JDK 17 it compiles yet throws `IllegalAccessError` at runtime, blowing up on every signature**, see section 4.1; `netty-tcnative:2.0.31.Final`'s classifier is **`linux-x86_64-fedora`** (`cloudhsm/proxy/pom.xml:99-104`) — locking not only to x86_64 but to Fedora/RHEL-family OpenSSL, and the three rpms the `Dockerfile` installs are likewise `el7.x86_64`, so it **cannot go directly to Graviton**. **Note this is a runtime constraint, not a build-time one**: measured, `mvn package` is all green on aarch64 (see section 4.1), and the missing native library only surfaces at run time; Quarkus 1.7.0 / Camel-Quarkus 1.0.0 are both 2020 versions (`proxy/pom.xml:17-18`, and the AWS SDK BOM 2.13.0, Lombok 1.18.12 are of the same era) |
| **Build reproducibility** | Two things together make it non-reproducible: `cavium/pom.xml:32` pulls `cloudhsm-client-jce-latest.el7.x86_64.rpm` on every build, then uses antrun regex to reverse-derive `cloudhsm.version` from the jar filename and `install-file` it as `com.cavium:cloudhsm:${cloudhsm.version}`; the consumer `cloudhsm/proxy/pom.xml:75` picks it up with a **version range** `[3.0.0,)` (note the range is in the **proxy** module, not the cavium module — this document previously recorded both under cavium). The net effect is that **the build artifact depends on what AWS put on `latest` the day you build**. Measured anchor: **on 2026-09-19 it resolved to `cloudhsm 3.4.4`** |
| **Scope** | Covers only **outbound synchronous submission** (us → BACEN). It lacks the inbound path for BACEN's **asynchronous push-back** messages, as well as the complementary architecture for authorization, cancellation (SAGA), settlement, etc. A rough estimate is that it covers only **30–40%** of a complete Pix integration |

### 5.1 Evidence for each row above (2026-09-19 independent re-review)

The table above was originally assertions, with no place for the reader to check. Below is item-by-item measured evidence, paths relative to the repo root; `R` refers to
`proxy/cloudhsm/proxy/src/main/java/com/amazon/aws/pix/cloudhsm/proxy/PixCloudHSMProxyRouteBuilder.java`.

| Item | Evidence |
|---|---|
| mTLS private key must be extractable | `R:181` casts `cloudHsmKeyStore.getKey(MtlsKeyLabel)` to `PrivateKey`, `R:186-187` uses `SslProvider.OPENSSL` + `keyManager(privateKey, certificates)` — the OpenSSL provider needs the real key bytes, so that key cannot be `-nex` (non-extractable). The signing key goes through the other path at `R:194` and is not subject to this |
| CloudHSM Client SDK 3 | `R:12-13` imports `com.cavium.cfm2.*`, `R:174` `new com.cavium.provider.CaviumProvider()`, `R:175` `LoginManager.getInstance().login("PARTITION_1", …)`; `proxy/cloudhsm/cavium/pom.xml:32` pulls `cloudhsm-client-jce-latest.el7.x86_64.rpm`; the README uses `/opt/cloudhsm/bin/key_mgmt_util` (4 places) |
| HSM session no reconnect | `login` appears in the whole repo only at `R:175`, within the `configure()` call chain, i.e. **login happens once at startup only**; no re-login, no session health check |
| No XSD validation | Zero `SchemaFactory` / `setSchema` / ISO 20022 `.xsd` in the whole repo (the only `.xsd` hits are all pom schema declarations and surefire reports) |
| Hostname validation not enabled | Zero `setEndpointIdentificationAlgorithm` / `HostnameVerifier` in the whole repo. `NettyHttpClientInitializerFactory:140` only sets SNI (`setServerNames`); SNI is "tell the peer which name I want to connect to", it **does not validate that the peer's certificate belongs to that name** |
| `bcbEndpoint` no explicit timeout | The construction at `R:129-140` only sets `bridgeEndpoint` / `throwExceptionOnFailure` / `ssl` / `enabledProtocols` / `sslContextParameters` / `nativeTransport`, no `requestTimeout`. **Distinguish the two**: `connectTimeout` has a default of 10000 ms, so **connecting** is bounded; but `requestTimeout` has **no default** in `camel-netty-http-3.4.2`'s component metadata (i.e. the `int` is 0), and the `ReadTimeoutHandler` at `NettyHttpClientInitializerFactory:102` is only installed when `getRequestTimeout() > 0` — so the **response wait is completely unbounded, and that timeout handler was never installed into the pipeline**. When the BACEN side connects but does not reply, the request hangs forever |
| No certificate expiry monitoring | The only `checkValidity()` in the whole repo is at `X509IssuerSerialKeySelector:47`, which is the **verify-time** validity check (i.e. the action point of defect 5), not proactive pre-expiry monitoring/alarming |
| Config read only at startup | `R:156`'s `ssmClient.getParametersByPath(...)` is driven by `configure()` (executed once at Camel `RouteBuilder` startup), with no refresh or reload path |
| No metrics / tracing / alarms | Zero `Micrometer` / `MeterRegistry` / `OpenTelemetry` / `X-Ray` / `putMetricData` in the whole repo, and zero CloudWatch alarm config in both READMEs (the only SNS mention is in the opening paragraph about "complementary architecture", not an alarm) |
| HSM client and application in the same container | `proxy/cloudhsm/proxy/src/main/docker/Dockerfile:4-9` runs `yum install` for three CloudHSM rpms in the same `FROM amazoncorretto:11` image, and lines 22-23 then copy `wrapper_script.sh` and `application.jar` in |

**Cannot be verified on this machine** (hard constraints, not omissions): multi-HSM failover needs a ≥2-HSM cluster; S3/Firehose/LGPD and the observability items are deploy-time config and need a real account. (The `cavium` module's compilation **has been verified to pass**, see the correction in section 4.1 — it was previously thought to need an x86_64 host, but measured it does not.)

---

## 6. Upstream status (why not wait for a merge)

Upstream has had no substantive commit since 2024-10-17. The observable pattern: **dependabot's PRs get merged, humans' PRs get closed** —

- [PR #4](https://github.com/aws-samples/pix-proxy-samples/pull/4) (fixes the RSA exponent, two lines): created 2021-08-16, closed 2024-10-17, **hung for 1158 days (3 years 2 months)**, `merged=false`, `comments=0` + `review_comments=0` (zero comments);
- [PR #10](https://github.com/aws-samples/pix-proxy-samples/pull/10) (fixes CVE-2024-47554): the submitter `llins` is **this repository's most prolific contributor** — 23 commits, more than anyone else (the second human `joaoarag` has 3, dependabot 10). That PR was closed unmerged, while the same fix proposed by dependabot in [PR #12](https://github.com/aws-samples/pix-proxy-samples/pull/12) was merged **19 seconds** before it (`#12 merged_at=2024-10-17T20:22:48Z`, `#10 closed_at=2024-10-17T20:23:07Z`). This document previously wrote "a minute before"; measured, it is 19 seconds. It also previously described the submitter as "one of the original blog authors"; that claim cannot be verified from GitHub and has been replaced with the verifiable contributor ranking.

**"No substantive commit" can be stated more precisely**: the tip of upstream `master` is `fa20042` (2024-10-17), with **zero commits after it**; and the 5 commits on 2024-10-17 are **all dependabot version bumps and their merges**. The last non-dependabot commit is **`02985fdb` "xml parsing fix" on 2022-02-03** — 4 years 7 months ago.

Current state of the 5 reported issues (measured 2026-09-19): [#15](https://github.com/aws-samples/pix-proxy-samples/issues/15)–[#19](https://github.com/aws-samples/pix-proxy-samples/issues/19) **all exist and are state=open**, with titles matching the 5 defects in section 1 one for one.

So this fork does not wait for an upstream merge. The 5 defects have been reported as [#15](https://github.com/aws-samples/pix-proxy-samples/issues/15)–[#19](https://github.com/aws-samples/pix-proxy-samples/issues/19), and if upstream adopts them the fork will sync.

---

## 7. New findings from the independent audit (not among the original 5)

The following were **found by reading the code ourselves** during the 2026-09-19 independent re-review; they are not one of the 5 defects the fork originally reported. Provenance is recorded separately so the reader can distinguish "what the fork claims to have fixed" from "what the re-review additionally found".

### 7.1 `Iso20022URIDereferencer` reads a switch that nobody sets

`Iso20022URIDereferencer:40` takes `org.jcp.xml.dsig.secureValidation` out of the crypto context and feeds it to `XMLSignatureInput#setSecureValidation`:

```java
result.setSecureValidation(secureValidation(context));          // :40
private boolean secureValidation(XMLCryptoContext ctx) {        // :56
    return ctx == null ? false : getBoolean(ctx, "org.jcp.xml.dsig.secureValidation");
}
```

But **nowhere in the whole repo sets this property** — `XmlSigner:276` just does `new DOMValidateContext(keySelector, signatureNode)`. So it is always `false`, and the custom dereferencer **turns off** secure validation on the `AppHdr` / `Document` node sets it constructs itself. This is the mirror image of "written but nobody reads": **read, but nobody writes**.

**Fix**: `XmlSigner.getValidateContext` explicitly `setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE)`, so the value the dereferencer sees matches the JDK's own posture. The CloudHSM and KMS architectures share the same `XmlSigner.verify()`, so a single fix covers both.

### 7.2 An inference that did **not** hold (written down so nobody re-derives it)

My initial hypothesis was "secure validation is off overall, so an attacker could make the verifier dereference `file:` / `http:` references" — **measurement overturned it**. On Corretto 11.0.32, a three-state comparison over the same signed document carrying a `file:` reference:

| `org.jcp.xml.dsig.secureValidation` | Result |
|---|---|
| **not set** | ❌ rejected: `URI file:... is forbidden when secure validation is enabled` |
| explicit `TRUE` | ❌ rejected (same as above) |
| explicit `FALSE` | ✅ **passes**, and that `file:` reference is really dereferenced (`ref[1] valid=true`) |

That is, `DOMValidateContext`'s **default is enabled**. So this project was **never** exposed to the "attacker-specified path gets dereferenced" risk, and 7.1 is a consistency fix and **not** a patch for an exploitable vulnerability. Without this three-state comparison, one would push a no-op up as a security fix — which is also why the positive control is retained in the test below.

### 7.3 New regression tests

`XmlSignerSecureValidationTest` (4 methods, core test count 5 → 9):

- `validateContextCarriesSecureValidationTrue` — captures the real validate context and asserts the property the dereferencer reads is `TRUE`;
- `secureValidationRejectsFileUriReference` — a signature carrying a `file:` reference must be rejected;
- `explicitlyDisablingSecureValidationWouldAcceptFileUriReference` — **positive control**: after explicitly setting it to `FALSE`, the same document passes, proving the two above are not empty assertions and nailing "the cost of changing it to false" into the test;
- `normallySignedDocumentStillVerifies` — a normal signature is unaffected.

### 7.4 Two existing fixes contradict each other: an expired certificate loses the entire audit record (fixed)

This is the most valuable item this round, because it **is not an upstream defect but arises from the interaction of this fork's own two fixes**.

- The purpose of fix 4 (upstream #18): an audit write failure **must not** fail the transaction.
- The means of fix 5 (upstream #19): a certificate validity problem **throws an exception** `CertificateValidityException` instead of returning `false`.

The problem is that in both routes verification is ordered **before** the audit write:

| Architecture | Order | Location |
|---|---|---|
| CloudHSM | `…→ VerifyResponseProcessor → LogRequestResponseProcessor` | `PixCloudHSMProxyRouteBuilder:112-119` |
| KMS | `signer.verify(response); logger.log(request, response);` | `ProxyHandler:23-24` |

So the moment the BACEN signing certificate expires, the exception thrown by `verify()` **aborts the entire route / makes the Lambda handler exit with an error**, and the audit-write step is never reached — **not a single audit record lands**. And expiry is a persistent state: until someone rotates the certificate, **every** transaction both fails and has no audit. Fix 4 blocked "an audit failure destroying a transaction"; this is the same class of problem in the opposite direction: "a verification failure destroying the audit".

**Fix** (both call sites are changed, because they feed the same Glue table): catch `CertificateValidityException` at the verify call site, the transaction still fails with 500 (the response really is untrustworthy), but write the reason into the audit field and let the flow continue on to the audit write. A third value `PixConstants.SIGNATURE_VALID_CERTIFICATE_ERROR = "certificate-validity-error"` is added, alongside `"true"` / `"false"` — the Glue column `response_signature_valid` is already STRING, so **no schema change is needed** (the `audit-schema` gate re-ran green).

- `VerifyResponseProcessor` (CloudHSM)
- `Signer.verify` (KMS)

**Evidence**: a new `XmlSignerExpiredCertificateTest` (2 methods, core tests 9 → 11). An expired self-signed certificate fixture `expired-cert.p12` with validity 2020-01-01 → 2020-02-01 was built with openssl; the test first asserts the fixture **is indeed** expired (otherwise the whole test tests nothing), then asserts `verify()` **throws** `CertificateValidityException` with the cause being `CertificateExpiredException`.

**Honest boundary statement**: the two claims above that "the exception aborts the route / handler" are derived from **reading the route and handler source** (line numbers given), not run in a real Camel context and Lambda runtime — this machine has no way to bring up a Camel route and API Gateway. That the exception is indeed thrown from `verify()` is **measured**.

### 7.5 The repo carries a "BACEN" certificate with its private key that the docs steer into the same production parameter (guard added)

`proxy/test/src/main/docker/ssl/` holds both `sig.cer` / `mtls.cer` **and their private keys** `sig.key` / `mtls.key`. Measured, these two `.key` files are **real RSA 2048 private keys** (`openssl pkey` parses `Private-Key: (2048 bit, 2 primes)`), and their public-key fingerprints **match** their respective certificates (the pairing holds). The subjects of the two certificates are:

```
C=BR, ST=DF, L=Brasilia, O=BCB, OU=PIX, CN=*.pi.rsfn.net.br
notBefore=Jul  5 2020   notAfter=Jul  3 2030
```

That is, **the wildcard subject of BACEN's real production domain RSFN, valid until 2030**. As a simulator, this is fine in itself. **What is a problem is that the docs write them into the same parameter used for production**: `README-CloudHSM.md:469`/`502` and `README-KMS.md:201`/`234`, when creating `BcbSignatureCertificate` and `BcbMtlsCertificate`, each carry a "TO USE THE TEST - SIMULATOR, use:" block giving the contents of these two certificates.

So the act of switching from the simulator to the real BACEN amounts to "**remember to change that parameter**". The consequence of forgetting is: the proxy will accept a response signed by a **private key that is public on the internet**, and **nothing in the system will notice** — the certificate format is correct, it is within its validity period until 2030, and by definition it is in the trust store, so even the validity check newly added by fix 5 stays silent. `.gitignore` does not cover these files either, and the docs give zero warning.

**The guard added**: a new `WellKnownTestCertificates` identifies these two certificates by their **SHA-256 fingerprint** (`sig.cer` = `2ECA12B3…92F3`, `mtls.cer` = `8F43D131…C274`); on a hit it logs an ERROR that names names — "you are trusting a simulator certificate whose private key is public; if this process is to talk to the real BACEN, replace the parameter immediately".

Two design decisions worth explaining:

- **Match by fingerprint, not by subject.** The subject is BACEN's real DN; matching by subject would also alarm on the **real** certificate, defeating the guard.
- **Warn only, do not refuse to start.** The simulator flow is a formally supported usage in the docs, and a hard failure would directly break the path the README guides. Please configure an alarm on this log line in any environment that "is supposed to talk to the real BACEN".

The mount point is chosen at `KeyStoreUtil.getCertificates(String)`: `generateTrustStore(String,String)` delegates to it when handling `BcbSignatureCertificate`, and the mTLS path calls it directly when handling `BcbMtlsCertificate` — **one place covers both trust paths of both architectures, and cannot be bypassed**.

**Evidence**: a new `WellKnownTestCertificatesTest` (3 methods, core tests 11 → 14), including a **negative control** `doesNotFlagAnUnrelatedCertificate` — if the guard also alarmed on an unrelated certificate, it would be ignored as noise in a real environment, so this control is as important as the positive assertion.

### 7.6 Deployment-steps audit: one missing IAM permission, one parameter-type trap, and one link that never pointed anywhere (all fixed)

Item by item, the deployment steps of the two READMEs were reconciled against what the code actually reads/calls. **First, what is fine**: the 12 `/pix/proxy/cloudhsm/*` parameter names match the `Param` enum **one for one**, the 9 parameters on the KMS side plus the `MtlsPrivateKey` secret all have creation steps, and the JSON key names of `CloudHSMSecret` (`HSM_USER` / `HSM_PASSWORD`) match what `PixCloudHSMProxyRouteBuilder:171-172` reads. Three problems:

**① `cloudhsmv2:DescribeClusters` is not in the IAM list (will get stuck before startup)**

`wrapper_script.sh:33` runs `aws cloudhsmv2 describe-clusters` at container startup to discover the ACTIVE HSM's IP. This is an **IAM** permission, but the item related to CloudHSM in the README's original list pointed at [security group configuration](https://docs.aws.amazon.com/cloudhsm/latest/userguide/configure-sg.html) — which is network reachability, not IAM. Without this permission the container **exits before the JVM even starts**. It has been added to `README-CloudHSM.md`'s permission list with a note on how it differs from the security-group item.

**② Creating the parameters as `SecureString` will make startup fail, and the docs never said to use `String`**

Neither `getParametersByPath` sets `withDecryption`. A SecureString parameter, undecrypted, returns ciphertext; the ciphertext is then fed into `CertificateFactory` → startup fails, and the error does not mention "parameter type" at all. And "anything touching certificates/keys uses SecureString" is a hard rule at many organizations. **It has been changed to unconditional `withDecryption(true)`** (for ordinary `String` parameters the flag is ignored, so both types work), with a note in both READMEs: when really using SecureString, the task role also needs `kms:Decrypt` on that parameter's KMS key.

**③ The link at `README-KMS.md:31` is the literal `xxx`**

The original "To learn how to generate a CSR …, see `[here](xxx)`" — `xxx` is the link target itself, and it never pointed anywhere (`git show upstream/master` confirms upstream is the same, so not introduced by this fork). It has been replaced with a verifiable destination: this sample signs with exactly `aws-samples/aws-kms-jce`, whose `kms-jce-util` module has `CsrGenerator.generate(keyPair, csrInfo, kmsSigningAlgorithm)` (and a companion `SelfSignedCrtGenerator`). Incidentally, a permission-precision note: generating a CSR needs to construct a `KeyPair`, going through `KmsRSAKeyFactory.getKeyPair(kmsClient, keyId)` → needs **`kms:GetPublicKey`**, a **deploy-preparation-time** permission; whereas the runtime proxy only calls `KmsRSAKeyFactory.getPrivateKey(keyId)`, and reading the source confirms it merely constructs a reference and **does not contact KMS**, so at runtime only `kms:Sign` is needed. This is written into the README to keep someone from granting over-broad permissions on the strength of the word "sign".

**A suspicion checked but not upheld**: it was once suspected that the KMS-side IAM list omits `kms:GetPublicKey`. Reading the `aws-kms-jce` source vetoed it — this project only calls `getPrivateKey`, and `KmsRSAKeyFactory:46-48`'s implementation is `new KmsRSAPrivateKey(keyId)`, with no KMS call at all. The original list is semantically accurate at runtime.

### 7.7 A **design trade-off** confirmed by the re-review, not a defect

Section 1 says the symptom of defect 5 is "every transaction becomes a 500". Reading the code confirms: **the 500 still exists after the fix** — `XmlSigner.verify()` throws `CertificateValidityException` (a RuntimeException) for a certificate validity problem, while `VerifyResponseProcessor:27` sets 500 only when `verify()` returns `false`, and the exception bubbles straight up into a Camel error. So what this fix changes is **diagnosability** (the log clearly says "certificate rotation problem, not a signature mismatch") and **not** the HTTP result. The document's wording is easy to read as "once fixed there is no more 500", which is not the case; actually stopping the 500 requires a business decision (whether an expired certificate should reject the transaction), of the "must be ruled by compliance" kind in section 5.

---

## Summary in English

This is a **patched fork** of `aws-samples/pix-proxy-samples` at upstream commit
`fa20042d19d4c0b78f6898f3053b4cd0729938d0`. It fixes five production-blocking defects,
reported upstream as issues #15–#19:

1. **`XmlSigner` KeyInfo** carried the certificate **Subject** DN where `X509IssuerSerial`
   requires the **Issuer** DN. Invisible with self-signed certificates (Subject == Issuer),
   which is all the upstream tests and simulator use. With a CA-issued certificate the signer
   cannot even verify its own output.
2. **`request_query` was silently dropped** — `AuditLog` emits 10 keys, the documented Glue
   schema declares 9, and Firehose discards unknown fields without error. The KMS architecture
   never captured the query string at all; it now does.
3. **`wrapper_script.sh` configured only `Hsms[0]`**, defeating multi-AZ HA. Now configures
   every ACTIVE HSM, bounds the readiness wait with a timeout, and `exec`s the JVM so it
   becomes PID 1 and receives SIGTERM.
4. **The audit write no longer fails the transaction** — a Firehose failure used to surface as
   an error for a message BACEN may already have accepted. See the code comments: this is a
   deliberate trade-off that still requires a compliance decision and a durable fallback.
5. **An expired trusted certificate is now distinguishable** from a signature mismatch instead
   of turning every transaction into an opaque HTTP 500. The per-Reference diagnostic logging
   was also fixed — it previously skipped exactly the most common failure case.

Also: the RSA public exponent in `README-CloudHSM.md` is corrected from the non-standard
composite `65541` to `65537` (upstream PR #4 proposed this in 2021 and was closed unmerged).

**Added**: a regression test using a two-level certificate chain (`XmlSignerCaIssuedCertificateTest`
+ `ca-signed-chain.p12`) and a GitHub Actions workflow — upstream has no CI.

**To verify the fix is real**, revert the one-line change and watch the test go red:
```bash
sed -i.bak 's/getIssuerX500Principal()/getSubjectX500Principal()/' \
    proxy/core/src/main/java/com/amazon/aws/pix/core/xml/XmlSigner.java
mvn -f proxy/pom.xml -pl core test        # XmlSignerCaIssuedCertificateTest must FAIL

# Restore with git checkout, NOT by moving the .bak back: the .bak keeps the original
# mtime, so Maven's incremental compiler skips recompilation and the re-run silently
# tests the mutated class again. See section 2 (Tier 1) for the measured evidence.
git checkout -- proxy/core/src/main/java/com/amazon/aws/pix/core/xml/XmlSigner.java
rm -f proxy/core/src/main/java/com/amazon/aws/pix/core/xml/XmlSigner.java.bak
mvn -f proxy/pom.xml -pl core test        # green again: 5 tests, 0 failures
```

**Independently re-verified on 2026-09-19** on a separate aarch64 Amazon Linux 2023 host with
user-level Corretto 11 + Maven 3.9.9 + ShellCheck 0.10.0: all locally verifiable checks pass,
the mutation test detects the reverted defect, the audit-schema gate fails as required under a
negative control, and the `kms` red mark is confirmed to be artifact resolution only — the
module compiles once `kms-jce-provider` is built from source and installed locally. The
previously open `--add-exports` question is now settled: **JDK 11 needs no runtime flag, JDK 17
compiles but throws `IllegalAccessError` on every signing path.** See section 4.1.

**The upstream disclaimer still applies in full**: this is still not a basis for a final
BACEN integration. See section 5 above for the substantial gaps that remain — SDK 3, no HSM
session reconnect, extractable mTLS key, no XSD validation, no certificate expiry monitoring,
unaddressed LGPD exposure in the audit log, and roughly 30–40% coverage of a complete Pix
integration.
