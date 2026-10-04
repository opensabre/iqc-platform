# Pipeline 资产与任务接口

## 资产版本

API 前缀 `/api/iqc/pipelines`，权限与现有组织资源注册复用，每个方法使用独立资源码。草稿更新使用 expectedRevision 乐观校验，发布版本不可覆盖。

| 方法与路径 | 请求字段 | 行为 |
|---|---|---|
| GET / | current、size | 可见资产分页 |
| GET /{id} | — | 草稿及发布指针 |
| POST / | code、name、release | 创建草稿 |
| PUT /{id} | expectedRevision、name、release | 更新草稿 |
| POST /{id}/availability | enabled | 资产启停 |
| GET /{id}/versions | — | 不可变版本历史 |
| POST /{id}/trial | expectedRevision、name、conversationIds、requestId | 冻结草稿创建试跑任务，最多10个会话 |
| POST /{id}/publish | expectedRevision、trialTaskId、resultsReviewed | 校验真实试跑后发布 |
| POST /{id}/tasks | versionNo、name、conversationIds、concurrency、requestId | 使用发布版本创建正式任务 |

任务创建返回 CREATED；继续使用现有任务运行接口发起执行，不在资产创建请求中隐式运行。正式任务最大1000会话、并发最大32；试跑并发1。requestId 可选，同一主体、流水线和 requestId 的相同请求返回原任务，变更请求内容拒绝。

release.schemaVersion 为 `iqc-pipeline-release-v1`，包含 pipeline、fusion、scoring、items。items 指定 itemCode、findingCode、hitMeaning，可选 appliesWhenFindingCode；评分项目与映射项目必须完全一致。当前适配器只消费 BOOLEAN Finding，不代表全部强类型引擎已完成。

节点 config 使用 ruleReference、findingReference，LLM 另需 agentReference；各引用形如 `{ "id": "资产ID", "versionNo": 1 }`。允许 inputScope=MESSAGE/CONVERSATION、phase=DIRECT/REVIEW/VERIFY。服务端从已发布版本嵌入快照，客户端不能提供任意配置替换。LLM 暂拒绝包含 MCP 服务的 Agent，避免未经审查的检测副作用。

## 发布门禁

需要同一草稿修订及同一规范化内容摘要的可见试跑任务，任务 SUCCEEDED，所有选中会话的最新结果 scoreStatus 为 FINAL 或 NOT_APPLICABLE、runtime 状态 SUCCESS，并显式确认 resultsReviewed。恢复任务保留此前成功会话，不只查看最后一次执行。该门禁没有金标数据集、指标阈值或例外发布功能，I3 继续实现。

## 结果与执行安全

沿用 result-hierarchy 的 executionTraceJson，新增 schemaVersion=`iqc-pipeline-execution-v1`；字段 inputHash、run、fusion、items、scoring、riskLevel、mode。run 含每次节点尝试、失败与跳过；fusion 保留候选事实和选择理由。内部 __task/__messages 不进入持久化变量，适配器不能覆盖可信上下文。

失败、未知、冲突、未执行分别保存，不转换为未命中。VERIFY 仅接受上游在原消息中可验证的逐字引文。结果与任务消息检查点在现有事务保存，执行ID或租约失效时拒绝写入。节点之间更新心跳；跨实例周期心跳与完整持久化调度仍属于 I4。

当前风险级别是评分映射事实的基础投影，评分不完整时为 UNKNOWN；独立 RiskPolicy、动作和复核适配仍待 I5。前端已编码两种轨迹展示，包含节点尝试、融合候选与原因及计分明细，尚未完成浏览器联调。

## 数据与验证

新增 V1.1.35 仅承担 Pipeline 资产版本；复用现有任务、执行、结果表。组织资源另需 V20261004_01 和 V20261004_02 迁移。后端及现有测试源码编译通过；未执行自动化测试、迁移或端到端运行，未部署。

## 基于事实的政策重算

POST `/api/iqc/conversation-results/{id}/decision-revisions`：targetPipelineId、targetVersion、requestId（16–64位字母数字下划线短横线）、reason。使用指定已发布版本的 fusion/items/scoring，将原结果保存的全部原始 Finding 重新融合和评分；不执行目标版本的检测节点，不调用模型，不发业务动作。目标新增事实但源数据不存在时保留 NOT_EVALUATED/PENDING，不能假定通过。

GET 同一路径返回该源结果最新100条重算记录。源任务数据范围和目标资产范围分别校验；原始融合与评分须可由原冻结政策再现，拒绝不一致快照。requestId 对当前主体幂等，不同内容拒绝；结果为 append-only iqc_decision_revision（V1.1.36），targetPolicyJson 冻结目标版本，projectionJson 包含原始/重算评分、融合理由及源摘要。原始检测结果及人工复核不覆盖，不自动替换报表当前结论。

当前仅支持 Pipeline BOOLEAN 业务评分；旧 Scheme、标签、独立风险政策重算及结构化评分对比仍待扩展。前端已接入发布版本选择、重算请求及最近100条记录查看。权限 iqc:decision:recalculate / iqc:decision:history:view。
