# 外部动作适配

PRD V1.0 §12 要求 WEBHOOK、CREATE_WORK_ORDER、CREATE_COACHING_TASK。三个类型已沿原 ActionAdapterRegistry 接入精确 v1 适配器，由部署目的地配置选择接收端；公共通知 SEND_ALERT 继续提供短信/邮件。现有工作区未找到可复用的工单/辅导业务实现，因此通过外部拥有这些业务模型的系统创建，不新增平行业务表。接收端真实验收仍待完成。

## 复用边界

复用原 ActionPolicy 冻结配置、ActionEnqueueService 生产来源证明、ActionExecution 独立账本、ActionClaimService 领取、ActionSettlementService 围栏、ActionRecoveryService 与只读核对。外部请求在领取事务提交后执行，分析结果不因发送失败而撤销。评测和试运行不发送外部动作。

原 SendAlertPreparationService 的不可变请求/来源/策略摘要、适配器精确版本、领取 token/时间/尝试次数与期限校验移入原 ActionFrozenPolicy.validateOutboundRequest；告警继续负责其当前授权、事务和通知配置解析。其它适配器应复用此校验，不复制一套来源证明。源码调整及告警回归状态以协作记录为准。

## 远端契约及接收端待验收要求

| 动作 | 外部职责 | 成功证据 |
|---|---|---|
| WEBHOOK | 接收冻结业务事件，按幂等键去重 | 与幂等键及请求摘要绑定的持久回执 |
| CREATE_WORK_ORDER | 在拥有工单模型的系统创建或返回既有工单 | 可核对的工单 ID 和创建回执 |
| CREATE_COACHING_TASK | 在拥有辅导模型的系统创建或返回既有任务 | 可核对的辅导任务 ID 和创建回执 |

适配器配置须引用部署侧批准的目的地及精确协议版本，不能让发布配置任意改变远端凭据或接收地址。配置不应包含认证秘密。外部请求携带原 idempotencyKey 与 inputSnapshotHash；外部系统须保证重复键的请求内容一致。HTTP 2xx 只表示传输结果，未核对持久回执不能结算 CONFIRMED_SUCCESS。超时或无法确定是否已提交保持 UNKNOWN，由原账本进入核对流程；查询不存在不自动授权重发。默认不登记不具备实际实现的适配器。

已实现部署目的地注册表、HTTP 发送/只读核对客户端、Spring 配置及适配器登记。客户端、响应限流和目的地绑定共 10 项测试通过；Spring 绑定、适配器、准备及告警组合 21 项通过；真实事务代理拦截及合并后的会话组合 20 项通过。界面配置与载荷 3 项测试及构建通过。隔离接收端、完整来源账本 SQL 和浏览器验收仍待完成；没有部署或真实外部发送记录。

## 配置和接收端协议草案

动作配置为 `destinationId`、正整数 `destinationVersion`、对象 `payload`；payload 按 UTF-8 限制 16KiB、嵌套不超过 16 层，并在解析和读取时深拷贝。目的地的 URL、连接/请求期限、响应上限与认证引用由部署侧配置，发布版本只引用目的地精确版本。改变端点或认证契约时增加目的地版本，旧引用不自动改为最新版本。

发送请求应包含 `schemaVersion`、`actionType`、`idempotencyKey`、`inputSnapshotHash`、原生产来源引用及冻结 payload。接收端重复收到相同键时返回同一持久回执；同键不同摘要必须拒绝。只读核对以该键查询同一目的地版本，禁止把查询失败降为再次 POST。

回执应回传 `idempotencyKey`、`inputSnapshotHash`、`outcome`、`evidenceReference`、`resultReference` 和可选 `errorCode`。本地验证键与摘要一致后，继续由原 ActionAdapter.Receipt 检查确认成功/失败的证据与错误约束；不新增本地回执表。响应缺失、超限、摘要不一致或无法判断远端提交状态均保持 UNKNOWN。

