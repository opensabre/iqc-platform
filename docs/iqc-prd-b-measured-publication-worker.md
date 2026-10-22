# B 实际测量发布与冻结输入 worker 组合验证

日期：2026-10-22。基线：e0ed424。输入仅为处理好的会话及已提供的消息时间坐标，不涉及语音转换。

## 覆盖

`IsolatedMysqlMeasuredPublicationIT` 使用真实 Spring 事务代理、MyBatis Mapper、本机独立 MySQL 和实际应用服务：

1. 创建并发布数字 Finding 目录，创建精确 v2 Pipeline 草稿；阈值字符串 `0.900000000000000001` 保持不变。
2. 初始候选样本由测试写入；实际 Gold 服务冻结两条消息、拒绝标注人自审，独立审核人批准后形成金标版本。
3. 分别创建并冻结 RISK、REGRESSION 数据集；实际评测任务物化、queue、executePersisted 与检查点提交，生成两个完整且 F1=1 的真实报告。
4. 实际草稿试跑；缺失试跑时拒绝发布，使用两个真实 Task/EID 与成功试跑执行普通发布，断言非强制、门禁通过、证据数量及发布决策 hash。
5. 从真实已发布版本创建生产任务，冻结输入后修改原数据库消息正文与时间坐标；实际 worker 仍按原输入得到分数80、HIGH风险、两个成功检查点，并确认原消息确已改变。
6. 再由真实 publishedTaskRelease 核验正式任务绑定的版本；未模拟发布资产、门禁、报告、运行时、任务服务或结果持久化。

## 暴露并修复的生产缺陷

- `PipelineDefinition.Node.outputSchema` 原本 Java null 有效，但草稿保存后 JSON null 解码为 NullNode，第二次冻结会被 ValueSchema 拒绝。节点构造时将 NullNode 统一为无 schema；序列化表示仍是 null，未放宽具体 schema 规则。两项回归覆盖反复往返、有效对象及非法字符串 schema。
- `TranscriptMetricEngine` 原事实 ID 使用独立公式，不符合评测持久化已有的执行轮次/会话/节点/尝试身份规则，真实评测 worker 因而拒绝合法输出。改用既有公式，保留原消息 manifest 的 source.inputHash 与指标证据内容；拒绝空 manifest，避免生成无会话绑定的事实。新增执行轮次/尝试隔离断言；不修改评测校验规则。

## 终态证据

第五轮原进程28560终态0，日志 `/tmp/iqc-b-measured-publication-fifth.log`。执行：

```sh
mvn -o -q -Dtest=IsolatedMysqlMeasuredPublicationIT,PipelineNodeSchemaRoundTripTest,ParallelPipelineRuntimeTest,ParallelCompletionPolicyRuntimeTest,StructuredDetectorSchemaTest,TranscriptMetricEngineTest -DargLine=-javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar compiler:compile compiler:testCompile surefire:test
```

完整 main/test 编译成功；六份当轮 XML 共30项，零失败、错误、跳过：SQL链路1、schema往返2、并行4、并行完成政策14、结构化schema2、指标引擎7。75个既有迁移校验成功，schema 1.1.74 无待迁移。

本机脚本 `/private/tmp/iqc-b-measured-publication-validation.sh` 校验实际端口34307、datadir `/private/tmp/iqc-mysql-validation.LuvVgU/`，退出自动正常关闭；终态后 mysql.pid/mysql.sock 均不存在。未连接共享数据库。

前四轮均终态1：75323消息SQL夹具缺必填列；16218发现可选schema往返缺陷；42929共23项中22通过、发现指标身份缺陷；91895双评测真实成功、共29项中28通过，随后测试配置同类型任务服务bean选择不唯一。补测试完整任务服务为Primary；生产发布门禁未修改。

## 证据边界与剩余

组织授权 IqcDataScope 使用受控测试身份；初始候选是夹具，未证明真实复核产生候选。未使用外部模型供应商；指标来自现有处理后会话的时间坐标。此单场景通过不能证明生产数据集质量、容量或性能 SLO。

3cc7d30 时本测试尚不含复核重算、Action派发/对账；后续扩展见下一节。外部渠道、真实PDP/collector/浏览器仍未验证。未激活默认v2，未前移B主树、未修改A工作树/framework/gateway、未push或部署。完整适用I1–I7继续active。


## 后续扩展：动作、复核重算和候选回流

