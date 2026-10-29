# B：自动重试与尝试账本联合事务

基于 A `4020981` 和 B `29d0ff2`，在原隔离 MySQL 重试测试上接入真实 `AutomaticRetryService`、`TriggerAttemptService` 和 `TriggerAttemptEventMapper`。不新增生产模型或修改服务代码。

运行期间只读确认 A 已提交 `6fcddb4`（动作账本及围栏状态操作），且工作树干净；该提交不在本批测试分支内，本结果不覆盖新增动作实现。后续需在独立集成工作树纳入并重验。

调用链为真实 `peek → begin → fire → queueRetrySystem → committed`。`begin` 独立事务保存 STARTED；`fire`、新 Execution/TaskItem 和 FINISHED 共用 Spring 事务。MySQL 沿用 datadir/port 双重身份核对和正式 Flyway 迁移，只有测试专用库允许夹具清理。

新增五项验收：

1. 成功排队与 DOMAIN_COMMITTED/processedUnits=1 同时保存。
2. 工作项 INSERT 被真实触发器拒绝：Task 状态/轮次、Execution 和工作项回滚，STARTED 保留；外层结束后可独立追加 FAILED/错误类别/阶段。
3. FINISHED INSERT 被真实触发器拒绝：此前已经准备成功的完整排队事务回滚，STARTED 保留，不留下 QUEUED 或成功完成事实。
4. 观察后策略停用：不新增轮次，追加 NO_CHANGE。
5. 成功提交后迟到的 failed 调用不覆盖原 DOMAIN_COMMITTED，不追加第三行。

原八项冻结清单/成功引用/多轮/竞争/损坏输入/工作项回滚测试保留。scope、Conversation 来源归属和资产依赖校验仍为隔离 mock，未调用真实组织服务或模型；来源政策和失败 Execution 则由数据库实际保存、读取和核对。不能据此声称真实 fresh 授权或完整后台调度已通过。

```bash
IQC_MYSQL_VALIDATION_DIR=/private/tmp/iqc-mysql-validation.FVGdkd \
mvn -o -q -Dtest=IsolatedMysqlProductionRetryIT \
  -DargLine=-javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar test
```

日志 `/private/tmp/iqc-b-retry-ledger-tests.log`；单组 **13 项，0 failures/errors/skipped，47.330 秒，Maven exit0**。测试清理仅在已验证的专用库内，触发器在 finally 移除。完整 PRD、组织授权、模型推理、动作及浏览器验收继续，未推送/合并/部署。

最终串行联合 `-Dtest=IsolatedMysqlProductionRetryIT,IsolatedMysqlTaskExecutionStateIT`，日志 `/private/tmp/iqc-b-retry-ledger-combined.log`，Maven exit0；新报告合计 **40 项，0 failures/errors/skipped**，包含本组13项及原状态27项，验证共享Mapper配置和夹具生命周期。
