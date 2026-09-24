# 向 CloudHSM 集群添加 HSM：加入过程中所做的更改会发生什么

> **English:** [`CLOUDHSM_ADD_HSM_FAQ.md`](CLOUDHSM_ADD_HSM_FAQ.md)
>
> **这是译文。** 权威副本是 [`CLOUDHSM_ADD_HSM_FAQ.md`](CLOUDHSM_ADD_HSM_FAQ.md)——发现错误请改那一份。CI 的 `doc-parity` 作业会在两份文件的标题结构、任何命令输出、或任何测量值出现分歧时让构建失败。

**适用对象：** 正在规划 CloudHSM 容量变更的某 PSP 平台团队或安全团队。
**状态：** 下面每一个答案都是**在真实的 CloudHSM 硬件上实测**得出的（`hsm2m.medium`、FIPS 模式、
Client SDK 5.18.0），使用的是一个事后即被销毁的临时集群。凡是未实测的内容，文中都会明确说明。文中原样引用了
命令的真实输出，方便你与自己的集群逐一对照。

---

## 简要说明

添加 HSM 会把某个现有 HSM 的**某一时间点的快照**恢复到新 HSM 上。在该快照生成*之后*创建的任何内容都不在
其中，而接下来会发生什么则**取决于你创建的是什么**：

| 你在加入过程中创建的内容 | 它会自行到达新 HSM 吗？ |
|---|---|
| 一个**密钥** | **会。** 服务端同步会定期把密钥克隆到每一台 HSM。无需任何操作。 |
| 一个**用户** | **不会，永远不会。** 不存在服务端的用户同步机制。实测：14.6 分钟后仍处于分叉状态，且不会自愈。 |
| 一个 **mTLS 信任锚**（或任何策略） | **不会，永远不会。** 原因相同——策略不会被重新同步。 |

**所以规则只有一句话：** 先添加 HSM，等它进入 `ACTIVE` 状态，然后再去创建或修改用户、或注册或注销 mTLS
信任锚。密钥则可以在任何时候安全地创建（但须遵守 `README-CloudHSM.md` 中另行说明的法定人数相关注意事项）。

---

## Q1. “加入会生成快照”到底是什么意思，快照又是在什么时候生成的？

AWS 的文档说明，添加 HSM 会对某个现有 HSM 上的**所有密钥、用户和策略**做一次备份，再把该备份恢复到新
HSM 上。

AWS 并未公开备份是在*什么时候*生成的，因此这一点是从外部实测得出的：在整个加入窗口期内，每隔三十秒创建一个
用户，共创建十个用户，待新 HSM 进入 `ACTIVE` 后再读取它们的覆盖情况。

```text
create-hsm issued at epoch 1790239945
  u01  created t+4s    -> cluster-coverage "full"
  u02  created t+34s   -> cluster-coverage "full"
  u03  created t+65s   -> cluster-coverage "inconsistent"
  u04  created t+96s   -> cluster-coverage "inconsistent"
  ...
  u10  created t+280s  -> cluster-coverage "inconsistent"
second HSM reached ACTIVE at t+313s
```

边界清晰可见：在**前约 34 秒**内创建的用户进入了快照并到达了新 HSM；而从**约 65 秒起**创建的内容则没有。
在这个集群上，快照是在这两个时间点之间的某一刻生成的。

**不要把约 34 秒当作安全窗口。** 这只是在一个集群上的一次测量，其时机没有任何文档说明，而且会发生变化。安全
的做法是等待 `ACTIVE`，而不是抢时间快速操作。

## Q2. 如果在加入过程中创建了密钥，它会丢失吗？

不会。密钥有服务端兜底机制：AWS 将服务端同步描述为定期把密钥克隆到集群中的每一台 HSM，无需人工管理。密钥会有
一段时间只存在于比你预期更少的 HSM 上，随后便会出现在全部 HSM 上。

有两点在运维上很重要的注意事项，两者都有 AWS 文档佐证：

- **追赶同步的间隔时间未公开。** AWS 只说它“会有所不同，取决于集群的工作负载以及其他难以量化的因素”。你无法
  从文档中给它设定上界；请用 CloudWatch 来确定你的集群实际表现如何。
- **使用全新密钥的调用可能失败**——如果它恰好被路由到一台还没有该密钥的 HSM 上。AWS 给出的缓解措施是：在
  创建密钥后立即在应用层进行重试。

## Q3. 如果在加入过程中创建了用户，会发生什么？

它会留在旧的 HSM 上，永远不会到达新的那台。没有任何机制会修复这一点。

