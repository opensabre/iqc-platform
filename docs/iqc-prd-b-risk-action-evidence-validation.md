# 风险事件证据与历史复用隔离 SQL 验证（2026-10-28）

基线 0fc2957，生产风险证据门禁 4f73190。仅新增 IsolatedMysqlRiskActionEvidenceIT，无生产代码修改。输入为已处理会话，不涉及语音转换。

## 终态证据

原进程 49895 正常退出 0；`/tmp/iqc-b-risk-action-evidence-proxy-fixed-20261028.log.exit` 为 0。新 Surefire 报告：新增 5 项（16.65 秒）、原 IsolatedMysqlRiskHandlingIT 8 项（10.83 秒），均零失败、错误、跳过。专用本机 MySQL 34307，全新 schema 完整应用 58 个迁移至 1.1.77，validate 成功，再次 migrate 为 0。

## 覆盖

- HIGH 无证据、仅融合被拒绝来源带引用：实际 create 拒绝、无事件残留、lookup 为空，动作仍 RUNNING。
- CRITICAL 选中事实有引用：重复 create 同一产物且仅一条；动作结算后历史 lookup 可用，新 create 拒绝。
- 显式植入历史弱证据 RESOLVED 事件：create/lookup 复用原产物，状态与版本不回写。
- 真实 BEFORE INSERT 故障：create 回滚无产物，动作 RUNNING/version 1 保持；解除故障后可重试。
- 创建授权受控撤销：create 拒绝，历史 lookup 保持同一产物，不新增事件。

实际使用 RiskEventActionService 的 Spring 事务代理、真实 MyBatis mapper、MySQL 约束与审计迁移；FindingFusion 和 ActionPolicy 为真实实现。来源 validator 与组织 scope 是受控 mock，未证明原始 trace 完整校验、真实 PDP/Feign 或网关角色链。历史事件为显式 fixture；没有任务/分析结果写入，不能扩大为原始分析全链路不变证明。未覆盖 LOW/MEDIUM 无证据创建、真实并发争用、完整 PRD 场景。

## 失败记录与修正

首轮 10300 和后续 96605：新增 5 项都在 seed 的来源 mock 配置时触发 Spring MANDATORY 事务代理，尚未进入目标业务；原处置 8 项通过。中间 final 轮因 JUnit 泛型断言重载歧义编译失败。最初将事务问题误判为 insert；完整堆栈定位后改用 AopTestUtils 获取受控 mock 的真实 target 配置，业务 service 仍保留事务代理。插入夹具置于真实 TransactionTemplate。上述日志原样保留，不归因生产缺陷。

自动审批一次超时导致重跑未启动；允许的一次重试获准，才产生本次通过结果。未操作共享数据库、框架、推送或部署。完整适用 I1–I7 仍在推进。
