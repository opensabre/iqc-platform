# 批次原子物化真实发布 SQL 验证

基线db6c7bc，生产代码固定4b1fc73/ef54158。本轮只扩既有IsolatedMysqlMeasuredPublicationIT主方法和其真实Spring Bean，不纳入A后续API/清理/锁顺序未提交改动。

原正式Finding/Gold/独立复核/Dataset/测评门禁发布后，处理好会话经真实Selector、BulkTaskMaterializationService与InspectionTaskService生产输入冻结。新增物化服务确为Spring事务代理。组织证据及scope仍受控，不代表真实PDP或worker调度。

## 最终证据

原51864终态0，`/tmp/iqc-b-bulk-materialization-sql-final.log.exit=0`；fresh XML主集成方法1项35.118秒，零失败错误跳过。隔离实例已停，专用socket不存在。

- 同一create请求返回原父ID，不重新建立批次。
- 设置独立父execution RUNNING夹具；触发器只在该父检查点变化时拒绝UPDATE，真实materializeNext抛数据库异常。父检查点原文不变、关联子任务0、整个任务表行数不变，证明没有留下未关联孤立任务。
- 移除故障重试成功且只有1子，CREATED状态、父ID/片号0、扫描/选中计数均1、scanComplete真；原流水线/生产输入物化路径和分片扫描证据均落库，scanPageHash为64位Hash。
- 已完成扫描再次调用不创建子，原进度及子完整快照不变；旧EID拒绝且不改检查点。
- 原主方法后续真实生产执行、机器结果/人工改判/回流及MVC断言仍通过；不是另外模型方法或整个类全量。

## 失败记录与限制

原18996终态1，日志`/tmp/iqc-b-bulk-materialization-sql.log`：在新增分片断言之前，EvaluationDatasetVersion插入因本机时钟回拨1713ms被MyBatis ID生成器拒绝。未改代码重跑原92308终态0，`/tmp/iqc-b-bulk-materialization-sql-retry.log.exit=0`、1项130.154秒通过；随后补整表行数断言得到上述最终结果。失败证据未覆盖。

本轮只创建一个真实分片并验证回滚/已完成检查点重复调用。未验证双worker争租约、进程重启、父子暂停取消/自动恢复、当前组织撤权、公共API/UI或百万容量；不能据此宣布全部批次功能完成。没有生产/schema/framework修改、共享库写入、push或部署。
