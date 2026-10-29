# 父子任务生命周期真实 SQL 验证

基线7cc9e18，合A固定1ff933c，仅扩既有MeasuredPublicationIT主方法与真实BulkTaskLifecycleService Spring事务代理。沿用正式发布、Selector、生产输入冻结及原子物化回滚链；scope/组织证据仍受控。

原57453终态0，`/tmp/iqc-b-bulk-lifecycle-sql-final.log.exit=0`；fresh XML原主方法1项37.938秒，零失败错误跳过。专用socket不存在，无活跃验证。不是整个类另模型方法的测试结论。

- 两个线程同时调用真实claim，父/Execution行锁使同父EID恰好一个成功。
- 真实tick将已冻结CREATED子任务交原queueSystem，生成子execution；父等待，不把扫描完成当质检完成。
- 原requestPause记录父请求，清空用户上下文后走真实后台controlIfRequested：父、子及子execution均PAUSED。
- 原父queue恢复保留父EID；普通子resume按既有规则新建执行轮次，旧轮次仍PAUSED，子共两轮。恢复后的子QUEUED。
- 原cancel与后台control传播到父、子及当前子execution的CANCELLED；原分片快照全文和父检查点原文不变。

首轮80347终态1，`/tmp/iqc-b-bulk-lifecycle-sql.log.exit=1`，原新增断言错误地要求普通子恢复也同EID。读取既有resume实现后只修测试；普通恢复创建新attempt，预算等待特例及父协调恢复才保留EID。未修改生产语义或将错误假设变成要求，失败日志保留。

线程竞争不等同两个真实worker或跨进程故障。父execution先由夹具建立，未证明真实创建API/当前PDP；本轮只排队子暂停取消，不是运行中模型中断。进程重启、撤权后的停止、错误汇总、统一清理竞争和百万容量仍需独立验证，不宣称全PRD完成。无framework/共享库/push/部署。
