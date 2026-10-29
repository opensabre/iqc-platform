# B：历史回归覆盖及冻结 null 修复迁入

基线为动作集成分支 `44c53d0`（A `6fcddb4` 加当前 B 链）。先比较旧 B `e6b740a` 与本树，恢复当前缺失的14份测试，没有覆盖已经适配的新夹具或独立 MySQL IT。

## 迁入范围

- `PipelinePublicationAuthorizationTest`、`PipelinePublicationGateServiceTest`：发布证据、分母、override权限及真实授权门禁。
- `BusinessReviewSampleServiceTest`、`ReviewSampleInputValidatorTest`：已复核候选捕获、来源/hash及输入一致性。
- `FindingEvaluationMetricsTest`、`PipelineEvaluationReportTest`：金标完整分母、缺失/失败产物、真实证据匹配、继承轮次来源、结构化指标。
- `EvaluationDatasetVersionTest`、`GoldSampleReviewTest`、`PipelineEvaluationAdmissionTest`：冻结金标/数据集、独立审核和批准准入、冻结依赖。
- `PeriodicDefinitionTest`、`ScheduledActorAccessServiceTest`、`ScheduledActorScopeTest`：计划/宽限/DST及当前身份/权限/范围交集、异常作用域清理。
- `DurableInspectionWorkerTest`、`TaskExecutionStateServiceTest`：后台发现/拒绝恢复/局部并发上限、领取与终态围栏。

这些主要是服务逻辑回归，仓储/组织网络等按各测试原有边界隔离。迁入不能替代真实组织、模型、SQL、后台多实例或浏览器验收。

## 当前实现实际失败和修复

迁入后先针对全部14类运行：**105项，1 failure、14 errors，Maven exit1**，日志 `/private/tmp/iqc-b-history-coverage-red.log`。未弱化断言。

当前生产树确实缺少 B 已有的三批修复：`3feaf40`、`d0addb9`、`06b6c72`。实际复现无可选业务上下文的 GoldConversation/GoldDraft JSON null 被当作非对象，金标审批及数据集冻结解析失败；失败模型/取消轮次冻结融合值 JSON null 与 Java null 不一致，报告无法再现原事实。

恢复四处最小变更：

1. FindingFusion.Result 的 null/NullNode 统一为无值。
2. GoldConversation 与 GoldDraft 校验前将可选业务上下文 NullNode 统一为无上下文。
3. GoldSampleService 不把省略可选标注上下文序列化出的 NullNode 当作对象。

非 null 值仍执行原对象/来源/完整清单等校验，原深复制保留；没有改金标权限、状态、指标分母或数据库模型。此次不是回退整个旧生产树，只纳入对比确认缺失且回归复现的四个边界处理。

针对性复跑 **Maven exit0、14份报告/105项、0 failures/errors/skipped**，日志 `/private/tmp/iqc-b-history-coverage-verified.log`。使用离线 Maven 和既有 ByteBuddy agent，结果按本次新 Surefire 报告核对；后续完整回归须计入新迁入的105项，不能只引用上一批619项。

未改A原工作树或framework，未读取共享数据库/凭据、推送/合并/部署。完整非实时语音PRD继续，离线音频保留。

## 完整回归结果

当前集成树完整默认 `mvn -o -q -DargLine=-javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar test`：**exit0、86份报告/724项、0 failures/errors/skipped**。所有默认报告均于本次08:19:48至08:23:49生成，排除独立IT历史报告；日志 `/private/tmp/iqc-b-history-full-verified.log`。diffcheck通过。

本次保留原619项并新增105项历史回归。未重跑独立MySQL IT，不能用默认套件证明真实SQL围栏。本树A基线仍为6fcddb4；协作记录中新动作策略、适配器、注册表、内部入队等后续A提交尚未纳入本次验证，需要后续合入并专门验收。完整PRD保持进行中。
