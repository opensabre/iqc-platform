# 固定数据集 Pipeline 评测执行

状态：本地编码与编译，未运行数据库/模型/角色/浏览器验收，不代表 I3 完成。

2026-10-05：`mvn -o -q -DskipTests compile` 与 `mvn -o -q -DskipTests test-compile` 均退出0；后者只编译既有测试源码，没有执行测试，也没有新增测试文件。数据库迁移及真实评测仍未运行。

## 复用与隔离

任务类型 `PIPELINE_EVALUATION`；复用 InspectionTask、TaskExecution、TaskItem 以及原 execute/pause/resume/cancel 接口和租约。task.ruleSnapshotJson 冻结 Pipeline Prepared（发布版本或精确草稿修订）与 DatasetVersion 身份/hash/sampleCount，不复制第二套样本主库。队列、执行、恢复均校验数据集和冻结依赖当前可用性；运行前一次解析完整数据集，再按会话分派共享运行时，避免每个节点重复读取全部金标。

任务创建/队列/会话执行均不需要实时 Conversation/ConversationMessage 仍存在。运行消息从 DatasetSnapshot.gold 转换，保留 ID/内容/顺序/相对时间，角色使用与生产相同的 ConversationSpeakerRole 规范化，原金标文本及角色不被重写。businessContext 进入运行输入。共享 FrozenDetectorEngine、规则/DLS/LLM 与 PipelineRuntime，Mode.EVALUATION；仍持有真实Task EID并按节点续租。

V1.1.40 保存每个 Task/EID/sample 的不可变 `iqc_evaluation_sample_result`，包含 Dataset/Gold版本和摘要、实际轨迹/hash、SUCCESS/ERROR及评测会话计算耗时。提交锁Task，验证租约/EID/冻结配置/输入/实际fact执行身份与证据归属，复用已有冻结机器事实/融合/评分验证，再事务更新原TaskItem。没有 InspectionResult/ConversationInspectionResult/LabelResult 写入。生产结果分页、业务导出及评分统计拒绝或排除评测任务。

评测状态 SUCCESS 要求 Run SUCCESS 且没有业务ERROR，PARTIAL_SUCCESS 等以 ERROR 保存并可重试；业务 PENDING 可作为执行产物保留，但不能因此声称业务达标。耗时为 ConversationEvaluator 墙钟耗时（包含本会话引擎运行，排除排队、批次准备与结果落库）。停止、取消或租约丢失时不提交旧 worker 产物。

## API

基址 `/api/iqc/evaluation/tasks`：

| 方法/后缀 | 权限 | 契约 |
|---|---|---|
| POST | iqc:evaluation:task:create | `{datasetId,datasetVersion,pipelineId,pipelineVersion?,draftRevision?,name?,concurrency?,requestId}` |
| GET /{taskId}/results | iqc:evaluation:results:view | executionId可选、current/size分页元数据；不批量返回actualJson |
| GET /{taskId}/results/{resultId} | iqc:evaluation:result:view | 精确样本机器轨迹和校验后来源 |
| GET /{taskId}/report | iqc:evaluation:report:view | executionId可选；完整冻结金标分母的报告 |

目标 pipelineVersion/draftRevision 二选一，DatasetVersion必须精确；requestId为16–64字符字母数字/下划线/短横线，按登录用户幂等，内容不同拒绝。并发1–32，最多1000个数据集样本，消息ID在整集合中唯一。任务不自动执行；沿用现有任务执行入口。目标已有同名事实时检查金标类型及ENUM允许值；缺失事实能力保留为指标缺失，而不是删掉金标。

接口仅按拥有Task的数据范围读取；历史报告不要求资产当前启用，不查实时消息。创建仅ADMIN/QUALITY_MANAGER，四类IQC角色可查询。权限 V20261004_09 为4个独立资源/按钮900193–900196，复用数据集菜单与原任务运行权限，未执行SQL。

## 报告口径

报告保存 datasetHash、expected/available/successfulSamples、complete、missingSampleIds及每个成员原 sourceExecutionIds。指定轮次包含更早轮次的最新成员产物及当前轮次，不混入同编号的并发废弃轮次或未来重试。摘要列表只显示指定EID自己的行；报告明确显示继承来源。产物和输入/金标/hash均再次验证。

FindingMetrics 覆盖全部冻结样本，缺失行传入空实际Finding，仍计覆盖/值准确率/阳性FN分母；complete仅表示终态且每个成员有产物，不等于全部SUCCESS或门禁通过。错误样本保留原输出及错误计数，不删除坏样本提高分数。指标缺分母为null。

RegressionMetrics 只对 REGRESSION 数据集计算，全体冻结样本为分母，保留失败 sampleId。通过要求执行SUCCESS、所有已标注Finding类型/状态/KNOWN值精确一致、选中证据ID与可选金标精确一致，以及已标注Score状态/数值和Risk精确一致。没有标注的额外Finding不视为误判；有标签期望时因当前无标签输出不能通过。其他类型数据集 expectedSamples=0、passRate=null，不能当作100%回归通过。该严格匹配指标没有替代发布准入或证明现有版本已通过回归。

BusinessMetrics：已标注FINAL分数且实际FINAL的比较数与MAE，同时报告完整gold scoreCoverage；高风险HIGH/CRITICAL金标为分母，实际HIGH/CRITICAL为召回，缺失/未知实际不算检出。风险使用当前兼容评分事实投影，独立Risk Policy仍属I5。标签期望数量保留，但当前Pipeline无独立标签输出，comparableLabels=0、labelAccuracy=null，不能作为正确空标签。LatencyMetrics：实测样本墙钟P50/P95/P99、已物化产物的执行失败率；缺失样本单独显示，分位数采用nearest-rank，不宣称批次TPS、Token/GPU或成本指标完成。

单产物/流水线解析最多16MiB，Dataset输入沿用64MiB总量限制。评测任务/报告前端、复核候选B交付、完整业务/模型/系统/离线音频指标、单变量Benchmark对照、回归通过率、发布门禁及强制发布审计仍待实现。此接口不能绕过现有发布流程，也未改变现有试跑发布门禁。
