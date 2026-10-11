# B：工具启动与预算暂停竞态修复

基线A4020981，B `feature/iqc-prd-b-current-mysql-validation`，接续262b183。仅在B独立工作树修改GatewayCallService、既有隔离IT与本文；未修改framework、数据库迁移或A工作树。

## 原问题与证据

`TaskExecutionStateService.waitForBudget` 锁Task/Execution后读取工具记录，再恢复工作项并暂停。原 `GatewayCallService.markToolsStarted` 只锁Call，普通一致读检查Task/Execution仍RUNNING。暂停事务尚未提交时，另一工具标记事务因此可以通过并提交启动记录，最终PAUSED与不可自动重放的工具启动证据同时存在。

复现使用实际服务、Mapper、Spring事务和MySQL8.0.34。仅在TaskItemMapper执行真实update前设置测试同步屏障，此时等待已完成真实hasToolExposure查询并持有两张状态表行锁。另一事务实际调用markToolsStarted。测试不替换Mapper结果；确认工具事务已经完成或真实InnoDB等待Task行锁后再放开暂停事务，不依赖随机睡眠制造调度顺序。

两次修复前独立运行均失败：预计 `MODEL_EXECUTION_PROVENANCE_STALE`，实际 `STARTED`，预算暂停成功。日志 `/private/tmp/iqc-b-tool-budget-race-red.log`、`/private/tmp/iqc-b-tool-budget-race-red-repeat.log`，Maven均退出1，各1项断言失败，无测试错误/跳过。

## 最小修复与影响

复用既有Task/Execution行锁边界，不新增锁表/持久模型/中间件。带执行来源的工具标记先按Task→Execution→Call锁顺序取得行锁，随后沿用assertExecutionActive与原Call来源校验：

- 暂停先获得Task锁：工具标记等待其提交，随后看到PAUSED并拒绝，不写启动记录。
- 工具标记先提交：暂停读取真实启动证据并拒绝自动等待。

保留原REQUIRES_NEW短事务、无执行来源legacy路径、原工具来源检查和幂等标记。标记方法提交返回后，原SnapshotChatModelRouter才调用ToolCallingManager；锁不包围工具I/O。Task控制请求与执行轮次转换使用同一Task锁顺序，避免加入Call→Task倒序获取。

## 验收与边界

新增两个回归：真实并发暂停胜出后拒绝工具标记；真实工具标记先提交后拒绝预算暂停。原25项预算等待/线程/跨JVM状态回归保留，并执行原SnapshotChatModelRouterTest及SnapshotMcpToolProviderTest调用点回归。第一轮修复后27项真实数据库回归全部通过；联合运行仍因原路由测试两个旧夹具仅含模型id、缺少当前身份字段而退出1（MODEL_IDENTITY_INVALID，在工具标记前失败）。补齐这两个测试的provider/endpoint/modelName/versionNo后保留原固定模型和降级顺序断言，最终串行运行33项（数据库27项、Router3项、MCP3项）全部通过，0 failures/errors/skipped，Maven退出0。数据库测试耗时66.82秒，两个新并发边界用例均通过；此次完整SQL回归也再次验证原预算等待和跨JVM测试。有效最终日志 `/private/tmp/iqc-b-tool-budget-race-verified.log`；首轮修复日志 `/private/tmp/iqc-b-tool-budget-race-fixed.log` 不作为整组通过证据。

本次专属MySQL datadir `/private/tmp/iqc-mysql-validation.FVGdkd`，loopback:34307；写入前核对实际datadir/port。Worker状态IT允许在身份核对后的同一专属库重复运行，fixture只清理该测试库内自己的任务/工作项/调用记录；不适用于迁移历史升级测试，更不能使用共享应用数据库。

本批验证数据库准入边界，不证明真实MCP供应商、远端工具返回、网络中断后副作用对账、预算预留/核销、管理员恢复或完整PRD完成。

## 源码依据

- [工具标记服务](../src/main/java/io/github/opensabre/iqc/result/llm/gateway/GatewayCallService.java)
- [预算暂停状态服务](../src/main/java/io/github/opensabre/iqc/task/TaskExecutionStateService.java)
- [工具调用顺序](../src/main/java/io/github/opensabre/iqc/result/llm/SnapshotChatModelRouter.java)
- [真实并发验收](../src/test/java/io/github/opensabre/iqc/result/IsolatedMysqlTaskExecutionStateIT.java)

最终命令（同一隔离实例中串行执行，失败复现与通过日志分开保留）：

```sh
IQC_MYSQL_VALIDATION_DIR=/private/tmp/iqc-mysql-validation.FVGdkd mvn -o -q '-Dtest=IsolatedMysqlTaskExecutionStateIT,SnapshotChatModelRouterTest,SnapshotMcpToolProviderTest' -DargLine=-javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar test
```

生产没有添加调试输出或测试屏障；同步钩子只在隔离IT内部，所有SQL与Mapper返回仍来自真实数据库。没有执行当前基线的完整后端测试、真实外部工具、SQL生产迁移或部署。