HTTP 实现使用请求协议 `iqc-external-action-request-v1` 和回执协议 `iqc-external-action-receipt-v1`；Spring 目的地配置与适配器登记测试通过，尚无生产接收端验证，不表示三个外部动作已完成验收。

## 部署侧目的地约束

已实现不可变 `ExternalActionDestinations` 注册表，按 `(destinationId, destinationVersion)` 唯一绑定动作类型、HTTPS 发送端点和只读回执端点；构造时拒绝重复键，找不到精确版本或动作类型不匹配时拒绝解析。两个端点不得带 URI 用户信息、查询参数或片段；只读查询仅追加已经验证的 64 字符幂等键。HTTP 客户端接入后不得跟随重定向。

注册表已校验连接期限、请求期限、最大响应字节和秘密环境变量引用格式，并复制配置列表；目的地与动作配置共 4 个单元测试通过。`BoundedActionResponseSubscriber` 分块执行回执字节上限，超限取消订阅，其 3 项测试通过。认证由部署配置引用的秘密来源提供，不写入 ActionPolicy、inputSnapshot 或审计返回值。Spring 部署配置绑定及适配器登记已实现，尚无实际外部发送证据。

`HttpExternalActionTransport` 使用 HTTPS POST 和只读 GET，客户端禁用重定向，完整响应使用有界订阅者，额外等待期限覆盖完整响应体，超时取消 future；中断恢复线程标志。严格 JSON 解码拒绝重复字段和尾随内容，回执验证幂等键、输入摘要与协议，并沿原 Receipt 检查持久证据和错误码。异常与无法确认的响应保持 UNKNOWN，不保存远端响应或异常消息。5 项 HTTP 边界测试通过；模拟客户端验证不等于真实接收端验收。

## 部署配置

配置绑定与三个适配器已经通过局部测试。使用单独的 `iqc.actions.external.enabled` 开关，默认关闭；原派发器 `iqc.actions.enabled` 开关保持独立。发布动作只有在部署侧存在匹配类型的精确目的地版本时才能通过配置验证。下面是配置格式示例，不是已启用的部署：

```yaml
iqc:
  actions:
    external:
      enabled: false
      destinations:
        - id: webhook_receiver
          version: 1
          type: WEBHOOK
          send-uri: https://receiver.example.invalid/events
          receipts-uri: https://receiver.example.invalid/receipts/
          connect-timeout-ms: 1000
          request-timeout-ms: 10000
          maximum-response-bytes: 16384
          bearer-token-environment-variable: IQC_WEBHOOK_TOKEN
```

工单和辅导任务分别配置 `CREATE_WORK_ORDER` 与 `CREATE_COACHING_TASK` 的目的地，不能借用 WEBHOOK 类型配置。改变目的地协议或地址必须增加版本并保留历史回执查询能力；不得覆盖仍被冻结动作引用的旧版本。认证秘密只在调用前读取，不在发布或领取锁内访问远端。

## 已验证范围和剩余验收

隔离本机 HTTPS 接收 fixture 的首批 5 项测试通过：三个动作类型真实 TLS POST 与冻结来源/载荷、重复键返回同一 fixture 回执、历史 GET、接收后丢失响应仅通过 GET 恢复、重定向不访问目标。仅信任临时测试证书，保留主机名校验；不访问生产系统。fixture 回执存在内存中，不能据此证明工单/辅导系统的持久性。额外真实流式超限和响应体停滞 2 项也通过，共 7 项实际 HTTPS 测试通过。

动作历史界面已接入三个外部类型的原人工核对入口，仍要求原核对权限、观察版本和审计原因，未确认保持 UNKNOWN；原历史/恢复/核对及政策共 31 项界面测试通过，vue-tsc/Vite 构建通过。人工核对复用原 API 和账本，不新增重发入口。

剩余门禁包括拥有业务模型的真实接收端持久幂等/同键不同内容拒绝、认证接入与历史版本回执保留、原来源/领取/结算/核对 SQL 事务及竞争、实际接口权限与浏览器流程。完整 PRD 的其它需求继续，不能以本节局部测试标记全部交付完成。
