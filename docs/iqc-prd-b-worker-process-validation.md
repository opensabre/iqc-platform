# B：独立 JVM 领取与崩溃回收验收

基线 A `4020981`，延续 B `feature/iqc-prd-b-current-mysql-validation`，接续 `f8812b5`。不修改生产服务、恢复策略、迁移或 A 工作树。

## 复用与实际机制

复用原 `IsolatedMysqlTaskExecutionStateIT`、真实 Task/Execution Mapper、Spring 事务代理及 `InspectionTaskService.recoverStaleExecutions(timeout,false)`。当前实现把超时 RUNNING 收敛为 FAILED，PAUSE_REQUESTED 为 PAUSED，CANCEL_REQUESTED 为 CANCELLED；不是原 EID 自动重跑。QUEUED 被明确排除于此回收，由原持久队列发现器再次调度。

## 验收设计

- 两个独立 Java 进程各自建立 Spring 上下文和数据库连接，通过父进程 stdin 屏障同时领取同一任务/EID，要求恰好一次成功、两个进程正常退出。
- 子进程领取事务提交后输出确认，父进程核对两行 RUNNING 后强制终止该测试子进程；只将专属夹具 updated_time 调到过期时间，避免真实等待一分钟。另一新 JVM 调用原回收服务，要求两行 FAILED 且旧完成/失败回调不能覆盖。
- 过期 QUEUED 经新 JVM 回收返回0，随后同进程可以领取成功；证明待运行记录不会被超时回收错误终结，不证明发现器本身已执行真实模型链路。
- 暂停/取消请求分别由另一 JVM 回收到 PAUSED/CANCELLED，旧 EID 保持 QUEUED。
- 保留前批五项线程竞争/真实更新错误回滚/旧轮次/终态测试，形成同一正式迁移数据库的统一回归。

子进程信号等待最多30秒，进程退出最多10秒；每个进程句柄由 AutoCloseable 清理，强制终止仅针对本测试直接创建的进程。不启动完整应用、后台调度器或外部模型客户端。

## 环境与结果

MySQL 8.0.34，本轮全新专属 datadir `/private/tmp/iqc-mysql-validation.BXnDW7`，仅监听 loopback:34307。每个子进程在创建上下文前再次核对实际端口/datadir与环境变量完全匹配；父进程在新建数据库前执行同一隔离检查。

本轮 10 项全部通过，0 failures / 0 errors / 0 skipped，Maven 退出 0；其中原五项真实状态事务测试全部保留，新增五项独立 JVM 测试全部通过。Surefire 记录总耗时 77.71 秒。日志 `/private/tmp/iqc-b-worker-process-tests.log`。

```sh
IQC_MYSQL_VALIDATION_DIR=/private/tmp/iqc-mysql-validation.BXnDW7 mvn -o -q -Dtest=IsolatedMysqlTaskExecutionStateIT -DargLine=-javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar test
```

本批验证状态持久性和现有超时回收，不证明进程在结果提交/远端副作用中途崩溃的端到端幂等、自动重试/冻结输入重放、预算等待或整个 PRD 完成。
