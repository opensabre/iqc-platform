# 当前实施与验收缺口（2026-10-30）

本次复核后端 PR17 固定 `77993dd29938869969c9544696654e1785565fed` 的源码、正式领域文档和既有验证记录。前端集成基线为 `07321ff`，后续小 PR 仍分支待审；不能把各分支已有实现当作已合流或已部署。2026-10-22/25 的阶段缺口记录由本页当前状态替代，原记录仍可在 Git 历史中追溯。

范围保持正式 PRD V1.0 的全部适用 I1–I7：输入为已处理好的会话；排除实时语音、ASR/转写生成、音频解码与说话人分离生成，保留业务完成事件/API、消息时间及业务证据。单租户部署，沿用既有 owner/group/all 组织数据范围，不建设第二套租户或 RBAC 模型。完整目标仍在进行。

## 已有实现及其证据边界

| 能力 | 当前实现与已有证据 | 仍须证明 |
| --- | --- | --- |
| 标签准确率 | 原冻结 FindingLabelPolicy → FindingLabelProjection → LabelEvaluationMetrics 已接报告；规范绑定地址、KNOWN 可比值、未知/缺产物分母、SQL 重试继承已有专项 | 实际角色、标签发布到评测的浏览器/后端整链；不能把可比准确率称全量标签覆盖率 |
| 上游声明消息完整率 | 沿原 businessFields 清单、Gold/数据集冻结与 InputCompletenessMetrics；报告、SQL 来源冻结与历史 JSON/hash 兼容已有专项，见[完整率协议与验证](iqc-input-completeness.md) | 上游清单真实性、消息身份和语义完整性；数量相等只证明声明计数相等 |
| 模型引用真实性 | 过滤前显式引用计数、最后轨迹聚合及报告/UI 已实现；有引擎与受控模型/SQL 专项，见[引用真实性](iqc-llm-citation-grounding.md) | 广义语义幻觉、真实供应商错误引用到 SQL 的完整链；不以引用合法或 Finding F1 推导幻觉率 |
| 模型一致性 | 精确模型/声明部署身份、冻结输入及受控 Provider→worker→SQL→报告路径已有实现与专项，见[模型一致性](iqc-model-consistency.md) | 供应商不可变部署真实性、真实网关账本与角色网络链；缺部署仍必须保留分母并排除比较 |
| 自动候选挖掘 v2 | 原 ADD_TO_EVALUATION_CANDIDATE 的版本2、MinedCandidateSelection/Lineage/Service 已实现；沿 QualitySample 保存 UNCLASSIFIED/CANDIDATE/UNLABELLED，不自动裁决误报/漏报或批准 Gold | 真实调度身份/PDP、实际配置启用及完整页面链；动作默认关闭不等于已实际自动挖掘 |
| 批次执行 | 复用 Task/Execution 的持久分片、父子协调、恢复、停止与清理；已有分页物化、跨 JVM 互斥及真实 worker 恢复专项 | 百万规模、两套完整 scheduler 与活跃供应商调用并发、实际吞吐/延迟/费用；不得以约两万条分页夹具声称百万容量 |
| 资源注册与精确授权码 | 原 Controller/组织模型内已拆分冲突端点；实际 42 Controller/242 映射、91 固定契约、50 精确拒绝及原始映射完整性已有专项，见[全 Controller 验证](iqc-prd-b-all-controller-permission-validation.md) | 实际条件 Bean、组织资源 upsert、五类职责登录、成功写与跨服务撤权；不能以唯一权限码或按钮显隐替代真实授权 |

标签指标复核入口：[LabelEvaluationMetrics](../src/main/java/io/github/opensabre/iqc/quality/evaluation/LabelEvaluationMetrics.java)、[PipelineEvaluationService](../src/main/java/io/github/opensabre/iqc/quality/evaluation/PipelineEvaluationService.java)、[原 SQL 发布/评测场景](../src/test/java/io/github/opensabre/iqc/result/IsolatedMysqlMeasuredPublicationIT.java)。本次只读取既有源码和报告，未新增或执行测试。

自动候选的当前范围见[筛选专项](iqc-prd-b-mining-selection-validation.md)、[生产者 SQL 与发现/并发验证](iqc-prd-b-mined-producer-sql-validation.md)、[Gold 消费与事务](iqc-prd-b-mined-candidate-gold-sql.md)。生产者已有真实 discovery/enqueue/claim/settlement、无命中零账本、并发单候选及故障重试专项；不能继续将它标为“只有人工复核候选、自动生产者尚未实现”。旧 v1 人工复核来源与 v2 待标注来源仍各自保留。