AWS 把原因说得很直白：*“与密钥不同，不存在能在集群范围内同步 HSM 用户的**服务端机制**。”* CLI 只会在**你
运行命令的那一刻**尽力而为地同步，同步到它当时能够触及的那些 HSM——而正在加入的那台 HSM 并不在其中。

以时间为对照进行实测：在新 HSM 进入 `ACTIVE` 后的第 879 秒（14.6 分钟），六个分叉用户依然处于分叉状态，
而在同一时间段内手动修复的两个用户则一切正常。起作用的变量不是时间，而是那次修复。

```text
checked 879s after the new HSM became ACTIVE
  u03  full           <- had been repaired by hand
  u05  inconsistent
  u06  inconsistent
  u07  inconsistent
  u08  inconsistent
  u09  inconsistent
  u10  inconsistent
```

**为什么这比听上去更糟。** 分叉用户导致的是*间歇性*故障，而不是干净利落的失败。客户端连接会在多台 HSM 之间
做负载均衡，因此同一次登录会因落在哪台 HSM 上而时而成功、时而失败。这是从应用侧最难诊断的故障之一。

## Q4. 我怎么判断自己是否遇到了这种情况？

`user list` 会为每个用户报告一个 `cluster-coverage` 字段。`"full"` 表示集群中当前的每一台 HSM 都拥有它；
**`"inconsistent"` 表示有些有、有些没有**，而这个字符串正是判断信号。

```json
{ "username": "u05", "role": "crypto-user", "locked": "false",
  "mfa": [], "quorum": [], "cluster-coverage": "inconsistent" }
```

对于 mTLS 信任锚，对应的命令是 `cluster mtls list-trust-anchors`，它会为每个信任锚报告 `cluster-coverage`。

对于**密钥**，则改用 CloudWatch：`AWS/CloudHSM` 命名空间下的 `HsmKeysTokenOccupied` 是按每台 HSM 实例
分别报告的，AWS 的监控指南建议针对*“HSM 用户数或密钥数的差异进行告警，以识别同步问题”*。`HsmUsersAvailable`
为用户提供了同样的观察手段。

> **关于 `cluster-coverage` 的一个陷阱，已实测。** `"full"` 的含义是*当前集群中每一台 HSM 上都存在*——它
> **并不是**衡量冗余度或持久性的指标。某个信任锚在集群只有一台 HSM 时注册，当时报告为 `"full"`；而这个
> **未做任何改动的同一个信任锚**，在第二台 HSM 加入后就报告为 `"inconsistent"`。所以在解读任何覆盖状态
> 字符串之前，务必先弄清有多少台 HSM 处于 `ACTIVE`。客户端能数出它们的数量（`cluster hsm-info`），却无法
> 说出它们的名字——它报告的是序列号，而不是 HSM ID，因此任何需要 HSM ID 的操作都得从 `describe-clusters`
> 获取。
## Q5. 状态分歧的用户能否修复？如何修复？

可以，而且过程很干净。AWS 的建议是把你已经开始的操作做完，实测结果也正是如此：

- **该用户应当存在** → 对同一用户名和角色再次执行 `user create`。实测：
  `error_code 0`，覆盖状态从 `inconsistent` → `full`。
- **该用户不应存在** → 以**两种**角色分别对该用户名执行 `user delete`。实测：
  以正确角色执行的删除返回 `error_code 0`，以另一角色执行的删除返回
  `"Specified user does not exist"`，这是无害的；该用户随后从 `user list` 中
  彻底消失。

**AWS 有一条顺序规则，忽视它就会吃亏：** 如果**管理员账户本身**处于不一致状态，
请先修复管理员——你需要一个一致的管理员，才能用它去修复其他任何用户。

**有一种不一致无法就地修复。** 如果某个用户的 `role` 显示为 `inconsistent`，说明该用户在
一部分 HSM 上是加密用户，在另一部分上是管理员。你必须以两种角色都删除它，然后重新创建。
AWS 给出的原因是两个 SDK 同时以不同角色创建了同一用户名——所以请从单一入口、串行地创建用户。

## Q6. 状态分歧的 mTLS 信任锚能否修复？（请仔细阅读答案——命令会撒谎。）

可以，而且**修复会在报错的同时真正生效**。这是整个这一领域中最具误导性的一个行为。

我们通过在加入窗口期间注册信任锚，人为制造了一个真正处于分歧状态的信任锚，然后完全按照
AWS 故障排查页面的建议，重新运行注册命令来修复它：

