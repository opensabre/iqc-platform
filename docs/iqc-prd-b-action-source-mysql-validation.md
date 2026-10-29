# B：生产动作来源冻结输入与确定性投影验收

基于8a9601d，新增IsolatedMysqlActionSourceIT。复用独立MySQL保护检查、迁移和实际Task/Execution/ConversationResult Mapper。

冻结输入按已有生产协议序列化并SHA256，实际ProductionInputSnapshotService.prepare/validateResult核验清单和内容；实际PipelineConversationEvaluator把固定Finding映射为融合、业务项和评分；将此产物写入MySQL再在真实TransactionTemplate事务内调用实际ActionProductionSourceValidator，执行FOR UPDATE与事实/投影再现检查。

五项：合法冻结输入及一致产物得到稳定身份/action/context；currentExecutionId已转轮拒绝；canonical score投影篡改拒绝；冻结input contentHash篡改拒绝；EVALUATION mode产物不能成为生产动作来源。

模拟边界：PipelineRuntime输出固定事实（未调用模型），PipelineAssetService.publishedTaskRelease返回固定发布版本（未证明发布表/hash门禁）。SourceValidator直接置于实际事务中，未声称测其MANDATORY代理；组织网络/真实模型/正式发布资产核验仍需独立验收。不能把这五项泛化为完整线上链。

只操作127.0.0.1:34307和/private/tmp/iqc-mysql-validation.FVGdkd，不读共享库，仅一处IQC生产评分解析修复，不改A/framework；首轮五项1 error：合法确定性评分也被拒绝。逐项诊断确认同值finalScore DecimalNode(8E+1)与持久JSON readTree IntNode(80)直接equals为false，items比较true；诊断日志 `/private/tmp/iqc-b-action-source-diagnosis.log`。生产最小修复保存评分用既有InspectionScoring.Result类型反序列化后投影比较，保留数字/字段/完整性校验。未改变JSON/数据库格式或放宽篡改断言，最终独立MySQL Maven exit0、5项0 failures/errors/skipped，日志 `/private/tmp/iqc-b-action-source-mysql-verified.log`。完整默认回归Maven exit0、91报告/761项0 failures/errors/skipped，所有默认报告本次21:49:48..21:51:40刷新，排除历史独立IT；日志 `/private/tmp/iqc-b-action-source-full.log`。临时MySQL正常关闭，服务与shutdown均exit0；diffcheck通过，未推送/合入A/部署。完整非实时语音PRD继续。
