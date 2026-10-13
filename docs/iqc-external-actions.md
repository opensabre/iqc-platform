# 外部动作适配

PRD V1.0 §12 要求 WEBHOOK、CREATE_WORK_ORDER、CREATE_COACHING_TASK。当前原 ActionExecutionContract 有枚举，ActionAdapterRegistry 尚无这些类型的适配器；公共通知 SEND_ALERT 只提供短信/邮件，不等同于这些交付。现有工作区未找到可复用的工单/辅导业务实现。不能新建并行的工单、辅导或发送账本来掩盖外部系统未接入。

## 复用边界

复用原 ActionPolicy 冻结配置、ActionEnqueueService 生产来源证明、ActionExecution 独立账本、ActionClaimService 领取、ActionSettlementService 围栏、ActionRecoveryService 与只读核对。外部请求在领取事务提交后执行，分析结果不因发送失败而撤销。评测和试运行不发送外部动作。

原 SendAlertPreparationService 的不可变请求/来源/策略摘要、适配器精确版本、领取 token/时间/尝试次数与期限校验移入原 ActionFrozenPolicy.validateOutboundRequest；告警继续负责其当前授权、事务和通知配置解析。其它适配器应复用此校验，不复制一套来源证明。源码调整及告警回归状态以协作记录为准。

## 待实现的远端契约

| 动作 | 外部职责 | 成功证据 |
|---|---|---|
| WEBHOOK | 接收冻结业务事件，按幂等键去重 | 与幂等键及请求摘要绑定的持久回执 |
| CREATE_WORK_ORDER | 在拥有工单模型的系统创建或返回既有工单 | 可核对的工单 ID 和创建回执 |
| CREATE_COACHING_TASK | 在拥有辅导模型的系统创建或返回既有任务 | 可核对的辅导任务 ID 和创建回执 |

适配器配置须引用部署侧批准的目的地及精确协议版本，不能让发布配置任意改变远端凭据或接收地址。配置不应包含认证秘密。外部请求携带原 idempotencyKey 与 inputSnapshotHash；外部系统须保证重复键的请求内容一致。HTTP 2xx 只表示传输结果，未核对持久回执不能结算 CONFIRMED_SUCCESS。超时或无法确定是否已提交保持 UNKNOWN，由原账本进入核对流程；查询不存在不自动授权重发。默认不登记不具备实际实现的适配器。

端点配置、鉴权接入、实际发送及只读核对、管理配置和隔离接收端验收仍待实现。本文件不表示外部动作已经可用，也不是部署或真实发送记录。

## 配置和接收端协议草案

动作配置为 `destinationId`、正整数 `destinationVersion`、对象 `payload`；payload 按 UTF-8 限制 16KiB、嵌套不超过 16 层，并在解析和读取时深拷贝。目的地的 URL、连接/请求期限、响应上限与认证引用由部署侧配置，发布版本只引用目的地精确版本。改变端点或认证契约时增加目的地版本，旧引用不自动改为最新版本。

发送请求应包含 `schemaVersion`、`actionType`、`idempotencyKey`、`inputSnapshotHash`、原生产来源引用及冻结 payload。接收端重复收到相同键时返回同一持久回执；同键不同摘要必须拒绝。只读核对以该键查询同一目的地版本，禁止把查询失败降为再次 POST。

回执应回传 `idempotencyKey`、`inputSnapshotHash`、`outcome`、`evidenceReference`、`resultReference` 和可选 `errorCode`。本地验证键与摘要一致后，继续由原 ActionAdapter.Receipt 检查确认成功/失败的证据与错误约束；不新增本地回执表。响应缺失、超限、摘要不一致或无法判断远端提交状态均保持 UNKNOWN。

这些字段是待接入协议；尚无目的地注册、HTTP 实现或生产接收端验证，不表示三个外部动作已可派发。

## 部署侧目的地约束

下一步目的地配置按 `(destinationId, destinationVersion)` 唯一绑定动作类型、HTTPS 发送端点和只读回执端点；重复键应在启动时拒绝，找不到精确版本时保持适配不可用。两个端点不得带 URI 用户信息、片段或由动作载荷拼接的路径；只读查询仅追加已经验证的 64 字符幂等键。客户端不得跟随重定向。

连接期限、请求期限和最大响应字节由部署侧设定并有界校验，读取回执时也执行字节上限。认证由部署配置引用的秘密来源提供，不写入 ActionPolicy、inputSnapshot 或审计返回值。目的地配置和 HTTP 实现尚未编码；此节是实现约束而非已运行证据。
