# CloudHSM + BCB DICT v2 续作交接文档

**[English version / 英文版本](CLOUDHSM_BCB_V2_HANDOFF.md)**

> **用途**：交给一位具备可用 Java 11 + Maven 环境的代理/工程师，用于继续本 fork 中**维护中的 CloudHSM 教学骨架**。
>
> **范围刻意收窄**：XML 数字签名、mTLS、CloudHSM 客户端与容器集成、透明 HTTP 代理。**KMS 属于历史遗留/不受支持：不要修改、不要构建、不要测试，也不要把它当作验收标准。**

## 1. 非目标——不要把这个仓库扩张成一个 PSP

明确不在范围内：支付发起、入向 SPI 异步报文、清算与对账、退款业务流程、MED 2.0 / 资金追回、欺诈标记、事件通知、Pix Automático、授权、流动性、欺诈判定，以及运营 SLA。

目标仅限于：让这个**传输/密码学教学骨架**与当前 BCB DICT v2 传输契约兼容，并把「只有 BCB homologação 才能证明的事情」如实记录下来。

## 2. 当前外部基线（调研完成于 2026-09-20）

官方来源：

- [BCB DICT API v2.12.1](https://www.bcb.gov.br/content/estabilidadefinanceira/pix/API-DICT.html)
- [BCB DICT API changelog](https://bcb.gov.br/content/estabilidadefinanceira/pix/changelog.html)
- [BCB DICT v2 migration FAQ](https://www.bcb.gov.br/content/estabilidadefinanceira/pix/duvidas_comuns_api_v2.html)

与本骨架相关的事实：

1. DICT **v1 已于 2024-02-04 完全停用**。生产调用方必须发送 `/api/v2/...`。
2. 官方 DICT 页面给出的 BCB v2 基地址：
   - Homologação：`dict-h.pi.rsfn.net.br:16522`，调用路径以 `/api/v2/...` 开头
   - 生产：`dict.pi.rsfn.net.br:16422`，调用路径以 `/api/v2/...` 开头
   - 非支付类密钥校验在两个阶段都使用**独立的主机与端口**：
     - Homologação：`dict-np-h.pi.rsfn.net.br:16532/api-np/v2/keys/check`
     - 生产：`dict-np.pi.rsfn.net.br:16432/api-np/v2/keys/check`
3. BCB 仍然要求 mTLS、对 DICT 写/改操作做 XML 数字签名，以及强制校验响应签名。查询请求无需签名。
4. BCB v2 有一批查询驱动的操作（`Cursor`、`IncludeStatistics`、`Status`、`ModifiedAfter`、`Limit`、重复出现的查询值）。CloudHSM 代理必须透明保留 path/query/header/body。
5. API 页面上的安全手册链接（`cedsfn/Manual_de_Seguranca_PIX.pdf`）仍返回 **404**，而 `pix/Regulamento_Pix/` 下的同级手册返回 200——所以它是**受访问限制**，不是不存在。该文档**已**从该 BCB URL 的 Internet Archive 快照获得（摘要在 2025-07-16 → 2026-06-03 之间未变），其传输要求现已记录在 7.2 与 README-CloudHSM.md 中。SPI 的 `MsgDefIdr` 与 XSD 版本仍未核实——见 7.3——且**不得猜测**。
6. **压缩是预期行为，不是罕见情况。** API 页面建议调用方发送 `Accept-Encoding: gzip`；而发送压缩的*请求*明确不受支持。由于代理透明转发客户端请求头，BCB 会返回压缩内容，因此必须在验签**之前**解码响应体。已于 2026-09-20 修复；见 3.2。
7. **建议复用连接。** API 页面指出 mTLS 握手在延迟上代价很高，建议使用 HTTP 连接池，并返回带 `timeout` 的 `Keep-Alive` 头。本骨架未配置也未记录连接池——属于开放项，不是正确性缺陷。
8. **必须遵守 DNS TTL。** 安全手册规定客户端 "devem sempre respeitar o TTL"（必须始终遵守 DNS 服务器的 TTL），并警告不遵守可能导致失去访问能力。本骨架在启动时解析一次配置，尚未对照该要求检查——开放项。
9. **DICT API 版本已推进。** 已发布版本为 **2.12.1**；**2.13.0_rc1** 正在进行中。这一条此前的草稿有两处归属错误，在此更正：`PI-RequestingParticipant` → `^(?i)[a-z0-9]{8}` **已经在已发布的 2.12.1 中**，并非 rc1 的变更——此前被列为「即将到来」，会让读者把一个**当前生效的契约**当成推测。以及 2.12.1 中的 `PI-PayerId` 是 `^([0-9]{11}|[0-9]{14})$`，即普通的 CPF/CNPJ 数字；它**不是**伪名化的 64 字符值（那个读法来自 v1 时代的第三方来源，对 v2 是错的）。真正属于 **rc1** 的变更，按 changelog 原文引用共**三项**，而此前草稿只列了两项——这里补上，因为遗漏恰好发生在本文档声称格外严谨的那一处：

- "Alterado regex de **Participant e PI-RequestingParticipant** para permitir apenas números e letras
  maiúsculas（`^[A-Z0-9]{8}$`）"——所以 rc1**确实**触及 `PI-RequestingParticipant`，把它从大小写不敏感收紧为仅大写。它**当前**的取值仍是 2.12.1 的取值，这正是上面那处更正所指；但把该字段说成 rc1 未触及是错的。
- "Alterado regex de `PI-PayerId` e `TaxIdNumber` na parte do CNPJ …（`^([0-9]{11}|[A-Z0-9]{12}[0-9]{2})$`）"，对应巴西正在引入的**字母数字混合 CNPJ**。
- "Regex para número de conta passou apenas a permitir letras maiúsculas e números（`^[A-Z0-9]{1,20}$`）"。这些都不影响本代理——它转发字节，从不解析业务内容；`DictV2RequestPolicy` 只检查请求头的**存在性**。另有一点值得注意：`getBucketState`/`listBucketStates` 在 2.6.0 中已从 `dict-ratelimit.pi.rsfn.net.br` 迁走，旧主机现在返回 **HTTP 410**（`Gone`/`DeprecatedResource`，这是 DICT 有文档记载的状态）；MED 2.0 资金追回、欺诈标记与事件通知的端点确实存在，但按第 1 节属于范围之外。

## 2B. 条目查询上的 `Cache-Control`——发现并已修复的缺陷

这是在把本文档与 DICT API 页面做独立事实核查时发现的，属于**真实缺口**而非文档上的润色。API 页面在 *Consultar Vínculo → Cache* 一节写明：

> "Consultas a vínculos podem ter suas respostas *cacheadas* no PSP, **devendo seguir as diretivas
> contidas no header `Cache-Control`**. *Importante*: Para fazer uso de cache, clientes HTTP
> geralmente precisam ser configurados. Não é comum que tenham essa funcionalidade habilitada por
> padrão."

（该页面把这条指令关联到 RFC 7234 §5.2。）

**为什么它比一般的缓存问题更要紧。** 一个 `getEntry` 响应说明的是**哪个账户持有某个 Pix 密钥**。一个提供了过期答案的 PSP 会向一个**已不再持有该密钥的账户**发起支付——钱付给了错的人。BCB 的 `Cache-Control` 指令正是这个窗口的上界，所以遵守它是**正确性要求**，不是性能调优。

**哪些属于本代理的范围，哪些不属于。** 代理**不得干扰**：它必须原样透传 `Cache-Control`，让 PSP 自己的客户端能够遵守它；并且不得引入自身的缓存。PSP 是否缓存、如何缓存属于业务层决策，按第 1 节在范围之外。

状态：**曾是真实缺陷，现已修复。** 写这一节所要求的那个测试时立刻就发现了它。MEASURED：camel-netty-http 3.4.2 的标准 `NettyHttpHeaderFilterStrategy` 带有一张 out-filter 列表，其中包含 `cache-control`——以及 `pragma`、`warning`、`via`、`date` 和逐跳头——所以 BCB 响应到达调用方时**完全没有** `Cache-Control`，而它旁边的 `ETag` 却原样通过了。这个丢失是**静默的**。

`PixHttpHeaderFilterStrategy` 只移除端到端的缓存指令（`cache-control`、`pragma`、`warning`），两条腿上都如此。逐跳头与需要重算的头**刻意**继续过滤：转发 `content-length` 或 `transfer-encoding` 会截断或破坏被重新组帧的响应体——在 gzip 解码改变长度之后尤其严重——而转发 `connection` 或 `upgrade` 会让上游指令作用到另一条连接上。

`DictV2ResponseHeaderFidelityContractTest` 同时钉住**缺陷本身与修复**，这样修复就不会被误认为是框架本来就做到的事：一个测试断言标准策略会丢掉该头而 `ETag` 存活，另一个断言未过滤的策略会保留它。

随之记录了一个测试夹具陷阱，因为该测试的第一版让修复看起来像个 **no-op**：充当 BCB 的那一端**也必须**使用未过滤的策略，否则它自己的 consumer 会在该头到达线路之前就把 `cache-control` 过滤掉——于是测试会把一个发生在夹具里的丢失归咎于代理。现在两个用例**只在代理的过滤策略上不同**，这才使得比较有意义。

## 2A. Homologação 关卡——BCB 证书吊销检查

吊销检查**已实现、已测试，但出厂为关闭状态**（用 `-Dpix.tls.revocation.enabled=true` 打开，用 `pix.tls.revocation.softfail` 选择失败模式）。Netty 默认的信任管理器**完全不做**吊销检查，所以在打开它之前，一张被吊销的 BCB 证书会被接受。之所以默认关闭而非打开，是因为有两个前置条件属于**部署事实**、本仓库无法判定，而其中任一判断错误都比维持现状更糟。

**前置条件 1——信任参数必须装 ICP-Brasil CA，而不是 BCB 的叶证书。** 如果 `BcbMtlsCertificate` 装的是叶证书，那么该叶证书就成了信任锚，而 PKIX **不会**对信任锚做吊销检查：锚是被假定可信的，其上没有签发者去发布 CRL。因此对一张钉死的叶证书启用吊销检查是**纯粹的 no-op**，只是看起来安全。这一点是被断言过的，不是假设——`RevocationAwareTrustManagersTest.pinningTheLeafSilentlyDisablesRevocationChecking` 展示了即使在**硬失败**模式下该证书链依然通过。

**前置条件 2——CRL/OCSP 必须能从 RSFN 访问到。** 代理经 RSFN（一个专网）到达 BCB，而 ICP-Brasil 把 CRL 与 OCSP 端点发布在公网上。假定可达并采用硬失败，若实际不可达，则**每一次**到 BCB 的 TLS 握手都会失败。假定不可达并采用软失败，则吊销从未被真正校验过。

**一个实测出来的陷阱，让前置条件 2 比预期更尖锐。** `SOFT_FAIL` **不**容忍吊销信息**缺失**——它只容忍**获取失败**。实测：一条证书链若其证书既无 CRL 分发点也无 OCSP URL，在**两种模式下**都会被 `CertPathValidatorException: Could not determine revocation status` 拒绝。真实的 ICP-Brasil 证书确实带有分发点，所以软失败在生产中确实覆盖了「RSFN 不可达」这一情形——但一个自签名的预发端点或本地模拟器即使在软失败模式下也会被直接拒绝。启用这项能力可能在生产正常的同时**弄坏测试环境**，而这个方向最浪费时间。

**哪些必须在 homologação 阶段核实、且在这里无法核实：**

| # | 问题 | 为什么无法从本仓库回答 |
|---|---|---|
| 1 | `BcbMtlsCertificate` 装的是 ICP-Brasil CA（chain v10）还是 BCB 叶证书？ | 部署参数；两者对同一段代码路径都是合法输入 |
| 2 | 接入 RSFN 的容器能否到达 ICP-Brasil 的 CRL 端点？ | 网络出口事实，且 `dict.pi.rsfn.net.br` 没有公网 DNS 记录 |
| 3 | 那里是否有 OCSP 作为回退？ | 同上 |
| 4 | Pix 这条腿用软失败还是硬失败？ | 这是 PSP 的风险决策，不是技术决策——软失败会接受一张可能已被吊销的证书，硬失败会把一次 CRL 故障变成一次 Pix 故障 |

在 1 和 2 得到回答之前，把开关保持关闭是诚实的状态：另一种选择是一份**报告成功却什么都没检查**的配置。

## 3. 已修复并已验证的部分

- XML `KeyInfo` 使用证书的 **Issuer DN**，而不是 Subject DN（上游 issue #15），并带有一份 CA 签发的回归夹具（`Subject != Issuer`）。
- 审计 schema 包含 `request_query`（上游 issue #16）。
- CloudHSM wrapper 会配置所有活跃 HSM、具备有界就绪等待，并 `exec` JVM（上游 issue #17）。
- CloudHSM 审计投递不再让一个**已提交的交易**失败（上游 issue #18）；强制性的持久回退/合规决策见代码注释。
- 证书有效性问题可与签名不匹配区分开（上游 issue #19）。
- `README-CloudHSM.md`、`README.md`、`README-KMS.md` 与 `VERIFICATION.md` 现已明确定义「仅支持 CloudHSM」的范围。

此前的 CI 证据见 `VERIFICATION.md`：`core`、`cloudhsm`、shellcheck 与审计 schema 均通过。KMS 的失败是上游依赖问题，且 KMS 现已从维护中的 CI 工作流移除。

### 3.1 2026-09-20 新增——v2 传输契约现在是被测试的，不是被假定的

| 内容 | 位置 | 证据 |
|---|---|---|
| DICT v2 透明代理契约：`/api/v2/...` 路径、`IncludeStatistics=true`、**重复的**查询参数、`PI-RequestingParticipant` / `PI-PayerId` / `PI-EndToEndId`、XML 请求体 | `DictV2TransparentProxyContractTest`（11 个测试） | 驱动真实的 camel-netty-http 路由；请求体通过移除插入的签名后要求**字节相等**来检查 |
| 这些断言确实能失败 | 同一个类，5 个反向对照 | 丢掉 path 头、丢掉两个 query 头、丢掉 BCB 头、篡改请求体、移除 `matchOnUriPrefix` |
| 生产路由保留了契约所依赖的那些选项 | `.github/scripts/check-transport-contract.sh` + `transport-contract` CI 作业 | 源码级断言；已验证当 `matchOnUriPrefix`、`TLSv1.2` 或 KMS 范围守卫被破坏时它会失败 |
| 模拟器能够**拒绝**一个畸形的 v2 请求 | `DictV2RequestPolicy` + `DictV2RequestPolicyTest`（17 个测试） | 非 v2 路径 → 404 并引用 2024-02-04 的 v1 停用；缺少 participant 头 → 400；7 个有文档记载的错误状态全部可产生 |
| 证书过期与轮换可区分 | `XmlSignerExpiredCertificateTest`（2）、`XmlSignerNotYetValidCertificateTest`（2） | 过期 → `CertificateExpiredException` 作为 cause；**尚未生效** → `CertificateNotYetValidException` 作为 cause（这个分支此前可达但无测试） |
| 模拟器证书被误用是可检测的 | `WellKnownTestCertificatesTest`（3） | 对已提交的模拟器证书做指纹匹配，并带一个反向对照使其不会误报 |

**两条值得保留的实测更正**，都是由反向对照而非人工评审发现的：

1. `bridgeEndpoint=true` **并不是**保留 path/query 的原因。consumer 会填充
   `Exchange.HTTP_PATH` / `HTTP_QUERY`，producer 再把它们附加上去；`HTTP_URI` 到达时是
   **相对的**，所以在一个回环测试装置里，带与不带该选项的转发行为**完全相同**。保留该选项是为了它有文档记载的用途（绝对 `HTTP_URI`、Host 处理），而那个测试装置并不覆盖这些——**不要**引用契约测试作为该选项的证明。
2. 单独清除 `HTTP_QUERY` 不足以去掉查询：`HTTP_RAW_QUERY` 也被设置了，producer 会回落到它。要模拟查询丢失，**两者都必须清除**。

**一条比看起来更重要的 CI 结构性说明。** 在 2026-09-20 之前，除 `core` 以外的每个作业都传了 `-DskipTests`，所以加进 `proxy/test` 或 `proxy/cloudhsm` 的测试**在 CI 里根本不会运行**。`dict-v2-contract` 作业的存在就是为了堵住这个口子。在这里添加测试时，请通过**读取该次运行的测试数量**来确认它真的执行了——而不是看到运行变绿就算。

### 3.2 2026-09-20 新增——压缩的 BCB 响应不再看起来像签名失败

**这是一个实测到的缺陷，不是假想。** BCB 的 API 页面建议调用方发送 `Accept-Encoding: gzip`。代理透明转发客户端请求头，所以该头会到达 BCB，BCB 于是返回压缩内容。而路由此前在**验签之前**就把响应体直接交给了 `convertToString()`。

在本仓库上实测到的后果：gzip 的魔数 `0x1f 0x8b` 变成了 `31, U+FFFD`——`0x8b` 不是一个合法的独立 UTF-8 序列，因此被替换，载荷**不可逆地**被破坏。随后 `xmlSigner.verify()` 失败，代理对**一份 BCB 正确签名过的响应**回了 **HTTP 500 并声称「签名无效」**。触发条件是：调用方遵循了 BCB 自己文档里的建议。

| 主张 | 证据 | 备注 |
|---|---|---|
| gzip/deflate 响应体能解码为被签名的精确字节；未知/叠加的编码与损坏的流会被拒绝；解码后大小有上界 | `HttpContentDecoder` + `HttpContentDecoderTest`（16 个测试，proxy/core） | 其中一个测试钉住了根因，断言 `String` 往返是有损的 |
| 一个 gzip 响应到达验签时是被签名的 XML、`Content-Encoding` 被剥除、调用方收到完整响应体、无法解码的编码被记录而不是归咎于签名 | `DictV2CompressedResponseContractTest`（7 个测试，proxy/test） | 真实的 Camel 路由跑在回环 HTTP 上 |
| 生产路由在 `convertToString()` **之前**解码 | `check-transport-contract.sh` 比较行号 | **顺序**才是修复；仅仅存在是不够的 |

一次解码失败被记录为 `SIGNATURE_VALID_CONTENT_ENCODING_ERROR`，作为 `pix-signature-valid` 的第四个取值，与 `true`、`false`、`certificate-validity-error` 并列。理由与证书那个情形相同：**一次传输故障不得被归档为一次密码学故障**。它是作为 exchange 属性携带的，而不是抛出，这样 exchange 才能存活到 Firehose 审计写入。无需变更 Glue schema——`response_signature_valid` 的类型是 STRING。

反向对照（一个常驻测试）：`withoutDecodingTheBodyReachingVerificationIsDestroyed` 断言在移除解码步骤后，到达验签的响应体**不是**被签名的 XML，且确实包含 U+FFFD。

**一个值得知道的接缝。** `DecodeResponseProcessor` 位于 `proxy/cloudhsm/proxy`，而 `proxy/test` **刻意**不依赖它（那个模块需要 CloudHSM JCE rpm，在 CI 里是 `continue-on-error`）。因此契约测试行使的是该处理器所调用的**同一个** `HttpContentDecoder`，而门禁钉住的是生产接线。测试证明机制，门禁证明接线；**单靠任何一个都不够**。

## 4. 必须继续做的工作（仅 CloudHSM）

### A. 增加 CloudHSM v2 代理契约测试——P0——✅ 已于 2026-09-20 完成（见 3.1）

代理使用 Camel 的 `matchOnUriPrefix(true)` 与 `bridgeEndpoint(true)`，它们**应当**保留入向的 HTTP path/query/header/body。仅仅相信这个意图是不够的。

创建一个聚焦的测试，对一个 DICT v2 形态的请求证明代理保留了：

- `/api/v2/entries/{Key}` 路径；
- `IncludeStatistics=true` 查询；
- 重复的查询参数（例如多个 `Status` 值）；
- 必需的 `PI-RequestingParticipant`、`PI-PayerId`、`PI-EndToEndId` 请求头；
- XML 请求体除预期的签名插入之外未被改动。

避免让该测试依赖真实 HSM。必要时在签名/TLS 周围引入接口或工厂接缝，或使用仅测试用的 signer 与本地 Netty 目标。**该测试必须在 query/path/header 被丢弃时失败。**

### B. 强化本地模拟器，但不假装它就是 BCB——P0——✅ 已于 2026-09-20 完成（见 3.1 与 7.4）

`proxy/test` 目前在 8181/9191 上接受任何路径并返回固定的成功。把它作为密码学冒烟测试保留下来，但增加一个 v2 契约模式，或针对当前 `/api/v2` 的 path/query/header 情形加上显式断言。让本地模拟器的 DNS（`test.pi.rsfn.net.br`）与 BCB homologação **清晰分开**。

### C. TLS / 证书控制——P1——协议与加密套件策略已于 2026-09-20 完成（见 7.2）；证书链与主机名校验仍未解决

协议问题已定论：*Manual de Segurança do Pix* v3.7 要求 "TLS versão 1.2 ou superior"，并以 `ECDHE-RSA-AES-128-GCM-SHA256`（0xc02f）为最低套件，因此两条腿现在都钉住 `enabledProtocols("TLSv1.2,TLSv1.3")`，并由 `TlsProtocolNegotiationTest` 覆盖。原文引用、手册的获取方式，以及仍未证明的部分见 7.2。

本项下仍未解决的：

- 主机名校验缺失，这是**真实缺陷**而非未知项（7.2 第 3 条）；
- BCB 的 ICP-Brasil v10 证书链从未对真实端点验证过；
- 参与者签名证书的要求（`padrão SPB`）需要 *Manual de Segurança do SFN*；
- 证书过期/轮换现在有测试了（`XmlSignerExpiredCertificateTest`、`XmlSignerNotYetValidCertificateTest`），但没有针对 BCB 签发的材料测过。

### D. 文档 / 发布门禁——P0——见第 7 节

保持 `README-CloudHSM.md` 最新，并加入一份 BCB homologação 发布检查清单：

- 当前 BCB 端点/证书/TLS 策略已核实；
- 当前 DICT v2 OpenAPI/XSD 版本已记录；
- 请求与响应签名已被接受/校验；
- 400/403/404/409/410/429/503 的处理已测试；
- **不得**声称本地模拟器证明了与 BCB 的兼容性。

## 5. 构建与 CI 命令

只运行与 CloudHSM 相关的检查：

```bash
mvn -B -f proxy/pom.xml -pl core test                       # 79 tests, CI run 35525201219
mvn -B -f proxy/pom.xml -pl core,test test                 # + 38 in proxy/test, same date
mvn -B -f proxy/pom.xml -pl core,test package -DskipTests
mvn -B -f proxy/pom.xml -pl core,cloudhsm/cavium,cloudhsm/proxy package -DskipTests
bash -n proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh
shellcheck -S warning proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh
bash .github/scripts/check-transport-contract.sh
```

只有 `core` 与 `test` 会执行测试；`simulator` 与 `cloudhsm` 以 `-DskipTests` 运行，所以加进它们的测试会**静默地永不运行**。添加测试后，请确认 CI 运行中的**测试数量**，而不是相信一个绿勾。

**不要**运行或修复 `-pl kms`；KMS 是刻意排除在范围之外的。

每次做完一处聚焦改动后：

```bash
git status --short
git add <specific-files>
git commit -m "..."
git push origin master
```

目标远端是 [RadiumGu/pix-proxy-samples](https://github.com/RadiumGu/pix-proxy-samples)，分支 `master`。

## 6. 验收标准

只有当以下全部成立时才停止：

1. 仅 CloudHSM 的 CI 为绿（`core`、simulator、cloudhsm 编译、shellcheck、audit-schema）。
2. 一个聚焦的 v2 透明代理契约测试证明了 path/query/header/body 的保留。
3. README 与 `VERIFICATION.md` 清晰区分本地模拟器与 BCB homologação，并明确列出不受支持的 PSP 业务层。
4. 当前源码改动已提交并推送到 `master`。
5. 任何未核实的 BCB 安全/TLS/SPI 报文细节仍然被命名为 homologação 关卡——**绝不**声称已通过。

---

## 7. Homologação 关卡——开放项，**未**解决

本节的一切都是**本仓库未核实的**，不得被呈现为「已经可用」。每一项都需要来自 BCB onboarding/支持的材料，加上一次 homologação 运行。把它们列为关卡，正是为了让「CI 是绿的」永远不会被误读成「可以对接 BCB 了」。

### 7.1 mTLS 私钥——"必须可导出（EXTRACTABLE）"这一前提现已在 SDK 5 上被否证

**更新于 2026-09-24。本关卡已关闭，而它此前所用的标题是错误的。** 该标题写的是
"mTLS private key must be EXTRACTABLE"。这是错的。在真实硬件上实测
（`hsm2m.medium`、FIPS、Client SDK 5.18.0、JDK 17，运行本仓库自带的、
由 reactor 构建出的 jar 中的 `PixTlsEngineConfigurer`）：一个**不可导出**的 HSM 私钥
完成了一次真实的 mTLS 握手，服务器也接受了客户端证书。

#### 实测了什么——把失败的路径展示出来而非隐藏

针对一台要求客户端证书的服务器尝试了三条路径。只有第三条可行，而第二条
是个陷阱：

| 路径 | 结果 |
|---|---|
| 用 HSM 密钥执行 JKS `setKeyEntry` | `java.security.KeyStoreException: Cannot get key bytes, not PKCS#8 encoded` |
| `KeyManagerFactory.init(cloudHsmKeyStore)` | `init()` **成功**，握手**完成**，但 `clientCertsSent=0`——没有提供任何凭据 |
| **自定义 `X509KeyManager`，持有 HSM 密钥对象及证书链** | **`clientCertsSent=1`**，握手为 `TLSv1.2` / `TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384`，且服务器记录了 `depth=0 CN=pix-pix-mtls-priv` |

对照：完全不配置客户端密钥时，服务器记录到的客户端主体数为**零**，因此这一
证据不是测试装置造成的假象。

#### 整个已部署的应用，两条腿，都在真实硬件上

上面那项测量驱动的是本仓库的*类*。这一项运行的是两个**应用**——CloudHSM 代理与 DICT v2 模拟器——针对一个真实的
`hsm2m.medium` FIPS 集群、Client SDK 5.18.0，且代理的 mTLS 与签名私钥均以 `extractable=false`、
`never-extractable=true` 创建。

| 腿 | 请求 | 结果 |
|---|---|---|
| **DICT**（代理 `8080` → 模拟器 `8181`） | 明文传入 `GET /api/v2/keys/…` | **HTTP 200**，2597 字节，`<CreateClaimResponse>` 内含 `SignatureValue`，以及代理自己的判定头 **`pix-signature-valid: true`** |
| **SPI**（代理 `9090` → 模拟器 `9191`） | `POST` 本仓库自带的未签名 `pacs.008` 样本 | **HTTP 201 Created**，返回 `PI-ResourceId`——模拟器**验证了代理用 HSM 密钥所生成的签名** |
| 健康检查 | `7070` 上的 `GET /check` | **HTTP 200**，主体为 `OK` |

这两条腿并不冗余。在 DICT 这条腿上，代理*验证*它收到的签名；只有在 SPI 这条腿上，它才用 HSM 密钥*产生*一个签名
并被对端接受。模拟器要求客户端证书（`needClientAuth=true`），且其信任库由那张 HSM 背书的证书构建，因此握手能够
完成本身也排除了「客户端凭据被静默省略」这一情形。

**SPI 的结果在有对照之前没有意义。** 模拟器的检查是 `if (body non-empty && !verify(body))`，所以一个空的
请求体会跳过验证并照样返回 201。把它的 `SignatureCertificate` 参数指向一张无关的证书、再重放同一请求，得到
**HTTP 403 `Signature invalid!`**——因此验证是活的，那个 201 是对一个由 HSM 生成的签名的真实接受，而不是一次
从未运行过的检查。

该对照的第一次尝试什么都没改却「通过」了：它在 EC2 实例上执行 `put-parameter`，而该实例的 IAM 策略只授予了
`ssm:Get*`。**一个无法施加自身变异的对照，报告出的结果与一道真正有效的门禁完全一样。**

另有两项行为，记为非缺陷，因为二者都是本仓库按设计工作：

- 一旦 `BcbMtlsCertificate` 或 `BcbSignatureCertificate` 持有模拟器的证书，`WellKnownTestCertificates`
  就会记录一条 **ERROR**：*"TRUSTING A BACEN SIMULATOR CERTIFICATE whose PRIVATE KEY IS PUBLIC"*。正是这道
  护栏使得测试值无法被无声地留在原处。
- 第一次 DICT 请求未带 `PI-RequestingParticipant`，返回 **HTTP 400**，并说明*"代理必须转发它，因此它的缺失
  意味着它被丢弃了"*。模拟器检查这些头，正是为了抓住丢头的代理，所以那个 400 是透明性契约在工作。

**这一项并未确立什么。** 这些证书是自签的，不是 ICP-Brasil 链；对端是模拟器，不是 BCB；吊销检查按出厂状态处于
关闭。HSM 集成与传输行为是实测的——而一次真实的 BCB 会话仍然是一个 homologação 闸口。

**中间那一行才是最需要记录的，因为它是静默失败。**
在 CloudHSM 密钥库上调用 `KeyManagerFactory.init()` 不会抛出任何异常，握手
照样完成——只是它*不带*客户端证书就完成了。这项测量本身的一个早期版本，
仅凭"没有抛出异常"就把它记录为通过，直到 `clientCertsSent=0` 才把它暴露出来。
原因是结构性的：一个 `KeyManager` 必须在同一个别名下同时提供私钥**和**一条
证书链，而 HSM 密钥库持有的密钥没有附带任何证书链。

#### 为什么补救成本很小

`X509KeyManager` 接口交给 JSSE 的是一个 `PrivateKey` **对象**；它从不索取编码后的
字节。这正是不可导出密钥在这里能工作、却在 JKS 中失败的全部原因：JKS 必须做
序列化，而 JSSE 只需发起调用。补救大约是**40 行**代码，在一个 HSM 句柄之上实现
`getPrivateKey`/`getCertificateChain`——不是架构变更，也不构成把密钥改为可导出的
理由。

它取代了什么：下文中排序过的各项补救方案，是在"可导出性无法避免"这一前提下
写成的。**路径 B 中"必须从 `SslProvider.OPENSSL` 切换到 JSSE 路径"这一要求仍然
成立**——HSM 密钥的 `getEncoded()` 为 null，而基于 OpenSSL 的 provider 需要这些
字节，因此 JDK provider 是必需的。不再成立的，是"PSP 必须接受一个可导出的
mTLS 密钥"这一框定。

#### 该机制——以实测而非断言呈现

`PixCloudHSMProxyRouteBuilder` 用 `SslProvider.OPENSSL` 与 `keyManager(privateKey, certificates)`（第 204–205 行）构建 BCB 那条腿，所以 mTLS 密钥必须**不带** `-nex` 生成，即可导出。

README 给出的理由是*「Cavium 有 JCE，但没有 JSSE」*。那是一个架构性陈述，对 Client SDK 3 来说是成立的，但它**不是真正导致失败的机制**。这个区别很重要，因为两者指向不同的补救方案。

对照 `netty-handler-4.1.49.Final` 字节码实测：使用 OPENSSL provider 时，Netty 会把密钥**PEM 编码**后交给一个原生 TLS 库。`PemPrivateKey.toPEM(ByteBufAllocator, boolean, PrivateKey)` 会调用 `PrivateKey.getEncoded()`，当其为 `null` 时抛出 `IllegalArgumentException`，消息为 `"<class> does not support encoding"`；而它的**唯一**调用方是 `ReferenceCountedOpenSslContext`——也就是 OPENSSL 这条路径本身。按 JCA 契约，一把材料无法离开其设备的密钥在那里会返回 `null`。

可执行证据：`MtlsNonExtractableKeyTest`（proxy/test，3 个测试，在 CI 中运行）驱动的正是该调用，使用一把 `getEncoded()` 为 `null` 的桩密钥并断言其被拒绝，同时以一把可导出的软件密钥作为对照。

所以阻塞点是**这个 Netty API** 的性质，而不是 HSM 的普遍性质。正是这一点打开了那些无需等待 JSSE 集成的补救方案。

**哪些**不受**影响：签名**密钥。它是不可导出的、始终留在 HSM 内，而伪造一笔交易需要的是签名密钥，不是 mTLS 密钥。mTLS 密钥只负责建立通道。因此这个缺口关乎**合规论证的完整性**——能够说「无例外」而不是「有一个例外并有补偿控制」——而**不是**一个高危漏洞。不要让它挤占优先级更高的工作。

#### 已在真实硬件上验证，2026-09-20（hsm2m.medium，FIPS，Client SDK 5.18.0）

在 us-east-1 起了一个一次性集群，用来解决桩无法解决的问题。结果如下：

**一把不可导出的 SDK 5 密钥其 `getEncoded()` 返回 `null`——MEASURED。** 用
`cloudhsm-cli key generate-asymmetric-pair rsa --private-attributes extractable=false sign=true`
生成，其属性回读为 `"extractable": false, "never-extractable": true, "always-sensitive": true`。经 SDK 5 JCE provider 装载后：

```
keyClass = com.amazonaws.cloudhsm.jce.provider.CloudHsmRsaPrivateCrtKey
getFormat  = null
getEncoded = null
```

这证实了 `MtlsNonExtractableKeyTest` 里的桩与真实情况行为一致，并且解决了本节此前留下的一个问题：**路径 B 必须使用 `SslProvider.JDK`，而不是 `OPENSSL`。** 因为 `getEncoded()` 在 SDK 5 上与在 SDK 3 上一样是 `null`，Netty 的 OPENSSL 路径（`PemPrivateKey.toPEM` → `IllegalArgumentException: does not support encoding`）在迁移之后会以**完全相同的方式**失败。迁移到 SDK 5 **本身并不会**让当前的 Netty 代码可用。

**`extractable=false` 必须显式设置。** SDK 5 默认为可导出，所以一次只是照搬密钥生成步骤的迁移，会**静默地**产出一把**可导出**的 mTLS 密钥——与意图恰好相反。MEASURED：省略该属性会被接受，且不给任何警告。

**四条本仓库尚未记录的 SDK 3 → 5 迁移事实**，全部为 MEASURED：

1. `hsm2m.medium` 在 `CreateCluster` 上需要一个 `Mode` 参数（`FIPS` 或 `NON_FIPS`）；`hsm1.medium` 不需要。省略它会以
   `CloudHsmInvalidRequestException: Mode is a required argument for this hsm type.` 失败。
2. SDK 5 **默认强制 2 个 HSM 的密钥可用性 quorum**。在单 HSM 集群上，每一次密钥操作都会以*「the key must be available on at least 2 HSMs」*失败，直到该检查被关闭。这是一个合理的生产默认值，同时是任何只跑一个 HSM 的人会踩的坑。
3. SDK 5 **各组件分别配置**——`configure-cli`、`configure-jce` 与 `configure-dyn` 是三个不同的二进制、三份不同的配置文件。只配置 CLI 会让 JCE 那边仍留着字面占位符 `%%HSM_IP_ADDRESS%%`，并以 `Config key hostname has invalid value` 失败。容器 entrypoint 目前只配置一样东西；在 SDK 5 下它必须配置它所使用的**每一个**组件。
4. `cloudhsm-cli key generate-asymmetric-pair rsa` 需要 `--public-exponent`；它没有默认值。

#### 路径 D 的核心机制已在硬件上**证明**——MEASURED 2026-09-20

在同一个集群上、用上面那把不可导出的密钥（`extractable=false`、`never-extractable=true`）测试：

```
openssl engine -t -c cloudhsm
  (cloudhsm) CloudHSM OpenSSL Engine   [RSA, EC]   [ available ]

cloudhsm-cli key generate-file --encoding reference-pem --path hsmkey.pem \
    --filter attr.label=pix-mtls-priv
openssl dgst -engine cloudhsm -sha256 -sign hsmkey.pem -out sig.bin data.txt
  Engine "cloudhsm" set.        -> sig.bin, 256 bytes (RSA-2048)

openssl dgst -verify pub.pem -signature sig.bin -sha256 data.txt
  Verified OK

openssl req -new -engine cloudhsm -key hsmkey.pem -out client.csr -subj '...'
  Certificate request self-signature verify OK
```

所以该引擎在一把**无法离开 HSM** 的密钥上执行了一次真正的私钥操作，并且可以为那把密钥签发客户端证书。这正是路径 D 所需的**承重能力**。

**本节此前标记的那个未知项在很大程度上消解了，而且原因很有启发性。** 原先的担忧是：AWS 是针对*服务端*指令（`ssl_certificate_key`）来记录该引擎的，而本代理需要的是*客户端*指令（`proxy_ssl_certificate_key`），而客户端指令可能不会走引擎。实测结果：**根本不存在需要路由的引擎专用密钥语法。** 一个 `engine:name:id` 引用会**直接失败**——

```
openssl dgst -engine cloudhsm -keyform engine -sign pix-mtls-priv ...
  Could not find private key from org.openssl.engine:cloudhsm:pix-mtls-priv
  error:1300007D:engine routines:ENGINE_load_private_key:no load function
```

——因为 CloudHSM 引擎**没有实现 `load_private_key` 函数**。唯一的机制是 `reference-pem` **文件**，它是磁盘上一个普通文件，只是**看起来**像一把私钥，实际不含任何密钥材料。因此任何接受密钥**文件路径**的指令都会接受它，而「服务端 vs 客户端」就不再是一个有意义的区分。这也意味着 nginx 的 `ssl_certificate_key engine:...` 语法**无论如何都不会奏效**。

**用这把不可导出的密钥做出向 mTLS 是**可行的**——MEASURED。** 对一个本地 `openssl s_server -Verify 1`（OpenSSL **3.5.8**，其中 ENGINE 已被标记弃用——所以该引擎在现代 OpenSSL 上仍然工作）做了三次握手：

| 测试 | 客户端/服务端证据 | 判定 |
|---|---|---|
| mTLS，TLS 1.2 | 服务端记录 `depth=0 C=BR, O=pixpoc, CN=pix-client` 与 `1 server accepts that finished`；套件 `ECDHE-RSA-AES256-GCM-SHA384` | 用 HSM 密钥完成客户端认证成功 |
| 强制套件 | 双方均为 `Cipher is ECDHE-RSA-AES128-GCM-SHA256`，`1 server accepts that finished` | **0xc02f 能协商成功**，且密钥由 HSM 承载 |
| TLS 1.3 | `Protocol: TLSv1.3`、`Cipher is TLS_AES_256_GCM_SHA384`、`1 server accepts that finished` | TLS 1.3 同样可用 |

服务端验证了客户端证书链，这才是关键证明：客户端证明了自己持有一把 `extractable` 属性为 `false` 的私钥。日志里也出现了 `verify error:num=18 self-signed certificate`，但那是*客户端*在抗议这个夹具里*服务端*的自签名证书，与客户端认证无关。

有两个测试装置陷阱花掉了实际时间，记录在此以免重犯。`openssl s_server` 会在 **stdin EOF** 时退出，所以把它后台化并关闭 stdin 会让它打印 `ACCEPT` 然后 `DONE`，伴随 `0 server accepts that finished`——这读起来**完全像一次握手失败，但并不是**。另外，**绝不要在 SSM 的 `AWS-RunShellScript` 命令里后台化进程**：SSM 会把全部输出缓冲到命令结束，一个存活的子进程会把缓冲区占住，于是命令一直挂到超时并返回**空**。

**对本节自身验收标准的一处更正——MEASURED。** 早先的文字（以及那份外部调研笔记）说真正的证明是握手期间私钥操作在 CloudHSM **审计日志**中的条目。**那份证据不存在，也无法获得。** CloudHSM 的审计日志只记录**管理**命令。整个 POC 期间，日志组 `/aws/cloudhsm/<cluster>` 里包含的是：

```
CN_GENERATE_KEY_PAIR x1   CN_CREATE_USER x3   CN_LOGIN x29   CN_LOGOUT x7
CN_INIT_TOKEN  CN_INIT_DONE  CN_GEN_PSWD_ENC_KEY  CN_GEN_KEY_ENC_KEY
CN_BACKUP_BEGIN / CN_BACKUP_END   CN_APP_FINALIZE x19   CN_ENCRYPT_SESSION_V2 x53
```

**没有任何签名操作码**，尽管实际执行了大约十次私钥操作（两次 `dgst` 签名、一次 CSR，以及三次 TLS 握手）。数据面的密码运算不被审计记录——这对一个高吞吐 HSM 来说是合理的，但它意味着任何被要求「在审计日志里找到那次握手」的人都会失败，并可能**错误地**得出该操作没有在 HSM 内发生的结论。

**改用下面这个证明。它更强，而且每一部分都可查询：**

1. `local: true`——该密钥是在 HSM **内部生成**的，而非导入。这一点由审计日志里那条唯一的 `CN_GENERATE_KEY_PAIR` 条目锚定，而那条**确实**被记录了。
2. `never-extractable: true` 与 `always-sensitive: true`——该私钥材料从未存在于 HSM 之外，且无法被弄到外面去。
3. 通过该密钥产生的签名能用公钥验证通过（`Verified OK`），并且一个 TLS 服务端验证了客户端证书链——所以一次有效的 RSA 私钥操作**确实发生过**。
4. 因此该操作**必然**发生在 HSM 内部，因为在别处执行它所需的材料从未存在过。

这是一个从密钥属性加来源出发的**演绎证明**，而不是对操作日志的诉求，这才是应该摆在审计人员面前的东西。

**仍待确认、且**未**尝试的：**

- 用 stunnel 或 nginx 而非 `openssl s_client` 驱动同样的握手。现在风险已低，因为该密钥是以一个普通文件路径被引用的、任何密钥文件类指令都会接受，而 `openssl s_client` 与 stunnel 共用同一套 OpenSSL 引擎管道。

**`XmlSigner` 针对 SDK 5 的 keystore 无需修改即可工作——MEASURED，这是本节里的好消息。** 在硬件上以 SDK 5.18.0 运行，针对一把在 HSM 内以 `extractable=false` 生成的密钥（回读为 `never-extractable: true`）：

```
provider         = CloudHSM v5018000.0
privateKey class = com.amazonaws.cloudhsm.jce.provider.CloudHsmRsaPrivateCrtKey
getEncoded       = null
getFormat        = null
SIGN_OK          = true, length=1729
has <Signature>  = true
VERIFY           = true
```

JSR-105 会把 `Signature` 操作路由到 CloudHSM provider，所以本仓库中 **XML 签名那一半在 SDK 3 到 5 的迁移中完全不需要改动**。这对工作量评估很重要：迁移难度**完全局限在 TLS/mTLS 那一半**，那里 Netty 要的是密钥**字节**（见上文路径 B）。签名要的是**句柄**，而句柄正是 HSM 能给的。

那套夹具值得照做而不是硬扛。`keytool -genkeypair -storetype CloudHSM` 会以 `The given alias "pixsign" does not match label ""` 失败，所以密钥改用 `cloudhsm-cli` 生成。`XmlSigner` 把 `PrivateKey` 与 `X509Certificate` 作为两个独立参数接收，这意味着它们**不必来自同一个 keystore**：私钥按 label 从 CloudHSM keystore 取得，而证书则用 `openssl x509 -req -force_pubkey` 加一个一次性 CA、为**导出的公钥**签发。公钥可导出、私钥不可导出，而 `-force_pubkey` 不需要持有证明——所以在私钥从未参与的情况下就能得到一份可用证书。通过比较证书的公钥与导出的公钥来验证；两者摘要一致。

**POC 拆除，已于 2026-09-20 核实。** `describe-clusters` 返回 0 个集群；EC2 实例为 `terminated`；安全组已删除；一次性集群 CA 私钥已被 shred。账户中还有一个**无关的、此前就存在的已停止** Cloud9 实例，刻意未动。

#### CVE 覆盖之后的更新：路径 A 变便宜了

关闭 CVE-2021-43797 把 Netty 从 4.1.49.Final 移到了 4.1.118.Final，这改变了本节自身的成本估计。`MtlsNonExtractableKeyTest` 当初就是写来在 Netty 被升级时**大声失败**的，它确实做到了。

路径 A（通过 Netty 自己的回调把握手的私钥操作交给 HSM）此前被记录为需要**框架升级加原生库替换**，因为 `OpenSslContextOption` 在 4.1.49 上**完全不存在**。在 4.1.118 上它存在了，并且带有 `PRIVATE_KEY_METHOD`、`ASYNC_PRIVATE_KEY_METHOD` 以及一个 HSM 支撑的 signer 需要实现的 `OpenSslPrivateKeyMethod` 接口。所以框架那一半的代价**已经付过了**。

仍然阻塞路径 A 的东西更窄但真实：`PRIVATE_KEY_METHOD` 仅限 BoringSSL，而本仓库钉住的是 `linux-x86_64-fedora` 这个 tcnative 制品，也就是动态链接 OpenSSL 的那个构建。另有实测——初始化 `OpenSslPrivateKeyMethod` 会为 `io.netty.internal.tcnative.SSLPrivateKeyMethod` 抛出 `NoClassDefFoundError`，所以这个回调 API 从 `netty-handler` 单独是**不可达**的，还会牵进 `netty-tcnative-classes`。路径 A 现在是一次**原生库替换**而非框架升级，比原先记录的明显更便宜，但它仍然不是一次配置改动，而路径 D 仍是推荐方案。

#### 补救方案，按优先级排序

| | 方案 | 需要 SDK 3 → 5 迁移吗？ | Java 代码改动 | 主要风险 |
|---|---|---|---|---|
| **D** | mTLS 在 **JVM 之外**终结：用一个原生 sidecar（nginx / stunnel / Envoy / HAProxy）配合 CloudHSM 的 **OpenSSL Dynamic Engine**；应用通过回环以明文与之通信 | **不需要**——该引擎对 SDK 3 也存在 | **无** | sidecar 的**出向**客户端证书指令是否接受引擎支撑的密钥 |
| **A** | Netty 自带的私钥卸载，`OpenSslContextOption.PRIVATE_KEY_METHOD` | 推荐，但非严格必需 | 中等 | 需要 BoringSSL **且**需要 Netty 升级——见下文 |
| **B** | SDK 5 JCE + `keystoreType="CLOUDHSM"` KeyStore，`SslProvider.JDK` | **需要，硬依赖**（因此也需要 JDK 17+） | 小 | AWS 没有文档说明用这个 KeyStore 做客户端认证 |
| **C** | 把 CloudHSM OpenSSL 引擎载入 `netty-tcnative` | —— | 大 | 没有公开 API；需要定制 tcnative 构建或 JNI。**不要尝试。** |

**路径 A 在这里的代价比看上去更高，已实测：** Netty 的 `PRIVATE_KEY_METHOD` 文档写明仅限 BoringSSL，而本仓库钉住的是 `linux-x86_64-fedora` 这个 tcnative 制品，即 OpenSSL 变体。除了这次替换，`OpenSslContextOption` 在 `netty-handler-4.1.49.Final` 中**完全不存在**（它在更晚的 4.1.x 才出现）——这一点由 `MtlsNonExtractableKeyTest#openSslContextOptionIsAbsentSoThePrivateKeyCallbackIsNotAvailableHere` 断言。所以在一个 2020 年的 Quarkus 1.7.0 / Camel-Quarkus 1.0.0 技术栈上，路径 A 还需要一次 Netty 升级。

**为什么值得先验证路径 D：** 它是唯一不把这个缺口绑定到 SDK 5 迁移（7.6 与第 5 节把该迁移当作一个独立项目）的选项；它不触碰签名路径，所以签名相关测试无需重新验证；而且它使用的是 **AWS 有文档记载的配置**，而不是 Netty 的 `@UnstableApi`。它还会顺带解决本仓库在别处跟踪的三项：TLS 主机名校验缺失（7.2 第 3 条）、未设置的端点超时，以及 HSM 客户端与应用共用一个容器。

**路径 D 的诚实代价：** 一段回环链路会承载含 CPF、账号与金额的**明文**。它不跨越任何网络边界、停留在同一个任务的网络命名空间内，但在严格的零信任评审下这是一个新的讨论点，需要记入风险登记册。它还增加了一个需要维护的组件（版本、CVE、配置）。

#### 仍然**未**建立的事项

1. **路径 D 的承重未知项。** AWS 是以 nginx 作为**服务端**（`ssl_certificate_key` + `ssl_engine cloudhsm`）来记录该引擎的。本代理需要 nginx 作为**客户端**（`proxy_ssl_certificate_key`），而该指令是否也走引擎，AWS 文档没有覆盖。`stunnel` 的 `client = yes` 模式是最直接的替代，并且明确记录了 `engine` / `engineId`。**在投入路径 D 之前先验证这一点。**
2. **一把真实的 CloudHSM 密钥尚未被测试，而且在新基础设施上**无法**被测试。** 上面的证据用的是桩。测试真实情况意味着需要一把 Cavium SDK 3 密钥，而 SDK 3 只能配 `hsm1.medium`——它已不可创建。2026-09-20 在 `us-east-1` 对真实 API 实测：用 `hsm1.medium` 调 `CreateCluster` 返回 `CloudHsmInvalidRequestException: Provided HsmType is not supported.` 所以本仓库中的 SDK 3 代码路径**已经完全无法搭起来**，而「在一把真实 Cavium 密钥上确认 `getEncoded() == null`」是**永久无法核实**的，而不只是等一个集群。集群**仍然能**核实的是下面那些**补救方案**，在 `hsm2m.medium` 加 Client SDK 5 上。
3. **握手的私钥操作发生在 HSM 内部的证明。** 无论选哪个补救方案，验收标准都是握手操作在 CloudHSM **审计日志**中的条目——而不仅仅是一次成功的握手。把这项检查内建进 POC。
4. **BCB 自己的立场。** 通过让 BCB 接受一把可导出的 mTLS 密钥、纳入该机构的风险姿态来结掉这一项，仍然是有效路线。请把该决策与密钥策略的负责人一起记录下来。

**不要**通过「把密钥设为不可导出然后假定它能工作」来「修复」这个问题——上面已实测，它会在 TLS 上下文构建阶段以 `does not support encoding` 失败。

> 来源说明：四条路径的分析以及 SDK 5 / JSSE 的发现来自 2026-09-20 提供的一份外部调研笔记。其中关于本仓库的主张已在此重新核实（README 措辞、那两行路由代码、tcnative 分类符），三项全部核对通过；Netty 4.1.49 上 `OpenSslContextOption` 的缺失以及那个可执行的拒绝测试是本仓库补充的。该笔记中有**一项未在此核实**：一个 2026-03-01 的合规期限，未见任何一手来源——**没有一手来源前不要引用它。**

### 7.2 TLS 协议与加密套件策略——要求现已知；真实端点的证明仍是关卡

**已于 2026-09-20 解决。** 本项此前称获批的协议与套件清单「本仓库未知」。现在它已知，并引自一手来源。

**Manual de Segurança do Pix，v3.7**（PDF 创建于 2025-06-06），第 2 节 *"Comunicação segura"*：

> "O participante deve se conectar às APIs disponíveis no Pix exclusivamente por meio do protocolo
> HTTP versão 1.1 utilizando criptografia **TLS versão 1.2 ou superior**, com autenticação mútua
> obrigatória no estabelecimento da conexão. Deve ser suportada, **no mínimo, a Cipher Suite
> ECDHE-RSA-AES-128-GCM-SHA256 (0xc02f)**"

第 5.4.3 节补充：BC 使用 **ICP-Brasil chain v10** 的 SSL 证书做连接认证与加密，而参与者使用 ICP-Brasil **padrão SPB** 证书签名，其规范位于 *Manual de Segurança do SFN*。

**获取方式，之所以说明是因为它影响这份材料该被信任到什么程度。** API 页面链接的那个 URL 返回 404；`pix/Regulamento_Pix/` 下的同级手册返回 200，这一点通过枚举该目录、并以四份可取回的手册作为**正向对照**（证明枚举方法本身有效）得到确认。随后该 PDF 是从**该 BCB URL 本身**的 Internet Archive 快照取得的，其摘要在 2025-07-16 至 2026-06-03 的各次快照之间未变。它是经由归档取得的 **BCB 自己的文件**，不是第三方转述。**在 homologação 运行之前，请通过 BCB onboarding/支持重新确认当前版本。**

**代码里改了什么。** 两条腿现在都钉住 `enabledProtocols("TLSv1.2,TLSv1.3")`——1.2 是下限，而 `ou superior` 允许 1.3。该清单被钉住而非交给 JVM，是因为 Corretto 11 默认仍启用 TLS 1.1 与 1.0，低于手册的下限；Corretto 17 不会。`TlsProtocolNegotiationTest`（proxy/core，6 个测试）证明了：一个同时提供 1.2+1.3 的对端在面对只支持 1.2 的服务端时仍会协商到 1.2；0xc02f **确实能协商成功**而不只是被列出；以及这个钉住的清单排除了 1.1/1.0。该强制套件在 Corretto 11.0.32 与 17.0.20 上**都被支持且默认启用**——已实测。

**仍然是关卡，且上述任何一项都没有关掉它：**

1. **没有针对 BCB 真实端点的证明。** `dict.pi.rsfn.net.br` 没有公网 A 记录——RSFN 是专网——所以无法从外部探测该握手。上面的一切都是回环 JSSE 加一份文档。
2. **BCB 的证书链未经验证。** ICP-Brasil v10 在这里没有被行使过。
3. **主机名校验缺失。** 目前仅由「显式信任 BCB 证书」（即钉证书）来缓解。这一条**不**依赖那份手册——主机名校验是通用 TLS 要求——所以它是一个**真实的开放缺陷**而非未知项，在此跟踪是因为改动它会影响模拟器夹具证书的 subject。
4. **签名证书的要求**（`padrão SPB`，依据 *Manual de Segurança do SFN*）未核实；该手册尚未获得。

### 7.3 SPI 报文定义与 XSD 版本——未核实，不要猜

SPI 的 `MsgDefIdr` 取值以及 BCB 当前接受的确切 ISO 20022 XSD 版本在这里**没有被建立**，而且代码中**完全没有** XSD schema 校验。不要从本仓库的示例报文去推断它们。请从 BCB 获取当前的 XSD 集合，并在 homologação 中校验。

### 7.4 本地模拟器证明了什么、没证明什么

`proxy/test` 监听 `test.pi.rsfn.net.br:8181` / `:9191`。那个主机名**只是本地便利**——它不是 BCB homologação，后者是 `dict-h.pi.rsfn.net.br:16522`。

该模拟器现在会强制一个 v2 契约（`/api/v2/` 前缀、participant 头），并且可按请求产生 400/403/404/409/410/429/503，而 `DictV2RequestPolicy` 把这些规则记录为**本仓库的模拟器策略，不是 BCB 行为**。它是一个密码学与传输的测试替身。

一次绿色的模拟器运行证明：XML 签名与验签端到端可用、针对要求客户端认证的对端可完成 mTLS 握手、以及代理转发了它应该转发的东西。它对以下**什么都不证明**：BCB 真实的校验规则、它的状态码、它按操作划分的必需头集合、它的 TLS 策略，或它的报文 schema。

### 7.5 PSP 业务能力——刻意缺失

未实现、也不计划在这里实现：支付发起、入向 SPI 异步报文、清算、对账、流动性、退款业务流程、MED 2.0 / 资金追回、欺诈标记、事件通知、Pix Automático、授权、欺诈判定，以及运营 SLA。`DictV2RequestPolicyTest` 让这条边界保持**可执行**而不只是写在纸上：它断言模拟器对一个退款形态的路径和一个条目形态的路径做出**完全相同**的判定，这样业务状态机就无法被悄悄引入。

### 7.6 本仓库完全无法触及的第二层事项

- 在 homologação 中与真实 BACEN 的完整往返。
- 多 HSM 故障切换：需要一个至少两个 HSM 的集群，并在运行中替换其中一个。
- 针对 BCB 自己发布的已签名示例报文做验证。
