# I4 持久事件/API触发

## 实施前复用取舍

既有Conversation结构化API导入、Selector/Pipeline发布资产、Task/Execution/Item、输入冻结和持久worker可直接复用。EDA本地通知非持久投递；不能作为接收确认或唯一恢复来源。原Task无外部事件身份/等待输入/原始触发证据，本批扩展同一Task，使其先作为WAITING_INPUT接收记录，再在锁内物化为同一质检实例。不新建并行Inbox/任务主表，也不复制周期定义库。

API/事件请求直接携带明确Pipeline/可选Selector版本与单会话目标或结构化内容；接收来源命名空间、eventId/requestId、事件时刻和载荷摘要。同一负责人+来源+事件ID只能对应同一载荷，取消/终态删除保留身份行；重放不复活任务。输入缺失或ASR未完成时持久等待，后台重新取得负责人当前授权，事务中检查等待状态、输入修订、资产可用性后升级并入队。原请求和负责人创建范围上限始终保留。

Selector目标ID限制与原定义include/exclude/范围取交集，不更改资产定义或扫描其他事件；采样统计的候选池只包括该请求的目标。比例采样沿用现有CEILING语义，不声称单事件5%为跨所有事件的抽样率。匹配为空产生NO_DATA而非等待。输入要求就绪后才筛选。提交内容复用结构化导入并严格核对重复外部身份的正文和全部分析元数据，不用重复命中掩盖变化。

本轮交付先覆盖直接事件/API接收与持久等待恢复；自动事件订阅配置/实际MQ消费接线、全局预算、自动重试/失败尝试不可变历史继续推进。不能将HTTP接收已持久化与端到端MQ消费确认等同。总开关默认关闭，运行前需迁移及真实身份/事务验证。本批不新增/运行测试或部署。

## 接口与身份

`POST /api/iqc/triggers` 接收 JSON 对象，权限 `iqc:trigger:receive`；同时需要 Pipeline 任务创建和执行权限，提交 content 另需会话上传权限。后台每次重新查询负责人当前账号、授权与范围，取创建时范围上限交集。不会保存用户令牌。接口限流 120 次/60 秒。

请求协议 `iqc-task-trigger-v1`，必填 source、eventId、eventType、occurredAt、expiresAt、name、pipeline、concurrency；pipeline 使用明确 pipelineId/versionNo，可选 selector 使用 selectorId/versionNo。target 和 content 必须二选一。target 使用 conversationId，或 sourceType/externalId，并始终提供 64 位小写 SHA-256 inputRevision（归档 sourceFingerprint）。content 沿用结构化导入 externalId/batchNo/title/metadata/messages，每条消息 time/role/content。

```json
{
  "schemaVersion": "iqc-task-trigger-v1",
  "source": "crm",
  "eventId": "conversation-123-completed-v1",
  "eventType": "ConversationCompleted",
  "occurredAt": "2026-10-06T08:00:00Z",
  "expiresAt": "2026-10-07T08:00:00Z",
  "name": "会话结束质检",
  "pipeline": {"pipelineId": "published-pipeline-id", "versionNo": 1},
  "content": {
    "externalId": "conversation-123",
    "title": "文本会话",
    "messages": [{"time": "00:00:00", "role": "agent", "content": "您好"}]
  },
  "concurrency": 1,
  "requireASR": false
}
```

示例资产 ID 须替换为实际可用的发布版本；日期也须使用事件实际时刻及未来有效期。返回紧凑接收凭据 taskId/status/source/eventId/payloadHash/requestHash/currentExecutionId/等待原因及下次时间；首次接收为 WAITING_INPUT，持久化成功不等于执行完成。原 Task 详情、取消和删除接口沿用已有权限。

## 一致性与恢复

迁移 V1.1.45 在原 Task 添加事件身份及接收快照字段，唯一键为 created_by/source/event_id，来源和事件 ID 使用 binary collation。事件 ID 禁止首尾空白。重放同时校验稳定用户 ID，避免用户名复用泄露历史。相同规范化载荷返回原任务，不重置取消、删除或过期状态；不同载荷拒绝。原始接收证据包含明确发布版本快照、负责人范围及完整请求，独立保存 payloadHash 与完整 Accepted requestHash。

接收事务采用 RR；死锁、悲观锁冲突或唯一键竞争最多重试三次，每次开启独立事务。后台每轮最多发现 25 条，缺少输入或暂时授权/依赖失败退避 30 秒。每次锁住同一 Task，复查 WAITING_INPUT、两个摘要和当前授权后物化；旧 Frame、取消和过期不能复活任务。到期变为 TRIGGER_EXPIRED，确定身份/修订冲突变为 TRIGGER_CONFLICT，可逻辑删除但保留事件身份。

归档目标、Selector 与输入冻结使用同一 RR 视图，避免当前读看见新输入、筛选却看不见而误判 NO_DATA。复用原归档 source_type/external_id 和 source_fingerprint 唯一键，严格重放核对实际消息及全部分析元数据。特定 ConversationReplayConflictException 在任何归档写入前抛出，persistVerified 对该异常不标记回滚，使外层可以提交冲突终态；其他异常仍回滚并退避。

同一请求只筛选自己的目标；匹配为空为 NO_DATA，否则冻结原消息与上下文并在同一事务入队。未知目标及提交内容的接收任务初始私有，物化不会扩大已接受的可见范围。输入数据不被转换为评测金标。

## 约束与运行边界

- 日期规范化到毫秒后仍必须 expiresAt > occurredAt；接收拒绝已过期及未来超过 5 秒的事件。
- 请求最多 16MiB，Accepted 快照最多 32MiB；单会话最多 5000 条消息，正文每条最多 65535 UTF-8 字节，规范角色最多 32 字符，businessFields 序列化最多 65536 字节。确定格式错误在接收前拒绝。
- requireASR 或 eventType=ASRCompleted 时，提交内容必须已经标记 COMPLETED/SUCCESS/SUCCEEDED。未知归档可等待到达；既有 PENDING 归档不能在同一固定指纹内悄悄升级，完成转写应形成明确的新输入修订/身份。现阶段没有自动 ASR 写回。
- `iqc.trigger.enabled` 默认 false；显式启用后每 5 秒发现持久等待记录，执行使用已有持久 worker。上线须按发布流程应用 Task DDL 和组织权限 V20261004_15。

本批仅编译和 UI 构建，没有执行测试、数据库迁移、真实授权/模型调用或部署。自动订阅配置、MQ 消费与生产者投递、失败尝试不可变记录、自动失败重试和全局速率/成本预算尚未完成，I4 不标记完成。