批次证据见[原子物化](iqc-prd-b-bulk-materialization-sql-validation.md)、[跨 JVM 与 worker 恢复](iqc-prd-b-bulk-jvm-restart-validation.md)。其中父认领互斥、强杀后恢复和受控组织范围均有明确口径，不扩大为两套完整生产调度器或百万运行证明。

## 下一阶段交付与验收

| 顺序 | 剩余工作 | 完成证据与约束 |
| --- | --- | --- |
| 1 | 前端小 PR 与各自回归合流，继续清理尚未覆盖的私有副本、确认、写后回读和入口权限 | 固定合流头的实际构建/完整 CI 与对应行为断言；逐项 PR 保持可审阅，不把不同分支测试数相加。详情、历史、快照、创建、执行分别使用原独立资源 |
| 2 | 五类职责和当前组织授权整链 | 沿原角色配置表达质检管理员、业务管理员、质检员、模型工程师、系统管理员；证明网关/PDP、owner/group/all、同组/跨组、敏感副本、成功写与撤权。只读配置快照不代替登录；不创建账号或泛授 ADMIN/all 来通过验收，见[职责与只读基线](iqc-prd-five-duty-access-audit.md) |
| 3 | 外部运行契约 | I4 实际 broker/归档完成事件、提交后 ACK/重投/死信；I5 通知、工单、辅导、Webhook 的真实接收/幂等/失败补偿；I6/I7 供应商超时、降级、Token/费用/部署身份。复用原适配器，受控接收方不称真实渠道通过 |
| 4 | 指标语义与真实采集 | 广义语义幻觉仍需按正式 PRD 明确可审计的 Gold/来源分母并实施或补证；已有引用错误率仅保其窄口径。原 PromQL 清单须核验真实 scrape、应用版本/标签与授权；空向量或 promtool 夹具不等于指标已采集，见[监控复核](iqc-prd-b-monitoring-promql-validation.md) |
| 5 | 容量与运行 SLO | 按 PRD23 以实际压测形成基线，证明百万数据规模、恢复/取消、并发、TPS/P95/资源/单会话成本；保留未测量项，不从小夹具或线程池配置推断容量 |
| 6 | A–E 与十条研发边界的完整验收 | 固定源码、数据/金标、角色、配置与运行产物逐场景证明；功能源码、局部 SQL 或构建只覆盖其对应范围 |

这些是交付/验收集合，不能仅据一次文件搜索认定全部能力不存在。已有能力优先复用；新增存储、API、基础设施或公共抽象前仍执行工作区复用门禁。framework 源码变更仍须前置明确批准。

## 场景与边界不能缩减

- A：昨日销售会话 → Rule/Regex → SmallModel → 低置信度升级 LLM → Fusion → 评分 → 高风险复核。
- B：已处理会话的 ConversationCompleted 事件 → Selector → 红线 → Short Circuit → CRITICAL → 告警与复核；排除语音流不等于排除该业务完成事件。
- C：同一 Pipeline 输出合规、意图与情绪，Decision 分别产生分数和标签，不重复调用模型。
- D：固定 Gold Dataset 对照实际模型版本，风险 Recall/F1/P95 门禁后发布。
- E：人工纠正 → Candidate → 独立审核 → Regression Dataset → 下一版本必须通过原 Case。

PRD27 的十条边界全部保留：Engine 不改最终分数/不直接建工单；执行失败不同于未命中；Finding 有来源版本且 Fusion 保留原事实；发布版本不可原地修改；历史快照可还原；人工改判保留 AI 原值；高风险可定位 Evidence；评测复用生产 Pipeline/Engine。每条都需要与范围相符的实际证据，不能由整体 CI 绿色替代。

## 发布和验证状态

后端 PR17 固定头的[运行40901810573](https://github.com/opensabre/iqc-platform/actions/runs/40901810573)当前为 completed/success；源码头与本页基线一致。这证明该固定头对应流水线，不证明后续前端小 PR 已合流、应用已部署、五职责或 A–E 已验收。PR17 仍 open，本次不合并、不部署、不执行共享 SQL，不切默认 v2 或动作开关。

百万容量后续按[分片、恢复与容量验收协议](iqc-million-capacity-validation-protocol.md)逐项取证；协议待执行，不据此关闭容量缺口。
