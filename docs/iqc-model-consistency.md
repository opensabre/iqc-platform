# 模型重复运行一致性

## 复用与范围

单轮评测报告无法比较独立产物。扩展原PipelineEvaluationService与原评测controller的只读子资源 `GET /api/iqc/evaluation/tasks/{taskId}/report`，显式参数executionId/referenceTaskId/referenceExecutionId，沿用report:view当前授权、no-store、无正文QUERY审计和原组织范围。无新表、权限、后台运行器；不更改旧Report/ReportEvidence的JSON或发布门禁。

双方context先检查任务权限与冻结来源，要求datasetHash与流水线内容hash一致，再调用原report来源选择及validatedRow完整产物校验。重试继承的结果使用sourceExecutionIds实际轮次，不把请求EID不同视为产物独立。

## 统计定义

协议iqc-model-consistency-v1。分母为冻结数据集样本数乘声明LLM节点数；每个样本/节点只取轨迹最终节点结果。仅比较独立来源Task/EID/sample、SUCCESS节点、非空且全KNOWN的输出，以及完整有效的有序invocationFingerprints（有效输出数=调用数、无失败、指纹数=调用数）。实际modelKey与框架输入摘要有序序列必须相同。

语义比较为节点Finding的code/type/state/精确value/severity多重集合，复用Selector精确规范摘要；忽略Finding ID、来源执行ID、解释、置信度与证据措辞。该指标不是准确率、证据正确率或供应商逐字输出一致率；这些仍沿原独立质量指标。

comparisonCoverage=comparablePairs/expectedPairs；agreementRate=agreeingPairs/comparablePairs。零分母null，六位HALF_UP。排除原因分开列出：缺产物、同一产物、缺节点、未成功、事实未确认、调用缺测、模型或输入不同；不把缺测填成0/100%一致率。

页面在原评测报告中输入参考任务和轮次，显式只读比较；目标/轮次/权限/输入变化清除旧结果，卸载失效迟到响应。未知协议不显示为成功。结果只含聚合计数，无Prompt、正文或指纹明文。

## 验证边界

当前新增计算与服务边界测试、前端构建正在验证，终态以协作台账为准。真实重复模型调用→独立SQL产物→HTTP→浏览器整链、原组织/PDP角色以及历史精度协议边界仍待。默认发布协议v1未激活精确v2，本功能不替代该剩余工作。没有获得质量达标、真实供应商一致性或完整PRD完成的结论。

本批终态：原83373完整主/测试编译通过；最终原21474 testCompile/surefire通过，日志`/tmp/iqc-b-model-consistency-service-final.log`，计算5/报表18/准入5共28项零失败错误跳过。新增指标覆盖精确值、数字等价、不同输入、旧/坏指纹、失败、空分母、重复产物及源不变；服务覆盖显式双方执行、参考任务组织拒绝先于结果查询、缺产物分母及冻结版本不符拒绝。服务来源与数据库读取为mock，尚未证明真实持久化组合。

## 隔离 MySQL 边界验证（2026-10-22）

`IsolatedMysqlMeasuredPublicationIT` 在同一冻结数据集和流水线下，由真实工作器执行两个独立评测任务并读取持久化产物。该流水线仅含处理后会话时间间隔 METRIC 节点，因此一致性 expected/comparable/agreeing 均为 0，coverage/agreement 为 null，排除原因为空；不得报告为 100%。不同冻结数据集（RISK/REGRESSION）的实际执行比较被拒绝。

本轮 main/test 编译及三个测试类共 24 项通过（SQL 1、计算 5、报告 18）；日志 `/tmp/iqc-b-consistency-sql-validation.log`。使用本机专用 34307 数据库，75 项迁移校验通过，脚本结束自动关闭。本证据不包含模型供应商调用、继承产物的 SQL 场景、HTTP 或浏览器联调，不能证明模型一致性达标。

服务层继承边界另由 `PipelineEvaluationReportTest` 覆盖：显式比较 retry/prior 两个 EID，retry 的样本实际继承 prior 产物时，归为 SAME_ARTIFACT，未生成独立比较；缺失样本继续保留 MISSING_ARTIFACT 分母。原 report 校验后 selectOne 已无产物时，拒绝返回比较报告。该层使用真实报告/产物校验逻辑、模拟 mapper，不是 SQL 继承场景证据。最终 `/tmp/iqc-b-consistency-inherited-final.log`，报告 20 +计算 5 共 25 项通过；首轮测试设桩 API 编译错误已修正，无生产代码变化。

HTTP 合约由 `EvaluationCommandHttpPrivacyTest` 扩展两项：真实 MockMvc 参数绑定传递双方 Task/EID，响应保持缺测 null 和排除计数，返回 private/no-store、nosniff；当前授权服务失败时领域服务无调用。原评测命令/报告测试一并通过，最终 `/tmp/iqc-b-consistency-http.log`。该测试模拟领域服务与授权服务，不证明真实网关/PDP、SQL 或模型调用。

## 受控模型桥接的真实持久化（2026-10-22）

