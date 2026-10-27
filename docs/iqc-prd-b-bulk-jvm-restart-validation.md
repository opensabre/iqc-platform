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
