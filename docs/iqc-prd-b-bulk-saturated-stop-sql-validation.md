# 满载停止与分片优先级真实 SQL 验证

生产 b163836，B 基线 9a86ad9。仅扩原 IsolatedMysqlMeasuredPublicationIT 主方法，不修改生产源码。

原会话 78082 terminal 0，`/tmp/iqc-b-bulk-saturated-stop-sql.log.exit=0`，fresh 主方法 1 项 89.360s，零失败、错误、跳过。专用本机 MySQL 34307，结束后 socket 不存在。

## 新增行为证据

- 实际 ThreadPoolTaskExecutor 单线程运行一个受控阻塞任务，另一个任务填满单格队列；在确认 remainingCapacity=0 后主动调用真实 DurableInspectionWorker.discoverQueuedTasks。
- 实际 mapper 查询停止请求、实际 Coordinator 直接调用原生命周期事务。父与排队子及当前执行分别达到 PAUSED/CANCELLED；协调器不进入 executePersisted，组织 actors 无调用，线程池提交总数仍为原来的两个。
- 父 CREATED 时通过原 changePriority 设置 42，新物化片继承 42；父进入 QUEUED 后设置 99，已有 CREATED 片同步；父子暂停后设置 55，PAUSED 片同步。原冻结输入和父进度不变。

同次复验当前 b163836 的独立 JVM 崩溃后真实 worker 超时回收/SQL 发现/线程池协调推进，保留原 EID/唯一父执行/唯一子与输入；后续普通子新 attempt 恢复、取消和统一清理回滚/成功、原发布/Finding/MVC 断言仍通过。

## 边界

受控阻塞任务不是真实模型请求；组织数据范围仍受控。未证明运行中模型中断、远程 PDP/Feign、in-flight 满载的真实 SQL（其调度逻辑由前一单元专项覆盖）、双 worker 竞争、100+100 查询排序计划或百万容量。主动调用调度方法，不证明 cron 时钟。未修改 framework/共享数据库，未 push 或部署；处理后会话为输入，完整适用 I1–I7 继续。