基线3cc7d30上扩展同一SQL链路，仅修改测试配置与断言，没有新增或修改生产服务。

- 原发布草稿纳入SAVE_RESULT、CREATE_REVIEW策略；策略随实际评测、试跑、普通测量发布和正式任务冻结流转。
- 显式调用真实ActionEnqueueService工作流入口，真实发布资产/输入/来源校验通过；重复入队返回相同两个ID，数据库仅两条动作。由ActionExecutionService实际领取、适配及结算；SAVE_RESULT确认既有持久结果，不创建另一份结果。
- CREATE_REVIEW调用真实BusinessReviewActionService，在独立事务中创建一条待办。测试专属MySQL BEFORE UPDATE触发器只拒绝这条动作从RUNNING转COMPLETE，令回执落库失败。实际runner返回SETTLEMENT_PENDING，复核已提交而动作仍RUNNING；finally移除测试触发器。
- 无用户上下文调用真实ActionDispatchStateService将该失联领取隔离为REQUIRES_RECONCILIATION/UNKNOWN；随后实际ActionReconciliationService经适配器只读查找原复核产物，原动作转COMPLETE/CONFIRMED_SUCCESS。持久核对尝试有且仅有一个CONFIRMED，重复旧版本核对为STALE_OBSERVATION，复核仍只有一条。领取次数保持1，旧版本execute不再领取。
- 独立受控reviewer经真实BusinessItemReviewService提交FAIL→PASS，原分80/复核分100；相同裁决重试保持原快照。原机器trace、scoring、items三个存储JSON逐字节不变。
- 真实BusinessReviewSampleService拒绝捕获已漂移的原会话；恢复测试专属原输入后捕获候选，重复请求保持同ID/单条记录。候选为精确v3，保留真实复核ID与重算100分。

原75176终态1：testCompile通过，隔离配置未接现有创建人填充器，正式任务缺createdBy，动作来源守卫正确拒绝。测试接入框架已有PoMetaObjectHandler与受控UserContextHolder，不修改框架或生产来源校验。原50082终态0，`/tmp/iqc-b-measured-review-actions-second.log`；compiler:testCompile/surefire:test，当轮XML一项组合场景零失败错误跳过，30.08s。生产沿用3cc7d30已完整编译版本；之前30项回归属于该基线，本次不宣称重跑全部30项。

本机脚本 `/private/tmp/iqc-b-measured-review-actions-validation.sh` 沿用隔离目录/端口guard及正常关闭；75迁移校验通过，schema1.1.74当前，结束后pid/socket不存在。测试触发器是故障注入，不是生产迁移。

身份授权仍是受控seam：IqcDataScope及ScheduledActorAccessService.capture为mock，固定四个资源grant；没有真实PDP/current grant撤权或HTTP授权证据。调用实际内部工作流和runner，不是定时扫描器tick/自动发现验收。复核确认没有外部发送；不证明真实外部模型、渠道、浏览器、容量SLO。真实候选已产生，但该候选后续Gold审核→数据集→再次评测尚未覆盖。全适用I1–I7继续active。


## 回流金标与回归门禁扩展

在f7a8e73后复用刚刚由真实复核产生的候选，候选捕获后再次修改测试专属原消息。实际Gold服务仍从候选冻结体标注，拒绝自审，由另一受控审核人批准；所得精确v2金标保持原正文、2000ms坐标和真实reviewId。冻结为REGRESSION数据集，经实际评测创建与worker运行得到完整报告。

金标保留人工重算100分/NONE风险，原政策仍得到80分/HIGH风险。因此实际报告事实F1=1、平均绝对分差20、回归通过率0；实际PipelinePublicationGateService对原质量Task/EID及此回归Task/EID判定passed=false，REGRESSION_PASS明确FAILED。不会把事实提取正确当作业务政策通过；没有修改、放宽或绕过门禁。此处验证实际门禁evaluate，未声称对已发布修订再次publish可改变原幂等发布记录。

原31814终态0，`/tmp/iqc-b-feedback-gold-regression-first.log`，testCompile/surefire，当轮SQL组合场景1项零失败错误跳过。生产源码未修改；75迁移校验成功、1.1.74当前，隔离MySQL正常关闭，pid/socket不存在。候选原正文及lineage两个JSON逐字节保持，未重捕获当前会话。固定受控身份、单场景、无外部供应商/真实PDP/browser/容量SLO的边界不变。
