# 用 CloudHSM 实现 Pix 报文安全传输的教学架构

[![build](https://github.com/RadiumGu/pix-proxy-samples/actions/workflows/build.yml/badge.svg)](https://github.com/RadiumGu/pix-proxy-samples/actions/workflows/build.yml)
[![License: MIT-0](https://img.shields.io/badge/License-MIT--0-blue.svg)](LICENSE)

**[English version / 英文版本](README.md)**

> ## ⚠️ 本分支维护的范围
>
> 本分支**只**维护 AWS CloudHSM 这条路径:XML 数字签名、mTLS、CloudHSM 客户端与容器集成、透明 HTTP 代理。它是 Pix 集成中**传输与密码学层**的教学骨架——**不是一个完整的 Pix PSP 实现**。
>
> 明确不在范围内:支付发起、入向 SPI 报文、清算与对账、MED 2.0 / 资金追回、欺诈标记、事件通知、Pix Automático、退款业务流程、授权、流动性管理、欺诈判定,以及运营 SLA。这些属于受 BCB 现行规则约束的独立 PSP 领域服务。
>
> **`proxy/kms` 架构属于历史遗留,在本分支中不受支持。** 它保留在代码树里仅供参考。维护中的 BCB DICT v2 边界见 [`README-CloudHSM.md`](README-CloudHSM.md),已验证的修复与遗留限制见 [`VERIFICATION.md`](VERIFICATION.md)。

### ***你可以克隆、修改、运行它,但*不应将其作为金融机构与 PIX(SPI 与 DICT)最终集成的基础*。***

本项目包含用于演示「对发往巴西即时支付系统(PIX)的报文做数字签名并安全传输」的源码与配套文件。维护中的 CloudHSM 架构是一个与巴西央行(BACEN)通信的**代理**。该代理的设计意图是把 **AWS CloudHSM** 作为每一笔交易**直接且强制**的通路,目标有三:

```text
- 建立带双向认证(mTLS)的 TLS 隧道
- 对 XML 报文签名
- 将请求日志送入数据流
```

AWS CloudHSM —— **维护中的教学路径** | AWS KMS —— **历史遗留 / 不受支持** |
:-:|:-:|
<img src="/images/hsm.jpg" width="100" height="100">|<img src="/images/kms.jpg" width="100" height="100">|
[CloudHSM 范围与部署](README-CloudHSM.md)|[仅供历史参考](README-KMS.md)|

## 我该读哪份文档?

本仓库的多个 markdown 文件是在不同时期形成的,容易混淆。下面列出全部文件及各自的**用途**,这样你不必逐个打开去猜。

| 文档 | 它是什么 | 什么时候读 |
|---|---|---|
| **`README.md`** / **`README.zh-CN.md`**(本文) | 入口与范围声明:本分支维护什么、刻意不做什么 | 从这里开始 |
| [`README-CloudHSM.zh-CN.md`](README-CloudHSM.zh-CN.md) | 架构文档的中文版。`README-CloudHSM.md` 仍是权威副本——要更正就改它,`doc-parity` 作业负责让两边保持同步 | 你需要架构与实测数据但读中文 |
| [`README-CloudHSM.md`](README-CloudHSM.md) | **维护中的路径。** 架构详解、BCB / TLS / JDK / CloudHSM 的版本要求、完整 AWS 部署步骤、以及如何运行每一项检查 | 你要部署,或需要知道 BCB 在链路上要求什么、哪些版本能满足 |
| [`README-KMS.md`](README-KMS.md) | **历史遗留 / 不受支持。** 早期的 AWS KMS 变体,仅供参考 | 仅用于了解历史背景。它不被维护、不在 CI 中,不得作为基线 |
| [`PIX_CLOUDHSM_ASSESSMENT.zh-CN.md`](PIX_CLOUDHSM_ASSESSMENT.zh-CN.md) | 评估文档的中文版。`PIX_CLOUDHSM_ASSESSMENT.md` 仍是权威副本,`doc-parity` 负责让成本数字与实测数据保持同步 | 你要决定规模与预算但读中文 |
| [`PIX_CLOUDHSM_ASSESSMENT.md`](PIX_CLOUDHSM_ASSESSMENT.md) | 面向决策者的验证评估:在真实 CloudHSM 硬件上测到了什么、推荐哪条传输路径及为何排除其余、双 HSM 可用性发现、以及上生产前的开放关卡 | 你在判断是否以及如何采用这套方案,而不是在实现它 |
| [`VERIFICATION.md`](VERIFICATION.md) | 独立验证记录:复现了哪些缺陷、修了哪些、每个修复用什么测试过、哪些仍是限制 | 你想要某个主张背后的证据,而不是主张本身 |
| [`VERIFICATION.en.md`](VERIFICATION.en.md) | 同一份记录的英文版。`VERIFICATION.md` 仍是权威副本——要更正就改它。CI 的 `doc-parity` 作业会在标题、命令或任何测量值两边分歧时让构建失败 | 你需要这些证据但不读中文 |
| [`CLOUDHSM_ADD_HSM_FAQ.md`](CLOUDHSM_ADD_HSM_FAQ.md) | 面向客户的新增 HSM 问答:在新 HSM 加入期间创建的密钥、用户、mTLS 信任锚各会怎样。全部真机实测,附命令原始输出 | 客户问扩容期间正在进行的变更会怎样 |
| [`CLOUDHSM_BCB_V2_HANDOFF.zh-CN.md`](CLOUDHSM_BCB_V2_HANDOFF.zh-CN.md) · [English](CLOUDHSM_BCB_V2_HANDOFF.md) | 工程交接:外部 BCB 基线、传输契约的证据表、需要继续做的工作,以及**明确尚未解决的 homologação 关卡** | 你要接手这项工作,或需要一份诚实的「哪些尚未证明」清单 |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 贡献与安全问题上报流程 | 报告问题或提 PR |
| [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) | 项目行为准则 | 参与本项目 |

**如果本页之外你只读一份东西**,请读 [`CLOUDHSM_BCB_V2_HANDOFF.md`](CLOUDHSM_BCB_V2_HANDOFF.md) 的第 7 节。它列出了本仓库**尚未**证明的内容——包括 CloudHSM 这条路径**无法按现状部署**,因为代码面向 CloudHSM Client SDK 3,而 `hsm1.medium` 已于 2026-03-31 终止支持,唯一可创建的实例类型需要 SDK 5.9.0+,后者又需要 JDK 17+。测试套件通过**不等于** BCB homologação 通过。

## 它是怎么工作的,以及为什么「透明」就是整个设计

机构应用发出的 Pix 请求不会直接到 BCB,而是先到这个代理——它是每笔交易的强制通路。代理对请求做了什么,按顺序如下,这就是**生产路由的实际执行顺序**:

```
  机构应用
        │  VPC 内明文 HTTP
        ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │ 1. 注册 onCompletion 审计钩子   ← BCB 那一腿失败时同样会执行     │
  │ 2. 拒绝被压缩的请求体(415)                                     │
  │ 3. 将请求体转为字符串                                           │
  │ 4. 对 XML 签名  ── 私钥始终留在 CloudHSM 内 ────────────────────┼──▶ CloudHSM
  │ 5. 捕获请求内容以生成审计记录                                   │
  │ 6. 发往 BCB  ── mTLS,客户端密钥同样在 CloudHSM 内 ─────────────┼──▶ BCB / RSFN
  │ 7. 解码响应(gzip/deflate)后转为字符串                         │
  │ 8. 验证 BCB 对响应的签名                                        │
  └─────────────────────────────────────────────────────────────────┘
        │
        ▼  审计记录 → Firehose(异步,带一个已 fsync 的本地磁盘回退)
```

**私钥从不离开 HSM。** 代理持有的是**句柄**而非密钥材料——实测:JCE 对象是 `CloudHsmRsaPrivateCrtKey`,其 `getEncoded()` 返回 `null`。签名发生在 FIPS 边界内部,代理只是发起请求。

<p align="center">
  <img src="/images/proxy-cloudhsm-arch.png" width="620" alt="CloudHSM 代理架构:应用只能经代理到达 BCB,代理用 CloudHSM 集群中的密钥签名,并将审计记录送往 Firehose">
</p>

**透明性是正确性要求,不是锦上添花。** XML 签名覆盖整份文档,所以签名之后、到达 BCB 之前的**任何**改动都会让签名失效;而入向侧的改动意味着**签错了字节**。本仓库查出的多数缺陷都源于此,这也是为什么要用一条源码级 CI 门禁把路由的端点选项钉住,而不是指望它们自己不变。必须原样通过代理的东西,每一项都有对应测试:

- 请求路径、查询字符串,以及**重复出现的**查询参数
- BCB 设置的响应头,包括 `Cache-Control`——一个标准头过滤器曾静默地把它丢掉,而它对 `getEntry` 限定了「密钥归属答案可以有多旧」,一个过期的答案意味着**付错账户**
- 请求体,逐字节一致——被字符串转换破坏的压缩请求体会被当作残骸签名,所以改为直接以 415 拒绝
- `ETag`,以及响应状态码,包括 `410` 与反扫描行为

改这段代码前值得知道的两条实测更正:`bridgeEndpoint=true` **并不**单凭自身就保留路径与查询;清除 `HTTP_QUERY` 也不足以去掉查询,因为 `HTTP_RAW_QUERY` 是第二个来源。

## 版本:应当瞄准什么,以及当前构建钉住了什么

第一张表是建议,第二张表取自 `proxy/pom.xml` 与 `.github/workflows/build.yml`,是构建实际使用的值。两者不同,而这个差额就是迁移的工作量。

### 新部署应当瞄准的版本

按 **Client SDK 5** 和当前 JDK 编写，因为 SDK 3 根本无法访问到任何可创建的 HSM 类型。本表中的每一项，要么是 AWS 的文档硬要求，要么是在此实测得出。

| Component | Target | Why this one |
|---|---|---|
| **CloudHSM Client SDK** | **5**（`cloudhsm-cli` + `cloudhsm-jce`，实测于 **5.18.0**） | SDK 3 不支持 `hsm2m.medium`，而这是唯一可创建的 HSM 类型。这不是偏好——而是硬性要求 |
| **Java** | **21**，下限 **17** | SDK 5 的 JCE provider 仅支持 OpenJDK **17、21 和 25**。21 是当前主流 LTS，Corretto 支持周期长；17 是下限，25 则比支付系统所需更新 |
| HSM type | `hsm2m.medium`，FIPS 模式 | 唯一可创建的类型；`hsm1.medium` 已于 **2026-03-31** 结束支持 |
| mTLS to BCB | JDK/JSSE provider + [`HsmX509KeyManager`](proxy/core/src/main/java/com/amazon/aws/pix/core/tls/HsmX509KeyManager.java) | 让客户端密钥保持**不可导出**。无法使用 `SslProvider.OPENSSL`：它需要 HSM 不会给出的密钥字节 |
| Cluster size | **3 个 HSM**，或在关闭可用性检查后用 2 个 | SDK 5 拒绝使用存在于少于两个 HSM 上的密钥。参见 [`README-CloudHSM.md`](README-CloudHSM.md) |

**SDK 5 有支持半衰期，所以这是一个日历条目，而非一项决策。** 从 SDK 5.17 起，AWS 仅支持*此前三个次要版本以及自发布起一年*，并会关闭更旧版本的下载链接。本仓库通过 SHA-256 固定其 rpm，这就把上述情况变成一个**定时**故障：当链接失效时，哈希仍然正确，而文件已不复存在。参见 [`README-CloudHSM.md`](README-CloudHSM.md) 中的运维日历。

### 当前构建实际钉住的版本

这是代码树的当前状态，不是建议。其中两项是当前的，其余都是 SDK 3 时代遗留下来的。

| Component | Pinned | Status |
|---|---|---|
| Java | **11**（CI 中为 `temurin`） | **低于目标。** SDK 5.17.1 是最后一个支持 OpenJDK 11 的版本。实测：整个 reactor 在 **JDK 17** 上同样能构建，且所有测试通过 |
| Lombok | **1.18.48** | **当前。** 1.18.12 在 JDK 17 上根本无法作为注解处理器运行——构建*编译*失败。下限为 1.18.22 |
| Jackson | **2.21.2**（LTS 线） | **当前。** 此前的 2.15.4 落在受 CVE-2026-59888 影响的版本范围内，该问题在 2.18+ 中修复 |
| Netty | 4.1.138.Final | 更早的固定版本带有请求走私告警（CWE-444）；4.1.118 仍存在 CVE-2025-58056 |
| netty epoll native | `linux-x86_64` **和** `linux-aarch_64` | 现已同时声明两者。若只声明其一，应用会在另一架构上启动即挂——已实测，在 JDK 11 和 17 上表现一致 |
| netty-tcnative | 2.0.84.Final，`linux-x86_64-fedora` **与** `linux-aarch_64-fedora` | **两个架构都有。** 名字的不对称是刻意的:实测 2.0.84.Final 上并**没有**发布 plain `linux-aarch_64`。它是 OpenSSL provider，**无法承载 BCB 的 mTLS 密钥**——OpenSSL 需要密钥字节，而 HSM 密钥没有 |
| Quarkus | **2.13.9.Final** | **第一段已完成，但仍不受支持。** 2.13 的社区维护于 **2022-11-07** 结束，且它从来不是 LTS；当前的 LTS 是 **3.33**（支持到 2027-03-25）。剩下的一段是 2.13 → 3.x，`quarkus update` 覆盖该区间，并会带来 `javax.*` → `jakarta.*` 改名 |
| Camel Quarkus | **2.13.3**（Camel **3.18.6**） | 这是实际存在的最高版本——camel-quarkus 没有发布过 2.13.4 及以后，所以把它与 Quarkus 2.13.9 配对，正是平台 BOM 自身所发布的组合，而非此处臆造。仍然带来 `camel-netty-http` 及其 HTTP/1.1 |
| CloudHSM SDK 5 | 5.18.0-1 rpm，经 SHA-256 校验 | **已是当前版本，不再是阻塞项。** 那四行 SDK 3 代码已移除，provider 改为 `CloudHsmProvider`。该 jar 不在 Maven Central 上，由 `cloudhsm/jce5` 从 rpm 中解包取得，并且是 **`provided`** 作用域——绝不打包进制品，因为它有代码签名且与架构绑定 |
| Node (alarms app only) | 22 | 用于 CDK 告警应用，在 Maven 构建之外 |

**为什么它仍然无法按现状部署。** 将这段代码绑定到 SDK 3 的那四行——两处 `com.cavium.cfm2` 导入、`new CaviumProvider()` 以及 `LoginManager.login("PARTITION_1", …)`——需要替换为它们的 SDK 5 等价物，而 SDK 5 需要 JDK 17 或更高版本。哪些**不是**障碍，是实测而非假设得出的：XML 签名路径在 SDK 5 上无需改动即可运行（用 HSM 密钥签署了一条真实的 ISO 20022 报文，并验证了签名）；不可导出的 mTLS 密钥可以工作；整个 reactor 在 JDK 17 上构建并测试全绿。此次迁移是四行 provider 接线加上一次本就该做的框架升级——而不是重写签名逻辑。

## 审计路径,以及代表「记录有风险」的四个信号

一个已签名、已送达 BCB 却没有留下任何记录的请求,是这个代理能产生的最坏结果,所以审计路径被设计成**响亮地失败**而不是安静地失败。

审计写入注册为 `onCompletion` 钩子,它在**成功与失败时都会执行**。这个位置很关键:BCB 那一腿的传输或 TLS 失败会中止整个 exchange,所以放在路由末尾的处理步骤根本不会执行——于是那个**已经被签名、已经被发出**的请求不留任何痕迹。注意 `throwExceptionOnFailure(false)` 覆盖不了这种情况:它压制的是 HTTP 错误状态码,而这类失败发生在 HTTP 之下。

投递是异步的,以便把 Firehose 从调用方的延迟路径上移开,配有一个有界队列和一个持久回退。该回退以 `DSYNC` 打开——因为一个「字节进入页缓存就返回」的所谓持久 spool,对它唯一要对付的崩溃场景来说根本不是回退。

四个稳定的日志标记覆盖了记录丢失的每一种方式,`alarms/` 里的 CDK 应用把每一个都变成一条 CloudWatch 告警:

| 标记 | 含义 |
|---|---|
| `PIX_AUDIT_SPOOLED` | 投递失败,记录进入磁盘回退。可恢复,但应尽快导出并截断 spool |
| `PIX_AUDIT_QUEUE_FULL` | 异步队列已满。溢出转入 spool,而不是阻塞调用方 |
| `PIX_AUDIT_NO_RECORD` | 某次 exchange 完全没有产生审计记录 |
| `PIX_AUDIT_SPOOL_WRITE_FAILED` | **审计数据已丢失**——既未投递也未落盘。任何非零值都是真实损失 |

告警还用 `treatMissingData: BREACHING` 监视 Firehose 本身,这是刻意的:一条本该持续承载审计记录的流「无数据」**就是**故障——容器死了、投递停了、或者流被删了。指标过滤器类告警取相反设置,理由也相反:那些指标只在已经出问题时才产生数据点。

**告警已定义、已测试、可合成,但未部署。** 没有任何栈被推送到账户里。这是一个显式的缺口,不是含糊暗示的能力。

## 规划集群规模之前:双 HSM 不是冗余配置

这一条放在首页,因为它是很早就要做的架构与成本决策、结论违反直觉,而且做错的后果是**彻底中断**而不是性能下降。

CloudHSM Client SDK 5 强制**密钥可用性 quorum**:一把密钥必须存在于**至少两个 HSM** 上才能被使用,而且这个检查在**每次操作**时重新评估。所以双 HSM 集群不是冗余——它是该检查能通过的**最低配置**。真实硬件实测:

| | HSM 数 | Quorum | 丢掉一个 HSM | 实测结果 |
|---|---|---|---|---|
| **A** | 3 | 开启(默认) | 签名继续——还剩两个 | 由 quorum 规则推出 |
| **B** | 2 | 关闭 | **签名继续**,全速 | 5/5 成功,0.38–0.46 秒 |
| **C** | 2 | 开启(默认) | 新进程客户端失败；已运行的会话直到重启前仍可签名 | CLI 3/3 失败各 87.2 秒；JVM 279/279 成功 |

> **实测：长驻 JVM 会话不会被打断，这否证了上表对生产情形的描述。** 专门搭建的双 HSM 集群，quorum 保持
> **默认（开启）**，密钥在健康状态下生成（`cluster-coverage: full`、`never-extractable: true`）。一个 JVM
> 只安装一次 CloudHSM JCE provider、只加载一次 keystore、只解析一次 `PrivateKey` 句柄，然后循环签名。运行
> 中途删掉一个 HSM。结果：**279 次签名、0 次失败**，在删除之后继续正常运行 **475 秒**，全程耗时
> **1.9–2.2 毫秒**。
>
> 所以 quorum 是在客户端**建立会话**时、以及密钥被创建或列举时强制的——**不是**在每一次针对已解析句柄的私钥操作上。
> AWS 的故障排查页面指向同一结论：它列出的触发操作是密钥生成、`key list`，以及*「a new instance of the SDK
> was started」*，并注明*「OpenSSL frequently forks new instances of the SDK」*。
>
> **为什么先前的测量给出了相反结论。** 配置 C 的每一次失败都是用 `cloudhsm-cli` 测的，而它**每次调用都是一个全新
> 进程**——所以每次尝试都是一个新的 SDK 实例，而这本身就是触发条件。那些 87.2 秒的失败是真实的，它们是
> **短生命周期客户端**会看到的情形；**不是**长驻代理会看到的情形。
>
> **这改变了什么、没改变什么。** 配置 C 不是上表所说的「即刻完全中断」：一个正在运行的代理会在仅存的一个 HSM 上
> 继续签名。但它**仍然**是要避开的配置，理由同样有实测支撑——降级窗口期内的一次**重启**无法重新建立会话，所以任何
> 部署、崩溃、扩容或容器替换都会把「降级」变成「中断」，而且期间密钥创建与轮换全程失败。按三个 HSM 规划仍然是
> AWS 自己文档里的建议。区别在于故障是**由重启触发而非立即发生**，这把事故形态从「Pix 现在就停」变成
> 「Pix 在下一次重启时停」——而后者很容易被误判为已经扛过了这次故障。

**配置 C 的成本和 B 完全一样,能力却严格更差。** 而它恰恰是「按显而易见的路径走」会得到的形态,所以是要避开的那一个。失败是**慢失败而非快失败**——错误浮现前要等 87 秒——所以请求会堆积而不是快速失败;无论选哪种配置,客户端超时都必须相应设置。

在读细节之前,两个会误导人的陷阱:

- **`cluster-coverage: "full"` 不是持久性度量。** 它的含义是「存在于当前集群里的每一个 HSM 上」,所以一把只存在于单个 HSM 的密钥同样报告 `full`。要判断密钥是否已安全复制,应**清点 ACTIVE HSM 数量**,不要读这个字符串。
- **有两个完全不同的机制都叫「quorum」。** 密钥可用性 quorum 数的是 **HSM 台数**;仲裁认证(M of N)数的是**人数**。两者互不相关,却出现在同一条命令的输出里。

完整细节——包括加入/同步机制、可用区放置、审计覆盖范围,以及实测到的运维时序陷阱——见 [Cluster high availability](README-CloudHSM.md#cluster-high-availability-how-to-size-it-and-the-setting-that-decides-everything) 与 [Two different things are called "quorum"](README-CloudHSM.md#two-different-things-are-called-quorum-and-conflating-them-is-a-real-hazard)。双 HSM 与三 HSM 的成本对比见 [`PIX_CLOUDHSM_ASSESSMENT.md`](PIX_CLOUDHSM_ASSESSMENT.md)。

### 各部分代码在哪里

| 路径 | 用途 |
|---|---|
| `proxy/core` | 签名、TLS 与内容解码逻辑——**其测试在 CI 中运行** |
| `proxy/test` | 本地 BCB 模拟器与 DICT v2 传输契约测试——**其测试在 CI 中运行** |
| `proxy/cloudhsm` | CloudHSM 代理本身。在 CI 中编译,但其测试不在 CI 运行(需要 CloudHSM JCE rpm) |
| `proxy/kms` | 历史遗留、不受支持,由显式守卫排除在 CI 之外 |
| `alarms/` | 审计告警的 CDK 应用。刻意放在 Maven 反应堆**之外**,使 Java 构建不引入 Node 依赖;其 `cdk synth` 与模板断言是一个硬门禁作业 |
| `.github/scripts/check-transport-contract.sh` | 源码级门禁,钉住生产路由的端点选项 |
| `tools/generate_architecture_diagram.py` | 用官方 AWS 图标集重新生成架构图 |

只有 `proxy/core` 与 `proxy/test` 会执行测试。`proxy/cloudhsm` 与模拟器以 `-DskipTests` 构建,所以加进它们的测试会**静默地永不运行**——添加后请核对 CI 运行的测试**数量**,不要相信一个绿勾。

## 本地试运行

这里的一切都不需要 AWS 账号或 HSM——签名逻辑与整个 DICT v2 传输契约都针对一个本地 BCB 模拟器和已提交的证书夹具运行。

```bash
export JAVA_HOME=/path/to/jdk11          # Java 11;见上方版本表

# 所有会执行的测试:签名逻辑 + 透明代理契约
mvn -f proxy/pom.xml clean test

# 钉住生产路由端点选项的源码级门禁
bash .github/scripts/check-transport-contract.sh

# CDK 告警应用(Node 22,在 Maven 构建之外)
cd alarms && npm ci && npx jest && npx cdk synth
```

Maven 反应堆根是 `proxy/pom.xml`,所以按模块构建要写 `mvn -f proxy/pom.xml -pl <模块>`。构建 `proxy/cloudhsm` 需要本地安装 CloudHSM JCE rpm;CI 只编译它,不运行其测试。

**读测试数量,不看绿勾。** 只有 `proxy/core` 与 `proxy/test` 执行测试——其余模块以 `-DskipTests` 构建,所以加进它们的测试会静默地永不运行。这件事已经发生过:一条 `.gitignore` 规则曾把一个 jest 配置排除在提交之外,CI 回退到一个无法解析 TypeScript 的转换器,作业报告 `Tests: 0 total`,而本地全绿。告警作业现在会断言断言的**数量**,正是为此。

CI 共 **11 个作业**:两个执行测试的(`core`、`dict-v2-contract`)、两个仅编译的(`simulator`、`cloudhsm`)、一个容器入口脚本的 shellcheck、传输契约与 KMS 范围门禁、审计 schema 检查、三个文档门禁,以及作为硬门禁的 CDK 告警作业。

三个文档门禁存在的理由是:一份错误的文档会被人照着执行。

| 作业 | 什么情况下让构建失败 |
|---|---|
| `doc-parity` | 一份文档与其译文在结构、某条命令、或某个测量值上出现分歧 |
| `doc-claims` | 文档里的事实——钉住的版本、CI 作业数、路由顺序、告警 token——与它所描述的源不一致 |
| `doc-links` | 某个交叉引用或锚点已经解析不到 |

每一个都是在对应的错误**已经在这里发生过之后**才写的,不是预防性的:一条实测限定同时从四份文档里消失、首页在 CI 加到第九个作业后仍写着 8 个、以及一个链接指向已被改名的标题。

**这里每一条契约断言都有反向对照**——把被守护的东西移除,并确认检查**因为正确的原因**失败。这个习惯的由来是:本仓库曾有三条门禁在被守护对象已经消失的情况下依然通过,因为 `grep 'Foo'` 会匹配改名后的 `FooGone`。**断言要针对用法,不要针对名字片段。**

## 安全

详见 [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications)。

## 许可

本库采用 MIT-0 许可。见 LICENSE 文件。
