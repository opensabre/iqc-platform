# B：预算等待真实事务验收

基线A4020981，延续B `feature/iqc-prd-b-current-mysql-validation`，接续b8862df。只改B的既有隔离IT测试和本文；未修改生产服务、数据库迁移、framework或A工作树。

## 复用

复用原 `TaskExecutionStateService.waitForBudget`、`GatewayCallService.hasToolExposure`、Task/Execution/TaskItem/GatewayCall/Capacity Mapper、SqlSessionTemplate、Spring事务代理和正式Flyway迁移。GatewayCallService使用原实现查询真实工具启动记录，不模拟Mapper返回；本测试不调用模型网络、身份服务或预算核销方法。

## 验收范围

- 五种允许进入预算等待的原因分别验证：Token/费用耗尽、预算隔离、上界证据未确认、计价证据未确认；要求当前Task/Execution都PAUSED，并保存原因及完整modelKey。
- 当前轮RUNNING项变PENDING，已成功/失败项、result_id和attempt_count保留；旧EID工作项不变。总消息6、本轮4项且其中2终态时，沿原恢复计数规则得processed4/failed1。这里验证计数与保留，不证明外部检查点的生产归属或完整冻结输入重放。
- 无原因、未绑定/畸形modelKey、速率/容量类拒绝不能当预算等待。旧EID及暂停/取消请求或标志不得重置当前工作项。
- 同任务任一轮工具已经启动时，拒绝自动进入等待；其他任务工具记录及本任务尚未启动工具的已确认模型响应不阻塞安全等待。
- 真实MySQL触发器在Execution更新阶段拒绝写入时，Task状态、预算等待字段、TaskItem重置和进度都回滚；删除专属触发器后同一操作可以完成。
- 原十项线程/跨JVM领取、崩溃回收与状态事务测试继续执行，检查新完整构造注入不会破坏已有状态机制。

## 环境与命令

本轮全新专属MySQL8.0.34目录 `/private/tmp/iqc-mysql-validation.j5ddDN`，只监听loopback:34307。各测试/子进程核对实际datadir/port，未读取共享应用凭据。

```sh
IQC_MYSQL_VALIDATION_DIR=/private/tmp/iqc-mysql-validation.j5ddDN mvn -o -q -Dtest=IsolatedMysqlTaskExecutionStateIT -DargLine=-javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar test
```

本次实际执行25项，0 failures / 0 errors / 0 skipped，Maven退出0，Surefire总耗时79.41秒；新增15项预算等待场景和原10项线程/跨JVM状态回归均通过。日志 `/private/tmp/iqc-b-budget-wait-tests.log`。

本批不证明实际远端预算预留/核销、在途工具启动与等待之间的并发竞态、等待恢复/管理员新政策、自动重试重放、完整worker/模型链路或PRD完成。
