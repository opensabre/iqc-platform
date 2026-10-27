# 批次结果范围与分页 SQL 验证

生产基线：7071f5e，B 合入点 c8d93d5。质检输入为已处理会话；语音转换、ASR、音频解码和说话人分离不在本任务验收范围内。

## 验证结果

原会话 38045 已 terminal 0，`/tmp/iqc-b-scoped-page-sql.log.exit=0`。本机隔离 MySQL 34307、随机新 schema，Flyway 至 1.1.77，实际 MyBatis mapper 与 MySQL 分页插件：

- IsolatedMysqlScopedBusinessPageIT：4 项，16.710s，零失败、错误、跳过。
- BatchResultQueryServiceTest：15 项，6.207s，零失败、错误、跳过。

真实 SQL 验证 owner OR group 范围、空组仅 owner、all 范围、父任务过滤、排除评估任务、count/跨页/空尾；最新 attempt 优先于时间，先排名后过滤风险/分数，不回退到旧轮次；旧显式 task IDs 入口复用排名且拒绝空范围。服务测试更新原枚举任务 ID 假设，验证直接传递 SQL 范围且不枚举全量可见任务。

## 证据边界

SQL 测试直接调用 mapper，父任务归属由夹具设置，不能证明 service/controller 父可见性、真实 PDP/组织传输或浏览器联调。未做百万数据、查询计划或 SLO 验证，也未覆盖所有同时间 ID 排序组合。无生产源码、framework、共享库、push 或部署变更；完整适用 I1–I7 仍在进行。
