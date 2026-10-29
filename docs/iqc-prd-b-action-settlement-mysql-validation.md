# B：真实 MySQL 动作结算事务验收

基于fd7b574（A9397dc9加B修复/验收），新增IsolatedMysqlActionSettlementIT；复用IsolatedMysqlTaskExecutionStateIT的独立MySQL保护检查、迁移、生产MyBatis Mapper和Spring事务配置。账本为本机127.0.0.1:34307、datadir=/private/tmp/iqc-mysql-validation.FVGdkd；不接共享数据库。断言实际ActionSettlementService是Spring AOP代理。

六项范围：

1. 外层事务标记回滚，内部REQUIRES_NEW成功回执仍真实持久化COMPLETE、输出引用及version2。
2. MySQL BEFORE UPDATE拒绝结算，异常回滚后保留RUNNING/version1/token且无outcome；去除拒绝后同领取可真实恢复结算。
3. UNKNOWN持久化REQUIRES_RECONCILIATION且无自动retry时间，后到成功回执不能覆盖它。
4. 错token或错version不写回执。
5. 冻结输入内容不一致拒绝结算且保留领取。
6. 可重试失败但无冻结retry策略最终FAILED，保留真实证据引用，不回队。

先编译时测试配置类命名与注解冲突，改名；SQL首轮6项4 failures，领取夹具用LocalDateTime.now造成Asia/Shanghai时间比UTC结算晚8小时，真实SQL claimed_at_utc<=now围栏正确拒绝。修正夹具为Instant→UTC，未改变生产时间/SQL围栏或断言。首轮日志 /private/tmp/iqc-b-action-settlement-mysql.log，最终复跑Maven exit0、6项0 failures/errors/skipped，日志 `/private/tmp/iqc-b-action-settlement-mysql-verified.log`；读取本次独立Surefire报告确认。临时MySQL测试完成后正常关闭。diffcheck通过，未推送/合入A/部署。

只新增测试，无生产修改。真实adapter实现/网络与registry依赖仍模拟；不能证明来源校验、组织授权、领取完整链或实际外部发送。默认完整761项为上一提交已验证，本次不以其证明新IT；无需为仅新增独立IT重复默认套件。完整非实时语音PRD继续。
