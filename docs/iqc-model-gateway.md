# Model Gateway 并发与调用事实

## 实施前复用及缺口

复用 SnapshotChatModelRouter、SpringAiLlmQualityProvider/HttpLlmQualityProvider、GovernanceRateLimiter 和现有 UsageCounterRecorder。现有 Task/Execution claim 解决重复运行权，任务 concurrency 与 worker 本实例上限不能保证跨实例模型并发。治理 UsageRecord 只有逻辑事件次数，原 RouteExecutionTrace 只有节点结果，均不能表示一节点多次 retry/fallback 的实际模型调用及 nullable Token 用量。

因此 V1.1.49 增加 IQC 专属模型容量行与调用事实两表。容量行锁串行化跨实例准入；调用事实记录每次显式模型调用独立身份与真实响应用量。没有复制 Task、消息、Trace 或组织主数据，没有新增基础设施或修改 framework。此批不是 token/金额硬预算，未声明实现真实网络请求恰好一次。

## 协议

稳定模型键按 provider/endpoint/modelName 字节长度分帧 SHA256；OpenAI-compatible 供应商别名合并，URL host/默认端口/末尾斜杠规范化，防止相同声明端点分裂额度；不存 endpoint、secret 或提示正文。每次显式 retry/fallback 独立 UUID。短事务 RESERVED 占用容量，网络调用在事务外，响应结束后 CONFIRMED 并释放容量；异常 UNKNOWN 保留占用，进程崩溃后的 RESERVED 也不凭本地时钟自动释放。缺 usage 保持 null，绝不伪造0；非法或部分 usage 单独标识。

配置 iqc.llm.gateway.concurrency-limit 默认0（不限制，但记录占用），同模型各实例配置必须一致。容量行保存首次准入配置，配置变更需后续明确管理协议，不能单实例偷偷提高。速率复用同步治理 SLIDING_WINDOW，按每次显式候选/重试准入判定；治理默认 fail-open 的部署配置不能冒充严格限流。准入拒绝是策略结果，不能走 fallback/内部重试绕过。

事实逻辑 recordId/ruleId 是调用者明确传入的观察身份；当前尚不把它推测为 Task/EID。后台 owner 无明确传递时为 null，不能读取异步线程的伪用户。管理员 GET /api/iqc/model-gateway/calls 分页查询，权限 iqc:model-gateway:calls:view 与服务端 IQC_ADMIN/SUPER_ADMIN 身份同时校验；普通全范围读权限不等同管理员。模型配置页面复用 ModelGatewayCalls，Token 以字符串传输保持整数精度。V20261004_19 仅授予 IQC_ADMIN，无新MENU/全局ADMIN。未知调用人工恢复、Task/EID稳定归属和审计恢复尚待继续。

受管资产的 SDK maxRetries=0，由路由显式执行原 maxRetries（0–5），每次重试独立准入。Legacy 默认 ChatModel 的 SDK 隐式重试、自动工具多轮与 legacy ChatModel 内部行为需要单独处理；包装 model.call 只能证明显式调用边界，不能冒充传输层逐HTTP计量。后续需接入传输层、模型版本定价/最大输出token、跨Task预算及可查询等待状态。没有新增或执行测试、SQL、真实模型/事务/浏览器或部署。

同模型 identity 的全局容量不等同不同未声明端点的物理服务配额。Legacy 缺少 endpoint/model 声明时使用 deployment-default，真实部署身份后续完善。治理限次已发但并发拒绝可能保守消耗一次速率票据，不会多发模型请求。网络异常保留并发可能降低可用性，需要后续有审计的人工/远端核对恢复；当前不能凭 TTL 猜测远端停止。
