# 持久任务终态补充验收

2026-10-26：复用 IsolatedMysqlTaskMetricsIT，8项测试零失败、错误、跳过。

- 真实 Spring 事务/MyBatis/隔离 MySQL：陈旧运行失败、取消请求转取消、暂停请求转暂停；durable 路径不回收 QUEUED，旧入口保留回收语义。首次完成时间不重写，暂停不计终态时间。
- 直接取消 QUEUED/PAUSED 只记录首次完成时间；运行中的取消保留 CANCEL_REQUESTED，交由 worker 完成终态。
- 真实 SQL trigger 拒绝执行表更新后，直接取消的任务状态与时间一起回滚。权限对象受控放行，本专项不证明真实 PDP。
- 保留原5项迁移/完成时间/SQL计数验收。首次普通沙箱启动失败，原进程终态1；随后同脚本经 require_escalated 执行终态0，日志 /tmp/iqc-b-task-lifecycle-sql-retry.log 与 .exit，结束关闭实例。所有库为端口34307、专用datadir身份核验后的随机新库，不连接共享库。

用户已明确：输入为处理好的会话数据，ASR、解码、说话人分离等语音转换工作不属于任务或验收门禁。

本专项不外推精确时间窗下沿、预算自动恢复、真实权限整链、采集、容量或完整I1–I7验收。

## UTC窗口毫秒边界补充

在真实事务的同一MySQL连接固定测试时钟，并将会话时区设为+08:00；先断言UTC_TIMESTAMP与NOW相差8小时，再通过正式mapper统计生产与评测两类任务。五分钟前恰好边界和当前时刻恰好边界均计入；下沿再早1毫秒及上沿再晚1毫秒均排除。finally复原会话时钟和时区。

原50114 terminal0，/tmp/iqc-b-task-window-sql.log.exit=0；fresh XML共9项无失败、错误、跳过，27.965秒，隔离MySQL结束关闭、socket不存在。这证明查询UTC及闭区间语义，不证明大表执行计划、采样调度和容量SLO。

源码复核：预算等待恢复使用InspectionExecutionService.resume原入口，复用原执行轮次并经过依赖/权限检查。目前未据此证明自动恢复调度存在或必须新增；需继续对照正式需求，不把人工恢复测试冒称自动恢复。
