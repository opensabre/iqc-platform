# 高风险首次创建与历史批次幂等 SQL 验证

2026-10-28，基线2e3663f，生产4f73190；仅扩原MeasuredPublication主方法，无生产源码变化。

以该方法实际冻结的已发布Metric流水线为来源，构造default HIGH及ALWAYS→HIGH风险策略。真实PipelineAssetService.create拒绝新草稿，核对资产数不增。通过隔离SQL显式植入升级前不含publicationDecision的历史版本（原协议允许该历史形状），真实publishedRelease读取成功且摘要一致；真实普通任务createUsingPipelineSnapshot及bulk.create首次创建均因HIGH门拒绝，任务总数、执行总数不增。

随后显式植入对应历史批次父记录，使用原Definition/envelope/hash/Progress协议及原幂等ID公式。真实bulk.create相同请求复用原ID，改名请求拒绝；父快照全文、进度、历史版本JSON/Hash不变，无额外任务或执行。此植入是历史幂等夹具，不能称新门允许不合规批次创建，亦未证明历史不合规版本可继续物化新子任务。

另基于实际冻结节点定义，先验证要求证据的KNOWN条件可通过新静态门，再加入同code、evidenceRequired=false的第二生产者，实际validateNewRiskEvidence拒绝；强证据来源不能替弱来源放行。

证据：原10335 compiler:testCompile surefire:test终态0，`/tmp/iqc-b-high-risk-materialization-sql-20261028.log.exit=0`；fresh Surefire主方法1项93.54秒、零失败/错误/跳过。本机专用34307/已验证iqc_b_bulk_retry_20261027_01，退出后socket不存在，diff检查通过。同次继续通过原发布/评测、批次原子物化/跨JVM互斥与恢复/满载停止传播/优先级/暂停取消清理、六组终态、显式及配置子策略自动重试、Finding/MVC/复核链。

边界：组织与外部供应商仍受控，历史版本/父记录为明确fixture，不是完整历史系统升级回放。尚不能据此证明人工高风险事实改判、DecisionRevision、RiskEventAction全部负向/幂等场景、真实PDP/浏览器/供应商、模型中断或百万容量。原预期SQL故障与Action receipt pending日志保留，完整适用I1–I7继续；仅处理后的会话，无framework/共享SQL/push/部署。
