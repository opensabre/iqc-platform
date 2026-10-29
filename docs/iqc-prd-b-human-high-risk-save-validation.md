# 人工 HIGH 事实实际保存边界 SQL 验证（2026-10-28）

基线 9a1f44d，仅扩原 IsolatedMysqlMeasuredPublicationIT 主链 helper，无生产代码修改。原进程 53442 正常退出 0，`/tmp/iqc-b-human-risk-review-20261028.log.exit` 为 0。新 Surefire 报告主方法 **1 项**、139.1 秒、零失败/错误/跳过；不把 helper 断言数当独立测试项。专用本机 MySQL 34307，新 schema `iqc_b_human_risk_review_20261028` 完整应用 58 迁移至 1.1.77，结束后专用 socket 关闭。

沿实际 FindingCatalog 发布、独立 Gold 复核、Dataset 冻结、评测/试跑与 measured publication，生成已冻结处理好会话的真实生产 trace。经过原项目复核、人工事实修正与 UNKNOWN 复核后，再通过 owning BusinessItemReviewService.history/request/decide 创建新轮，显式确认 FAIL 项目和 KNOWN/HIGH 数值事实。

新增实际事务与 SQL 边界：

- HIGH 事实不保留任何证据、保留非本事实的证据 ID、添加非冻结会话消息，分别拒绝；每次检查错误含证据原因、PENDING 复核整行等于提交前。
- expectedFindingHash 过期时按事实基准拒绝，PENDING 整行不变。
- BEFORE UPDATE trigger 拒绝 COMPLETED 写入，实际事务失败回滚；解除故障后同轮同内容可保存。
- 保留原事实有效 METRIC 引用的人工 HIGH 保存成功，findingReview.riskLevel 为 HIGH，复核分数 80。
- 同轮相同决策重试返回同产物；改变已结束裁决的原因拒绝，已保存整行不变。
- 所有旧复核整行、原机器结果整行、任务冻结 JSON、全部 Action 行不变，模型网关调用数不增加。

同一主方法继续验证前轮重算的幂等/冲突/故障重试及原始数据不变、原批次跨 JVM 恢复/重试、Action 故障确认、候选/Gold/Finding MVC 等既有链路。不能由一个主方法推出全套测试、真实外部模型或全部 PRD 完成。

范围限制：本链未设置独立 RiskPolicy，HIGH 来自原 FAIL 项目映射的 severity；本轮证明该兼容路径上的人工保存事务，不代替独立风险条件多 code/最高等级归因的实际保存验证。原生产 trace、冻结输入与消息证据校验真实，组织 scope 和模型供应商仍受控；非真实 PDP/Feign/岗位角色。错误消息断言与最后成功保存共同防止把无关异常算作证据门禁通过。未修改框架、共享数据库、推送或部署。适用 I1–I7 仍 active，输入不涉及语音转换。
