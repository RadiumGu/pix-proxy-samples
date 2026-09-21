# 验证说明 / VERIFICATION

> ## 先读这一段：这份文档是什么、给谁看
>
> **这是一份审计轨迹，不是使用文档。** 它记录本 fork 的每一条主张是**如何被验证**的：什么被复现了、什么被实测了、什么后来发现是错的、以及什么没有修。它写给**核查这项工作的人**，不是写给使用这份代码的人。
>
> **如果你只是想使用或部署这个项目，你打开了错误的文件。** 请去 [`README-CloudHSM.md`](README-CloudHSM.md)——那里有架构、BCB / TLS / JDK / CloudHSM 的版本要求、部署步骤,以及如何运行各项检查。尚未证明的事项清单见 [`CLOUDHSM_BCB_V2_HANDOFF.md`](CLOUDHSM_BCB_V2_HANDOFF.md) 第 7 节。
>
> **两条警告，无论你怎么用这个仓库都适用：**
> 1. **CloudHSM 这条路径无法按现状部署**——代码面向 Client SDK 3，而 `hsm1.medium` 已于 2026-03-31 终止支持，唯一可创建的实例类型需要 SDK 5.9.0+，后者需要 JDK 17+。见下方 ⚠️ 小节。
> 2. **测试套件通过不等于 BCB homologação 通过。** 本地模拟器不是 BCB。
>
> **语言说明：** 本文正文为中文，因为它是作为工作记录写成的。文末 [Summary in English](#summary-in-english) 有一份英文摘要。仓库其余文档为英文，首页另有 [`README.zh-CN.md`](README.zh-CN.md) 中文版。

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
> **Language note:** most of this file is in Chinese, because it was written as a working record. An
> English summary of the findings is in [Summary in English](#summary-in-english) at the end. The
> rest of the repository's documentation is in English, and the front page also has a Chinese
> version at [`README.zh-CN.md`](README.zh-CN.md).


本文档给**接收这份代码的人**：如何独立验证这个 fork 相对 AWS 官方示例做了哪些修复、以及**这些修复是真的有效**。

- **上游仓库**：https://github.com/aws-samples/pix-proxy-samples
- **本 fork 基线提交**：`fa20042d19d4c0b78f6898f3053b4cd0729938d0`（2024-10-17，= 上游 `master` 顶点）
- **上游对应 Issue**：[#15](https://github.com/aws-samples/pix-proxy-samples/issues/15) · [#16](https://github.com/aws-samples/pix-proxy-samples/issues/16) · [#17](https://github.com/aws-samples/pix-proxy-samples/issues/17) · [#18](https://github.com/aws-samples/pix-proxy-samples/issues/18) · [#19](https://github.com/aws-samples/pix-proxy-samples/issues/19)

---

## 0. CI 实测结果（2026-09-19，供直接引用）

**当前状态（2026-09-20）：`master` 上 CloudHSM-only CI 的 7 个 job 全绿。** 工作流已改为只维护 CloudHSM 路径——`kms` 不再参与 CI（历史保留、不受支持），下表里那个 `kms + simulator - compile` job **已不存在**，保留下文记录只为说明当初那个红叉的来龙去脉，**不要当成当前状态读**。

当前 7 个 job：`core` · `CloudHSM simulator - compile` · `DICT v2 transparent-proxy contract test` · `cloudhsm - compile`（best effort）· `wrapper_script.sh - shellcheck` · `transport contract - production route options + KMS scope guard` · `audit schema`。

2026-09-20 新增的 BCB DICT v2 传输契约测试与门禁，连同**未解决的 homologação gate 清单**，见 [`CLOUDHSM_BCB_V2_HANDOFF.md`](CLOUDHSM_BCB_V2_HANDOFF.md) 第 3.1 与第 7 节。要点复述一遍，因为它决定这份文档怎么被引用：**本地模拟器通过 ≠ BCB homologação 通过**。

**2026-09-20 更新——本节原先的说法已有两处被证据推翻，不要再按旧版引用：**

- **TLS 与 cipher 不再是「未验证」。** 已取得 *Manual de Segurança do Pix* **v3.7**，§2 要求 「TLS versão **1.2 ou superior**」与最低套件 **ECDHE-RSA-AES-128-GCM-SHA256 (0xc02f)**。两条路由已改为 `enabledProtocols("TLSv1.2,TLSv1.3")`，并有 `TlsProtocolNegotiationTest` 覆盖。详见 HANDOFF **§7.2**。**仍未验证的是**：对 BCB 真实端点的握手（`dict.pi.rsfn.net.br` 无公网 A 记录，无法探测）、BCB 的 ICP-Brasil v10 证书链，以及**主机名校验缺失**——后者已重新归类为真实缺陷而非未知项。
- **mTLS 私钥「必须可导出」的机制已实测。** 不再是推断：Netty 的 `PemPrivateKey.toPEM` 调用 `getEncoded()`，为 null 时抛 `does not support encoding`，且其唯一调用方就是 OPENSSL 路径。`MtlsNonExtractableKeyTest` 在 CI 中固化了这一事实，HANDOFF **§7.1** 给出四条补救路径与排序。
- 仍然**未验证**且不要猜：SPI `MsgDefIdr` 与 XSD 版本（HANDOFF §7.3）。

### 历史记录：首次全绿与 kms 红叉（2026-09-19）

彼时状态：[run 35458232223](https://github.com/RadiumGu/pix-proxy-samples/actions/runs/35458232223)（提交 `50e61bc`）5 个 job 全绿；自 `13349dc` 修好唯一长期失败的 `kms + simulator` job 起（[首次全绿 run 35456018649](https://github.com/RadiumGu/pix-proxy-samples/actions/runs/35456018649)），连续 9 次推送 5/5 success。该 job 此后随 KMS 一并移出维护 CI。

`proxy/core` 的测试数在 2026-09-19 的独立复核中从 **5 增至 14**（`XmlSignerSecureValidationTest` 4 个、`XmlSignerExpiredCertificateTest` 2 个、`WellKnownTestCertificatesTest` 3 个，见第 7 节），2026-09-20 再增至 **16**（`XmlSignerNotYetValidCertificateTest` 2 个，覆盖此前可达但无测试的证书轮换分支）。另有 `proxy/test` 的 28 个测试（**已过时**：当前为 core 38 + proxy/test 38，见下方更新说明）（11 个透明代理契约 + 17 个模拟器 v2 策略），由新增的 `dict-v2-contract` job 执行。7 个 reactor 模块全部编译通过。

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

### ⚠️ CloudHSM 路径已不可部署（2026-09-19 复核发现，本文档此前未提）

**按 `README-CloudHSM.md` 走，你会卡在第一步——创建集群。** 这不是代码缺陷，是外部时间线已经走过去了，三条事实叠起来把这条路封死：

| 事实 | 来源 |
|---|---|
| **2025 年 4 月起无法新建 `hsm1.medium` 集群** | [AWS 弃用公告](https://docs.aws.amazon.com/cloudhsm/latest/userguide/compliance-dep-notif.html) |
| `hsm1.medium` **已于 2026-03-31 结束支持**（该日期已过） | 同上 |
| 唯一可新建的 `hsm2m.medium` **要求 Client SDK 5.9.0+** | [HSM 机型页](https://docs.aws.amazon.com/cloudhsm/latest/userguide/hsm-types.html) |

而本仓库的代码**硬绑在 SDK 3** 上（`com.cavium.cfm2.LoginManager`、`PARTITION_1`、`key_mgmt_util`、`cloudhsm-client-jce-latest.el7.x86_64.rpm`）。所以不是「能跑但用了旧 SDK」，而是：**旧机型已经建不出来，新机型这份代码连不上。** AWS 的迁移指引也明说迁到 hsm2m「必须升级到最新版 client SDK」。

要真正跑起来 CloudHSM 这一半，需要的是**把 SDK 3 重写成 SDK 5**（API 完全不同），并且同时把 JDK 抬到 17/21（SDK 5 的 JCE 只支持 OpenJDK 17/21/25）——而第 4.1 节已实测 JDK 17 下本项目**运行期必炸**，除非补上那两个 `--add-exports`。这三件事是一个包，不能分开做。

**KMS 那一半不受此影响**（`README-KMS.md`，无 CloudHSM 依赖），代价是签名密钥放在 KMS 而非机构独占的 HSM 分区里。若你只是想看这个示例怎么跑，走 KMS 路径。

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

**订正（复核自身的一处错误）**：上一版这里写「`cloudhsm` 模块编译未覆盖，因为 `cavium` 要 x86_64 rpm 且本机 aarch64 无 root」。**那是没试就下的结论，实测是错的。** 全仓 **7 个 reactor 模块在 aarch64、无 root、无 Docker 下全部编译通过**：

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

三个原因让它成立，都值得记下来：`rpm` 4.16.1.3 是 Amazon Linux 2023 自带的（解包不需要 root）；那个 `.el7.x86_64.rpm` 里真正被取用的只有 `/opt/cloudhsm/java/cloudhsm-<ver>.jar`，而**该 jar 里 `.so`/native 条目数为 0**，是纯 Java、与架构无关；`netty-tcnative` 的 `linux-x86_64-fedora` jar 也照常下载并参与编译。**所以架构限制是运行期的，不是构建期的**——Graviton 上 `mvn package` 会成功，跑起来才会缺 native 库，这个先绿后炸的顺序正是它容易被漏掉的原因。

顺带得到一个 A13（构建不可复现）的实测锚点：**2026-09-19 这天 `latest` 解析到的是 `cloudhsm 3.4.4`**（`[echo] cloudhsm version: 3.4.4`）。换一天构建可能是别的版本，而消费方用的是开区间 `[3.0.0,)`。

**本次复核确实未覆盖**（真正的硬约束）：Tier 2 端到端（需 AWS 账号 + CloudHSM 集群）、多 HSM 故障转移（需 ≥2 HSM 集群并在运行中替换其中一个）。

---

## 5. 本 fork 没有修的部分（必读）

这些**都还在**，交付时必须一并说明：

| 类别 | 仍存在的问题 |
|---|---|
| **密钥与合规** | mTLS 私钥**必须可导出**才能用（Netty 的 `SslProvider.OPENSSL` + `keyManager(PrivateKey,…)` 需要真实密钥字节），因此不满足"私钥始终处于机构独占控制之下"的最严要求。签名私钥不受影响（不可导出）。**注意：mTLS 私钥无法伪造交易，伪造交易需要签名私钥** |
| **SDK 代际（已升级为阻断项）** | 用的是 **CloudHSM Client SDK 3**（`com.cavium`、`key_mgmt_util`、`PARTITION_1`、`cloudhsm-client-jce-latest.el7` rpm）。当前是 **SDK 5**，API 完全不同。SDK 5 的 JCE 只兼容 OpenJDK 17/21/25——**与本项目的 JDK 11 及那两个内部包冲突**（见第 4.1 节），所以升级 SDK 与升级 JDK 必须一起做，而且后者已实测会炸 |
| **HSM 会话** | 会话失效**无重连机制**（只在启动时登录一次，`R:175`）。表现为"跑几天后所有签名失败、重启就好"。SDK 5 已内建改进的登录状态管理 |
| **HSM 机型（本文档原先严重低估）** | 原文只说 `hsm1.medium` 的 FIPS 证书 #4218 已于 2026-01-04 移入 CMVP 历史列表、应改用 `hsm2m.medium`。**这个说法本身没错但远不够**——按 AWS 自己的[弃用公告](https://docs.aws.amazon.com/cloudhsm/latest/userguide/compliance-dep-notif.html)与[机型页](https://docs.aws.amazon.com/cloudhsm/latest/userguide/hsm-types.html)：①**2025 年 4 月起就无法新建 `hsm1.medium` 集群**；②`hsm1.medium` 已于 **2026-03-31 结束支持**（该日期已过）；③2026 年 1 月起 AWS 开始把存量 hsm1 集群**自动迁移**到 `hsm2m.medium`；④`hsm2m.medium` **要求 Client SDK 5.9.0 及以上**。`hsm2m.medium` 的证书是 [#4703](https://csrc.nist.gov/projects/cryptographic-module-validation-program/certificate/4703)（FIPS 140-3 L3）。**净结论见下面的「⚠️ CloudHSM 路径已不可部署」** |
| **输入校验** | 无 ISO 20022 **XSD 模式校验**（全仓零 `SchemaFactory` / `setSchema` / `.xsd` 校验代码）。报文大小上限**未显式配置**——但**并非没有上限**：实际生效的是 Camel netty-http 的默认 `chunkedMaxContentLength=1048576`（1 MB），由服务端管道里的 `HttpObjectAggregator` 施加（`camel-netty-http-3.4.2` 的 `HttpServerInitializerFactory` 字节码实证）。所以风险不是「可 OOM」，而是**这个上限是隐式的**：既没写进配置也没写进文档，调高它或改动端点配置的人不会意识到自己在放大攻击面 |
| **TLS** | 未启用主机名校验（靠显式信任 BACEN 证书即证书锁定缓解）；`bcbEndpoint` 未显式配置超时 |
| **证书生命周期** | 无到期监控；配置只在启动时读取，**换证书必须重新部署** |
| **审计与隐私** | 审计日志含报文全文，即含姓名、CPF、账号、金额——属 **LGPD** 管辖的敏感数据，示例零处理（`AuditLog.java:34-35,50-51` 原样存 `request_body` / `response_body`；随仓库的测试报文里就有 `<Nm>Fulano da Silva</Nm>` 和 11 位 `<Id>`）。S3 未配加密 / Object Lock / 版本控制（**Object Lock 只能建桶时启用**）。**订正**：`errorOutputPrefix` 其实**两个 README 的两条流都配了**（`README-CloudHSM.md:363,372`、`README-KMS.md:140,149`），本文档此前写「未配」是错的。真正缺的是**针对那个已经存在的 `error/` 前缀的告警**——全仓零 CloudWatch 告警配置，所以投递失败的记录会静静堆在 `error/` 下而没有任何人知道 |
| **可观测性** | 无指标、无追踪、无告警。验签失败不区分"签名不匹配"（安全事件）与"证书过期/配置错误"（运维故障） |
| **架构** | HSM 客户端与应用同容器（`Dockerfile:4-9` 在跑应用的同一镜像里 `yum install` 三个 CloudHSM rpm）；依赖两个 **JDK 内部包**——**已实测**：JDK 11 运行期可用（靠默认 `--illegal-access=permit`），**JDK 17 编译通过但运行期 `IllegalAccessError`，每笔签名都炸**，见第 4.1 节；`netty-tcnative:2.0.31.Final` 的 classifier 是 **`linux-x86_64-fedora`**（`cloudhsm/proxy/pom.xml:99-104`）——不只锁 x86_64，还锁到 Fedora/RHEL 系的 OpenSSL，且 `Dockerfile` 装的三个 rpm 同样是 `el7.x86_64`，**不能直接上 Graviton**。**注意这是运行期限制而非构建期**：实测 aarch64 上 `mvn package` 全绿（见第 4.1 节），缺 native 库要到运行时才暴露；Quarkus 1.7.0 / Camel-Quarkus 1.0.0 均为 2020 年版本（`proxy/pom.xml:17-18`，另 AWS SDK BOM 2.13.0、Lombok 1.18.12 亦同期） |
| **构建可复现性** | 两处合起来导致不可复现：`cavium/pom.xml:32` 每次构建都拉 `cloudhsm-client-jce-latest.el7.x86_64.rpm`，再用 antrun 正则从 jar 文件名反推 `cloudhsm.version` 并 `install-file` 成 `com.cavium:cloudhsm:${cloudhsm.version}`；消费方 `cloudhsm/proxy/pom.xml:75` 用**版本区间** `[3.0.0,)` 接它（注意区间在 **proxy** 模块而非 cavium 模块，本文档此前把两者都记在 cavium 名下）。净效果是**构建产物取决于你构建那天 AWS 在 `latest` 上放了什么**。实测锚点：**2026-09-19 解析到 `cloudhsm 3.4.4`** |
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
| 无指标 / 追踪 / 告警 | 全仓零 `Micrometer` / `MeterRegistry` / `OpenTelemetry` / `X-Ray` / `putMetricData`，两个 README 里零 CloudWatch 告警配置（唯一的 SNS 提及是开头讲「互补架构」的段落，不是告警） |
| HSM 客户端与应用同容器 | `proxy/cloudhsm/proxy/src/main/docker/Dockerfile:4-9` 在 `FROM amazoncorretto:11` 的同一镜像里 `yum install` 三个 CloudHSM rpm，第 22-23 行再把 `wrapper_script.sh` 与 `application.jar` 拷进去 |

**本机无法验证**（硬约束，非遗漏）：多 HSM 故障转移需 ≥2 HSM 集群；S3/Firehose/LGPD 与可观测性各项属部署期配置，需实账号。（`cavium` 模块的编译**已验证通过**，见第 4.1 节的订正——此前以为它需要 x86_64 主机，实测不需要。）

---

## 6. 上游状态（为什么不等合并）

上游自 2024-10-17 起无实质提交。可观察到的模式：**dependabot 的 PR 被合并，人类的 PR 被关闭**——

- [PR #4](https://github.com/aws-samples/pix-proxy-samples/pull/4)（修 RSA 指数，两行）：创建 2021-08-16，关闭 2024-10-17，**挂了 1158 天（3 年 2 个月）**，`merged=false`，`comments=0` + `review_comments=0`（零评论）；
- [PR #10](https://github.com/aws-samples/pix-proxy-samples/pull/10)（修 CVE-2024-47554）：提交者 `llins` 是**本仓库贡献最多的人**——23 次提交，多于任何其他人（第二名人类 `joaoarag` 3 次，dependabot 10 次）。该 PR 被关闭未合并，而 dependabot 提的同一修复 [PR #12](https://github.com/aws-samples/pix-proxy-samples/pull/12) 在此之前 **19 秒**被合并（`#12 merged_at=2024-10-17T20:22:48Z`，`#10 closed_at=2024-10-17T20:23:07Z`）。本文档此前写「一分钟前」，实测是 19 秒；也曾把提交者描述为「原博客作者之一」，那个说法无法从 GitHub 核实，已换成可核实的贡献者排名。

**「无实质提交」可以说得更准**：上游 `master` 顶点就是 `fa20042`（2024-10-17），**此后零提交**；而 2024-10-17 那天的 5 个提交**全部是 dependabot 的版本升级与其合并**。最后一次非 dependabot 的提交是 **2022-02-03 的 `02985fdb` "xml parsing fix"**——距今 4 年 7 个月。

上报的 5 个 issue 现状（2026-09-19 实测）：[#15](https://github.com/aws-samples/pix-proxy-samples/issues/15)–[#19](https://github.com/aws-samples/pix-proxy-samples/issues/19) **全部存在且 state=open**，标题与第 1 节的 5 项缺陷一一对应。

因此本 fork 不等上游合并。5 个缺陷已作为 [#15](https://github.com/aws-samples/pix-proxy-samples/issues/15)–[#19](https://github.com/aws-samples/pix-proxy-samples/issues/19) 上报，若上游采纳，本 fork 会同步。

---

## 7. 独立审计新发现（不属于原来那 5 项）

以下是 2026-09-19 独立复核时**自己读代码找出来的**，不是 fork 原先上报的 5 个缺陷之一。provenance 分开记，是为了让读者能区分「fork 声称修了什么」与「复核另外发现了什么」。

### 7.1 `Iso20022URIDereferencer` 读了一个没人设置的开关

`Iso20022URIDereferencer:40` 把 `org.jcp.xml.dsig.secureValidation` 从 crypto context 取出来，喂给 `XMLSignatureInput#setSecureValidation`：

```java
result.setSecureValidation(secureValidation(context));          // :40
private boolean secureValidation(XMLCryptoContext ctx) {        // :56
    return ctx == null ? false : getBoolean(ctx, "org.jcp.xml.dsig.secureValidation");
}
```

而**全仓没有任何地方设置这个属性**——`XmlSigner:276` 只是 `new DOMValidateContext(keySelector, signatureNode)`。所以它恒为 `false`，自定义解引用器会在它自己构造的 `AppHdr` / `Document` 节点集上**关掉** secure validation。这是「写了但没人读」的镜像版本：**读了，但没人写**。

**修法**：`XmlSigner.getValidateContext` 显式 `setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE)`，让解引用器看到的值与 JDK 自身的姿态一致。CloudHSM 与 KMS 两种架构共用同一个 `XmlSigner.verify()`，所以一处修复覆盖两边。

### 7.2 一个**没有**成立的推论（写下来，免得别人再推一遍）

我最初的假设是「secure validation 整体是关的，所以攻击者能让验签方去解引用 `file:` / `http:` 引用」——**实测推翻了它**。在 Corretto 11.0.32 上对同一份携带 `file:` 引用的签名文档做三态对照：

| `org.jcp.xml.dsig.secureValidation` | 结果 |
|---|---|
| **不设置** | ❌ 拒绝：`URI file:... is forbidden when secure validation is enabled` |
| 显式 `TRUE` | ❌ 拒绝（同上） |
| 显式 `FALSE` | ✅ **通过**，且那条 `file:` 引用被真实解引用（`ref[1] valid=true`） |

即 `DOMValidateContext` 的**默认就是开启**。所以本项目**从未**暴露「攻击者指定路径被解引用」这个风险，7.1 是一致性修复而**不是**补一个可利用漏洞。如果没做这个三态对照，就会把一个 no-op 当成安全修复推上去——这也是为什么下面那个测试里保留了阳性对照。

### 7.3 新增回归测试

`XmlSignerSecureValidationTest`（4 个方法，core 测试数 5 → 9）：

- `validateContextCarriesSecureValidationTrue` —— 捕获真实的 validate context，断言解引用器读到的那个属性为 `TRUE`；
- `secureValidationRejectsFileUriReference` —— 携带 `file:` 引用的签名必须被拒；
- `explicitlyDisablingSecureValidationWouldAcceptFileUriReference` —— **阳性对照**：显式设成 `FALSE` 后同一文档通过，证明上面两条不是空断言，并把「改成 false 的代价」钉在测试里；
- `normallySignedDocumentStillVerifies` —— 正常签名不受影响。

### 7.4 两个既有修复互相打脸：证书过期时审计记录会整体丢失（已修）

这是本轮最有价值的一条，因为它**不是上游的缺陷，而是这个 fork 自己的两个修复相互作用产生的**。

- 修复 4（上游 #18）的目的：审计写入失败**不能**让交易失败。
- 修复 5（上游 #19）的手段：证书有效期问题**抛异常** `CertificateValidityException`，而不是返回 `false`。

问题在于两条路由里验签都排在审计写入**之前**：

| 架构 | 顺序 | 出处 |
|---|---|---|
| CloudHSM | `…→ VerifyResponseProcessor → LogRequestResponseProcessor` | `PixCloudHSMProxyRouteBuilder:112-119` |
| KMS | `signer.verify(response); logger.log(request, response);` | `ProxyHandler:23-24` |

所以 BACEN 签名证书一过期，`verify()` 抛出的异常会**中止整条路由 / 让 Lambda handler 异常退出**，审计写入那一步根本执行不到——**一条审计记录都不会落**。而且过期是持续状态：在有人轮换证书之前，**每一笔**交易都既失败又无审计。修复 4 拦住了「审计故障毁掉交易」，这里是反方向的同一类问题：「验签故障毁掉审计」。

**修法**（两个调用点都改，因为它们喂同一张 Glue 表）：在验签调用点捕获 `CertificateValidityException`，交易仍按 500 失败（响应确实不可信），但把原因写进审计字段并让流程继续走到审计写入。新增第三个取值 `PixConstants.SIGNATURE_VALID_CERTIFICATE_ERROR = "certificate-validity-error"`，与 `"true"` / `"false"` 并列——Glue 列 `response_signature_valid` 本就是 STRING，**不需要改 schema**（`audit-schema` 门禁复跑通过）。

- `VerifyResponseProcessor`（CloudHSM）
- `Signer.verify`（KMS）

**取证**：新增 `XmlSignerExpiredCertificateTest`（2 个方法，core 测试 9 → 11）。用 openssl 造了一张有效期为 2020-01-01 → 2020-02-01 的已过期自签证书夹具 `expired-cert.p12`，先断言夹具**确实**已过期（否则整个测试什么都没测），再断言 `verify()` **抛** `CertificateValidityException` 且 cause 是 `CertificateExpiredException`。

**诚实交代边界**：上面那两条「异常会中止路由 / handler」是**读路由与 handler 源码**得出的（行号已给），不是在真实 Camel 上下文与 Lambda 运行时里跑出来的——本机没有起 Camel 路由与 API Gateway 的条件。异常确实会从 `verify()` 抛出这一点，是**实测**的。

### 7.5 仓库里带着私钥的「BACEN」证书，会被文档指引写进生产同一个参数（已加守卫）

`proxy/test/src/main/docker/ssl/` 里同时放着 `sig.cer` / `mtls.cer` **和它们的私钥** `sig.key` / `mtls.key`。实测这两个 `.key` 是**真的 RSA 2048 私钥**（`openssl pkey` 解析出 `Private-Key: (2048 bit, 2 primes)`），且与各自证书**公钥指纹一致**（配对成立）。两张证书的主体是：

```
C=BR, ST=DF, L=Brasilia, O=BCB, OU=PIX, CN=*.pi.rsfn.net.br
notBefore=Jul  5 2020   notAfter=Jul  3 2030
```

即 **BACEN 真实生产域 RSFN 的通配主体，有效期到 2030 年**。作为模拟器，这本身没问题。**有问题的是文档把它们写进生产用的同一个参数**：`README-CloudHSM.md:469`/`502` 与 `README-KMS.md:201`/`234` 创建 `BcbSignatureCertificate` 与 `BcbMtlsCertificate` 时，各自带一段「TO USE THE TEST - SIMULATOR, use:」并给出上面这两张证书的内容。

于是从模拟器切到真 BACEN 的动作，等于「**记得改那个参数**」。忘了改的后果是：代理会接受由一把**公开在互联网上的私钥**签出的响应，而系统里**没有任何东西会察觉**——证书格式正确、2030 年前都在有效期内、并且按定义就在信任库里，所以连修复 5 新加的有效期检查也保持沉默。`.gitignore` 也没有覆盖这些文件，文档里零提醒。

**已加的守卫**：新增 `WellKnownTestCertificates`，按 **SHA-256 指纹**识别这两张证书（`sig.cer` = `2ECA12B3…92F3`，`mtls.cer` = `8F43D131…C274`），命中就打一条 ERROR 日志，点名「正在信任一张私钥公开的模拟器证书，若本进程要对接真 BACEN，请立刻替换参数」。

两个设计决定值得说明：

- **按指纹而不是按主体匹配**。主体就是 BACEN 的真实 DN，按主体匹配会把**真**证书也一起报警，守卫就废了。
- **只告警，不拒绝启动**。模拟器流程是文档正式支持的用法，硬失败会直接打断 README 指引的路径。请在任何「本该对接真 BACEN」的环境里对这条日志配告警。

挂载点选在 `KeyStoreUtil.getCertificates(String)`：`generateTrustStore(String,String)` 处理 `BcbSignatureCertificate` 时会委派到它，mTLS 路径处理 `BcbMtlsCertificate` 时直接调它——**一处覆盖两种架构的两条信任路径，且绕不过去**。

**取证**：新增 `WellKnownTestCertificatesTest`（3 个方法，core 测试 11 → 14），含**阴性对照** `doesNotFlagAnUnrelatedCertificate`——若守卫对无关证书也报警，它在真实环境里就会被当噪音忽略，所以这条对照和阳性断言一样重要。

### 7.6 部署步骤审计：一条缺失的 IAM 权限、一个参数类型陷阱、一条从未指向任何地方的链接（均已修）

逐条把两个 README 的部署步骤与代码实际读取/调用的东西对账。**先说没问题的部分**：12 个 `/pix/proxy/cloudhsm/*` 参数名与 `Param` 枚举**逐一对上**，KMS 侧 9 个参数与 `MtlsPrivateKey` secret 也都有创建步骤，`CloudHSMSecret` 的 JSON 键名（`HSM_USER` / `HSM_PASSWORD`）与 `PixCloudHSMProxyRouteBuilder:171-172` 读取的一致。三处问题：

**① `cloudhsmv2:DescribeClusters` 不在 IAM 清单里（会卡在启动前）**

`wrapper_script.sh:33` 在容器启动时执行 `aws cloudhsmv2 describe-clusters` 来发现 ACTIVE HSM 的 IP。这是一条 **IAM** 权限，而 README 原清单里与 CloudHSM 相关的那一条指向的是[安全组配置](https://docs.aws.amazon.com/cloudhsm/latest/userguide/configure-sg.html)——那是网络可达性，不是 IAM。缺这条权限时容器**在 JVM 启动之前就退出**。已加进 `README-CloudHSM.md` 的权限清单并写明它与安全组那条的区别。

**② 参数建成 `SecureString` 会让启动失败，而文档从未说过要用 `String`**

两处 `getParametersByPath` 都没设 `withDecryption`。SecureString 参数在不解密时返回的是密文，密文随后被喂进 `CertificateFactory` → 启动失败，且报错完全不提「参数类型」。而「凡是与证书/密钥沾边的都用 SecureString」是很多组织的硬性规范。**已改为无条件 `withDecryption(true)`**（对普通 `String` 参数该标志被忽略，所以两种类型都能用），并在两个 README 注明：真用 SecureString 时任务角色还需要该参数 KMS 键的 `kms:Decrypt`。

**③ `README-KMS.md:31` 的链接是字面量 `xxx`**

原文「To learn how to generate a CSR …, see [here](xxx)」——`xxx` 就是链接地址本身，从来没指向任何地方（`git show upstream/master` 确认上游同样如此，非本 fork 引入）。已替换为可核实的去处：本示例签名用的就是 `aws-samples/aws-kms-jce`，它的 `kms-jce-util` 模块里有 `CsrGenerator.generate(keyPair, csrInfo, kmsSigningAlgorithm)`（以及配套的 `SelfSignedCrtGenerator`）。顺带补一条权限精度：生成 CSR 需要构造 `KeyPair`，走的是 `KmsRSAKeyFactory.getKeyPair(kmsClient, keyId)` → 需要 **`kms:GetPublicKey`**，属**部署准备期**权限；而运行期的代理只调 `KmsRSAKeyFactory.getPrivateKey(keyId)`，读源码确认它只是构造一个引用、**不联系 KMS**，所以运行期只需 `kms:Sign`。这一点已写进 README，免得有人按「签名」二字给出过宽的权限。

**一处查了但不成立的怀疑**：曾怀疑 KMS 侧 IAM 清单漏了 `kms:GetPublicKey`。读 `aws-kms-jce` 源码后否决——本项目只调 `getPrivateKey`，而 `KmsRSAKeyFactory:46-48` 的实现是 `new KmsRSAPrivateKey(keyId)`，没有任何 KMS 调用。原清单在运行期语义上是准确的。

### 7.7 复核确认的一处**设计取舍**，不是缺陷

第 1 节说缺陷 5 的症状是「每笔交易变 500」。读代码确认：修复后**500 依然存在**——`XmlSigner.verify()` 对证书有效期问题抛 `CertificateValidityException`（RuntimeException），而 `VerifyResponseProcessor:27` 只在 `verify()` 返回 `false` 时设 500，异常则直接冒泡成 Camel 错误。所以这个修复改变的是**可诊断性**（日志明确说"证书轮换问题，不是签名不匹配"）而**不是** HTTP 结果。文档的表述容易被读成"修了就不 500 了"，实际不是；真正要停掉 500 需要业务决策（过期证书是否拒绝交易），属第 5 节「必须由合规裁定」那一类。

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
