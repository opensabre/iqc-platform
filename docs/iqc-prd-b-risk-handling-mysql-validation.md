# 风险处置 MySQL 事务验证

2026-10-11，IsolatedMysqlRiskHandlingIT 三项测试一次执行通过，零失败、错误或跳过。使用端口/datadir 身份围栏后的专属 MySQL 8.0、新随机 schema、正式 Flyway 至 V1.1.70，validate 与重复 migrate 零迁移；结束关闭上下文并删除本次随机库。

实际 RiskHandlingService Spring 事务代理、RiskEventMapper 和 RiskHandlingEventMapper 验证：

- 状态 CAS 与原因、操作人、版本同事务提交，旧观察不覆盖新状态；正式不可变触发器拒绝修改与删除历史。
- 测试专属数据库触发器拒绝原因 INSERT 后，已执行状态 CAS 回滚至 OPEN/0，零历史；移除测试触发器后可成功处置。
- 两线程均获得版本0观察后竞争，只有一个成功，状态版本1且只有赢家的一条原因。

历史生产来源 View 与当前 owner 使用替身；该测试不证明真实来源链、HTTP 权限、历史分页、风险中心范围查询或浏览器验收。日志 `/private/tmp/iqc-b-risk-handling-mysql-tests.log`。完整 PRD 仍未完成。
