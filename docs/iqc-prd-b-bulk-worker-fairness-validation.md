# worker 公平发现与满载停止专项

生产 b163836，B 合入 4845130。仅测试与文档改动。

原 43269 terminal 0，`/tmp/iqc-b-bulk-worker-fairness-final.log.exit=0`。fresh DurableBulkWorkerTest 4 项 7.068s、DurableInspectionWorkerTest 原 6 项 0.413s，合计 10 项零失败、错误、跳过。

新场景：executor 队列满仍直接调用父停止协调器且不提交任务；八个 in-flight 槽满后新停止仍被传播且不再查询待分派行；普通/父各 100 个候选、仅一槽时两个 tick 分别服务两个家族首项；executor 同步完成释放本地 lease 后，同轮重复行仍只分派一次。原普通测试继续验证本地去重、拒绝后重发现、异常释放、不同 EID、槽上限与满队列行为。

首轮 20191 terminal 1，`/tmp/iqc-b-bulk-worker-fairness.log.exit=1`，10 项都在 metadata select 的 MyBatis lambda cache 构建阶段报错。测试未启动真实 mapper，需初始化 InspectionTask 表元数据；仅在新旧测试 BeforeEach 复用项目 TableInfoHelper 夹具，未修改生产源码。模拟的异常与 backpressure 日志保留。

此处 mapper/Coordinator/模型执行边界受控，证明分派与资源逻辑；不证明真实 SQL 排序/100+行查询计划、真实满载停止事务、跨片优先级继承或 PDP。9ca8fa4 的真实 worker/JVM 恢复属于前一生产基线，不能自动外推本版本；后续需结合 SQL 场景补验。完整适用 I1–I7 继续，输入为处理后的会话，无 framework/共享数据库/push/部署修改。
