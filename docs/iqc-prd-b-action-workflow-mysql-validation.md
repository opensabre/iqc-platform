# B：动作入队领取执行的真实事务验收

基于40a8518，复用前一结算IT的隔离MySQL配置、迁移、Spring事务与生产Mapper，新增IsolatedMysqlActionWorkflowIT。实际ActionEnqueueService、ActionClaimService、ActionExecutionService与ActionSettlementService均由容器创建，前三者显式断言AOP代理。

五项范围：

- 实际入队PENDING→领取RUNNING→适配器调用→结算COMPLETE；模拟adapter入口断言没有活动事务，独立JdbcTemplate连接读取到已提交RUNNING，最终attempt1/version2。
- 外层检测事务内调用执行入口由真实NEVER代理拒绝，仍PENDING，无适配发送。
- 两个真实事务同时竞争领取，恰好一个Claim，attempt1/version1；领取阶段无adapter发送。
- 完成后同源重复入队只有一条账本、保留完成状态，不能领取或重发。
- REQUIRES_NEW入队在外层rollback后仍真实持久化PENDING/attempt0。

显式隔离边界：ActionProductionSourceValidator、当前组织scope、adapter实现模拟；来源返回同一个固定生产引用、政策、冻结输入和稳定身份。验证实际服务事务、账本SQL和发送时序，不证明生产来源Task/Execution/Result完整核验或外部业务副作用。不得称完整真实外部动作成功。

实例只为127.0.0.1:34307和/private/tmp/iqc-mysql-validation.FVGdkd；测试检查隔离身份，不使用共享数据库。首轮5 errors均发生fixture设置代理mock时：Spring MANDATORY来源代理拒绝无事务validate，改为获取Advised目标进行mock配置，保留实际调用代理约束。未弱化事务断言。日志 `/private/tmp/iqc-b-action-workflow-mysql.log`。最终Maven exit0、5项0 failures/errors/skipped，本次独立Surefire核对通过，日志 `/private/tmp/iqc-b-action-workflow-mysql-verified.log`；临时MySQL正常shutdown，服务进程exit0。diffcheck通过，仅新增独立IT未重复默认761套件，未推送/合入A/部署，无生产/framework/A修改。完整PRD与真实来源验收继续。
