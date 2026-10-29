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

## 配置页面

前端 /pipelines 已编码：复用现有资产查询选择发布版本，支持节点顺序、条件、错误政策/备用节点、输入映射及输出校验、融合及Boolean评分映射；草稿未保存时禁止试跑/发布。复用现有会话选择、任务运行与任务选择组件；真实试跑发布仍由服务端校验。发布历史、启停、正式版本任务创建与幂等重试已接入，菜单需V20261004_06。vue-tsc/Vite构建通过，尚未浏览器和角色联调。

复核改造复用 BusinessItemReviewEvaluator/Service，已提取评分政策及冻结项目/来源验证边界。Pipeline机器状态先依据冻结Finding/Fusion再现，人工覆盖单独计分；实现和待办见下文，不生成合成Scheme。

## Pipeline 人工业务复核

复用现有 BusinessItemReviewService、ResultReview 轮次、队列、申请/裁决/历史与单轮CSV/任务ZIP接口，不新增复核表或平行API。共用计算器接收冻结项目契约与评分政策；Pipeline分支先校验冻结政策摘要、事实的节点/引擎/版本/语义、证据约束、原始节点输出、融合再现、业务映射及机器评分，再保留独立人工覆盖。请求还要求当前会话最新结果及任务终态；每轮来源摘要包含原始Pipeline轨迹，后续源事实变化会拒绝沿用裁决。历史结果导出仍指定原结果和轮次。

保留原始机器状态、分数、Finding/Fusion，人工只修改业务项结论/理由/消息证据并通过原冻结政策重评分。CSV新增Pipeline身份、版本/试跑修订、内容摘要，且说明规则列为冻结项目绑定；真实多引擎来源仍在执行轨迹中。单轮与任务导出继续限制数据范围，任务ZIP沿用500会话/50000项目/50MiB上限。

前端复用BusinessReviewPanel，Pipeline可以申请、裁决、查看轮次及导出人工复核ZIP。原始机器项目CSV、事实级/证据级修订、二审仲裁及评测候选闭环尚未完成；不能把本批当作完整Review或I5交付。没有执行自动化测试、数据库运行、浏览器联调或部署。

## 原始机器业务CSV

复用 GET /api/iqc/results/export?view=SCHEME&taskId=... 的既有业务导出通道，按照任务快照分派 Scheme 或 Pipeline，沿用 iqc:result:export 及任务数据范围。Pipeline分支校验冻结政策摘要、原始事实/来源/证据、融合和评分，再导出整个任务所选会话的最新canonical结果；没有结果的会话明确为 NOT_EVALUATED/PENDING，确定值仅在KNOWN状态导出。

Pipeline列包括资产ID/发布版本/试跑修订/摘要、源结果/执行、业务项结论与贡献、确定事实值、选中事实ID/来源节点、证据消息ID及保存的融合证据JSON。融合证据保留未选中候选，不当作只来自赢家；项目绑定规则与真实来源分列。未复制完整会话原文，证据JSON沿用已保存内容。该导出保持机器结果，不应用人工覆盖或政策重算。最多50000行、50MiB，无业务项目时拒绝伪造空导出。

前端既有业务CSV按钮已对Pipeline开放。后端及现有测试源码编译、前端vue-tsc/Vite构建通过；测试、数据库和浏览器验收未执行，未部署。

## 完整发布体的默认HTTP映射（2026-10-20）

Create/Update的PipelineRelease包含Jackson2领域树，默认MVC使用Jackson3。源码核对确认原Node.config/outputSchema、Condition.value、Action.configuration/Condition.value、FindingLabelPolicy.directory没有本地HTTP桥接；这些均为实际请求字段，不能依据内部Fusion.Result不作为HTTP输入来跳过。

复用现有HumanFindingReviewEvaluator.HttpExactValueDeserializer和BenchmarkService.HttpTreeSerializer，为上述六个字段增加Jackson3专用注解；不改变Jackson2读写配置/存储解码、不注册全局模块、不修改framework。对象/数组/标量/大整数和嵌套小数走已有局部精确JSON桥接，不将JsonNode作为普通JavaBean。输出也沿已有树serializer避免变成属性集合。原领域构造/防御性复制/引用与Schema校验保持。

原PipelineRelease.DecimalThresholdDeserializer只约束Jackson2输入。新增对应的单一HttpDecimalThresholdDeserializer，复用其必须JSON字符串的契约，供NumericPredicate.threshold、ActionPolicy.decimalOperand、ReviewPolicy置信度区间/概率和InspectionScoring高级权重/调整/维度上下界/等级共用。数字、布尔、数组或对象不能先被String默认强转再进入领域十进制校验；词法/精度/范围仍由各原构造器验证，可选null仍由各原可空字段语义处理。

本批后端原98028 terminal exit0（mvn -o -q -DskipTests test-compile）；无UI接口变更，未新增/运行Root测试。B须从修复前95cce15保留实际完整有效Create/Update默认MVC原失败证据，再覆盖六树字段精确内容/树输出、字符串数值拒绝强转、WEIGHTED/Action/Risk/Review/高级评分组合及Jackson2历史读取不变。编译不证明真实MVC/发布/worker/重算已通过。

### 单列待核实：存储与摘要数值语义

PipelineAssetService.hash仍通过默认Jackson2 readTree构造既有canonical hash；parse/read与parseExact路径亦不同。HTTP精确树接线不能替代完整保存/发布/冻结/读取/重算中的精度证明。应对相邻高精度小数/大整数、原1.0等历史表示、存储字符串/内容摘要和后续政策比较逐项独立验证；若需改变摘要协议，必须制定可识别新旧版本的兼容方案，不全局替换算法而让旧发布/任务失效。本批不宣称摘要碰撞/精度缺陷已被运行复现或修复。
