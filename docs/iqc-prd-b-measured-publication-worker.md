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

本测试不含复核重算、Action派发/对账、外部渠道、真实PDP/collector/浏览器。未激活默认v2，未前移B主树、未修改A工作树/framework/gateway、未push或部署。完整适用I1–I7继续active。
