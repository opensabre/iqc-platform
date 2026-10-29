# 真实实测发布结果的政策重算 SQL 验证（2026-10-28）

基线 32ac25f，仅扩 IsolatedMysqlMeasuredPublicationIT 的原主方法 helper 与 Spring 配置，接入真实 DecisionRevisionService/mapper。没有生产代码或框架修改。

原进程 69259 正常退出 0，`/tmp/iqc-b-decision-revision-20261028.log.exit` 为 0。新 Surefire 报告主方法 **1 项**，168.9 秒，零失败、错误、跳过；不把 helper 内断言数量记成独立测试项。新专用 schema `iqc_b_decision_revision_20261028` 完整执行 58 迁移至 1.1.77；本轮测试结束后专用 MySQL socket 已关闭。

沿原实际 FindingCatalog、独立 Gold 审批、冻结 Dataset、评测/试跑证据和 measured publication，实际创建生产任务、冻结处理好的会话输入、执行 METRIC 节点并持久化结果。生产结果 risk HIGH、score 80；活会话在冻结后已改动，重算走实际 ProductionInputSnapshotService 验证原冻结输入和原 trace。

新增 helper 通过真实服务事务与 SQL 验证：

- 对已实测发布的同一版本进行政策重算，保存独立 revision；投影原风险/重算风险均 HIGH，重算分数等于原分数。
- 同 actor/requestId/参数复用同 revision；同 requestId 改原因拒绝，不多写记录。
- 原主链显式植入的历史不合规 HIGH 版本仍可按历史发布契约读取，但作为新重算目标被拒绝，没有 revision 残留。它是升级前 fixture，不是本轮新发布成功证明。
- scope.canView 受控撤销时，即使已有幂等请求也拒绝访问；解除后恢复其它场景。
- 实际 BEFORE INSERT trigger 拒绝 revision 写入，事务失败且计数不变；解除故障后同 requestId 成功重试。
- history 精确返回两个成功 revision；原 conversation result **整行**、任务 rule_snapshot_json、全部 Action 行保持相同，模型网关调用数量不增加。

同一个原主方法继续执行发布/批次跨 JVM claim 与恢复、显式和自动重试、Action 收据故障与确认、人工复核/候选/Gold 闭环和 Finding MVC 等既有断言。本轮成功只覆盖该固定组合和主方法，不推导全测试套件通过。

边界：组织 scope、当前授权解析和模型供应商仍受控；不是实际 PDP/Feign/岗位角色或外部模型验证。源 trace 和冻结输入来自本轮真实主链，不再用简化 Context 代替。重算目标为相同已发布版本，本轮未证明所有更换融合/评分/风险目标策略的组合；人工高风险改判的完整正负向保存边界仍待补齐。日志中的预期 SQL 故障和 Action receipt settlement pending 来自已有故障注入，不是外部通知送达证明。

无共享数据库、推送或部署；完整适用 PRD I1–I7 保持进行中，输入不涉及语音转换。
