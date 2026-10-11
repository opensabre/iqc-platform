# 风险处置 MySQL 事务验证

2026-10-11，IsolatedMysqlRiskHandlingIT 三项测试一次执行通过，零失败、错误或跳过。使用端口/datadir 身份围栏后的专属 MySQL 8.0、新随机 schema、正式 Flyway 至 V1.1.70，validate 与重复 migrate 零迁移；结束关闭上下文并删除本次随机库。

实际 RiskHandlingService Spring 事务代理、RiskEventMapper 和 RiskHandlingEventMapper 验证：

- 状态 CAS 与原因、操作人、版本同事务提交，旧观察不覆盖新状态；正式不可变触发器拒绝修改与删除历史。
- 测试专属数据库触发器拒绝原因 INSERT 后，已执行状态 CAS 回滚至 OPEN/0，零历史；移除测试触发器后可成功处置。
- 两线程均获得版本0观察后竞争，只有一个成功，状态版本1且只有赢家的一条原因。

历史生产来源 View 与当前 owner 使用替身；该测试不证明真实来源链、HTTP 权限、历史分页、风险中心范围查询或浏览器验收。日志 `/private/tmp/iqc-b-risk-handling-mysql-tests.log`。完整 PRD 仍未完成。

## 历史分页回归与排序修复（2026-10-12）

新增真实历史服务/SQL验证55条版本记录的50+5分页、缺失起始记录拒绝、缺失当前记录及空内部页拒绝。首次六项执行中，两项暴露 CAST(version AS CHAR) AS version 的同名字符串别名被 ORDER BY 使用，9排在55前，正确的一致性围栏拒绝了合法记录。修复仅将排序限定为原表 BIGINT 列，客户端版本字符串不变。

修复后同组六项一次执行全部通过，零失败/错误/跳过，包含原事务原子性、回滚和并发单赢家。日志 `/private/tmp/iqc-b-risk-handling-history-mysql-fixed.log`。历史授权View仍是替身，结果不替代真实来源证明、HTTP权限与浏览器验收。