`controlledModelBridgePersistsIndependentComparableArtifacts` 复用原 `FrozenDetectorEngine`、`InspectionExecutionService`、评测工作器、Gold 独立审核/数据集冻结、SQL 结果表和一致性服务。规则及智能体版本为数据库夹具，事实通过目录发布，智能体资产校验器与模型适配器为 mock；适配器明确提供固定响应和固定调用指纹，不是路由器生成或真实供应商证据。

两个独立 Task/EID 实际运行并落库后，读取结果得到 expected/comparable/agreeing=1，agreement=1；同一 Task/EID 比较归 SAME_ARTIFACT，不算独立执行。模型会话入口确切调用两次。该事实为无强制证据的普通低风险布尔项，未放宽高风险证据约束。

最终 `/tmp/iqc-b-consistency-model-sql-final.log`、原2622 terminal0，testCompile/测试共27项通过（真实SQL2、计算5、报告20）。首轮已完整main编译；此前三轮分别暴露夹具必填relative_time缺失、高风险无证据、错误设桩单消息入口，均仅修测试夹具。最终MySQL脚本自动关闭。仍不包含真实模型供应商、SQL重试继承或浏览器整链证明。

真实 SQL 重试继承也已覆盖：扩展上述受控模型场景为两个 Gold 样本，适配器令第二个样本首次失败，原队列/工作器进入 PARTIAL_FAILED；恢复后再次 queue/executePersisted，任务 SUCCEEDED，当前轮结果表恰一行。report 中第一个样本 sourceExecutionId 保留前轮，第二个指向重试轮。比较 retry/prior 得 expected=2、comparable=0、agreement=null、SAME_ARTIFACT=1、UNSUCCESSFUL_NODE=1；provider 全场景调用恰5次（两次独立单样本、首次双样本、重试单样本），未重复调用已成功样本。

原97699 terminal0，`/tmp/iqc-b-consistency-retry-sql.log`，testCompile 与 SQL2/计算5/报告20共27项零失败错误跳过。SQL 继承证据现已具备，模型输出与指纹仍受控，不改变真实供应商/路由指纹生成和浏览器整链尚未验证的边界。

## 生产 Provider/Router 指纹到 SQL（2026-10-22）

上述 SQL 模型夹具进一步替换固定指纹：mock 适配器的正常分支委托真实 `SpringAiLlmQualityProvider.evaluateConversation`，使用真实提示词构造、解析、`SnapshotChatModelRouter` 路由与指纹生成，再经原工作器落库并比较。冻结 Agent 提供模型 profile；仅 router.create 返回的 ChatModel 和 ModelGateway 为 mock，gateway 调用传入 supplier 并返回按实际 ModelIdentity.key 构造的句柄。Agent 资产 validator 仍 mock。未发外部请求、未真实记账结算。

独立 Task/EID 的同输入产物 comparable/agreeing=1；SQL 重试继承及未成功节点排除仍通过。provider 入口5次，实际模型与 gateway.invokeTracked 各4次（首次失败样本在适配器注入点直接失败）。不再用固定64字符常量代替输入指纹。最终原91129 terminal0，`/tmp/iqc-b-consistency-router-sql.log`，testCompile+SQL2/计算5/报告20共27项零失败错误跳过；专用MySQL自动关闭。这补齐生产 Provider/Router 指纹到真实SQL的组合证据，仍不证明真实供应商/网关账本/PDP或浏览器整链。

## SQL 到控制器响应组合验证（2026-10-22）

上述真实工作器/Provider/Router/SQL重试场景继续接真实 `PipelineEvaluationController`、响应包装与异常处理，MockMvc请求显式双方Task/EID。响应data经相同JSON序列化与直接服务报告逐字段相等；private/no-store与nosniff存在，不含会话正文或inputHash。授权服务mock抛错时返回IQC-1006且无data，读取未增加模型或gateway调用（仍各4次）。

原13595 terminal1只因测试JsonNode的0/0.0节点类型比较，改为双方均经JSON序列化解析；原43057最终terminal0，`/tmp/iqc-b-consistency-sql-http-final.log`，testCompile及SQL2/计算5/报告20共27项零失败错误跳过。mock当前授权与范围、模型响应/网关账本/Agent资产validator的边界沿前节。此为SQL与真实MVC控制器组合，不是真实socket HTTP、PDP或浏览器整链；无生产源码改动，隔离MySQL自动关闭。


## A/B组合资源映射修订

现有base_org_resource.code唯一且每条记录只有一个method/url。为避免report:view同时注册/report和/consistency而覆盖，组合分支复用GET /api/iqc/evaluation/tasks/{taskId}/report，显式executionId/referenceTaskId/referenceExecutionId选择一致性子报告。无reference参数仍原单轮报告，原两参Java方法/Report/ReportEvidence不变；半缺参数拒绝，不默认替换为当前轮次。无新表/权限码/框架更改。原B受控浏览器网络证据对应旧分支路径，不能外推为此新路径已浏览器验收。
