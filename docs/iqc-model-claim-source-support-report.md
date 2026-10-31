# 模型事实来源支持标注的报告摘要

复用原 BUSINESS 报告 includeFindingDistribution、已选任务/轮次、冻结标准/灰度分组、机器轨迹验证和最新已完成复核投影。既有 FactCounts 只统计融合事实状态/BOOL频次，不能统计逐原始模型观察的人工来源支持；因此在相同可选分布增加嵌套摘要，不建立新表/API/参数/权限/数据查询或第二套评测器。

## 协议与分母

findingDistributions[groupKey].sourceSupport 的 schemaVersion 为 iqc-model-claim-source-support-summary-v1，scope 为 KNOWN_RAW_LLM_FINDINGS_IN_SELECTED_RESULT_RUNS。分母 knownClaims 仅为所选实际持久结果中冻结融合事实携带的 KNOWN 且 engineType=LLM 的原始观察，每个结果/事实代码/原始观察ID一次；包括未成为融合赢家的观察。不是 Gold 期望数、声明全部自然语言命题数、节点样本数、供应商物理尝试数或所有模型响应。

仅最新已完成、经原服务验证的 BUSINESS 复核中明确 sourceSupport 纳入 annotatedClaims；SUPPORTED、CONTRADICTED、INSUFFICIENT_SOURCE 分别汇总。未完成复核不纳入，新待处理轮次不抹去上轮有效完成结论；沿用人工事实或仅改分数但未明确来源标注不算已标注。跨事实/未知模型观察/重复标注或重复原观察身份拒绝报告，不静默计入。

knownClaims、annotatedClaims、unannotatedClaims、supportedClaims、contradictedClaims、insufficientSourceClaims 均为精确非负十进制字符串；前三类守恒：annotated=supported+contradicted+insufficient，known=annotated+unannotated。annotationCoverage=annotated/known，六位小数，HALF_UP；known=0 时为null。计数是权威，不能用四舍五入后的覆盖率推断完整标注。

state：无已知原始模型声明为 UNMEASURED；有声明无标注为 UNANNOTATED；部分标注为 PARTIAL；全部已明确标注为 COMPLETE。COMPLETE 只表示本分母中已标注，不能当作都受来源支持、Gold已独立审核或模型质量已验收。

没有结果/没有产出声明/UNKNOWN/ERROR/NOT_EVALUATED/非LLM观察不人为制造已知模型声明，仍分别由原报告缺来源、五状态和原监控指标呈现。这里的零known、零标注或来源不足均不是零幻觉率；本批不计算幻觉率，也不把Finding F1、引用合法性、矛盾和不足擅自合并成广义语义指标。

## 权限与兼容

沿原report:view + 完整会话 + PII的IO前准入及owner/group/all、固定版本/轮次、500任务会话/50000项上限。仅读取原验证回调已经载入的冻结事实和原最新完成投影，不新增SQL、当前消息读取、模型调用或动作。摘要不含原值/原因/证据/操作者/原始观察ID或其他PII。

默认includeFindingDistribution=false不增加回调/IO/字段；原FindingDistribution三参构造保留，新增sourceSupport为null时不序列化。外层v1分布保持原machine/reviewed语义，以可选字段扩展，不改变旧输入/事实/结果Hash。非Pipeline方案不生成此摘要，旧服务和控制器签名保持。

## 验证范围

Root只编译/源码与diff核对，自动同头CI后补实际证据；不新增或运行本地应用测试。B独立补多节点/多raw观察/三结论/未完成与缺结果、重试轮次去重、计数和覆盖率守恒/零分母、重复身份/跨来源拒绝、默认兼容与三权限IO前拒绝、任意正文不泄漏和原SQL组合。完整语义幻觉定义、独立Gold审核、真实PDP/供应商及全部PRD仍继续。

本批 `mvn --batch-mode --no-transfer-progress -DskipTests compile` 实际 BUILD SUCCESS（58.053s），日志 `/tmp/iqc-claim-support-report-compile-20261031.log`；原弃用警告保留，未编写或执行本地应用测试。
