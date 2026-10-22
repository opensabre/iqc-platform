# 模型重复运行一致性

## 复用与范围

单轮评测报告无法比较独立产物。扩展原PipelineEvaluationService与原评测controller的只读子资源 `GET /api/iqc/evaluation/tasks/{taskId}/consistency`，显式参数executionId/referenceTaskId/referenceExecutionId，沿用report:view当前授权、no-store、无正文QUERY审计和原组织范围。无新表、权限、后台运行器；不更改旧Report/ReportEvidence的JSON或发布门禁。

双方context先检查任务权限与冻结来源，要求datasetHash与流水线内容hash一致，再调用原report来源选择及validatedRow完整产物校验。重试继承的结果使用sourceExecutionIds实际轮次，不把请求EID不同视为产物独立。

## 统计定义

协议iqc-model-consistency-v1。分母为冻结数据集样本数乘声明LLM节点数；每个样本/节点只取轨迹最终节点结果。仅比较独立来源Task/EID/sample、SUCCESS节点、非空且全KNOWN的输出，以及完整有效的有序invocationFingerprints（有效输出数=调用数、无失败、指纹数=调用数）。实际modelKey与框架输入摘要有序序列必须相同。

语义比较为节点Finding的code/type/state/精确value/severity多重集合，复用Selector精确规范摘要；忽略Finding ID、来源执行ID、解释、置信度与证据措辞。该指标不是准确率、证据正确率或供应商逐字输出一致率；这些仍沿原独立质量指标。

comparisonCoverage=comparablePairs/expectedPairs；agreementRate=agreeingPairs/comparablePairs。零分母null，六位HALF_UP。排除原因分开列出：缺产物、同一产物、缺节点、未成功、事实未确认、调用缺测、模型或输入不同；不把缺测填成0/100%一致率。

页面在原评测报告中输入参考任务和轮次，显式只读比较；目标/轮次/权限/输入变化清除旧结果，卸载失效迟到响应。未知协议不显示为成功。结果只含聚合计数，无Prompt、正文或指纹明文。

## 验证边界

当前新增计算与服务边界测试、前端构建正在验证，终态以协作台账为准。真实重复模型调用→独立SQL产物→HTTP→浏览器整链、原组织/PDP角色以及历史精度协议边界仍待。默认发布协议v1未激活精确v2，本功能不替代该剩余工作。没有获得质量达标、真实供应商一致性或完整PRD完成的结论。

本批终态：原83373完整主/测试编译通过；最终原21474 testCompile/surefire通过，日志`/tmp/iqc-b-model-consistency-service-final.log`，计算5/报表18/准入5共28项零失败错误跳过。新增指标覆盖精确值、数字等价、不同输入、旧/坏指纹、失败、空分母、重复产物及源不变；服务覆盖显式双方执行、参考任务组织拒绝先于结果查询、缺产物分母及冻结版本不符拒绝。服务来源与数据库读取为mock，尚未证明真实持久化组合。