```console
before:  "certificate-reference": "0x02",  "cluster-coverage": "inconsistent"

$ cloudhsm-cli cluster mtls register-trust-anchor --path ca2.crt
{
  "error_code": 1,
  "data": "Certificate error received from Hsm. Trust anchor is already installed in Hsm."
}

after:   "certificate-reference": "0x02",  "cluster-coverage": "full"
```

**`error_code 1`，而信任锚已被修复。** 报错来自那台已经拥有该信任锚的 HSM；而原本缺少它的
那台 HSM 收到了信任锚。如果操作员把非零退出码当作失败、又不去重新检查覆盖状态，就会得出
修复没生效的结论——而实际上它生效了。

> **务必通过重新读取 `cluster mtls list-trust-anchors` 来验证信任锚的修复，绝不能凭命令的退出码来判断。**

消息文本中其实还有一个可区分的差异，不过它没有文档记载，不应依赖它：

| 情形 | 消息 |
|---|---|
| 信任锚已存在于**每一台** HSM 上（无需修复） | `"Invalid Certificate: Trust anchor already exists."` |
| 信任锚在**部分** HSM 上缺失（执行了修复） | `"Certificate error received from Hsm. Trust anchor is already installed in Hsm."` |

如果你想要一条不含歧义的路径，可以改用先注销再重新注册的方式——实测可以干净地完成：

```console
$ cloudhsm-cli cluster mtls deregister-trust-anchor --certificate-reference 0x02
{ "error_code": 0, "data": { "message": "Trust anchor with reference 0x02 deregistered successfully" } }

$ cloudhsm-cli cluster mtls register-trust-anchor --path ca2.crt
{ "error_code": 0, ... "cluster-coverage": "full" }
```

请注意这条路径的代价：一个集群**最多**只能持有**两个**信任锚，因此只有在你能承受信任锚短暂
缺失的情况下，先注销再重新注册才是安全的；而当两个槽位都被占用时，轮换必须先腾出一个。

## Q7. 上述这些问题是否适用于 Pix 签名密钥或 mTLS 客户端密钥？

两者都是**令牌密钥（token key）**，因此都在自动密钥同步的覆盖范围内。它们既不是用户，也不是
策略。在本工作负载中，它们只在开通时生成一次，并且仅在轮换时再次生成——两者都是计划内活动
——所以实际的暴露面很小。

仍然存在的暴露面是**运维层面的**：如果把容量变更与凭证或信任锚变更安排在同一个维护窗口内，
后者可能会悄无声息地只应用一半。请把它们按先后顺序错开执行。

---

## HSM 容量变更检查清单

1. 在开始之前，记录有多少台 HSM 处于 `ACTIVE` 状态（`describe-clusters`）。
2. 添加 HSM。**在它加入期间不要做任何其他改动**——不要创建、删除用户或修改密码，
   不要注册或注销信任锚，不要变更 mTLS 强制策略。
3. 等待新的 HSM 达到 `ACTIVE` 状态，并通过 `describe-clusters` 确认数量符合预期。
4. 现在再进行任何用户或策略变更。
5. 验证：`user list` 中没有出现 `"inconsistent"`，并且 `cluster mtls list-trust-anchors` 对你预期的
   每一个信任锚都显示 `full`——**要结合第 3 步得到的 HSM 数量来解读**，因为 `full` 始终只意味着
   “当前存在的每一台 HSM”。
6. 为 `HsmKeysTokenOccupied` 和 `HsmUsersAvailable` 的逐台 HSM 差异保留一个常设的 CloudWatch 告警，
   这样分歧会被主动上报，而不是通过一笔失败的交易才被发现。

---

## 哪些内容未经测量，且不应被推断

- **快照瞬间并不是一个公布过的、稳定的数值。** 那个约 34 到 65 秒的区间只是在一个集群上的
  一次观测，**不得**把它当作安全窗口来使用。
- **服务端的密钥同步间隔在此处未测。** 本次实验中没有在加入窗口内创建密钥；上文关于密钥的
  行为来自 AWS 文档，加上本仓库另行进行的法定人数测量，而不是对密钥追赶时序的直接观测。
- **分歧只是通过加入窗口制造出来的。** 在一个健康的集群上部分失败的 `user create` 或
  `register-trust-anchor` 是否会表现出相同的行为，尚不确定；那条路径未经测试。
- **用户在 14.6 分钟内一直处于分歧状态，没有自愈。** 这个时长足以否定“存在即时自动修复”的
  说法，也与 AWS 声明的“不存在此类机制”一致。但它**并不能证明**在数天的时间跨度内也永远
  不会发生任何变化。
