# 用于示范数字签名及向巴西即时支付系统安全传输消息的 AWS CloudHSM 架构

> **English:** [`README-CloudHSM.md`](README-CloudHSM.md)
>
> **这是译文，不是权威副本。** 本文档记录的是在真实 CloudHSM 硬件上做出的测量，而权威副本是 [`README-CloudHSM.md`](README-CloudHSM.md) —— 发现错误请改那一份。两份文件不靠人的自觉保持一致：CI 的 `doc-parity` 作业会在标题结构、任何命令、或任何测量值两边出现分歧时让构建失败。理由很直接——一份存在两个副本的测量记录，就是一份可能悄悄自相矛盾的测量记录。

<p align="center">
  <img src="/images/proxy-cloudhsm.png">
</p>

> ## ⚠️ 维护范围与 BCB 时效边界
>
> 本 fork **仅**维护 CloudHSM 教学骨架：**XML 数字签名、mTLS、CloudHSM 客户端/容器集成，以及透明 HTTP 代理**。它是一份传输/加密参考，**并非**完整的 Pix PSP 实现。
>
> **明确不在范围内（并非待办清单）：** 支付发起；入站 SPI 异步消息；清算/对账；退款业务流程；MED 2.0 / Funds Recovery；Fraud Markers；Event Notifications；Pix Automático；授权；流动性；欺诈决策；以及运营 SLA。真实的 PSP 必须依据当前 BCB 规则，在独立的领域服务中实现这些功能。
>
> **DICT v2 边界：** DICT API v1 已于 2024-02-04 完全停用。生产调用方必须发送 `/api/v2/...`；下文中本地的 `test.pi.rsfn.net.br` 模拟器不是 BCB homologação。在每次 homologação/生产发布之前，须获取当前的 BCB OpenAPI、安全手册（Security Manual）、端点基址、证书链以及允许的 TLS 策略：[DICT API](https://www.bcb.gov.br/content/estabilidadefinanceira/pix/API-DICT.html) · [changelog](https://bcb.gov.br/content/estabilidadefinanceira/pix/changelog.html)。
>
> 已测试的 CloudHSM 修复见 [`VERIFICATION.md`](VERIFICATION.md)，剩余的仅涉及 CloudHSM 的工作见 [`CLOUDHSM_BCB_V2_HANDOFF.md`](CLOUDHSM_BCB_V2_HANDOFF.md)。

## BCB homologação 发布检查清单

**CI 证明了什么、又没有证明什么。** 所维护的 CI 以机械方式证明传输契约：DICT v2 路径、查询字符串（包括重复参数）、BCB 的 `PI-*`
头部以及 XML 主体在代理跳转中得以保留；XML 签名/验证有效；一张已过期的与一张尚未生效的受信证书可被区分；并且本地模拟器会
拒绝格式错误的 v2 请求，而不是返回 200。**以上皆非 BCB 兼容性的证据。**
位于 `test.pi.rsfn.net.br:8181/:9191` 的本地模拟器是一个回环测试替身——BCB homologação
是 `dict-h.pi.rsfn.net.br:16522`。

在任何 homologação 或生产发布之前，请逐项完成本清单。每一个未勾选的方框都是不予发布的理由，而不是可有可无的加分项。

- [ ] **已获取当前 BCB 材料** —— DICT v2 OpenAPI、安全手册（Security Manual）、端点基址、
      证书链以及允许的 TLS 策略，均取自 BCB 的入网/支持渠道，而非本仓库。在
      2026-09-20 的调研中，BCB API 页面上的安全手册链接返回了 404，因此这一步无法在此处走捷径。
- [ ] **已记录 DICT v2 OpenAPI / XSD 版本**——记入你的发布说明中，并注明你所验证依据的确切版本。
      本仓库**完全不执行任何 XSD 模式校验**，其示例消息不得被当作版本参考。
- [ ] **已对照 BCB 当前手册确认 TLS 策略。** BCB 一侧被固定为 `TLSv1.2`，
      因为这是此处唯一被实际使用的协议；若被改动，CI 会失败
      （`.github/scripts/check-transport-contract.sh`）。不要仅凭本仓库就将其提升到 TLS 1.3——
      请确认获批的协议与密码套件列表，然后在 homologação 中加以证明。
- [ ] **已对照真实的 BCB 端点验证证书链**，并已审慎地就主机名校验作出决定：
      此处**并未**启用它，仅通过显式信任 BCB 证书（证书固定）来缓解。
- [ ] **已将两张 BCB 信任证书替换**为当前由 BCB 提供的证书链。
      `BcbSignatureCertificate` 和 `BcbMtlsCertificate` 与模拟器所使用的是同一批参数，
      因此从模拟器切换过来并不仅仅是更换一个端点。当某张已知的模拟器证书被信任时，应用会记录一条指名该证书的 ERROR 日志
      （`WellKnownTestCertificates`）——在本地模拟之外，应对该日志行设置告警。
- [ ] **已就绪证书到期与轮换监控。** 配置**仅在启动时读取一次**，
      因此轮换证书需要重新部署。本仓库没有任何到期监控。
- [ ] **请求与响应签名在 homologação 中被 BCB 接受**，包括针对 BCB 自己发布的已签名示例消息进行一次往返。
      本地模拟器复用的是*我们自己的*签名器，
      所以一次通过的模拟器运行只能证明我们与自己达成一致。
- [ ] **已针对 400 / 403 / 404 / 409 / 410 / 429 / 503 演练错误处理。** 模拟器可通过 `PI-Simulate-Status`
      头部按需产生全部七种——这是一项**绝不可发送给真实 BCB 的模拟器特性**。请确认你的调用方与审计记录能处理
      每一种，然后再向 BCB 确认真实的代码与触发条件，因为模拟器的映射是
      本仓库的策略，而非 BCB 的行为。
- [ ] **已以书面形式决定 mTLS 私钥策略。** 对于当前的 Netty TLS 路径，mTLS 私钥必须是**可导出的**，
      因此本部署并不满足"私钥永不离开机构控制"这一最严格解读。签名私钥不受影响，
      且不可导出。见 `CLOUDHSM_BCB_V2_HANDOFF.md` §7.1——并请注意，仅仅把私钥设为不可导出会在握手时失败。
- [ ] **已作出审计持久性决策**，依据 `LogRequestResponseProcessor` 中的代码注释：
      审计投递被有意设计为不会使一笔事务失败，这是以一个合规问题换取一个正确性问题。必须具备一个持久的兜底汇聚点（sink）
      以及针对 `AUDIT DELIVERY FAILED` 的告警，而审计失败是否应当拒绝一笔事务，则是一个合规判断。
- [ ] **审计日志的 LGPD 处理。** 记录包含完整的消息主体——姓名、CPF、
      账号、金额——在本仓库中没有任何脱敏。
- [ ] **已审查 HSM 代次与会话处理。** 本代码面向 **CloudHSM Client SDK 3**
      和 `hsm1.medium`，后者已无法再创建，并已于 2026-03-31 结束支持；
      `hsm2m.medium` 需要 Client SDK 5.9.0+。此外也没有 HSM 会话重连。见
      `VERIFICATION.md`。
- [ ] **已测试多 HSM 故障转移**，在至少含两个 HSM 的集群上进行，并在运行中替换其中一个。
      单 HSM 环境无法演练此项。
- [ ] **任何地方都不得声称本地模拟器可证明 BCB 兼容性。**

本项目包含源代码及支持文件，包括以下文件夹：

- `proxy/cloudhsm` - 使用 AWS CloudHSM 的代理。
- `proxy/core` - 对 XML 消息进行签名。
- `proxy/test` - BACEN 模拟器。

应用的主要代码使用了若干 AWS 资源，包括 AWS CloudHSM 和一个 AWS Fargate。解决方案的审计部分使用了其他 AWS 资源，包括 [Amazon Data Firehose](https://aws.amazon.com/firehose/)（前称 **Amazon Kinesis Data Firehose**）、[Amazon Athena](https://aws.amazon.com/athena/?nc1=h_ls&whats-new-cards.sort-by=item.additionalFields.postDateTime&whats-new-cards.sort-order=desc)、[Amazon S3](https://aws.amazon.com/s3/?nc1=h_ls) 和 [AWS Glue](https://docs.aws.amazon.com/glue/latest/dg/components-overview.html)。
## 版本要求：BCB Pix、TLS、JDK 与 CloudHSM

本节的所有内容要么引自一手来源，要么经过实测。凡是无法验证的要求，都会明确说明而非猜测。

### BCB 在传输层的要求

引自 **Manual de Segurança do Pix, v3.7**（PDF 创建于 2025-06-06）第 2 节
*"Comunicação segura"*：

> "O participante deve se conectar às APIs disponíveis no Pix exclusivamente por meio do protocolo
> **HTTP versão 1.1** utilizando criptografia **TLS versão 1.2 ou superior**, com **autenticação
> mútua obrigatória** no estabelecimento da conexão. Deve ser suportada, **no mínimo, a Cipher
> Suite ECDHE-RSA-AES-128-GCM-SHA256 (0xc02f)**"

| 要求 | 值 | 本仓库的现状 |
|---|---|---|
| HTTP 版本 | 1.1 | camel-netty-http 使用 1.1 |
| TLS 版本 | **1.2 或更高**（`ou superior`） | 路由固定 `enabledProtocols("TLSv1.2,TLSv1.3")` |
| 双向认证 | 强制 | 模拟器上使用 `needClientAuth`；生产环境的 mTLS keystore 来自 SSM |
| 密码套件 | **至少** `ECDHE-RSA-AES-128-GCM-SHA256`（0xc02f） | 在 Corretto 11 和 17 上受支持**且默认启用** —— 实测，非假设 |
| 签名 | XMLDSig；DICT 场景下 XML 根处的 `<Signature>` | `XmlSigner` / `Iso20022XmlSigner` |
| 签名证书 | ICP-Brasil **padrão SPB**（规范见 *Manual de Segurança do SFN*） | 此处未验证 —— homologação 关卡 |
| BC 的连接证书 | ICP-Brasil **chain v10** SSL | 此处未验证 —— homologação 关卡 |
| DNS | 客户端"devem sempre respeitar o TTL"（始终遵守 DNS 服务器的 TTL） | **未验证** —— 此骨架仅在启动时读取一次配置 |

注意，1.2 是**下限，而非上限**。`ou superior` 允许 TLS 1.3，这就是两者都提供的原因。之所以固定
该列表而不交给 JVM 默认值，是因为 Corretto 11 仍会启用 TLS 1.1 和 1.0，它们*低于*该下限：

```
Corretto 11.0.32  default enabled: [TLSv1.3, TLSv1.2, TLSv1.1, TLSv1]   <- 1.1/1.0 must be excluded
Corretto 17.0.20  default enabled: [TLSv1.3, TLSv1.2]
```

**该手册的获取方式。** DICT API 页面链接指向的 URL
（`/content/estabilidadefinanceira/cedsfn/Manual_de_Seguranca_PIX.pdf`）返回 **404**，而其在
`pix/Regulamento_Pix/` 下的同级手册返回 200。此处的文档取自该 BCB URL 精确对应的 Internet Archive
快照；其内容摘要在 2025-07-16 至 2026-06-03 的各快照之间保持不变。它是通过存档获取到的 BCB 自有
文件。在依赖它进行 homologação 运行之前，请通过 BCB 的接入/支持渠道确认当前版本。

### JDK 版本

约束 JDK 选择的**不是** TLS —— 下面两个 JDK 都满足该手册的要求：

| | 0xc02f 受支持 / 默认启用 | TLS 1.3 |
|---|---|---|
| Corretto 11.0.32 | 是 / 是 | 是 |
| Corretto 17.0.20 | 是 / 是 | 是 |

该强制密码套件自 **JDK 8u161** 起可用，TLS 1.3 自 **JDK 11** 起可用（并向后移植到 8u261）。真正
迫使 JDK 升级的是 CloudHSM，见下文。

### CloudHSM 版本 —— 这是阻塞性约束

| 项目 | 状态 |
|---|---|
| 本代码面向 | Client **SDK 3**（`com.cavium.cfm2`、`PARTITION_1`、`key_mgmt_util`） |
| `hsm1.medium` | **无法创建** —— 于 2026-09-20 针对 `us-east-1` 的实时 API 实测：以 `hsm1.medium` 调用 `CreateCluster` 返回 `CloudHsmInvalidRequestException: Provided HsmType is not supported.` 支持终止日期为 **2026-03-31**（已过） |
| `hsm2m.medium` | 唯一可创建的类型；需要 Client SDK **5.9.0+** |
| SDK 5 JCE 提供程序 | 仅支持 **OpenJDK 17 / 21 / 25** |

因此，本仓库中的 CloudHSM 路径**无法按现状部署**，而迁移到 SDK 5 的必要工作也会强制要求 JDK 17+。
这是 CloudHSM 生命周期的结果，而非任何 BCB 要求所致。在 JDK 17 上，本仓库的 XMLDSig 路径还需要两个
`--add-exports` 标志。参见 `CLOUDHSM_BCB_V2_HANDOFF.md` 第 7 节。

### 响应压缩

BCB 的 API 页面建议客户端发送 `Accept-Encoding: gzip`。本代理透明转发客户端头，因此压缩响应是
**预期**情况。由于 XML 签名覆盖的是 XML 文档而非压缩后的字节，路由会在验证正文*之前*先对其解码
（先 `DecodeResponseProcessor`，再 `convertToString()`，最后 `VerifyResponseProcessor`）。颠倒
该顺序不仅仅是失败 —— 先被转换为 `String` 的 gzip 正文，其魔术字节 `0x8b` 会被替换为 U+FFFD 并被
不可逆地破坏。发送压缩**请求**则完全不被 BCB 支持。

## 如何验证上述所有内容

只有 `core` 和 `test` 模块会运行测试。`simulator` 和 `cloudhsm` 使用 `-DskipTests` 构建，因此
放在其中的测试会悄无声息地永不运行。

```bash
export JAVA_HOME=~/.local/opt/jdk11
export PATH=$JAVA_HOME/bin:$PATH

# 1. 签名、TLS、内容解码、审计缓冲、健康探针和吊销单元测试
#    (core：79 个测试，读取自 2026-09-20 的 CI run 35525201219 —— 数量会变，请自行核实)
mvn -B -f proxy/pom.xml -pl core test

# 2. 所有会执行的测试，包括 DICT v2 传输契约
#    (core 79 + proxy/test 48 = 127，读取自 2026-09-20 的 CI run 35525201219)
mvn -B -f proxy/pom.xml -pl core,test test

# 3. 模拟器构建
mvn -B -f proxy/pom.xml -pl core,test package -DskipTests

# 4. CloudHSM 构建（需要在本地安装 CloudHSM JCE rpm）
mvn -B -f proxy/pom.xml -pl core,cloudhsm/cavium,cloudhsm/proxy package -DskipTests

# 5. 容器入口点
bash -n proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh
shellcheck -S warning proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh

# 6. 源码级传输契约 + KMS 作用域守卫
bash .github/scripts/check-transport-contract.sh
```

### 哪个测试覆盖哪条主张

| 主张 | 测试 / 检查 |
|---|---|
| TLS 1.2 为下限，允许 1.3，仅支持 1.2 的对端仍可访问 | `TlsProtocolNegotiationTest`（proxy/core） |
| 强制套件 0xc02f 能实际协商，而不仅是被列出 | `TlsProtocolNegotiationTest` |
| TLS 1.1/1.0 被固定列表排除 | `TlsProtocolNegotiationTest` |
| gzip/deflate 解码、拒绝、炸弹上限、根因 | `HttpContentDecoderTest`（proxy/core） |
| gzip 响应以已签名 XML 的形式到达验证环节 | `DictV2CompressedResponseContractTest`（proxy/test） |
| 路径 / 查询 / 重复查询 / 头 / 正文均被保留 | `DictV2TransparentProxyContractTest`（proxy/test） |
| 模拟器请求策略 | `DictV2RequestPolicyTest`（proxy/test） |
| 为何当前 mTLS 密钥必须可导出，以及为何此处无法使用 Netty 的私钥回调 | `MtlsNonExtractableKeyTest`（proxy/test） |
| 生产路由仍声明固定选项，且解码先于验证 | `check-transport-contract.sh` |

上面的数量特意标注了日期。每当新增测试时它们都会变化，因此应将 **CI run 自身的输出**视为权威，而非
本页面 —— 并且当你新增测试时，请在该输出中确认数量，而不要信任一个绿色对勾。

### 负向对照

此处每条契约断言都有一个配对的负向对照，因为一个只会通过的测试无法证明它能察觉到回归。其中两个是
永久性测试（`withoutBridgeEndpointThePathAndQueryAreLost`、
`withoutDecodingTheBodyReachingVerificationIsDestroyed`）；其余则通过手动破坏相应内容并确认检查
变红来运行。要复现一个关卡对照：

```bash
cp .github/scripts/check-transport-contract.sh /tmp/gate.bak   # 从文件副本恢复
# 然后，例如在路由中把 DecodeResponseProcessor 移到 convertToString 之后，再重新运行：
bash .github/scripts/check-transport-contract.sh               # 必须以非零状态退出
```

从文件副本恢复，并用 `cmp` 校验。**不要**依赖 `git checkout --` 来撤销一次改动：对于未跟踪的文件
它会悄无声息地什么都不做，而对于已跟踪的文件它会回退到最后一次*提交*而非你尚未提交的工作。这两种
失败模式在构建本项目时都曾产生过误导性的"全绿"运行。

### 这一切都未能证明什么

这些检查是在**环回上的纯 HTTP**上运行的，使用桩签名器，没有 HSM、没有证书、也没有 AWS。它们对
针对 BCB 的 TLS、对 BCB 的证书链、或对 CloudHSM 都不能说明任何问题。`dict.pi.rsfn.net.br` 没有
公开的 A 记录 —— RSFN 是私有网络 —— 因此无法从外部探测真实端点。通过这些检查**并不**是 BCB
homologação 的证据。
## 集群高可用：如何确定规模，以及决定一切的那个设置

以下全部内容均**在真实 CloudHSM 硬件上实测**——两个集群，在签名过程中删除 HSM，
然后再恢复——而非从文档推理得出。计时数据来自由 `cloudhsm-cli` 5.18.0 驱动、
运行于 FIPS 模式的 `hsm2m.medium` 集群。

### 唯一必须做对的事

SDK 5 强制执行一种**密钥可用性法定数量（quorum）**：默认情况下，一个密钥必须存在于**至少两个
HSM** 上，应用才可以使用它。AWS 将其后果记述为*"任何创建**或使用**令牌密钥的尝试都将失败"*——
注意是*使用*，而不仅仅是*创建*。该 quorum 会在**每一次操作**时根据当前集群成员重新评估，
因此一个已经完全复制的密钥，会在集群跌破两个 HSM 的那一刻停止工作。

这产生了三种配置，其中一种绝不应上线：

| | HSMs | Quorum | 丢失一个 HSM | 实测情况 |
|---|---|---|---|---|
| **A** | 3 | 启用（默认） | 签名继续——仍剩两个 | 由 quorum 规则推断 |
| **B** | 2 | 禁用 | **签名继续**且全速运行 | 实测 5/5 OK，0.38–0.46 s |
| **C** | 2 | 启用（默认） | 新进程客户端失败；运行中的会话在重启前仍存活 | 实测：CLI 3/3 在 87.2 s 时失败；JVM 279/279 OK |

> **实测：长生命周期的 JVM 会话不会被中断，这对生产环境而言否证了上面那一行。** 一个专门搭建的
> 双 HSM 集群，quorum 处于其**默认（启用）**状态，密钥在集群健康时生成（`cluster-coverage: full`、
> `never-extractable: true`）。一个 JVM 安装了一次 CloudHSM JCE provider，加载了一次 keystore，
> 解析了一次 `PrivateKey` 句柄——然后在循环中签名。运行途中删除了一个 HSM。结果：**279 次签名，
> 0 次失败**，在删除之后继续运行了 **475 秒**，全程每次 **1.9–2.2 ms**。
>
> 因此 quorum 是在客户端**建立**其会话时、以及在创建或列出密钥时强制执行的——而不是在针对一个
> 已解析句柄的每一次私钥操作时。AWS 的故障排查页面也指向同一方向：它把密钥生成、`key list`
> 以及*"启动了一个新的 SDK 实例"*列为触发条件，并指出*"OpenSSL 会频繁地 fork 出新的 SDK
> 实例"*。
>
> **为什么早先的测量给出了相反的结论。** 每一次配置 C 的失败都是用 `cloudhsm-cli` 测量的，
> 而它**每次调用都启动一个全新进程**——因此每次尝试都是一个新的 SDK 实例，这本身就是一个触发
> 条件。那些 87.2 s 的失败是真实的，它们是一个新进程客户端所看到的；它们**不是**一个长生命周期
> 代理所看到的。
>
> **这改变了什么，又没有改变什么。** 配置 C 并不是上面所述的即时彻底停机：一个运行中的代理会在
> 一个幸存的 HSM 上继续签名。它仍然是应当避免的配置，理由依然是实测的——在降级窗口期间的一次
> **重启**无法重新建立会话，因此任何部署、崩溃、扩容或容器替换都会把降级变成停机，而且密钥创建
> 和轮换在整个期间都会失败。按三个 HSM 来确定规模仍然是 AWS 自己文档中的建议。区别在于：这种
> 失败是**由重启触发的，而非即时的**，这把事故形态从"Pix 现在就停"变成"Pix 在下一次重启时
> 停"——而后者很容易被误以为是熬过了这次故障。

**配置 C 被严格劣于（strictly dominated）。** 它的成本与 B 完全相同，却在一个 HSM 消失时丧失全部
签名能力。带默认 quorum 的双 HSM 正是你沿着显而易见的路径会得到的形态，而它恰恰是应当避免的那
一种。

### 实测了什么，按顺序

健康的双 HSM 集群，quorum 禁用，密钥以一个 PSP 签名密钥所需的属性生成（`extractable=false`、
`never-extractable=true`、`sign=true`、`cluster-coverage: full`）：

```
{"phase":"baseline_2hsm","n":1,"dur":0.515,"ok":true}   ... 5/5 ok, 0.46-0.52 s
```

随后删除了两个中的一个 HSM，再次使用同一密钥：

```
{"phase":"degraded_1hsm_existing_key","n":1,"dur":0.393,"ok":true}   ... 5/5 ok, 0.38-0.46 s
```

**在没有任何配置更改、没有重启的情况下，签名以全速继续。** 对配置 C 执行同样的删除，连续三次
产生了：

```
error_code: 1 — Cannot perform the requested key operation as the key must be
available on at least 2 HSMs
```

每次都在 **87.2 秒**之后（三次测量，散布 0.04 s——这是一个固定的内部重试预算，而非网络抖动）。

恢复第二个 HSM 会在 0.45 s 内自动恢复配置 C，**无需**把替换 HSM 的新 ENI IP 加入客户端配置——
客户端会自行发现集群成员。注意替换 HSM 会以一个**新的** IP 出现。

### 一个加入的 HSM 如何被同步，以及之后靠什么保持同步

这是**两种不同的机制**，把它们混为一谈会导致错误的运维预期。

**加入时：一次完整快照，而非增量追赶。** AWS 记述了五个事件，其中只有第一个是你的：

1. 你调用 `create-hsm`。
2. CloudHSM 对集群中一个**已有**的 HSM 做一次备份。
3. 它**把该备份恢复到新的 HSM 上**，这正是使其达成同步的操作。
4. 已有的 HSM **通知客户端**有一个新 HSM 存在。
5. 客户端连接到新的 HSM。

因此新的 HSM 并不是在"追赶"——它被一个对等节点状态的副本整体覆盖。AWS 明确指出，该恢复会"覆盖
恢复之前 HSM 上可能存在的所有其他数据"。一次备份携带所有用户（CO、CU、AU）、所有密钥材料和
证书，以及 HSM 的配置和策略。

第 4 步解释了一个实测结果：当一个替换 HSM 以一个**新的** ENI IP 加入后，即使该 IP 不在客户端
配置中，签名仍然有效。集群把成员信息**推送**给客户端；客户端并不轮询或重新读取配置。只有在给一个
*全新的*客户端主机提供初始联系点时，才需要重新运行 `configure-cli`。

**源 HSM 只被读取，不被修改。** 文档所述流程中没有任何环节会改变已有的 HSM——它是备份源。集群
扩展被描述为克隆"集群中另一个 HSM 上的所有用户、密钥和策略。你无需执行任何额外步骤"。

**加入之后：令牌密钥被持续同步，而这一切都无需你管理。**

| 密钥类型 | 是否在集群间同步？ |
|---|---|
| **令牌密钥（Token keys）**——持久化，由 generate / import / unwrap 创建 | **是。** 客户端同步会在它们创建时进行克隆；服务端同步作为兜底会定期把密钥克隆到每一个 HSM。无需管理。 |
| **会话密钥（Session keys）**——临时性，作用域限于单个会话 | **否。** 它们只存在于单个 HSM 上，从不被复制。 |

代理的签名密钥和 mTLS 密钥都是按标签寻址的令牌密钥，因此它们是被覆盖的。任何对会话密钥的使用
则不会被覆盖。
### 密钥创建后紧接着的时间窗口，以及仲裁的第二重用途

这里有一个容易被忽视、而 AWS 也已明确记载的竞态：一个使用**新创建**密钥的调用"可能被路由到集群中任意一个可用的 HSM。**如果你把调用路由到一个没有该密钥的 HSM，那么调用就会失败。**"官方记载的缓解措施是在密钥创建后立即发出的调用上做应用层**重试**，因为同步所需的时间会随集群负载而变化。

这重新界定了密钥可用性仲裁的用途。它不仅仅是一种持久性控制——它还**消除了这个竞态**，办法是在密钥存在于两个 HSM 上之前拒绝使用该密钥。一个随机发生的"被路由到缺少该密钥的 HSM"式失败，就此变成了一次确定性的等待。

因此，配置 B（禁用仲裁）在已经描述过的持久性暴露之外，又重新接受了新创建密钥的这个竞态。对本工作负载而言，这个窗口很小，且局限于开通和轮换阶段——签名密钥和 mTLS 密钥只创建一次，随后使用数月——但这意味着配置 B 的运维规则有两部分，而非一部分：

> 1. 在创建或导入密钥**之前**，先确认至少有两个 ACTIVE 的 HSM。
> 2. 创建密钥之后，在依赖它**之前**先确认它可用（并重试），而不是假定下一次调用一定会成功。

在配置 A 下，仲裁会强制执行这两点，这正是"A 换来的是强制执行，而不仅仅是余量"的实质所在。

**同步机制**不**覆盖的内容：用户和策略，其中就包括 mTLS 设置。** 上述密钥同步是一种*服务端*机制。而对于用户和策略，则**根本不存在任何服务端机制**——AWS 明确指出：*"与密钥不同，不存在跨集群同步 HSM 用户的服务端机制。"* CLI 会对用户和策略操作执行**尽力而为（best-effort）**的同步，*"但如果某个操作部分失败，就可能出现不一致"*，而要解决这些不一致**可能需要人工干预**。检测手段是 `user list`，它会显示出不一致。

这对本仓库有一个特定的后果，因为 **mTLS 设置是一种策略，而不是密钥**——AWS 正是拿它举例：*"用户和策略（例如 mTLS 设置）不会被自动重新同步。"*

**首先，要把两种不同的 mTLS 机制区分开，因为它们很容易被混为一谈，而本文档差一点就这么做了。** `cluster mtls` 保护的是**客户端到 HSM（client-to-HSM）**通道：由管理员在 HSM 上注册一个信任锚，每个 SDK 都配置有客户端密钥和证书链，然后就可以在整个集群范围内设置强制级别。交接关卡 7.1 则是**另一种** mTLS：即**代理到 BCB（proxy-to-BCB）**这一段，代理向 RSFN 出示一份 ICP-Brasil 客户端证书，且其私钥在理想情况下永远不离开 HSM。关闭 7.1 与 `cluster mtls` 毫无关系，启用 `cluster mtls` 也不会推进它。它们除了共用一个名字之外别无关联。

**策略同步缺口对 `cluster mtls` 意味着什么。** 信任锚和强制级别属于**不会**自愈的那一类。除非另有标注，下面的一切都是在一个用完即弃的单 HSM `hsm2m.medium` 集群上**实测（MEASURED）**得到的；在实测与 AWS 官方页面一致之处引用了该页面，在不一致之处则予以反驳。

| # | 主张 | 判定 |
|---|---|---|
| 1 | 在**单 HSM** 集群上，信任锚会报告 `cluster-coverage: "full"` | **实测（MEASURED）——已确认。** 在一个 1-HSM 集群上注册了一个锚；注册响应和 `list-trust-anchors` 都报告了 `"cluster-coverage": "full"` |
| 2 | 重新运行 `register-trust-anchor` 能够完成一次未完成的注册 | **实测（MEASURED）——被否证（FALSIFIED）。** 重新注册同一份证书会返回 `error_code 1`、`"Invalid Certificate: Trust anchor already exists."`。它既不是幂等的，也不是叠加式的——而是被**拒绝** |
| 3 | 最多**两个**信任锚 | **实测（MEASURED）——已确认。** 第二个以 `0x02` 注册成功；第三个返回 `"Maximum number of certificates registered."`；数量保持为 2 |
| 4 | 证书链上限为 **6980 字节** | **实测（MEASURED）——已确认。** 一条 7320 字节的链返回了 `"Oversized Certificate: Certificate is too long"` |
| 5 | `set-enforcement` 要求 CLI **已经处于一个 mTLS 连接上** | **实测（MEASURED）——已确认。** 在没有客户端证书的情况下：`"Failed to set the mtls enforcement. Current connection must be mtls to set this enforcement."` |
| 6 | `set-enforcement` 需要使用**默认 admin** | **实测（MEASURED）——效果上已确认，但错误信息会误导人。** 一个用 `--role admin` 创建的第二个用户被拒绝，报 `"An Admin must be logged in to set policy on an HSM"`，而内置的 `admin` 却成功了。该信息从不提及用户名，因此运维人员会去排查权限问题 |
| 7 | 强制会**丢弃非 mTLS 连接** | **实测（MEASURED）——已确认，并带有专门的对照。** 见下文 |
| 8 | `user list` 会暴露每个用户的 `cluster-coverage` | **实测（MEASURED）——已确认。** 还会报告 `locked`、`mfa`、`quorum` |

**检测命令是真实有效的；但官方记载的修复方法并非 AWS 排障页面所暗示的那样。** `cluster mtls list-trust-anchors` 确实会暴露每个锚的 `cluster-coverage`，因此分歧是可观察的。但那个页面说要*"重新运行注册命令以完成该操作"*，而在锚已经存在的集群上，重新运行会被**拒绝**，报 `"Trust anchor already exists."`。这些测量是在一个健康的集群上进行的，因此它们无法说明：当锚确实从一部分 HSM 上缺失时，同一条命令是否会有不同的行为——刻意制造一次部分失败的注册，并不是本次实验能够可靠做到的事情。**因此，对于一个确实发生分歧的锚，其修复路径是一个开放问题（open question），而不是一套已知的流程。** 现在唯一能确立的是：它不是一次盲目的重新运行——在一个看起来健康的集群上尝试这样做的运维人员会收到一个错误，并可能错误地断定该锚没有问题。

**强制行为，是通过对照测量出来的，而不是断言出来的。** 只有强制级别被改变了：

| 强制级别 | 是否配置了客户端证书？ | 结果 |
|---|---|---|
| `cluster` | **否**（配置项已从配置中删除） | `Error: "HSM is disconnected"` |
| `none` | **否** | 正常工作——列出了全部 3 个用户 |
| `cluster` | 是 | 正常工作 |

中间那一行是对照：同一份没有客户端证书的配置在 `cluster` 下失败，在 `none` 下却成功，因此这次拒绝是强制行为造成的，而不是网络或配置故障。

**这里有两个会让运维人员实实在在浪费时间的陷阱。**

*错误信息指错了对象。* 一个启用了 mTLS 强制的集群在拒绝没有证书的客户端时，报告的是 **`"HSM is disconnected"`**——只字不提 mTLS、证书或策略。人的第一反应会是去检查安全组、ENI 和路由。如果一个客户端在强制级别变更之后立即失去访问权限，应先怀疑证书，再怀疑网络。

*把配置项置空并不等同于删除它们。* 把 `client_cert_hsm_tls_path` 设为 `""` 会让该键仍然存在，于是客户端会在本地失败，报 `"Could not read configuration-referenced file client_cert_hsm_tls_path at location : No such file or directory"`——这是一个从未到达 HSM 的客户端侧错误。这些配置项必须被**删除**。本测量的一个早期版本把它们置了空，结果什么都没测出来，却表面上看起来像是跑过了一次测试。

**强制是可逆的，而且 CLI 接受的取值比页面所暗示的更窄。** `--level` 恰好只接受 **`none`** 和 **`cluster`**——没有针对单个 HSM 的级别。`--level none` 执行成功（`"Mtls enforcement level set to None successfully"`），因此切换并不是一扇单向门。但顺序很重要：**一旦放宽到 `none`，重新启用仍然需要一个 mTLS 连接。** 实测——在强制为 `none` 且没有客户端证书的情况下，`set-enforcement --level cluster` 因与之前相同的原因被拒绝。因此重新收紧意味着要先恢复一份客户端证书配置。

**`set-enforcement` 接受一个仲裁令牌，这把它与 M-of-N 相关工作联系了起来。** 它的选项中包括 `--approval <APPROVAL>`，即*"用于批准操作的已签名仲裁令牌文件的路径"*。本仓库先前测量过的仲裁服务是 `user`、`quorum` 和 `cluster`——而本命令是一次 `cluster` 策略操作。因此，把 **`cluster`** 服务置于 M-of-N 仲裁之下，正是使得一次 mTLS 强制变更需要多个管理员批准、而不是一个管理员加一个密码就够的原因。这正是先前那条建议点名这三个服务的具体缘由。

**`cluster-coverage: "full"` 经实测比它字面读起来要弱，而且现在对策略对象也做了实测。** 本仓库此前已经在单 HSM 集群上对一个密钥（KEY）测量过 `"full"`。上面的主张 1 通过**实测**而非类比，把这一点扩展到了信任锚：覆盖度是相对于**当前成员数**而言的，而不是相对于任何持久性或冗余目标。一个在集群已降级时仍报告 `full` 的锚，实际所在的 HSM 有多少个就是多少个——可能只有一个。

**客户端能数出 HSM 的数量，却无法说出它们的名字。** `cluster hsm-info` 返回 `vendor`、`model`、`serial-number`、固件版本以及 `fips-state`——它**没有 HSM-ID 字段**。因此，从客户端数出 ACTIVE 的 HSM 是可行的（数一数条目数，或者 `serial-number` 的值），但任何需要 **HSM ID** 的操作——一个 CloudWatch 维度、一次 `delete-hsm` 调用、把某个单 HSM 指标关联回一个实例——都必须来自控制平面（`describe-clusters`）。这一点对本文档后面描述的单 HSM 分歧告警很重要，那个告警的指标维度就是 HSM ID：该告警是针对控制平面的标识符定义的，而不是针对客户端所报告的任何东西。

**在为 HSM 通道采用 mTLS 之前还值得知道的一点**，来自 AWS 文档而非此处实测：该特性**仅存在于 `hsm2m.medium` 上**（反正这也是唯一可创建的类型，所以算不上实际约束），并且它**不支持与 AWS KMS 搭配使用的 CloudHSM 密钥库**。上面测得的两锚上限，正是使得信任锚轮换成为一个排序问题的原因：两个槽位都被占用时，第三个就无法注册，所以必须先腾出一个槽位，新的锚才能放进去。

**appliance 用户是可见的，而且它有名字。** `user list` 会把它显示为 `app_user`，角色为 `internal(APPLIANCE_USER)`，与人类管理员并列——因此自动密钥重新同步背后的机制并不是隐藏的基础设施，而是每个 HSM 上一个可枚举的用户。

**自动密钥重新同步究竟是如何工作的，以及为什么可以放心依赖它。** 它使用 **appliance 用户（AU）**的凭据，该用户存在于 AWS 提供的每一个 HSM 上，负责执行"克隆与同步操作"。它只拥有两项能力：能够对一个 HSM 上的对象取**哈希（hash）**，以及能够**提取和插入被掩码（加密）的对象**。它无法读取明文密钥材料，也无法执行密码学运算。AWS 自己的措辞是：AWS *"无法查看或修改你的用户或密钥，也无法使用这些密钥执行任何密码学运算。"* 当某个 PSP 的安全评审问及 AWS 是否能看到生成其 Pix 签名的那把签名密钥时，这句话就是可供引用的答案。

**用户不一致有一张修复表，还有一条会让运维人员吃亏的排序规则。** `user list` 会报告每个属性外加 `cluster-coverage`；某个属性显示为 `inconsistent` 意味着该用户在不同的 HSM 上以不同的值存在。**先修复 admin 账户**——AWS 明确指出，如果 admin 自身不一致，你必须先修复它，反复登录直到它一致为止，然后才能用那个 admin 去修复其他任何人。随后，逐个属性处理：

| `user list` 显示 | 发生了什么 | 修复方法 |
|---|---|---|
| `role` 不一致 | 两个 SDK 在同一时间用不同的角色创建了同一个用户名 | **无法就地修复。** 在**两种**角色下分别 `user delete`，然后用目标角色 `user create` |
| `cluster-coverage` 不一致 | 一次 `user create` 或 `user delete` 部分成功 | 完成你已经开始的那个操作——在两种角色下删除，或者重新创建 |
| `locked` 不一致或为 `true` | 该用户仅对部分 HSM 用错误密码进行了认证 | `user change-password`；如果开启了 MFA，先用 `user change-mfa token-sign --disable` 将其禁用 |
| `mfa` 状态不一致 | 一次 MFA 操作仅在部分 HSM 上完成 | 禁用 MFA，重置密码，然后让该用户用一个已签名的令牌和一份公钥 PEM 重新启用 MFA |

对本工作负载而言，暴露面很窄——代理只使用一个在开通期间创建一次的加密用户（crypto user）——但 `role` 那一行值得牢记：它是唯一一种**没有就地修复方法**的不一致，而其载明的成因是两个 SDK 在同一个用户名上发生竞争。请在一个地方、串行地开通用户。

**SDK 3 注意事项，供仍在使用它的人参考：** `cloudhsm_mgmt_util` 直接与 HSM 通信，绕过了客户端守护进程，而且当有 HSM 被加入时，它的配置**不会**被动态更新。在集群成员数发生变化期间用它执行用户管理，可能会让用户处于不同步状态。在它运行时不要加入 HSM。SDK 5 的 `cloudhsm-cli` 没有*那个特定的*问题——客户端会随着 HSM 的加入和离开而自我重新配置，这也是此处所实测的——但要注意它修复了什么、又没有修复什么。**本文档此前说 SDK 5"没有这个问题"，这读起来仿佛用户不同步是 SDK 3 的一个缺陷。事实并非如此。** 动态重新配置是一种客户端发现（client-discovery）属性；用户和策略同步在**两个** SDK 上都是尽力而为的，两者都没有服务端兜底。SDK 5 消除的是过期配置这一成因，而不是这种故障模式本身。
### A key created while a new HSM is joining

加入过程会恢复一份**快照（snapshot）**。在该快照拍摄之后创建的密钥并不在其中，因此必须由别的机制把它带过去。这个机制就是**服务端同步（server-side synchronisation）**，AWS 将其描述为周期性地把密钥克隆到集群中的每个 HSM，且无需任何管理。它的存在正是为了作为这种情形的兜底——客户端同步（client-side synchronisation）帮不上忙，因为它是在创建时刻克隆到*当时*已在集群中的那些 HSM。

实际发生什么，取决于集群规模和法定人数（quorum）设置：

| 扩容 | 法定人数 | 加入过程中创建密钥 |
|---|---|---|
| 1 → 2 个 HSM | 启用 | **创建失败。**一个 ACTIVE 的 HSM 无法满足数量为二的法定人数。这种情况是被阻止，而不是被处理。 |
| 1 → 2 个 HSM | 禁用 | 创建在旧 HSM 上成功，在服务端同步运行之前，该密钥**只存在于一个 HSM 上**。若在此窗口内丢失那个 HSM，就会丢失该密钥，且无法从上一次备份中找回。 |
| 2 → 3 个 HSM | 启用 | 创建成功——两个已有的 HSM 满足法定人数，客户端同步会把密钥放到两者上。在服务端同步到达第三个 HSM 之前，该密钥位于 3 个中的 2 个上。 |

在 2 → 3 的情形里，法定人数自始至终都被满足，因此密钥永远不会*被阻止*。但满足数量为二的法定人数，并不等同于"这次调用被路由到的那个 HSM 拥有该密钥"，而 AWS 明确记载：被路由到一个没有该密钥的 HSM 的调用会**失败**。所以在这个窗口内调用仍可能失败，其记载的缓解措施是应用层重试。（把这两项记载中的行为组合起来是我们的推读；它未经实测。）

**服务端同步的间隔并未记载**，因此无法根据文档给追赶时间设定上界——AWS 只说它"可能变化，取决于你集群的工作负载以及其他难以量化的因素"，并指向 CloudWatch 来确定应用应采用的时机。

**如何检测某个密钥没有传播出去。**`AWS/CloudHSM` 命名空间中的 `HsmKeysTokenOccupied` 会按**每个 HSM 实例**以及按集群报告正在使用的 token 密钥数。AWS 自己的监控最佳实践建议对*"HSM 用户数或密钥数的差异进行告警，以识别同步问题"*——因此在一个集群的各个 HSM ID 之间比较该指标，就是官方支持的用于发现分歧的方式。持续存在的差异意味着密钥存在于某些 HSM 上而不在其他 HSM 上。注意 `HsmUsersAvailable` 为用户分歧提供了同样的抓手，而用户正是加入过程必须带过去的另一样东西。

这就补全了配置 B 的运维规则：在创建密钥前先清点 ACTIVE 的 HSM 数量，在依赖某个新密钥之前先确认它可用，并对每个 HSM 的密钥数分歧告警，好让同步失败是可见的，而不是靠一次失败的签名才被发现。

#### The same question for USERS and POLICIES, where the answer is worse

以上全部讲的是在加入窗口内创建的**密钥**，它有一个让人安心的形态：快照漏掉了该密钥，而服务端同步最终会把它带过去。**这个兜底对用户或策略并不存在。**本节此前只回答了加入过程中间创建密钥的问题，这使得情况看起来比实际要好。

AWS 毫无保留地陈述了这一点：*"与密钥不同，**没有服务端机制**在集群范围内同步 HSM 用户"*，以及*"用户和策略（例如 mTLS 设置）**不会自动重新同步**"*。CLI 的同步是**在你运行命令那一刻的尽力而为（best-effort）**，只作用于它当时能触达的那些 HSM。

那么追踪一下，在一个 HSM 加入期间创建的用户会遭遇什么：

1. 拍摄加入备份。它包含**在那一刻**存在的密钥、用户和策略。
2. 你创建一个用户。CLI 把它推送到当前在集群中的那些 HSM。正在加入的那个 HSM 不在其中——它还不是 ACTIVE，而且它马上就要被恢复操作覆盖掉。
3. 恢复完成。新 HSM 现在持有第 1 步的那份快照，其中**不**包含第 2 步的那个用户。
4. 没有任何机制修复这一点。不存在任何周期性的用户克隆来察觉这个差异。

**这是经过实测的（MEASURED），而非推断得来。**在一个加入窗口内每隔三十秒创建了十个用户，并在新 HSM 变为 ACTIVE 后读取它们的覆盖情况一次。快照时刻在结果里清晰可见：

```
create-hsm issued at epoch 1790239945; second HSM ACTIVE at t+313s
  u01  t+4s    -> "full"            u06  t+157s  -> "inconsistent"
  u02  t+34s   -> "full"            u07  t+188s  -> "inconsistent"
  u03  t+65s   -> "inconsistent"    u08  t+218s  -> "inconsistent"
  u04  t+96s   -> "inconsistent"    u09  t+249s  -> "inconsistent"
  u05  t+126s  -> "inconsistent"    u10  t+280s  -> "inconsistent"
```

在最初约 34 s 内创建的用户在快照里；从约 65 s 起的一切都不在。**这个区间只是在一个集群上的一次观测，绝不能被当作安全窗口来用**——其时序是未记载的。

而且它不会自愈。在**新 HSM 达到 ACTIVE 之后 879 秒（14.6 分钟）**，六个分歧的用户仍然是 `inconsistent`，而在同一区间内手动修复的两个用户则是 `full`——所以变量不是时间，而是修复动作。在同一窗口内较晚注册的一个信任锚（trust anchor）也出现了完全相同的分歧，下文那个修复测试所需的真正分歧的信任锚也正是这样被制造出来的。

其结果是一种**永久（permanent）**的分歧：该用户存在于旧 HSM 上而不在新的那个上，集群会一直以这种状态运行，直到有人手动修复它。由于客户端连接在各个 HSM 之间做负载均衡，其症状是**间歇性（intermittent）**的认证失败——同一次登录会成功还是失败，取决于它落到哪个 HSM 上。恰好把该用户的登录路由到新 HSM 的客户端会认证失败；路由到别处的则会成功。同样的追踪也适用于在该窗口内注册的 mTLS 信任锚，因为信任锚是一种策略对象。

**因此该运维规则与密钥并不对称，而这正是值得告诉客户的部分：**

> 在加入过程中创建的密钥会自行追赶上来。在加入过程中创建的**用户或策略**则**不会**。不要在一个 HSM 正在加入时执行用户管理，或注册、注销 mTLS 信任锚。先把 HSM 加进来，等它达到 ACTIVE，然后再做变更。

**万一还是发生了，如何检测。**`user list` 会报告每个用户的 `cluster-coverage`，而只存在于部分 HSM 上的用户会显示 `"cluster-coverage": "inconsistent"` 而非 `"full"`——那个字符串就是信号。对于信任锚，对应的是 `cluster mtls list-trust-anchors`，只有当每个当前的 HSM 都持有它时，其每个锚的 `cluster-coverage` 才是 `full`。两者都已被实测证实存在；关于修复它们哪些已验证、哪些未验证，见上文 mTLS 策略一节。

**修复现在对两者都已实测，而信任锚这一情形带来了意外。**对于用户，AWS 给出了明确的流程，且它干净利落地生效：把你开始的操作做完——如果该用户不该存在，就在两种角色下都执行 `user delete`；如果它应当存在，就再次执行 `user create`——并且如果管理员账户本身不一致，就**先修复管理员账户**，因为你需要一个一致的管理员才能去修复其他任何人。实测：对一个分歧的用户重新执行 `user create` 返回了 `error_code 0`，并把覆盖情况从 `inconsistent` → `full`。

对于信任锚，此项工作的较早一轮只能在一个健康的集群上测试，看到的是 `"Invalid Certificate: Trust anchor already exists."`，并把锚的修复记为一个悬而未决的问题。**这个问题现在通过在一个加入窗口内制造一次真正的分歧得到了回答，而答案推翻了此前的读法：记载中的修复方法有效（WORKS），却报告了 `error_code 1`。**

```
before:  "certificate-reference": "0x02",  "cluster-coverage": "inconsistent"
  cluster mtls register-trust-anchor --path ca2.crt
  -> { "error_code": 1,
       "data": "Certificate error received from Hsm. Trust anchor is already installed in Hsm." }
after:   "certificate-reference": "0x02",  "cluster-coverage": "full"
```

这个错误来自那个已经持有该锚的 HSM；缺失它的那个 HSM 收到了它。所以一个把非零返回码当作失败的运维人员会得出修复没有发生的结论。**验证一次锚的修复要靠重新读取 `cluster mtls list-trust-anchors`，绝不能靠退出码。**这两种情形确实会产生不同的消息文本——当每个 HSM 都已拥有它时是 `"Invalid Certificate: Trust anchor already exists."`，而当执行了一次修复时是 `"Certificate error received from Hsm..."`——但这个区别是未记载的，不应依赖它。先注销再注册（deregister-then-register）也被实测有效，且两次都返回 `error_code 0`，代价是出现一个锚缺失的窗口，以及占用仅有的两个锚位之一。

这一切面向客户受众的写法，连同测量时间线和完整的命令输出，见 [`CLOUDHSM_ADD_HSM_FAQ.md`](CLOUDHSM_ADD_HSM_FAQ.md)。

### `cluster-coverage: full` does not mean what it looks like

这是整个这一块领域里的陷阱，它使那个显而易见的安全检查失效。

一个**在集群已降级为单个 HSM 时**创建的密钥会报告：

```
"cluster-coverage": "full"
```

`full` 的意思是*当前存在于集群中的每个 HSM 上都有*——而当时只有一个。所以一个只存在于单个 HSM 上、会随那个 HSM 一起丢失的密钥，报告出的覆盖字符串，与一个安全地复制到两个 HSM 上的密钥完全相同。**覆盖是相对于当前集群成员而言的；它不是一个持久性度量。**

因此，如果你运行配置 B，运维规则**不是**"创建密钥后检查 `cluster-coverage`"——那个检查不可能失败。规则是：

> **在创建或导入任何密钥之前，先确认集群至少有两个 ACTIVE 的 HSM。**
> 清点 HSM 数量（`describe-clusters`，或 `cloudhsm-cli cluster hsm-info`），不要去读一个覆盖字符串。

对这个工作负载来说，这条规则很容易遵守：PSP 签名密钥和 mTLS 客户端密钥在预置时生成一次，并只在轮换时再生成一次，两者都是有计划的活动。

### Choosing between A and B

- **A 买到的是强制约束，而不仅仅是余量。**集群拒绝使用一个复制不足的密钥，所以它不可能因意外而发生。不需要任何人的自律。代价：多一个 HSM。
- **B 用两个 HSM 的钱买到同样的可用性**，代价是一条运维规则和一道更薄的裕度：如果幸存的那个 HSM 在替代者完成同步之前就故障，恢复就得从备份进行。AWS 每天做一次自动备份，并在集群生命周期事件（例如添加或移除一个 HSM）时做额外备份，因此对一个静态签名密钥而言这不会丢失任何东西，而对上次备份以来创建的密钥则会丢失它们。

**可用区（Availability Zone）的放置是选择 A 的一部分。**三个 HSM 只分布在**两个** AZ 上并不能容忍一次 AZ 故障：丢失那个承载其中两个的 AZ 会只剩一个，低于法定人数，这次中断与配置 C 完全相同。把每个 HSM 放在各自的 AZ 里。

### Client timeouts must be set deliberately

配置 C 中的降级失败是**慢的，而非快的**——错误浮现之前要 87 秒。在一条同步的 Pix 请求路径上，这意味着请求会堆积起来而不是快速失败，因此无论选择哪种配置，都必须设置一个低于 Pix 响应预算的客户端超时。代理的 BCB 端点正是出于这个原因设置了 `requestTimeout(30_000L)`。

### Audit coverage of HSM add/remove is split across two sources

一个正在加入的 HSM，其审计流以 `CN_RESTORE_BEGIN`、`PARTITION_BACKUP_RESTORE_LOG`、`CERT_AUTH/RSA/KEK` 开头——把密钥复制进一个新 HSM **是**可审计的。**移除不会被记录为一个事件**：被删除的那个 HSM 的流只是以 `END_MARKER_OPCODE` 结束。仅凭 CloudHSM 日志，一次蓄意的删除和一次崩溃是无法区分的。

生命周期存在于 **CloudTrail** 中（`CreateHsm`、`DeleteHsm`、`InitializeCluster`，并归因到某个主体），它还记录**被拒绝**的尝试。审计追踪必须同时采集**两个**来源；单凭 HSM 日志无法回答"是谁移除了一个 HSM"。

### Operational sequencing traps, all measured

1. **在集群被完全 ACTIVATED 之前无法添加第二个 HSM**，仅仅完成初始化是不够的：`CreateHsm` 会被拒绝，报 *"already contains an HSM but has not yet been fully activated"*。激活意味着用客户端登录并设置管理员密码。
2. **控制平面滞后于数据平面。**`cluster activate` 返回了成功，而 `describe-clusters` 在约 80 s 内仍然读到 `INITIALIZED`，在这整个窗口内 `CreateHsm` 都被拒绝。在拆除时，两次 HSM 删除都被接受了，而集群仍报告有 2 个 HSM 持续约 120 s，因此仅凭 API 响应就发出的 `delete-cluster` 会失败。自动化必须轮询状态，而不是信任即时的应答。
3. **在安全组允许之前，客户端无法触达一个 HSM。**HSM 的 ENI 只携带集群自己的安全组，该安全组允许组内流量，因此处于不同安全组中的实例在 2223 端口上什么都收不到。症状具有误导性：激活失败并报 *"Failed to initialize hsm1 context"*，这读起来像是集群类型不匹配，实际却纯粹是网络隔离。把集群安全组附加到客户端实例上。
4. **5.18.0 中的 `configure-cli` 接受不带子命令的选项。**记载中的 `configure-cli update -a <ip>` 形式来自更旧的 SDK 5，会被拒绝并报 *"unrecognized subcommand 'update'"*。此外 `-a <HSM ENI IP>...` 是**可变参数（variadic）**——两个地址放在同一个 `-a` 里，重复该标志会失败。
5. **在 Amazon Linux 2023 上 `rpm -E %rhel` 返回字面量 `%rhel`。**通过尝试候选值来发现 EL 版本，而不是信任那个宏。
6. **默认的密钥属性对一个 PSP 签名密钥是错误的。**在没有显式属性的情况下生成时，私钥返回的是 `extractable: true`、`never-extractable: false`、`sign: false`——可导出且无法签名。要显式传入 `--private-attributes sign=true extractable=false`。同样的缺口也存在于 JCE 路径上，那里 SDK 5 把 `extractable` 默认为 true。
## 两种不同的机制都被称为 "quorum"，混淆二者是真实的隐患

CloudHSM 用 *quorum* 这个词指代两种互不相关的机制。一种统计 **HSM**，另一种统计**人**。它们出现在同一条命令的输出里，这正是二者被混淆的原因。

| | **密钥可用性 quorum** | **Quorum 认证（M of N）** |
|---|---|---|
| 统计对象 | 持有该密钥的 **HSM** | 批准该操作的**用户** |
| 目的 | 密钥持久性，以及消除创建后的路由竞态 | 多人管控、职责分离 |
| 强制执行方 | 客户端配置加上当前集群成员关系 | 在 HSM 内部，对照已注册的公钥进行验证 |
| 配置方式 | `configure-cli --disable-key-availability-check` | `cloudhsm-cli` quorum 命令；每个密钥的值在**密钥生成时**设定 |
| 默认值 | **启用** —— 至少两个 HSM | **关闭** —— quorum 值为 0 |
| 阻止 | 创建或使用副本不足的密钥 | 批准数不足的操作 |

两者都能在一条 `key list --verbose` 的响应里看到。`cluster-coverage` 和可用性行为属于前者；`key-quorum-values` 属于后者：

```
"key-quorum-values": {
  "manage-key-quorum-value": 0,      <- M of N for key MANAGEMENT
  "use-key-quorum-value": 0          <- M of N for key USE (signing)
},
"cluster-coverage": "full"           <- unrelated: availability, not approvals
```

第一种机制已在上文讲过。本节讨论的是第二种。

### M of N 能控制什么

没有任何单个用户能执行受 quorum 控制的操作；必须有最少数量的用户——在 **2 到 8** 之间——协同配合。受控操作被归类为若干 *service*（服务），而这种分组方式对支付类工作负载至关重要：

| 服务 | 角色 | 操作 |
|---|---|---|
| `user` | Admin | `user create`, `user delete`, `user change-password`, `user change-mfa` |
| `quorum` | Admin | `quorum token-sign set-quorum-value` |
| `cluster` | Admin | `cluster mtls register-trust-anchor`, `deregister-trust-anchor`, `set-enforcement` —— **仅 hsm2m.medium** |
| `key-management` | Crypto User | `key wrap`, `key unwrap`, `key share`, `key unshare`, `key set-attribute` |
| `key-usage` | Crypto User | `key sign` |
| `registration` | 均可 | 为 quorum 认证注册一个公钥 |

### 批准流程

1. 每个用户在 **HSM 之外生成一对 RSA-2048 签名密钥**，并自行保管——HSM 从不持有它。
2. 每个用户登录并注册自己的**公**钥：
   `user change-quorum token-sign register --public-key <pub.pem> --signed-token <tokenfile>`。
3. 想执行受控操作的用户获取一个 token：
   `quorum token-sign generate --service key-management --token <path> --filter attr.label=<label>`。
   对于密钥类服务，该 token 通过这个 filter **绑定到特定的密钥**。
4. 批准者在 **HSM 之外**对 token 的 `token` 字段——即 `approval_data` 的 SHA-256 摘要——进行签名，例如使用 `openssl pkeyutl -sign -pkeyopt digest:sha256`，然后把他们的 base64 签名连同用户名和角色一起粘贴进 token 文件的 `signatures` 数组。
5. 请求者用 `--approval <token file>` 运行该操作。
6. HSM 对照已注册的公钥验证每一个签名，只有验证通过才执行该操作。

签名发生在 HSM 之外，验证发生在 HSM 之内，因此批准者的私钥从不进入 HSM，而伪造一次批准必须拥有那把私钥。

### 实测：两个密钥 quorum 值无法分开设置

本节的早先版本曾建议设置 `manage-private-key-quorum-value` 而不动 `use-private-key-quorum-value`，从而让密钥管理需要批准、而签名不需要。**这一点经过实测，它不可实现。** 以下是三次密钥生成的尝试：

| `--manage-private-key-quorum-value` | `--use-private-key-quorum-value` | 结果 |
|---|---|---|
| 2 | 0 | **被拒绝** —— `The manage key and use key quorum values must be set to a value greater than 1` |
| 2 | 2 | 接受 |
| 0 | 0 | **被拒绝** —— 同样的错误 |

只提供其中一个 flag 而不提供另一个会导致 CLI 报错，且两个值都必须大于 1。要做到没有密钥 quorum，唯一的办法是完全省略这两个 flag，从而让它们的值保持为 0。所以密钥级别的 quorum 认证是**按密钥要么全有、要么全无**：对管理设门槛必然会对签名也设门槛。

在一把 `manage=2, use=2` 的密钥上端到端实测的结果：

```
sign, no approval          -> error_code 1  "Quorum Failed"
sign, two approvals        -> error_code 0  signature returned
sign, same token reused    -> error_code 1  "Invalid username-signature pair for quorum authorization"
sign, one approval only    -> error_code 1  "Too few quorum approvals: currently 1 approvals, but 2 approvals required"
set-attribute, 2 approvals -> error_code 0  "Attribute set successfully"
```

Token 是一次性的，且 M 被精确强制执行。因此 **密钥级别的 quorum 对自动化的 Pix 签名密钥不可用**：每一次签名都需要一个新生成的 token 加上一轮新的人工批准。

### 那么什么在保护签名密钥

密钥级别 quorum 本应防范的威胁是有人导出或削弱该密钥。在一把**完全没有** quorum 的密钥上实测，这一威胁已经被属性所封堵：

```
key set-attribute --name extractable       --value true
  -> "Attribute extractable cannot be set to the value true by the user for this operation"
key set-attribute --name never-extractable  --value false
  -> "Attribute never-extractable cannot be set to the value false by the user for this operation"
```

之后属性保持不变。以 `extractable=false` 和 `never-extractable=true` 生成的密钥日后无法被改成可导出，因此无论有没有 quorum 都无法被 wrap 导出。这就消除了这里当初想用密钥级别 quorum 来解决的大部分需求。

**quorum 认证真正值得使用的地方**是管理侧，其值可以用 `quorum token-sign set-quorum-value --service <user|quorum|cluster>` 独立设置——注意该命令只接受这三种服务，绝不接受密钥类服务：

- **`user`** —— 没有任何单个管理员能独自创建一个 crypto-user。这很重要，因为新的 crypto-user 可以生成自己的密钥并用它签名。
- **`quorum`** —— 没有任何单个管理员能降低 quorum 值。
- **`cluster`** —— 在 `hsm2m.medium` 上，没有任何单个管理员能更改客户端到 HSM 的 mTLS 强制策略或其信任锚。

这些都不触及签名路径。

### 两种 token 格式，而文档只展示了一种

`cloudhsm-cli` 5.18.0 会根据服务发出**不同的 token 结构**，在针对它编写任何自动化之前这一点很关键：

```
registration service:
  { "version": "2.0",
    "tokens":     [ { "approval_data": "...", "unsigned": "<b64 sha256>", "signed": "" } ],
    "signatures": [ { "username": "...", "role": "...", "signature": "" } ] }

key-usage / key-management:
  { "version": "2.0", "service": "key-usage", "key_reference": "0x...",
    "approval_data": "...", "token": "<b64 sha256>", "signatures": [] }
```

AWS 文档只展示了**扁平**的密钥服务形态。registration token 里没有 `token` 字段——要签名的值是 `tokens[].unsigned`——所以把文档里的形态套用到 registration token 上会得到一个空签名和 `InvalidQuorumSignature`。反过来，把 registration 形态套用到密钥 token 上则以另一种方式失败，报 `No token signatures provided`。一个签名辅助程序必须同时处理这两种情况。

两种情况下，被签名的值都是一个 32 字节的 SHA-256 摘要，按原样签名而不是重新哈希：

```
openssl pkeyutl -sign -inkey <approver.key> -pkeyopt digest:sha256 -keyform PEM \
  -in <decoded digest> -out <sig>
```

Registration 本身就是一个 quorum-token 操作，这看起来像是循环依赖，其实不是：该 token 来自 `registration` 服务，并由正在注册的用户**自己**的私钥签名，而这恰恰是 HSM 在信任那把公钥之前所需要的持有性证明。

### 锁死风险

AWS 自己的建议是**保持比 M 值至少多两名管理员**，这样即使有一名被锁在外，其他人仍能重置密码。删除用户是危险操作：如果可用批准者的数量降到 M 以下，**你将再也无法创建用户或授权任何操作，并且丧失管理该集群的能力**。文档给出的恢复办法是把备份还原到一个**新集群**里。

值得了解的 token 维护细节：默认情况下 token 在创建**十分钟**后过期；一个 HSM 最多存储 **1,024** 个 token，在存满时会清除一个已过期的；用户可以对自己的 token 签名，这算作所需批准中的一个；而“每个用户每个服务只能有一个活动 token”的限制适用于 `user` 和 `quorum` 服务，但**不**适用于密钥类服务。启用 MFA 时，**同一把密钥**同时用于 MFA 和 quorum 认证。

## 以下是建议的架构

这里展示的架构可以成为一个更完整的[基于事件的解决方案](https://aws.amazon.com/en/event-driven-architecture/)的一部分，该方案能够覆盖从银行核心系统开始的整个支付报文传输流程。例如，金融机构（付款方或收款方）的完整解决方案，可以包含其他互补架构，例如**授权（Authorization）**、**撤销（Undo）**（基于 [SAGA 模型](https://docs.aws.amazon.com/whitepapers/latest/microservices-on-aws/distributed-data-management.html)）、**生效（Effectiveness）**、**与本地环境的通信**（[混合环境](https://aws.amazon.com/en/hybrid/)）等，并使用其他服务，例如 [Amazon EventBridge](https://aws.amazon.com/en/eventbridge/)、Amazon Simple Notification Service（[SNS](https://aws.amazon.com/en/sns/?whats-new-cards.sort-by=item.additionalFields.postDateTime&whats-new-cards.sort-order=desc)）、Amazon Simple Queue Service（[SQS](https://aws.amazon.com/en/sqs/)）、[AWS Step Functions](https://aws.amazon.com/en/step-functions/)、[Amazon ElastiCache](https://aws.amazon.com/en/elasticache/)、[Amazon DynamoDB](https://aws.amazon.com/en/dynamodb/)。

<p align="center">
  <img src="/images/proxy-cloudhsm-arch.png" width="600" height="600">

> 这张图是**生成的**，不是手绘的。它的源文件是
> [`tools/generate_architecture_diagram.py`](tools/generate_architecture_diagram.py)，它组合了
> 官方的 [AWS Architecture Icons](https://aws.amazon.com/architecture/icons/)（发布版本
> `07312026`）。当 AWS 发布新的图标版本或重命名某个服务时，请重新运行它——如果脚本预期的某个图标
> 已不在包中，它会**高声**报错失败，*Kinesis Data Firehose → Data Firehose* 和 *QuickSight → Quick* 的重命名
> 正是这样被发现的。图标集本身是刻意不提交到这里的；请从上面的链接下载它并传入 `--icons`。
</p>


1. 在 [AWS Secrets Manager](https://aws.amazon.com/en/secrets-manager/) 中存储登录名和密码，用于与 AWS CloudHSM 通信。
2. 在 [AWS CloudHSM](https://aws.amazon.com/cloudhsm/?nc1=h_ls) 上存储或导入私钥。
3. 在 [AWS Systems Manager Parameter Store](https://docs.aws.amazon.com/systems-manager/latest/userguide/systems-manager-parameter-store.html) 中存储三份证书：为签名生成的密钥证书、为 mTLS 生成的证书、CloudHSM 证书（客户 CA）。
4. 服务/应用以 XML 格式发送交易请求。
5. [ELB](https://aws.amazon.com/en/elasticloadbalancing/) 在 [AWS Fargate 容器](https://aws.amazon.com/en/fargate/)之间均衡请求。
6. 应用（AWS Fargate）使用 AWS CloudHSM 对 XML 进行数字签名。
7. 应用（AWS Fargate）使用 AWS CloudHSM 建立 mTLS 并将 XML 传输给 [BACEN](https://www.bcb.gov.br/en/financialstability/instantpayments)。
8. 应用（AWS Fargate）接收来自 BACEN 的响应，并在必要时验证所收到 XML 的数字签名。
9. 应用（AWS Fargate）通过将请求日志直接发送到 [Amazon Data Firehose](https://aws.amazon.com/en/kinesis/data-firehose/) 来记录请求日志。
10. 回复报文被发送到 ELB。
11. 回复报文被服务/应用接收。
12. Amazon Data Firehose 使用 [AWS Glue Data Catalog](https://aws.amazon.com/en/glue/?whats-new-cards.sort-by=item.additionalFields.postDateTime&whats-new-cards.sort-order=desc) 将日志转换为 parquet 格式。
13. Amazon Data Firehose 将日志发送到 [Amazon S3](https://aws.amazon.com/en/s3/)，已按“文件夹”（/year/month/day/hour/）分区。
14. [Amazon Athena](https://docs.aws.amazon.com/athena/latest/ug/glue-athena.html) 使用 AWS Glue Data Catalog 作为存储和检索表元数据的中心位置。
15. [AWS Glue crawlers](https://docs.aws.amazon.com/glue/latest/dg/add-crawler.html) 每小时自动更新元数据仓库。
16. 你可以使用无服务器分析服务立即直接在 Amazon S3 上查询数据，例如 [Amazon Athena](https://aws.amazon.com/en/athena/?whats-new-cards.sort-by=item.additionalFields.postDateTime&whats-new-cards.sort-order=desc)（使用标准 SQL 进行即席查询）以及可选的 [Amazon Quick](https://aws.amazon.com/quicksight/)（原 **Amazon QuickSight**）。
## 如何部署？

### AWS CloudHSM

以下是跟随这两种架构进行操作时你需要准备的资源：

- 一个包含以下组件的 Amazon Virtual Private Cloud (Amazon VPC)：

位于某个可用区（Availability Zone）中的私有子网，用于 HSM 的弹性网络接口（ENI）。
一个包含网络地址转换（NAT）网关的公有子网。
一个私有子网，其路由表将互联网流量（0.0.0.0/0）路由到 NAT 网关。你将使用此子网来运行 AWS Fargate 应用程序。NAT 网关允许你连接到 AWS CloudHSM、[AWS Systems Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/setup-create-vpc.html) 以及 [AWS Secrets Manager 终端节点](https://docs.aws.amazon.com/secretsmanager/latest/userguide/vpc-endpoint-overview.html#vpc-endpoint)。

 **注意**：为实现高可用性，你可以添加多个公有子网和私有子网实例。有关如何创建带有公有和私有子网以及 NAT 网关的 Amazon VPC 的更多信息，请参阅 [Amazon VPC 用户指南](https://docs.aws.amazon.com/vpc/latest/userguide/VPC_Scenarios.html)。

- 一个**处于活动状态的 AWS CloudHSM 集群**，至少包含一个活动的 HSM。HSM 应在私有子网中创建。你可以按照 [AWS CloudHSM 指南](https://docs.aws.amazon.com/cloudhsm/latest/userguide/create-cluster.html)中的入门内容来创建并初始化 CloudHSM 集群。

> ### ⚠️ 此前提条件已无法按原文所述满足
>
> 本示例的代码硬编码绑定到 **CloudHSM Client SDK 3**（`com.cavium.cfm2.LoginManager`、
> `PARTITION_1`、`key_mgmt_util`、`cloudhsm-client-jce-latest.el7.x86_64.rpm`），它只能与
> `hsm1.medium` HSM 类型配合使用。根据 AWS 自己的
> [弃用通知](https://docs.aws.amazon.com/cloudhsm/latest/userguide/compliance-dep-notif.html)
> 和 [HSM 类型页面](https://docs.aws.amazon.com/cloudhsm/latest/userguide/hsm-types.html)：
>
> * **自 2025 年 4 月起已无法创建新的 `hsm1.medium` 集群**；
> * `hsm1.medium` **已于 2026-03-31 结束支持**；
> * 现有的 `hsm1.medium` 集群自 2026 年 1 月起已被自动迁移到 `hsm2m.medium`；
> * `hsm2m.medium` **需要 Client SDK 5.9.0 或更高版本**。
>
> 因此，旧的 HSM 类型已无法创建，而这段代码又无法与新类型通信。要让
> CloudHSM 路径重新工作，需要**将 SDK 3 移植到 SDK 5**（一套不同的 API），*并且*迁移到
> JDK 17/21（SDK 5 的 JCE 仅支持 OpenJDK 17/21/25），*并且*添加两个 `--add-exports` 标志，
> 因为若没有它们，本项目在 JDK 17 上运行时会抛出 `IllegalAccessError`。这三项
> 改动是一个整体。
>
> **[README-KMS.md](README-KMS.md) 中的 KMS 架构不受影响**，如果你只是想运行本示例，它就是应当
> 使用的路径。测量所得的证据见 [VERIFICATION.md](VERIFICATION.md)。

- 安装并配置好、用于连接到 CloudHSM 集群的 **AWS CloudHSM 客户端**。你也可以选择使用一台安装并配置了 CloudHSM 客户端的 Amazon Linux 2 EC2 实例。客户端实例应在公有子网中启动。你可以再次参考 [AWS CloudHSM 入门](https://docs.aws.amazon.com/cloudhsm/latest/userguide/getting-started.html)来配置并连接客户端实例。另外，请安装 [AWS CloudHSM Dynamic Engine for OpenSSL](https://docs.aws.amazon.com/cloudhsm/latest/userguide/openssl-library-install.html)。

- 按照[用户指南](https://docs.aws.amazon.com/cloudhsm/latest/userguide/manage-hsm-users.html#create-user)中的步骤创建 **CO** 和 **CU 凭证**：CO（crypto officer，加密管理员）和 CU（crypto user，加密用户）。

#### Generate keys and certificate to digital signature

你可以[使用 OpenSSL 生成或导入私钥](https://docs.aws.amazon.com/cloudhsm/latest/userguide/ssl-offload-import-or-generate-private-key-and-certificate.html)。 
我们建议私钥应设置为不可导出（non-extractable）。

- 生成一个[不可导出（NON-EXTRACTABLE）](https://docs.aws.amazon.com/cloudhsm/latest/userguide/key_mgmt_util-genRSAKeyPair.html)的私钥：

启动 key management util：
```
$ /opt/cloudhsm/bin/key_mgmt_util
```

登录：
```
Command: loginHSM -u CU -s <HSM_USER> -p <HSM_PASSWORD>
```

生成密钥对：
```
Command: genRSAKeyPair -m 2048 -e 65537 -l <LABEL> -nex 

Cfm3GenerateKeyPair:    public key handle: <X>    private key handle: <Y>
```

退出
```
Command: exit
```

启动 CloudHSM management util：
```
$ /opt/cloudhsm/bin/cloudhsm_mgmt_util /opt/cloudhsm/etc/cloudhsm_mgmt_util.cfg
```

登录：
```
aws-cloudhsm> loginHSM CU <HSM_USER> <HSM_PASSWORD>
```

检查私钥是否不可导出：
```
aws-cloudhsm> getAttribute <Y> 354

OBJ_ATTR_EXTRACTABLE
0x00000000
```

请注意，公钥始终是可导出的：
```
aws-cloudhsm> getAttribute <X> 354

OBJ_ATTR_EXTRACTABLE
0x00000001
```

检查私钥标签：
```
aws-cloudhsm> getAttribute <Y> 3

OBJ_ATTR_LABEL
<LABEL>
```

检查公钥标签：
```
aws-cloudhsm> getAttribute <X> 3

OBJ_ATTR_LABEL
<LABEL>
```

更改公钥标签：
```
aws-cloudhsm> setAttribute <X> 3 <LABEL:PUBLIC>
```

检查公钥标签：
```
aws-cloudhsm> getAttribute <X> 3

OBJ_ATTR_LABEL
<LABEL:PUBLIC>
```

退出
```
aws-cloudhsm> quit
```

启动 key management util：
```
$ /opt/cloudhsm/bin/key_mgmt_util
```
登录：
```
Command: loginHSM -u CU -s <HSM_USER> -p <HSM_PASSWORD>
```

导出这个假的私钥（fake private key）：
```
Command: getCaviumPrivKey -k <Y> -out <LABEL>.key
```

退出
```
Command: exit
```

导出 HSM_USER 和 HSM_PASSWORD 以便与 OpenSSL 一起使用：
```
$ export n3fips_password=<HSM_USER>:<HSM_PASSWORD>
```

生成 CSR：
```
$ openssl req -engine cloudhsm -new -key <LABEL>.key -out <LABEL>.csr
```

生成自签名证书（仅用于测试）：
```
$ openssl x509 -engine cloudhsm -req -days <DAYS> -in <LABEL>.csr -signkey <LABEL>.key -out <LABEL>.cer
```

#### Generate keys and certificate to mTLS

- 为 mTLS 生成一个可导出的密钥（Cavium 有 JCE，但没有 JSSE）：

启动 key management util：
```
$ /opt/cloudhsm/bin/key_mgmt_util
```

登录：
```
Command: loginHSM -u CU -s <HSM_USER> -p <HSM_PASSWORD>
```

生成密钥对：
```
Command: genRSAKeyPair -m 2048 -e 65537 -l <LABEL>

Cfm3GenerateKeyPair:    public key handle: <X>    private key handle: <Y>
```

退出
```
Command: exit
```

启动 CloudHSM management util：
```
$ /opt/cloudhsm/bin/cloudhsm_mgmt_util /opt/cloudhsm/etc/cloudhsm_mgmt_util.cfg
```

登录：
```
aws-cloudhsm> loginHSM CU <HSM_USER> <HSM_PASSWORD>
```

检查私钥是否可导出：
```
aws-cloudhsm> getAttribute <Y> 354

OBJ_ATTR_EXTRACTABLE
0x00000001
```

公钥始终是可导出的：
```
aws-cloudhsm> getAttribute <X> 354

OBJ_ATTR_EXTRACTABLE
0x00000001
```

检查私钥标签：
```
aws-cloudhsm> getAttribute <Y> 3

OBJ_ATTR_LABEL
<LABEL>
```

检查公钥标签：
```
aws-cloudhsm> getAttribute <X> 3

OBJ_ATTR_LABEL
<LABEL>
```

更改公钥标签：
```
aws-cloudhsm> setAttribute <X> 3 <LABEL:PUBLIC>
```

再次检查公钥标签：
```
aws-cloudhsm> getAttribute <X> 3

OBJ_ATTR_LABEL
<LABEL:PUBLIC>
```

退出
```
aws-cloudhsm> quit
```

启动 key management util：
```
$ /opt/cloudhsm/bin/key_mgmt_util
```

登录：
```
Command: loginHSM -u CU -s <HSM_USER> -p <HSM_PASSWORD>
```

导出这个假的私钥（fake private key）：
```
Command: getCaviumPrivKey -k <Y> -out <LABEL>.key
```

退出
```
Command: exit
```

导出 HSM_USER 和 HSM_PASSWORD 以便与 OpenSSL 一起使用：
```
$ export n3fips_password=<HSM_USER>:<HSM_PASSWORD>
```

生成 CSR：
```
$ openssl req -engine cloudhsm -new -key <LABEL>.key -out <LABEL>.csr
```

生成自签名证书（仅用于测试）：
```
$ openssl x509 -engine cloudhsm -req -days <DAYS> -in <LABEL>.csr -signkey <LABEL>.key -out <LABEL>.cer
```

### AWS Secrets Manager

[创建](https://docs.aws.amazon.com/secretsmanager/latest/userguide/tutorials_basic.html)一个名为 `/pix/proxy/cloudhsm/CloudHSMSecret` 的密钥（secret），其值为：
```
{
  "HSM_USER": "<HSM_USER>",
  "HSM_PASSWORD": "<HSM_PASSWORD>"
}
```

### Register (log audit)

1. 在你能够向 Amazon S3 上传数据之前，你必须先在某个 AWS 区域中[创建一个存储桶](https://docs.aws.amazon.com/AmazonS3/latest/user-guide/create-bucket.html)来存储数据。创建存储桶后，你可以向该存储桶上传数量不限的数据对象

2. AWS Glue Data Catalog 包含对数据的引用，这些数据被用作 AWS Glue 中提取、转换和加载（ETL）作业的源和目标。Data Catalog 中的信息以元数据表的形式存储，其中每个表指定一个[单一数据存储](https://docs.aws.amazon.com/glue/latest/dg/populate-data-catalog.html)。

3. 在你的 Data Catalog 中[定义一个数据库](https://docs.aws.amazon.com/glue/latest/dg/populate-data-catalog.html)。

4. [定义两个表](https://docs.aws.amazon.com/glue/latest/dg/tables-described.html)：SPI 和 DICT。这两个表都需要具备[列结构（Columns Structure）](https://docs.aws.amazon.com/glue/latest/dg/aws-glue-api-catalog-tables.html#aws-glue-api-catalog-tables-Column)和[分区键（Partition Keys）](https://docs.aws.amazon.com/glue/latest/dg/tables-described.html#tables-partition)，如下例所示：

```
columns: [
    {name: 'request_date', type: glue.Schema.STRING},
    {name: 'request_method', type: glue.Schema.STRING},
    {name: 'request_path', type: glue.Schema.STRING},
    {name: 'request_query', type: glue.Schema.STRING},
    {name: 'request_header', type: glue.Schema.STRING},
    {name: 'request_body', type: glue.Schema.STRING},
    {name: 'response_status_code', type: glue.Schema.INTEGER},
    {name: 'response_signature_valid', type: glue.Schema.STRING},
    {name: 'response_header', type: glue.Schema.STRING},
    {name: 'response_body', type: glue.Schema.STRING},
    {name: 'transport_failure', type: glue.Schema.STRING}
],
            
partitionKeys: [
    {name: 'year', type: glue.Schema.STRING},
    {name: 'month', type: glue.Schema.STRING},
    {name: 'day', type: glue.Schema.STRING},
    {name: 'hour', type: glue.Schema.STRING}
]
```

DICT 表必须指向你所创建的 S3 存储桶，并且必须使用前缀 `log/dict`。
<br/>
SPI 表必须指向你所创建的 S3 存储桶，并且必须使用前缀 `log/spi`。

5. [创建一个爬网程序（crawler）](https://docs.aws.amazon.com/glue/latest/dg/add-crawler.html)，其目标指向已创建的表（SPI 和 DICT）。

6. 在 AWS Glue Data Catalog 中创建两个 Amazon Data Firehose 传输流：（SPI 和 DICT），以便将数据转换为 parquet 格式。这样，我们就得到了以下前缀以及目标 S3 存储桶：

  * DICT 传输流：
    * 传输到已创建的 S3 存储桶
    * 使用 Glue DICT 表转换为 PARQUET
    * 指定：
```
prefix: log/dict/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/hour=!{timestamp:HH}/
errorOutputPrefix: error/dict/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/hour=!{timestamp:HH}/!{firehose:error-output-type}
```

   * SPI 传输流：
      * 传输到已创建的 S3 存储桶
      * 使用 Glue SPI 表转换为 PARQUET
      * 指定：
```
prefix: log/spi/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/hour=!{timestamp:HH}/
errorOutputPrefix: error/spi/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/hour=!{timestamp:HH}/!{firehose:error-output-type}
```

### Alarms on unaudited transactions

未经审计的 Pix 交易是一个合规事件，而到目前为止这里没有任何东西对其进行监视。下面的
`errorOutputPrefix` 已配置，因此 Firehose 无法传输的记录会被写入 S3——但
一个没有人查看的前缀并不构成一种控制手段。以下这些告警能让现有的管道
变得可观测。

**应针对日志令牌（token）告警，而不是针对日志措辞。** 代理会在每条人类可读语句
旁边发出稳定的令牌，这些令牌在 `AuditAlarmTokens` 中声明。一个匹配散文措辞的指标筛选条件，
会在有人第一次改进某条消息时悄无声息地停止触发——而一个已经沉默的告警读起来
恰好就像“一切正常”。**更改其中任何一个字符串都会破坏一个已部署的告警；应将它们
视为一个已发布的接口。**

| 令牌 | 含义 | 严重程度 |
|---|---|---|
| `PIX_AUDIT_SPOOLED` | 一条记录无法传输，转而进入了本地缓冲队列（spool） | 高——可恢复，但前提是缓冲队列中的数据被发送出去 |
| `PIX_AUDIT_QUEUE_FULL` | 旁路传输跟不上流量 | 高——持续出现意味着记录正在不断进入缓冲队列 |
| `PIX_AUDIT_NO_RECORD` | 一次交换完全没有产生审计记录 | 高——没有任何内容被发送到 BCB，但这一缺口无法解释 |
| `PIX_AUDIT_SPOOL_WRITE_FAILED` | 既未传输也未持久化 | **严重——记录确实已丢失** |

```typescript
// 每个令牌对应一个指标筛选条件；将 PIX_AUDIT_SPOOL_WRITE_FAILED 视为值得触发呼叫（page）的级别。
const auditLost = new logs.MetricFilter(this, 'AuditRecordLost', {
    logGroup: proxyLogGroup,
    filterPattern: logs.FilterPattern.literal('"PIX_AUDIT_SPOOL_WRITE_FAILED"'),
    metricNamespace: 'Pix/Audit',
    metricName: 'AuditRecordLost',
    metricValue: '1',
    defaultValue: 0,          // REQUIRED: without it the metric has no datapoints while healthy,
});                           // and the alarm sits in INSUFFICIENT_DATA rather than OK

new cloudwatch.Alarm(this, 'AuditRecordLostAlarm', {
    metric: auditLost.metric({period: cdk.Duration.minutes(1), statistic: 'Sum'}),
    threshold: 0,
    comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
    evaluationPeriods: 1,     // a single lost audit record is already reportable
    treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
});
```

**同时也要针对 Firehose 一侧告警**，因为上面的令牌无法察觉在代理
把记录移交出去之后发生的故障：

```typescript
// Firehose 已接受但无法传输的记录——这些会落到 errorOutputPrefix 下。
new cloudwatch.Alarm(this, 'AuditDeliveryFailedAlarm', {
    metric: new cloudwatch.Metric({
        namespace: 'AWS/Firehose',
        metricName: 'DeliveryToS3.Success',
        dimensionsMap: {DeliveryStreamName: dictAuditStream.deliveryStreamName!},
        period: cdk.Duration.minutes(5),
        statistic: 'Average',
    }),
    threshold: 1,             // Average < 1 means some puts are failing
    comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
    evaluationPeriods: 1,
    treatMissingData: cloudwatch.TreatMissingData.BREACHING,   // no data = not delivering
});

// 新鲜度（Freshness）用于捕获一条悄然停滞、而非报错的流。
new cloudwatch.Alarm(this, 'AuditDataFreshnessAlarm', {
    metric: new cloudwatch.Metric({
        namespace: 'AWS/Firehose',
        metricName: 'DeliveryToS3.DataFreshness',
        dimensionsMap: {DeliveryStreamName: dictAuditStream.deliveryStreamName!},
        period: cdk.Duration.minutes(5),
        statistic: 'Maximum',
    }),
    threshold: 900,          // reconcile with the buffering hint actually configured
    comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
    evaluationPeriods: 2,
});
```

关于 `treatMissingData` 的说明：这两个 Firehose 告警特意使用了 **BREACHING**。一条已停止接收
任何内容的传输流不会发布数据点，因此 `NOT_BREACHING` 会让一条完全失效的审计管道停留在
`OK` 状态。那正是最值得捕获、也最容易配置错误的故障模式。

尚未完成，且刻意不作声称：本仓库中并没有把这些内容接入 CDK，因为这里没有可供接入的 CDK 应用——这套
基础设施是被记录下来的，而非已部署的。代码目前所能保证的是：上述每一种情况都会
发出一个稳定、可被 grep 检索的令牌。

### AWS Systems Manager Parameter Store

1. [创建](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html)一个参数 `/pix/proxy/cloudhsm/CloudHSMClusterId`，其值为：
```
<CLOUDHSM_CLUSTER_ID>
```
找出 CloudHSM Cluster Id 很简单。在 AWS 控制台中输入 CloudHSM，你就会找到你的集群。在 CloudHSM 集群列表中，你会看到格式为“cluster-xxxxxxxxxxx”的 Cluster Id。

<p align="center">
  <img src="/images/cloudhsm-id.jpg">
</p>


2. [创建](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html)一个参数 `/pix/proxy/cloudhsm/CloudHSMCustomerCA`，其值为：
```
-----BEGIN CERTIFICATE-----
<CLOUDHSM_CUSTOMER_CA_CERTIFICATE>
-----END CERTIFICATE-----
```

3. [创建](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html)一个参数 `/pix/proxy/cloudhsm/SignatureKeyLabel`，其值为：
```
<SIGNATURE_LABEL>
```

4. [创建](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html)一个参数 `/pix/proxy/cloudhsm/SignatureCertificate`，其值为：
```
-----BEGIN CERTIFICATE-----
<SIGNATURE_CERTIFICATE>
-----END CERTIFICATE-----
```

5. [创建](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html)一个参数 `/pix/proxy/cloudhsm/MtlsKeyLabel`，其值为：
```
<MTLS_KEY_LABEL>
```

6. [创建](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html)一个参数 `/pix/proxy/cloudhsm/MtlsCertificate`，其值为：
```
-----BEGIN CERTIFICATE-----
<SIGNATURE_CERTIFICATE>
-----END CERTIFICATE-----
```

7. [创建](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html)一个参数 `/pix/proxy/cloudhsm/DictAuditStream`，其值为：
```
<FIREHOSE_DICT_DELIVERY_STREAM_NAME>
```

8. [创建](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html)一个参数 `/pix/proxy/cloudhsm/SpiAuditStream`，其值为：
```
<FIREHOSE_SPI_DELIVERY_STREAM_NAME>
```

9. [创建](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html)一个参数 `/pix/proxy/cloudhsm/BcbDictEndpoint`。

> **值格式：** 仅填写主机名加端口——**不要**包含 `https://`、结尾的斜杠或 `/api/v2`。CloudHSM 代理使用 Camel `bridgeEndpoint(true)`，并保留传入请求的路径/查询字符串。因此，调用方应用程序必须把 BCB v2 路径（例如 `/api/v2/entries/{Key}`）发送给代理。
>
> **BCB DICT v2 基线（使用前需与 BCB 核实）：**
> - Homologação（验收环境）：`dict-h.pi.rsfn.net.br:16522`
> - Production（生产环境）：`dict.pi.rsfn.net.br:16422`
> - API v1 已于 2024-02-04 完全停用。不要配置或调用 `/v1/` 路径。
>
> 这些只是 BCB 的终端节点基址，并不能替代 BCB 参与方接入、最新证书、安全手册（Security Manual）要求或 homologação（验收）测试。

```
<DICT_V2_HOST_AND_PORT>
```

> **⚠️ 本地模拟器证书警告。** 该模拟器证书带有一个公开的私钥。它随本仓库一起分发，位于 `proxy/test/src/main/docker/ssl/`，与 `sig.key` / `mtls.key` 相邻，其主题是 BACEN 真实的生产域名（`O=BCB, OU=PIX, CN=*.pi.rsfn.net.br`，有效期至 2030-07-03）。任何能读到本仓库的人都可以伪造一个会被信任该证书的代理所接受的响应。
>
> `BcbSignatureCertificate` 参数与生产环境使用的信任机制相同。从模拟器切换到 BCB 不仅仅是更改终端节点：还要用当前由 BCB 提供的证书链替换**两个** BCB 信任证书。当这个已知的模拟器证书被信任时，应用程序会记录一条 ERROR 日志并指名点出它（`WellKnownTestCertificates`）；在本地模拟之外，应针对该日志行告警。

**仅限本地模拟器——并非 BCB 终端节点：**
```
test.pi.rsfn.net.br:8181
```

10. [创建](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html)一个参数 `/pix/proxy/cloudhsm/BcbSpiEndpoint`。

> **值格式：** 仅填写主机名加端口。调用方负责提供 SPI 消息路径。示例 `pacs.008.spi.1.4` 固定装置（fixture）与本地模拟器属于历史教学材料；在用于 homologação（验收）/生产环境之前，请从 BCB 获取当前的 SPI 消息定义（Message Definition）、XSD、终端节点、证书链和 TLS 策略。不要根据本仓库推断生产环境的 SPI 终端节点或消息版本。

```
<SPI_HOST_AND_PORT_FROM_CURRENT_BCB_ONBOARDING>
```

**仅限本地模拟器——并非 BCB 终端节点：**
```
test.pi.rsfn.net.br:9191
```

11. [创建](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html)一个参数 `/pix/proxy/cloudhsm/BcbSignatureCertificate`，其值为：
```
-----BEGIN CERTIFICATE-----
<BACEN_SIGNATURE_CERTIFICATE>
-----END CERTIFICATE-----
```

若要使用测试 - 模拟器（TEST - SIMULATOR），请使用：
```
-----BEGIN CERTIFICATE-----
MIIDnDCCAoSgAwIBAgIEaLkRBjANBgkqhkiG9w0BAQsFADBkMQswCQYDVQQGEwJC
UjELMAkGA1UECBMCREYxETAPBgNVBAcTCEJyYXNpbGlhMQwwCgYDVQQKEwNCQ0Ix
DDAKBgNVBAsTA1BJWDEZMBcGA1UEAwwQKi5waS5yc2ZuLm5ldC5icjAeFw0yMDA3
MDUxMzUwMzVaFw0zMDA3MDMxMzUwMzVaMGQxCzAJBgNVBAYTAkJSMQswCQYDVQQI
EwJERjERMA8GA1UEBxMIQnJhc2lsaWExDDAKBgNVBAoTA0JDQjEMMAoGA1UECxMD
UElYMRkwFwYDVQQDDBAqLnBpLnJzZm4ubmV0LmJyMIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAy38YHSwphFKHH49rFbl/caqP/ugD0vD3n6lrGzC9xukG
q81bVYXKBzbVtn8gxCOsUCIktMoZNe6QCUeTGshreohIFKdzV/ZH70eZcCOcGoZX
3evPJuRYIpjjxp0CJbj71EubylavUNpgGjj9v02ezlto94oQN87YR77sDBPBPGeW
CwaYPN8KY0tW8CqrmJXkMsA+pd/1tv3QbBpkUbEgbTvrVTz+9qEUpAg6SeytIulg
icLQrklYPv/Jex4KKcZxAp6SGBrMYmuCViw40qd1SriWk5HYfmMzXSy6DJ7HO5Im
0F1g43XdEWr0hUmUpsFu2JTIO5qGgx9OcQ6Tw74mgQIDAQABo1YwVDAdBgNVHQ4E
FgQUMswECZ5M0yc1aMkxWdNucYsKjU8wMwYDVR0RAQH/BCkwJ4IJbG9jYWxob3N0
ghRob3N0LmRvY2tlci5pbnRlcm5hbIcEfwAAATANBgkqhkiG9w0BAQsFAAOCAQEA
varWSOwcE2A5sIsJbPHczsDXiVOObfJjVol/JBXPH00A8uZ6hbsWDCNp7XZHjheW
snw9acXzKvi+NY/kCYaSegsUr9O+2BBcGhCN4LI5uITE9s3YZKyl+2rqk93P7EDB
RSitjPXeRm9ANPZCR90h+amZQLNbfiK0Povrv61isFqLfdGXnk9B6tfLB+baeS8f
HhxEM22sd+5yo9rUZOdAGI72SMzgaMT1AJZbVbb3z2ymDByJkgTAsVdkkSNkqwEi
Y3lg6cJ5thj5NdaXWc8wCzG6L85uAVV/7eh0SMJ2ITMJwkrrqtrX47LeNPrtCTy/
B8Um+Ao1f9w4nbxP53d+6w==
-----END CERTIFICATE-----
```

12. [创建](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html)一个参数 `/pix/proxy/cloudhsm/BcbMtlsCertificate`，其值为：
```
-----BEGIN CERTIFICATE-----
<BACEN_MTLS_CERTIFICATE>
-----END CERTIFICATE-----
```

若要使用测试 - 模拟器（TEST - SIMULATOR），请使用：
```
-----BEGIN CERTIFICATE-----
MIIDnDCCAoSgAwIBAgIEBR5HdTANBgkqhkiG9w0BAQsFADBkMQswCQYDVQQGEwJC
UjELMAkGA1UECBMCREYxETAPBgNVBAcTCEJyYXNpbGlhMQwwCgYDVQQKEwNCQ0Ix
DDAKBgNVBAsTA1BJWDEZMBcGA1UEAwwQKi5waS5yc2ZuLm5ldC5icjAeFw0yMDA3
MDUxMzUxMTFaFw0zMDA3MDMxMzUxMTFaMGQxCzAJBgNVBAYTAkJSMQswCQYDVQQI
EwJERjERMA8GA1UEBxMIQnJhc2lsaWExDDAKBgNVBAoTA0JDQjEMMAoGA1UECxMD
UElYMRkwFwYDVQQDDBAqLnBpLnJzZm4ubmV0LmJyMIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAztOPl4NGjpvf/d07FHHkbJKC7xRwoBhvTpTQ/vQp9E3v
hUI4fIgvvsXAzaknifMysdtk1BRS3Urk6tiL9ZCKEVcqfTPTdawcBi2AABrBvWYx
jDTk5dK1o8wPcUyWRDMRXiWv7grODR75u5a+s3bZTOWLIDxGpY2cuDSRWK0bT8Zh
of+8cn4yML03A83mqrfri1rahH/WpGzwOPk6+pv2m/VKv6GTS1ADD5xTExO5Zotg
wYAuU/zUVZ007CvHGGVoJ87hbUr8EmW1DsgxcPGWeKZ0SzCZkV88eLaD6sedyg0q
5w1ACRPSK0fRXHpxLkqckJ72hyinr/S/axOvM9bijQIDAQABo1YwVDAdBgNVHQ4E
FgQU+hfJS2Fp0POVlnuOxCsgquNqJocwMwYDVR0RAQH/BCkwJ4IJbG9jYWxob3N0
ghRob3N0LmRvY2tlci5pbnRlcm5hbIcEfwAAATANBgkqhkiG9w0BAQsFAAOCAQEA
hjIm6Cj35JR1cbKsIlUFlFVfN7/D9Rx2GOq7JtD4SbzTbDyJ3zm/usVMFFNDNuSs
mJhqLpTKmPX9Akp55RSdnLDEs2tDs7rN5Fy5BODwHblnnyflN0oSihnGop0TtEtv
Gw+zWXms4Pm9Vyi3l+UQA3ENJICP3H7iiPyxj0kThjhFnoIn4kqd+/xSp/BBR6JB
1UofthomxU4qcYcb4gWBYdgaGzoUIk3W3iMBzQDQmmiqAgKYhEA24SrOgkwWw0/o
3RNL7mYI5L3tivyrc0/K3/aE0yPhDAMHpC8V7taUwnb4k7rClB0bqF5GmCnWzBis
NAoejbjou87yzYUTY8nRnw==
-----END CERTIFICATE-----
```

### AWS Fargate (PROXY)

1. 若要使用 Fargate 配置 Amazon ECS，请使用此[步骤](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/getting-started-fargate.html)。你可以使用 dockerfile `proxy/cloudhsm/proxy/src/main/docker/Dockerfile`。你还需要配置以下[权限](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-iam-roles.html)，以便：

- 读取密钥（AWS Secrets Manager）。
- 读取参数（AWS Systems Manager Parameter Store）。
- **描述 CloudHSM 集群（`cloudhsmv2:DescribeClusters`）。** `wrapper_script.sh` 会在容器启动时调用
  `aws cloudhsmv2 describe-clusters` 来发现处于 ACTIVE 状态的 HSM IP。这是一项
  **IAM** 权限，与下面的安全组规则是相互独立的——若没有它，容器
  会在 JVM 尚未启动之前就退出。
- 如果上述任何参数是作为 **SecureString** 创建的，还需针对该参数的 KMS 密钥允许 `kms:Decrypt`。
  应用程序会无条件地请求解密，对于普通的 `String` 参数，这一请求
  会被忽略。
- 将数据（日志）放入传输流（Amazon Data Firehose）。
- [连接](https://docs.aws.amazon.com/cloudhsm/latest/userguide/configure-sg.html)到 AWS CloudHSM 集群。

你必须使用**内部（INTERNAL）**[Application Load Balancer](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/create-application-load-balancer.html)来暴露该服务。

### AWS Fargate (TEST - SIMULATOR)

1. 若要使用 Fargate 配置用于测试的 Amazon ECS，请使用此[步骤](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/getting-started-fargate.html)。你可以使用测试用 dockerfile `/proxy/test/src/main/docker/Dockerfile`。你还需要配置以下[权限](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-iam-roles.html)，以便：

- 读取参数（AWS Systems Manager Parameter Store）。

你必须使用**内部（INTERNAL）**[Network Load Balancer](https://docs.aws.amazon.com/elasticloadbalancing/latest/network/create-network-load-balancer.html)来暴露该服务。

2. 你必须配置一个域名为 `rsfn.net.br` 的[私有托管区域（private hosted zone）](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/hosted-zone-private-creating.html)。此外，请使用此[步骤](https://aws.amazon.com/premiumsupport/knowledge-center/route-53-create-alias-records/)为名称 `test.pi.rsfn.net.br` 配置一条 A 记录，并将别名指向 TEST Network Load Balancer。

### Amazon Athena and Amazon Quick

1. 请使用此[步骤](https://docs.aws.amazon.com/athena/latest/ug/getting-started.html)，通过 Amazon Athena 查询先前创建的 S3 存储桶中的数据。 

2. 你也可以选择使用 [Amazon Quick](https://docs.aws.amazon.com/quicksight/latest/user/setup-new-quicksight-account.html)，它让你能够轻松创建和发布包含 ML Insights 的交互式仪表板。之后，这些仪表板可以从任何设备访问，并嵌入到你的应用程序、门户和网站中。
