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

```bash
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
| [`README-CloudHSM.md`](README-CloudHSM.md) | **维护中的路径。** 架构详解、BCB / TLS / JDK / CloudHSM 的版本要求、完整 AWS 部署步骤、以及如何运行每一项检查 | 你要部署,或需要知道 BCB 在链路上要求什么、哪些版本能满足 |
| [`README-KMS.md`](README-KMS.md) | **历史遗留 / 不受支持。** 早期的 AWS KMS 变体,仅供参考 | 仅用于了解历史背景。它不被维护、不在 CI 中,不得作为基线 |
| [`PIX_CLOUDHSM_ASSESSMENT.md`](PIX_CLOUDHSM_ASSESSMENT.md) | 面向决策者的验证评估:在真实 CloudHSM 硬件上测到了什么、推荐哪条传输路径及为何排除其余、双 HSM 可用性发现、以及上生产前的开放关卡 | 你在判断是否以及如何采用这套方案,而不是在实现它 |
| [`VERIFICATION.md`](VERIFICATION.md) | 独立验证记录:复现了哪些缺陷、修了哪些、每个修复用什么测试过、哪些仍是限制 | 你想要某个主张背后的证据,而不是主张本身 |
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

## 版本:钉住了什么,以及那个阻塞部署的约束

以下取自 `proxy/pom.xml` 与 `.github/workflows/build.yml`,而非文字描述——这些是构建实际使用的值。

| 组件 | 钉住的版本 | 为什么钉在这里 |
|---|---|---|
| Java | **11**(CI 用 `temurin`) | CloudHSM Client SDK 3 支持的版本 |
| Quarkus | 1.7.0.Final | `camel-quarkus` 1.0.0 对应的代次 |
| Camel Quarkus | 1.0.0 | 提供 `camel-netty-http`,它讲 HTTP/1.1,符合 BCB 要求 |
| Netty | **4.1.138.Final** | 更早的版本带有请求走私公告(CWE-444);4.1.118 仍有 CVE-2025-58056 |
| netty-tcnative | 2.0.84.Final,`linux-x86_64-fedora` | 与该 Netty 配对;**仅 x86_64**,其他架构会在启动时失败 |
| Jackson | 2.15.4 | 安全下限;两个 BOM 都在 `quarkus-bom` **之前**导入,因此生效 |
| CloudHSM SDK 3 | **3.4.4-1** rpm,校验 SHA-256 | 这份代码所面向的版本 |
| Node(仅告警应用) | 22 | 用于 CDK 告警应用,在 Maven 构建之外 |

**那个阻塞约束。** 代码面向 CloudHSM Client **SDK 3**,但 `hsm1.medium` 已于 **2026-03-31** 终止支持,而唯一可创建的实例类型需要 **SDK 5.9.0+**,后者又需要 **JDK 17+**。所以它无法按现状部署。好消息来自真实硬件实测:`XmlSigner` 在 SDK 5 上**无需修改**即可运行,因为 JSR-105 会把 `Signature` 操作路由到 CloudHSM provider,所以迁移难点**完全局限在 TLS 那一半**——Netty 要密钥**字节**,而 HSM 只给**句柄**。推荐的解法见 [`PIX_CLOUDHSM_ASSESSMENT.md`](PIX_CLOUDHSM_ASSESSMENT.md) 第 3 节。

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
| **C** | 2 | 开启(默认) | **签名完全中断**(见下方限定) | 3/3 失败,每次 87.2 秒 |

> **配置 C 的测量建立了什么、没建立什么。** 所有测量都用 `cloudhsm-cli`，它**每次调用都是一个全新进程**。AWS 针对该错误的故障排查页面把*「a new instance of the SDK was started」*列为触发操作之一，与密钥生成、`key list` 并列——所以这些测量**无法分离**「quorum 拦截了签名操作本身」与「quorum 在 SDK 启动时触发」。代理在生产中持有**长生命周期 JVM 会话与已解析的密钥句柄**，而**双 HSM 丢掉一个后它是否仍能签名，在此并未建立**。持久性文档说 token key 的「create **or use**」都会失败，指向一个方向；故障排查页面的触发列表未提及「用已打开的句柄签名」，指向另一个方向。请把「签名完全中断」视为**对短生命周期客户端已实测、对长生命周期客户端未证明**。

AWS 自己针对该错误的故障排查指引独立得出了相同的规模结论：它列出的解决办法是关闭该检查、在双 HSM 集群上避免在初始化代码之外使用那些触发操作，或者*「increase the amount of HSMs in your cluster to at least three」*。

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

CI 共 **8 个作业**:两个执行测试的(`core`、`dict-v2-contract`)、两个仅编译的(`simulator`、`cloudhsm`)、一个容器入口脚本的 shellcheck、传输契约与 KMS 范围门禁、审计 schema 检查,以及作为硬门禁的 CDK 告警作业。

**这里每一条契约断言都有反向对照**——把被守护的东西移除,并确认检查**因为正确的原因**失败。这个习惯的由来是:本仓库曾有三条门禁在被守护对象已经消失的情况下依然通过,因为 `grep 'Foo'` 会匹配改名后的 `FooGone`。**断言要针对用法,不要针对名字片段。**

## 安全

详见 [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications)。

## 许可

本库采用 MIT-0 许可。见 LICENSE 文件。
