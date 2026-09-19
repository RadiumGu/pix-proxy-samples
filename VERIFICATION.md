# 验证说明 / VERIFICATION

本文档给**接收这份代码的人**：如何独立验证这个 fork 相对 AWS 官方示例做了哪些修复、以及**这些修复是真的有效**。

- **上游仓库**：https://github.com/aws-samples/pix-proxy-samples
- **本 fork 基线提交**：`fa20042d19d4c0b78f6898f3053b4cd0729938d0`（2024-10-17，= 上游 `master` 顶点）
- **上游对应 Issue**：[#15](https://github.com/aws-samples/pix-proxy-samples/issues/15) · [#16](https://github.com/aws-samples/pix-proxy-samples/issues/16) · [#17](https://github.com/aws-samples/pix-proxy-samples/issues/17) · [#18](https://github.com/aws-samples/pix-proxy-samples/issues/18) · [#19](https://github.com/aws-samples/pix-proxy-samples/issues/19)

---

## 0. CI 实测结果（2026-09-19，供直接引用）

**当前状态：`master` 上 5 个 job 全绿** —— [run 35456018649](https://github.com/RadiumGu/pix-proxy-samples/actions/runs/35456018649)（提交 `13349dc`）。这是本仓库首次全绿：唯一长期失败的 `kms + simulator` job 已修好（原因与修法见本节末尾）。

下表是最初那次 [Actions 运行](https://github.com/RadiumGu/pix-proxy-samples/actions/runs/35454878877)（推送 `fixes/p0-production-hardening` 触发），保留下来是因为它记录了 `kms` 红叉的原始现场：

| Job | 结果 | 说明 |
|---|---|---|
| **`core - build + test`**（签名逻辑） | ✅ **success** | 缺陷 1、5 的修复与 `XmlSignerCaIssuedCertificateTest` 编译并全部通过 |
| `wrapper_script.sh - shellcheck` | ✅ success | 缺陷 3 的脚本改动 |
| `audit schema`（字段/列对齐门禁） | ✅ success | 缺陷 2 |
| `cloudhsm - compile` | ✅ success | 缺陷 3、4 涉及的模块 |
| `kms + simulator - compile` | ❌ **failure**（该次运行） | ⚠️ **与本次改动无关**；**已于 2026-09-19 修好**，见下 |

**`kms + simulator - compile` 失败原因与本次修复无关**：错误是
```
Could not find artifact software.amazon.awssdk:kms-jce-provider:jar:1.0.0 in central
```
这个坐标在 **`proxy/kms/pom.xml`**（本次未改动的文件）里，是上游预先存在的问题——`kms-jce-provider` 来自 `aws-samples/aws-kms-jce` 项目，从未发布到 Maven Central。上游自己也没有 CI 跑过这个模块（见第 6 节），所以这个缺陷至今没被发现。**它独立于第 1–5 项，不在本 fork 的修复范围内。**

**修复途径只有一条：本地安装。** 本文档此前写的「指向 `aws-kms-jce` 的实际发布坐标」是错的——**不存在这样的坐标**（实测见下）。`aws-kms-jce` 从源码构建出的坐标与 `proxy/kms/pom.xml` 需要的完全一致，所以本地 `mvn install` 一次即可：
```bash
git clone https://github.com/aws-samples/aws-kms-jce.git
mvn -f aws-kms-jce/pom.xml install -DskipTests     # 产出 software.amazon.awssdk:kms-jce-provider:1.0.0
mvn -B -f proxy/pom.xml -pl core,kms,test package -DskipTests   # 此时通过
```

实测（2026-09-19）：
- `search.maven.org` 查 `a:kms-jce-provider` → `numFound: 0`（**任何**版本、任何 groupId 都没有）；直接探 `repo.maven.apache.org` 的 `1.0.0` / `1.0.1` / `1.1.0` 三个路径均 **HTTP 404**；`aws-samples/aws-kms-jce` 的 GitHub **releases 数为 0**。
- 本地 `mvn install` 后重跑上面第三条命令：`AWS PIX Core` / `Pix KMS Proxy Sync` / `PIX Proxy Test` **三个模块全部 SUCCESS**。即这个红叉**纯粹是工件解析问题，源码本身能编译**。

**CI 已据此修好（2026-09-19）**：`.github/workflows/build.yml` 的 `kms-and-simulator` job 增加了一步，先从源码构建安装 `kms-jce-provider`（固定在提交 `6f7f179`，不跟随分支，保证可复现），再编译 `core,kms,test`。此前那个红叉是**永久性的**且不携带任何关于源码质量的信息；现在这个 job 要么真的证明这两个模块能编译，要么红得有意义。**实测已生效**：[run 35456018649](https://github.com/RadiumGu/pix-proxy-samples/actions/runs/35456018649) 中该 job **success**，5 个 job 全绿。

## ⚠️ 先读这一段：这份代码是什么、不是什么

**上游的免责声明依然完全适用，并未因这些修复而失效：**

> *"You can clone, change, execute it, but **it should not be used as a basis for building the final integration** of the Financial Institution with PIX (SPI and DICT)."*

这个 fork 修的是**5 个具体缺陷**，不是把示例变成了生产系统。仍然存在的重大差距见 [第 5 节](#5-本-fork-没有修的部分必读)——包括 CloudHSM Client SDK 3 已是上一代、HSM 会话失效无重连、mTLS 私钥必须可导出、无 XSD 校验、审计日志含个人数据未做 LGPD 处理等。

**把这份代码交给任何人时，请连同本文档第 5 节一起交付。**

---

## 1. 修了什么（5 项）

| # | 缺陷 | 改动位置 | 严重性 |
|---|---|---|---|
| **1** | `KeyInfo` 把证书的 **Subject DN** 当 **Issuer DN**。自签证书下两者相同所以看不出来；换成真实 CA 签发的证书后，发给 BACEN 的签发者信息是错的，且**示例自己的验签也会失败** | `proxy/core/.../xml/XmlSigner.java` | 🔴 接入 BACEN 会出问题 |
| **2** | 审计字段 `request_query` 被静默丢弃（`AuditLog` 产出 10 个键，README 的 Glue 表只声明 9 列，Firehose 丢弃未知字段且不报错）。KMS 版更严重——**它根本没采集查询串** | 两个 README + `proxy/kms/.../service/Logger.java` | 🔴 审计缺失 |
| **3** | 启动脚本只配置 `Hsms[0]`，多 AZ 高可用形同虚设；就绪等待无超时会永久挂起；`java` 非 PID 1 导致优雅停机可能失效 | `proxy/cloudhsm/.../docker/wrapper_script.sh` | 🔴 可用性 |
| **4** | 审计写入是路由**最后一步且同步**，Firehose 故障会让"BACEN 已受理"的交易对调用方表现为失败 | `LogRequestResponseProcessor.java` + KMS `Logger.java` | 🔴 资金对账风险 |
| **5** | BACEN 证书过期时异常被吞成"验签失败"，**每笔交易变 500** 且看起来像被攻击；另修正了验签诊断日志在最常见失败场景下恰好不打印引用明细的条件判断 | `proxy/core/.../xml/XmlSigner.java` | 🔴 全量中断且难定位 |

**附带修正**：`README-CloudHSM.md` 的 RSA 公开指数 `-e 65541` → **`-e 65537`**。65541 = 3 × 21847 是合数，偏离所有主流实现。外部研究者早在 2021 年就提交了 [PR #4](https://github.com/aws-samples/pix-proxy-samples/pull/4) 修复此处，该 PR 挂了 3 年 2 个月后于 2024-10-17 被关闭未合并、零评论，所以上游 README 至今仍是 65541。

> ⚠️ **公开指数是密钥的固有属性，无法事后修改。** 如果你已经用 `-e 65541` 生成过密钥，必须重新生成。

**同时新增**（上游没有）：
- `proxy/core/src/test/java/.../XmlSignerCaIssuedCertificateTest.java` —— 证明缺陷 1 的回归测试
- `proxy/core/src/test/resources/security/ca-signed-chain.p12` —— 两级证书链测试夹具（Subject ≠ Issuer）
- `.github/workflows/build.yml` —— CI（上游完全没有 CI）

---

## 2. 三层验证，按投入从小到大

### Tier 0 —— 零安装，1 分钟

看 CI 状态和 diff：

```bash
git clone https://github.com/RadiumGu/pix-proxy-samples.git
cd pix-proxy-samples
git remote add upstream https://github.com/aws-samples/pix-proxy-samples.git
git fetch upstream

# 本 fork 相对上游改了什么，一览
git diff --stat upstream/master
# 逐行看
git diff upstream/master
```

GitHub 上直接看：仓库首页的 **Actions** 标签页应有绿色的 `build` 运行；`core` 这个 job 绿了，就说明**签名逻辑编译通过且回归测试全绿**。

### Tier 1 —— 本地构建验证，约 5 分钟 ★ 推荐 ★

**前置**：JDK **11**（项目 `pom.xml` 设 `java.version=11`，且 `proxy/core` 依赖两个 `java.xml.crypto` 内部包的 `--add-exports`，换大版本 JDK 不是在验证这个项目实际发布的东西）+ Maven。

```bash
mvn -f proxy/pom.xml -pl core test
```

期望：**全部通过**，其中包括
- `Iso20022XmlSignerTest`（上游原有）
- `XmlSignerCaIssuedCertificateTest`（本 fork 新增，3 个测试方法）

### ★★ Tier 1 的关键一步：证明这个测试不是空测试 ★★

**只通过的测试可能什么都没测。要验证修复是真的，请把修复改回去，看测试是否变红。**

```bash
# 把缺陷 1 手动改回上游的错误写法
sed -i.bak 's/getIssuerX500Principal()/getSubjectX500Principal()/' \
    proxy/core/src/main/java/com/amazon/aws/pix/core/xml/XmlSigner.java

mvn -f proxy/pom.xml -pl core test
```

**期望：`XmlSignerCaIssuedCertificateTest` 失败**，且失败信息应指出

- `keyInfoMustCarryIssuerDnNotSubjectDn` —— 发出的 `<ds:X509IssuerName>` 是叶证书的 Subject DN，而非 Issuer DN；
- `signerMustVerifyItsOwnSignatureWithACaIssuedCertificate` —— 签名者**连自己产出的签名都验不过**。这是因为 `X509IssuerSerialKeySelector` 按「签发者 DN + 序列号」到信任库里找证书，错误的签发者 DN 匹配不到任何证书。

而 `Iso20022XmlSignerTest`（上游原有、用自签证书）**依然通过**——这正好演示了为什么这个 bug 在上游能长期隐形。

改回来：
```bash
git checkout -- proxy/core/src/main/java/com/amazon/aws/pix/core/xml/XmlSigner.java
rm -f proxy/core/src/main/java/com/amazon/aws/pix/core/xml/XmlSigner.java.bak
mvn -f proxy/pom.xml -pl core test   # 应重新全绿
```

> ⚠️ **不要用 `mv …XmlSigner.java.bak XmlSigner.java` 还原**（本文档此前给的就是这条，已更正）。`sed -i.bak` 产生的 `.bak` 保留的是**原文件的 mtime**，把它 `mv` 回去会得到一个比 `target/classes/…/XmlSigner.class` **更旧**的源文件；`maven-compiler-plugin` 的增量判定据此认为无需重编译，于是这一步测的仍是**变异过的 class**，`XmlSignerCaIssuedCertificateTest` 会再次变红——一个纯粹由还原方式造成的**假红**。
>
> 实测证据（2026-09-19，本地 JDK 11.0.32 / Maven 3.9.9）：`mv` 还原后 `git status` 干净、源码第 218 行确为 `getIssuerX500Principal()`，但
> ```
> javap -p -c target/classes/com/amazon/aws/pix/core/xml/XmlSigner.class | grep -oE 'get(Subject|Issuer)X500Principal'
> →  getSubjectX500Principal      # class 里还是变异后的版本
> ```
> 用 `git checkout --`（写入当前时间的 mtime）或补一条 `touch`，或改用 `mvn clean test`，都能避免。

> 如果你只做一项验证，就做这一项。它同时证明了：① 缺陷真实存在；② 修复有效；③ 测试有检出能力。

### Tier 2 —— 端到端（需要 AWS 账号 + CloudHSM 集群）

按 `README-CloudHSM.md` 部署，用 `proxy/test` 的 BACEN 模拟器做端到端。注意模拟器的局限：它复用**同一个** `Iso20022XmlSigner`，所以模拟器全绿只证明"我们和自己一致"，**不证明"我们和 BACEN 一致"**。真实验证必须：
1. 用**真实 CA 签发**（Subject ≠ Issuer）的证书；
2. 用 BACEN 官方发布的签名样例报文跑我们的 `verify()`；
3. 在 BACEN 的 **homologação** 环境做真实往返。

---

## 3. 逐项验证方法

### 缺陷 1 —— KeyInfo Issuer DN

```bash
grep -n 'newX509IssuerSerial' proxy/core/src/main/java/com/amazon/aws/pix/core/xml/XmlSigner.java
```
期望看到 `getIssuerX500Principal()`。自动化验证见 Tier 1。

夹具本身也可独立核对（Subject 必须 ≠ Issuer）：
```bash
openssl pkcs12 -in proxy/core/src/test/resources/security/ca-signed-chain.p12 \
        -passin pass:secret -nokeys -clcerts 2>/dev/null \
  | openssl x509 -noout -subject -issuer -serial
```
期望：`subject` 是 `O=Test PSP, CN=pix-signature-test`，`issuer` 是 `O=Test PIX Issuing CA`，两者不同。

### 缺陷 2 —— `request_query`

```bash
# AuditLog 产出的键
grep -o 'put("[a-z_]*"' proxy/core/src/main/java/com/amazon/aws/pix/core/audit/AuditLog.java | sort -u
# 两个 README 声明的列里必须都有 request_query
grep -c "name: 'request_query'" README-CloudHSM.md README-KMS.md   # 各应为 1
# KMS 版现在会采集查询串
grep -n 'setRequestQuery' proxy/kms/src/main/java/com/amazon/aws/pix/kms/proxy/service/Logger.java
```

CI 里的 `audit-schema` job 把这条做成了**构建门禁**：任何 `AuditLog` 产出但 README 未声明的字段都会让构建失败。可以故意删掉 README 里的 `request_query` 那行、推一次，看 CI 是否变红。

### 缺陷 3 —— 启动脚本

```bash
bash -n proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh    # 语法
shellcheck -S warning proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh
grep -n "Hsms\[?State=='ACTIVE'\]" proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh  # 配置全部 HSM
grep -n 'READY_TIMEOUT_SECS'  proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh        # 就绪等待有超时
grep -n '^exec java'          proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh        # JVM 为 PID 1
```

> 注：多 HSM 故障转移的**真实**验证需要一个 ≥2 HSM 的集群，并在运行中替换其中一个。单 HSM 测试环境**不会**暴露原缺陷，这正是它容易被忽略的原因。

### 缺陷 4 —— 审计写入不再让交易失败

```bash
grep -n -A3 'try {' proxy/cloudhsm/proxy/src/main/java/com/amazon/aws/pix/cloudhsm/proxy/processor/LogRequestResponseProcessor.java
```
期望：`firehoseClient.putRecord` 在 `try` 内，`catch` 里记录 `AUDIT DELIVERY FAILED`。

> ⚠️ **这个修复是一个取舍，不是纯粹的改进。** 它把"正确性问题"换成了"合规问题"——审计记录可能丢失。代码注释里写明了上生产前必须补的三件事（持久化兜底、该日志行的告警、把写入移出关键路径），以及**必须由合规书面裁定"审计失败是否拒绝交易"**。请不要跳过这个决策。

### 缺陷 5 —— 证书过期可区分

```bash
grep -n 'findCertificateValidityProblem\|CertificateValidityException' \
     proxy/core/src/main/java/com/amazon/aws/pix/core/xml/XmlSigner.java
```
期望：存在沿 cause 链查找 `CertificateExpiredException` / `CertificateNotYetValidException` 的逻辑，命中时抛出独立的 `CertificateValidityException`，而不是返回 `false`。

> 用沿链查找而非按类型 `catch`，是因为该异常由 `KeySelectorResult#getKey()` **延迟抛出**（在 `validate()` 期间），到达调用方时可能已被包装多层。

**运行时验证**（可选）：临时把 `BcbSignatureCertificate` 换成一张已过期的证书，期望看到日志 `trusted certificate is outside its validity period ... NOT a signature mismatch`，而不是无差别的 `failed to verify signature`。

---

## 4. 本机限制的坦白交代

生成这些修复的机器**没有 JDK、没有 Maven**（`~/.m2` 为空），因此：

- ✅ `wrapper_script.sh` 已用 `bash -n` 本地验证语法；
- ✅ 测试夹具已用 openssl 本地验证 Subject ≠ Issuer；
- ⚠️ **Java 改动的编译与测试由 GitHub Actions 完成，不是在本地**。请以 Actions 的 `core` job 结果为准。若该 job 为红色，说明改动有编译或测试问题，**此时不要使用这份代码**，请开 issue 或直接联系。

> **本节描述的是「生成这些修复时」的状态，已不是当前唯一证据。** 2026-09-19 在一台独立机器上完成了本机复核（JDK 11 + Maven 实装），结果见 **第 4.1 节**——包括变异测试、门禁阴性对照、以及对上游缺陷状态的逐项对照。

顺带一件事：CI 会回答一个悬而未决的问题——`proxy/core` 在**运行期**是否也需要 `--add-exports`（`pom.xml` 只在编译期声明了它，而 `Dockerfile` 的 `java -jar` 没有对应参数）。`core` job 的测试如果通过，说明 JDK 11 运行期访问那两个内部包没问题；如果报 `IllegalAccessError`，则实证确认了这个升级隐患。

**这个问题已在 2026-09-19 的独立复核中实测结论（见第 4.1 节）：JDK 11 运行期不需要，JDK 17 会炸。**

## 4.1 独立复核实测结果（2026-09-19，非 CI，aarch64 本机）

在一台**与生成修复的机器无关**的 Amazon Linux 2023 / **aarch64** 机器上，用用户级安装的 **Corretto 11.0.32 + Maven 3.9.9 + ShellCheck 0.10.0**（无 root、无 Docker）完整重跑了第 2、3 节的全部可本地验证项：

| 验证项 | 结果 |
|---|---|
| 基线提交 == 上游 `master` 顶点 | ✅ `fa20042…`，`merge-base` 一致 |
| `git diff --stat upstream/master` 的改动文件集 | ✅ 10 个文件，**无声称之外的改动** |
| `mvn -pl core test` | ✅ **5 tests / 0 failures**（`XmlSignerCaIssuedCertificateTest` 3 + `XmlSignerTest` 1 + `Iso20022XmlSignerTest` 1） |
| **变异测试**（缺陷 1 改回上游写法） | ✅ `XmlSignerCaIssuedCertificateTest` **2 个方法变红**，断言信息精确指出 `expected:<…O=Test PIX Issuing CA…> but was:<…O=Test PSP…>`；`Iso20022XmlSignerTest`（自签）**依然绿** |
| 夹具 Subject ≠ Issuer | ✅ `O=Test PSP, CN=pix-signature-test` vs `O=Test PIX Issuing CA` |
| 缺陷 2：`AuditLog` 键 vs README 列 | ✅ 产出 10 键，两个 README 各声明 `request_query` 1 次；上游实测为 **10 键 vs Glue `Columns` 块 9 列**（另 4 个 `name:` 是分区键），本文档的 10-vs-9 说法准确 |
| `audit-schema` 门禁 + **阴性对照** | ✅ 当前 exit 0；删掉 `README-KMS.md` 的 `request_query` 行后 exit 1 并指名缺失列——门禁**有检出能力** |
| 缺陷 3：`bash -n` / `shellcheck -S warning` / 三条 grep | ✅ 全通过，`shellcheck` **零告警**；上游版实测为 `Hsms[0]`、`while true` 无超时、`java -jar` 非 `exec` |
| 缺陷 4：`putRecord` 在 `try` 内、`catch` 记 `AUDIT DELIVERY FAILED` | ✅ `LogRequestResponseProcessor.java:59,63,64`；上游版该调用**无任何 try/catch** |
| 缺陷 5：`findCertificateValidityProblem` / `CertificateValidityException` | ✅ `XmlSigner.java:150,154,168,188`；上游版为 `catch (Exception)` → `return false` |
| RSA 指数 | ✅ 本 fork `65537` 共 2 处（第 82、202 行），上游同两处均为 `65541` |
| `kms + simulator` 红叉归因 | ✅ 本地**同样错误复现**；`proxy/kms/pom.xml` 相对上游 **diff 为空**，确认与第 1–5 项无关（详见第 0 节） |

**JDK 内部包的运行期行为（原悬而未决的问题，现已实测）：**

- **JDK 11：运行期不需要 `--add-exports`。** `surefire` 没有 `argLine`，测试仍全绿——因为 JDK 9–15 的默认 `--illegal-access=permit` 会把 JDK 8 时代的包开放给 unnamed module。
- **JDK 17：运行期直接失败。** 同一份代码用 Corretto 17.0.20 跑，**编译成功**（`pom.xml` 的 `compilerArgs` 仍生效）但**运行期 3 个测试报错**：
  ```
  IllegalAccessError: class com.amazon.aws.pix.core.xml.Iso20022URIDereferencer (in unnamed module)
  cannot access class com.sun.org.apache.xml.internal.security.signature.XMLSignatureInput
  (in module java.xml.crypto) because module java.xml.crypto does not export
  com.sun.org.apache.xml.internal.security.signature to unnamed module
  ```
- 因此第 5 节「依赖两个 JDK 内部包（升级 JDK 高风险）」**不再是推断，而是实测事实**，且失败形态是**编译期静默通过、运行期每笔签名都炸**——最难在上线前发现的那一类。
- **当前没有活跃缺陷**：两个 `Dockerfile` 都是 `FROM amazoncorretto:11`。但凡是 bump 基础镜像到 17/21（例如为了上 CloudHSM SDK 5，其 JCE 只兼容 OpenJDK 17/21/25）的人，**必须同时**给 `wrapper_script.sh` 的 `exec java`（可经 `JAVA_OPTS`）和 `surefire` 的 `argLine` 补上这两个 `--add-exports`，否则 CI 全绿而生产全挂。

**本次复核未覆盖**（与本机能力无关的硬约束）：Tier 2 端到端（需 AWS 账号 + CloudHSM 集群）、多 HSM 故障转移（需 ≥2 HSM 集群）、`cloudhsm` 模块编译（`cavium` 需构建时下载 `cloudhsm-client-jce-latest.el7.**x86_64**.rpm` 且需 `rpm` 工具，本机为 **aarch64** 且无 root）。另注：第 5 节提到 `netty-tcnative` 锁 `linux-x86_64`，本机 aarch64 也印证了该模块无法在 Graviton 上直接跑。

---

## 5. 本 fork 没有修的部分（必读）

这些**都还在**，交付时必须一并说明：

| 类别 | 仍存在的问题 |
|---|---|
| **密钥与合规** | mTLS 私钥**必须可导出**才能用（Netty 的 `SslProvider.OPENSSL` + `keyManager(PrivateKey,…)` 需要真实密钥字节），因此不满足"私钥始终处于机构独占控制之下"的最严要求。签名私钥不受影响（不可导出）。**注意：mTLS 私钥无法伪造交易，伪造交易需要签名私钥** |
| **SDK 代际** | 用的是 **CloudHSM Client SDK 3**（`com.cavium`、`key_mgmt_util`、`PARTITION_1`）。当前是 **SDK 5**，API 完全不同。SDK 5 的 JCE 只兼容 OpenJDK 17/21/25 |
| **HSM 会话** | 会话失效**无重连机制**（只在启动时登录一次）。表现为"跑几天后所有签名失败、重启就好"。SDK 5 已内建改进的登录状态管理 |
| **HSM 机型** | `hsm1.medium` 的 FIPS 证书 #4218 已于 2026-01-04 移入 CMVP 历史列表，应改用 `hsm2m.medium`（FIPS 140-3 L3） |
| **输入校验** | 无 ISO 20022 **XSD 模式校验**（全仓零 `SchemaFactory` / `setSchema` / `.xsd` 校验代码）。报文大小上限**未显式配置**——但**并非没有上限**：实际生效的是 Camel netty-http 的默认 `chunkedMaxContentLength=1048576`（1 MB），由服务端管道里的 `HttpObjectAggregator` 施加（`camel-netty-http-3.4.2` 的 `HttpServerInitializerFactory` 字节码实证）。所以风险不是「可 OOM」，而是**这个上限是隐式的**：既没写进配置也没写进文档，调高它或改动端点配置的人不会意识到自己在放大攻击面 |
| **TLS** | 未启用主机名校验（靠显式信任 BACEN 证书即证书锁定缓解）；`bcbEndpoint` 未显式配置超时 |
| **证书生命周期** | 无到期监控；配置只在启动时读取，**换证书必须重新部署** |
| **审计与隐私** | 审计日志含报文全文，即含姓名、CPF、账号、金额——属 **LGPD** 管辖的敏感数据，示例零处理。S3 未配加密/Object Lock（**Object Lock 只能建桶时启用**）；Firehose `errorOutputPrefix` 与 `error/` 前缀告警未配 |
| **可观测性** | 无指标、无追踪、无告警。验签失败不区分"签名不匹配"（安全事件）与"证书过期/配置错误"（运维故障） |
| **架构** | HSM 客户端与应用同容器；依赖两个 **JDK 内部包**——**已实测**：JDK 11 运行期可用（靠默认 `--illegal-access=permit`），**JDK 17 编译通过但运行期 `IllegalAccessError`，每笔签名都炸**，见第 4.1 节；`netty-tcnative` 锁定 `linux-x86_64`，**不能直接上 Graviton**；Quarkus 1.7.0 / Camel-Quarkus 1.0.0 均为 2020 年版本 |
| **构建可复现性** | `cavium` 模块每次构建都拉 `cloudhsm-client-jce-latest.rpm`，依赖版本区间 `[3.0.0,)` —— **构建不可复现** |
| **范围** | 只覆盖**出向同步提交**（我们 → BACEN）。缺 BACEN **异步回推**消息的入向链路，以及授权、撤销（SAGA）、生效等互补架构。粗估只覆盖完整 Pix 接入的 **30–40%** |

### 5.1 上表各项的取证（2026-09-19 独立复核）

上表原本是断言，没有给读者核对的落点。以下是逐条实测证据，路径相对仓库根；`R` 指
`proxy/cloudhsm/proxy/src/main/java/com/amazon/aws/pix/cloudhsm/proxy/PixCloudHSMProxyRouteBuilder.java`。

| 项 | 证据 |
|---|---|
| mTLS 私钥必须可导出 | `R:181` 把 `cloudHsmKeyStore.getKey(MtlsKeyLabel)` 强转为 `PrivateKey`，`R:186-187` 用 `SslProvider.OPENSSL` + `keyManager(privateKey, certificates)`——OpenSSL provider 需要真实密钥字节，故该密钥不能是 `-nex`（不可导出）。签名密钥走 `R:194` 的另一条路径，不受此限 |
| CloudHSM Client SDK 3 | `R:12-13` 导入 `com.cavium.cfm2.*`，`R:174` `new com.cavium.provider.CaviumProvider()`，`R:175` `LoginManager.getInstance().login("PARTITION_1", …)`；`proxy/cloudhsm/cavium/pom.xml:32` 拉 `cloudhsm-client-jce-latest.el7.x86_64.rpm`；README 用 `/opt/cloudhsm/bin/key_mgmt_util`（4 处） |
| HSM 会话无重连 | 全仓 `login` 只出现在 `R:175`，位于 `configure()` 调用链内，即**仅启动时登录一次**；无重登录、无会话健康检查 |
| 无 XSD 校验 | 全仓零 `SchemaFactory` / `setSchema` / ISO 20022 `.xsd`（唯一的 `.xsd` 命中全是 pom 的 schema 声明与 surefire 报告） |
| 未启用主机名校验 | 全仓零 `setEndpointIdentificationAlgorithm` / `HostnameVerifier`。`NettyHttpClientInitializerFactory:140` 只设了 SNI（`setServerNames`），SNI 是「告诉对端我要连哪个名字」，**不校验对端证书是否属于该名字** |
| `bcbEndpoint` 无显式超时 | `R:129-140` 的构造只设 `bridgeEndpoint` / `throwExceptionOnFailure` / `ssl` / `enabledProtocols` / `sslContextParameters` / `nativeTransport`，无 `requestTimeout`。**分清两者**：`connectTimeout` 有默认 10000 ms，所以**建连**是有界的；但 `requestTimeout` 在 `camel-netty-http-3.4.2` 的组件元数据里**无默认值**（即 `int` 取 0），而 `NettyHttpClientInitializerFactory:102` 的 `ReadTimeoutHandler` 只在 `getRequestTimeout() > 0` 时才装——因此**响应等待完全无界，那个超时处理器从未被装入过管道**。BACEN 侧连上却不回包时，请求会一直悬挂 |
| 无证书到期监控 | 全仓唯一的 `checkValidity()` 在 `X509IssuerSerialKeySelector:47`，属**验签时**的有效期检查（即缺陷 5 的作用点），不是到期前的主动监控/告警 |
| 配置只在启动时读取 | `R:156` 的 `ssmClient.getParametersByPath(...)` 由 `configure()`（Camel `RouteBuilder` 启动时执行一次）驱动，无刷新与重载路径 |

**本机无法验证**（硬约束，非遗漏）：多 HSM 故障转移需 ≥2 HSM 集群；`cavium` 模块需 x86_64 rpm 与 `rpm` 工具（复核机为 aarch64 且无 root）；S3/Firehose/LGPD 与可观测性各项属部署期配置，需实账号。

---

## 6. 上游状态（为什么不等合并）

上游自 2024-10-17 起无实质提交。可观察到的模式：**dependabot 的 PR 被合并，人类的 PR 被关闭**——

- [PR #4](https://github.com/aws-samples/pix-proxy-samples/pull/4)（修 RSA 指数，两行）：挂 3 年 2 个月，关闭未合并，零评论；
- [PR #10](https://github.com/aws-samples/pix-proxy-samples/pull/10)（修 CVE-2024-47554，由原博客作者之一提交）：被关闭，而 dependabot 提的同一修复 [PR #12](https://github.com/aws-samples/pix-proxy-samples/pull/12) 在一分钟前被合并。

因此本 fork 不等上游合并。5 个缺陷已作为 [#15](https://github.com/aws-samples/pix-proxy-samples/issues/15)–[#19](https://github.com/aws-samples/pix-proxy-samples/issues/19) 上报，若上游采纳，本 fork 会同步。

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
