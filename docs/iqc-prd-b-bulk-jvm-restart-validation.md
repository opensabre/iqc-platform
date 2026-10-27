# 批次父认领后 JVM 崩溃与恢复验证

生产基线 2a8a475 / B 1c1159e。仅增加测试，沿用已发布流水线、已处理会话与真实原子分片主链。

## 结果

原会话 85018 terminal 0，`/tmp/iqc-b-bulk-jvm-restart.log.exit=0`；fresh Surefire 原主方法 1 项，43.210s，零失败、错误、跳过。专用 MySQL 34307，独立 datadir；结束后 socket 不存在。

原主链真实物化一片后：

1. 保留原双线程 claim 恰有一个成功的验证，然后将父/执行夹具恢复 QUEUED。
2. 启动独立 JVM，加载新的 Spring 事务代理、MyBatis 与连接，调用真实 BulkTaskLifecycleService.claim。父与执行均确认 RUNNING，认领事务已提交。
3. 强制结束该 JVM 并确认进程退出；只将该父持久心跳设为超时，避免等待实际超时窗口。
4. 启动 PID 不同的新 JVM，调用真实 InspectionTaskService.recoverStaleExecutions、claim、tick，正常退出。
5. 原父 EID 不变，父仅一条 execution；原子任务仍仅一个并被排队，父回到 QUEUED 等待；父进度 JSON 与子冻结输入全文不变。

同次原主方法后续暂停、恢复、取消、清理回滚/成功及既有发布/Finding/MVC 断言仍通过。没有把父恢复同 EID 扩大为普通子恢复同 EID：普通子仍按原语义生成新 attempt。

## 证据边界

新 JVM 使用真实应用服务和 SQL，但测试 Config 的组织范围仍受控，直接调用服务，未启动完整 DurableInspectionWorker scheduler；不证明远程 PDP/Feign、活跃模型调用中断、两台真实 worker 并行或百万容量。现有普通 task 的进程测试工具模式被复用，未建立第二套生产编排设施。无生产源码、framework、共享数据库、push 或部署修改；完整适用 I1–I7 继续推进。

## 真实 worker 接线增量

基线 cc4f25b。原 76043 terminal 0，`/tmp/iqc-b-bulk-worker-restart-final.log.exit=0`，fresh 原主方法 1 项 52.100s，零失败、错误、跳过，专用 socket 不存在。

第二 JVM 不再直接 recover/claim/tick：实例化真实 DurableInspectionWorker，使用实际 mapper、任务服务、ThreadPoolTaskExecutor、BulkTaskCoordinator 与生命周期事务代理。主动调用 discoverQueuedTasks 完成原超时回收、SQL 发现和异步分派；精确验证目标 parent/EID 协调器调用一次，再确认池已空闲和父等待，外层继续检查原 EID、唯一父执行/唯一子、游标、冻结全文及子 QUEUED。组织 resolve 和范围回调仍受控。

首轮 64937 terminal 1，`/tmp/iqc-b-bulk-worker-restart.log.exit=1`：新测试错误假设整个保留历史夹具的隔离库只会触发一次 resolve，实际有四次。终态后改为目标 parent/EID 精确调用校验，并防止 worker 尚未开始时把回收后的 QUEUED 当作已完成；保留首轮失败日志，不以此修改生产源码。

这是实际 worker 方法、SQL 和异步执行接线的验证，尚未验证 @Scheduled 时钟驱动、远程权限服务、活跃模型中断、双进程并行或百万容量。A 后续 worker 在途改动未取，本结果仅适用于本固定生产基线。

## 两个独立 JVM 同时认领

基线 d8d5dec，生产 b163836。原 65755 terminal 0，`/tmp/iqc-b-bulk-two-jvm-claim.log.exit=0`，fresh 主方法 1 项 114.100s，零失败、错误、跳过，专用 socket 不存在。

新增两个不同 PID 各加载自己的 Spring 事务代理、MyBatis 与连接，均 READY 后由父进程同时释放屏障，针对同一父任务和 EID 调用真实 claim。结果恰为一个 true、一个 false；两进程正常退出，父与执行均 RUNNING，父进度和原子输入全文不变。之后仍执行第三 JVM 认领后强杀、第四 JVM 的真实 worker 回收/异步协调、满线程池停止传播、优先级继承及原发布/清理/Finding/MVC 主链，并全部通过。

此增量证明父认领在跨 JVM 数据库竞争下互斥，不冒称两个完整 scheduler 同时执行全部子任务、活跃模型调用无重入或真实 PDP。组织范围仍受控，百万级容量仍需单列验证。PRD 第23章明确性能数值压测后基线化，本测试不新增任意 P95 阈值。无生产源码/共享库/framework/push/部署修改。
