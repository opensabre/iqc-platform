# B：动作账本新基线集成验收

独立分支 `feature/iqc-prd-b-action-integration`，工作树 `.worktrees/iqc-prd-b-action-integration`。基于 A `6fcddb4`，顺序纳入 B 原 `f8812b5`、`b8862df`、`262b183`、`a23cb7c`、`7f332c1`、`29d0ff2`、`84bf901`，无冲突；本树提交分别为 `b92f8e8`、`9bead08`、`63f2f74`、`b449e6d`、`eb91c07`、`1efe887`、`0b8cba6`。旧基线上的历史验收文档仍是当时证据，不表示自动覆盖动作新提交。

复用 A 的 `iqc_action_execution`、Mapper 和正式 V1.1.62，不新增平行动作/Outbox模型。`IsolatedMysqlActionExecutionIT` 调用真实 Mapper、InnoDB 和 Spring TransactionTemplate，按原隔离 guard 核对实际 datadir/port 后运行迁移，仅写本机测试库。

七项场景：

1. 两个独立事务竞争 version=0，只有一次领取，attemptCount/version 各增加一。
2. 错 version/token 不结算；成功只结算一次，终态不参与到期发现。
3. WAITING_RETRY 不能提前领取；到期新 token 领取后旧 token 即使使用新 version 也不能结算。
4. 失联只在 cutoff 覆盖领取时间时隔离为 UNKNOWN/REQUIRES_RECONCILIATION，保留 claim，未来也不重新入队；晚结算拒绝。
5. 错 outcome/next 或重试时间组合不结算；UNKNOWN 只能进入待核对。
6. 领取事务回滚不消耗 attempt/version，后续仍可正常领取。
7. 数据库拒绝重复稳定 idempotency_key，保留一行。

来源 Task/EID/结果/policy/input 仅是引用夹具，不证明真实生产关系或摘要有效；测试直接调用仓储原语，不执行 worker、适配器或网络。因此本批不能声称动作已入队发送、外部幂等、结果归属/冻结政策校验、人工核对或 UI 已完成。

```bash
IQC_MYSQL_VALIDATION_DIR=/private/tmp/iqc-mysql-validation.FVGdkd \
mvn -o -q -Dtest=IsolatedMysqlActionExecutionIT \
  -DargLine=-javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar test
```

首次命令编译失败，原因是两处 `assertThat(tx.execute(...))` 的泛型推导歧义，尚未运行 SQL；显式指定 Integer 返回类型后保留原断言。日志 `/private/tmp/iqc-b-action-integration-tests.log`。

联合复跑 `-Dtest=IsolatedMysqlActionExecutionIT,IsolatedMysqlMigrationIT`，日志 `/private/tmp/iqc-b-action-migration-verified.log`。迁移验收版本同步到1.1.62，新装及两个历史升级路径都核对新动作唯一索引，保留历史数据/重复零迁移及大载荷回滚检查。最终结果按新 Surefire 报告和退出码记录。

2026-10-09 最终 Maven exit0，新报告确认 **10 项，0 failures/errors/skipped**（动作7项、迁移3项）。正式 Flyway 新装及历史1.1.31/1.1.44升级都到达1.1.62，三条路径重复migrate零执行；原worker测试库从1.1.61追加1.1.62迁移成功。本批尚未在新基线上执行完整后端及原worker/预算/重试全部场景。

不修改 A 原工作树、framework，不推送/合并/部署；完整非实时语音 PRD 继续，离线音频保留。
