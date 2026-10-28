# 高风险证据归因独立验证（2026-10-28）

基线 e53c5c3，生产归因实现 863649b。仅新增 RiskEvidenceAttributionTest 并扩展原 IsolatedMysqlRiskActionEvidenceIT 夹具/场景，无生产代码修改。输入仍为已处理会话。

原进程 29898 正常退出 0，`/tmp/iqc-b-risk-attribution-20261028.log.exit` 为 0。新 Surefire 报告共 28 项，零失败、错误、跳过：归因领域 8（0.082 秒），原 RiskPolicyEvidenceTest 6（0.068 秒），风险 Action SQL 6（31.893 秒），原风险处置 SQL 8（14.82 秒）。专用本机 MySQL 34307，两个隔离 schema 各完整应用 58 迁移至 1.1.77、validate 成功、再次 migrate 为 0。

新增领域场景使用真实 ActionPolicy/RiskPolicy、FindingFusion 和 PipelineDecisionEvaluator：

- A 命中 HIGH 无证据，B 带选中引用但与条件无关：历史风险 evaluate 仍为 HIGH，新产物证据判断拒绝。
- ANY 只能使用实际 MATCH 分支的事实证据；带证据的未命中分支不能救场，换成该分支真实命中则通过。
- ALL 必须整体 MATCH，才可使用其中被条件引用的有证据事实。
- 有证据的 HIGH 规则不能支撑另一无证据 CRITICAL 规则，也不能支撑历史 CRITICAL 默认值。
- NOT 等值需要该 code 自身 KNOWN 的选中证据，仅被拒绝来源的引用不足。
- 无独立 RiskPolicy 的兼容判断，必须是实际 FAIL 项目映射、且事实 severity 等于最终风险；低 severity 或 PASS 项目的证据不足。
- 仅复核 B 不能采用未复核 A 被拒绝来源的引用；明确复核 A 才可沿人工有效引用路径通过，原融合 selected/rejected IDs 保持。

SQL 专项沿真实 RiskEventActionService 事务与 MyBatis/MySQL，新增 OTHER 事实有选中引用但 CONSENT 命中条件无证据的拒绝：无事件残留、lookup 为空、动作仍 RUNNING。原选中证据 CRITICAL 创建唯一产物、结算后历史 lookup、弱证据已处理历史事件复用、真实 INSERT 故障回滚并解除后重试、受控撤销创建权限同轮通过。

新生产实现在冻结输入中读取 taskSnapshotJson/pipelineSnapshot/release，因此 fixture 补入原契约结构，重算对应输入 hash；不将旧的简化纯 Context JSON 当真实生产输入。旧固定 3c988df 的通过仅适用于旧实现，本轮结果才证明当前 863649b 的风险 Action 行为。

边界：领域 release 使用不执行的空检测图，仅验证确定性政策投影；SQL 中生产 SourceValidator 与组织 scope 仍受控，完整 taskSnapshot 和结果 trace 的来源验证不由本测试证明。人工部分为纯 evidence projection，不是实际复核保存事务或 DecisionRevision 完整 SQL。没有验证真实 PDP/消息归属/模型调用、并发、容量或全部 PRD 场景。无框架、共享数据库、推送、部署；完整 I1–I7 继续。
