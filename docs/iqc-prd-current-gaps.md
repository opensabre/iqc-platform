# 当前实施与验收缺口（2026-10-31）

原后端集成基线为 PR17 固定 `77993dd29938869969c9544696654e1785565fed`；本页所在候选已接续到来源支持报告 PR28 固定 `3bea2f9601338b02fa79623dcdc6a05b7b1f3306`。前端已有跨模块组合 PR56 固定 `a7fba1b9644a4dbf700e81342083c582f7faa692` 的完整 CI；后续来源标注/报告仍为独立小 PR。以下同时记录固定分支源码、专项和自动 CI，各自范围分开，不代表所有分支已经合流或部署。历史阶段记录仍可在 Git 中追溯。

范围保持正式 PRD V1.0 的全部适用 I1–I7：输入为已处理好的会话；排除实时语音、ASR/转写生成、音频解码与说话人分离生成，保留业务完成事件/API、消息时间及业务证据。单租户部署，沿用既有 owner/group/all 组织数据范围，不建设第二套租户或 RBAC 模型。完整目标仍在进行。

## 已有实现及其证据边界

| 能力 | 当前实现与已有证据 | 仍须证明 |
| --- | --- | --- |
| 标签准确率 | 原冻结 FindingLabelPolicy → FindingLabelProjection → LabelEvaluationMetrics 已接报告；规范绑定地址、KNOWN 可比值、未知/缺产物分母、SQL 重试继承已有专项 | 实际角色、标签发布到评测的浏览器/后端整链；不能把可比准确率称全量标签覆盖率 |
| 上游声明消息完整率 | 沿原 businessFields 清单、Gold/数据集冻结与 InputCompletenessMetrics；报告、SQL 来源冻结与历史 JSON/hash 兼容已有专项，见[完整率协议与验证](iqc-input-completeness.md) | 上游清单真实性、消息身份和语义完整性；数量相等只证明声明计数相等 |
| 模型事实来源支持 | 原 BUSINESS Finding 复核已增加逐 KNOWN 原始 LLM 观察的三结论/冻结来源标注、上下文回显及报告计数/覆盖率；后端 PR23/26/28、前端 PR57/59。协议见[人工标注](iqc-model-claim-source-support.md)、[报告摘要](iqc-model-claim-source-support-report.md)。B PR25 有兼容/来源边界专项，PR27 有实际保存、读回、幂等及回滚专项 | 报告新增统计与界面专项及最终合流、Gold 独立审核、完整语义指标与真实角色/供应商链；三类人工标注和未标注分开，不能把本分母覆盖率或已有引用合法性称完整幻觉率 |
| 模型引用真实性 | 过滤前显式引用计数、最后轨迹聚合及报告/UI 已实现；有引擎与受控模型/SQL 专项，见[引用真实性](iqc-llm-citation-grounding.md) | 广义语义幻觉、真实供应商错误引用到 SQL 的完整链；不以引用合法或 Finding F1 推导幻觉率 |
| 模型一致性 | 精确模型/声明部署身份、冻结输入及受控 Provider→worker→SQL→报告路径已有实现与专项，见[模型一致性](iqc-model-consistency.md) | 供应商不可变部署真实性、真实网关账本与角色网络链；缺部署仍必须保留分母并排除比较 |
| 自动候选挖掘 v2 | 原 ADD_TO_EVALUATION_CANDIDATE 的版本2、MinedCandidateSelection/Lineage/Service 已实现；沿 QualitySample 保存 UNCLASSIFIED/CANDIDATE/UNLABELLED，不自动裁决误报/漏报或批准 Gold | 真实调度身份/PDP、实际配置启用及完整页面链；动作默认关闭不等于已实际自动挖掘 |
| 批次执行 | 原持久分片、恢复/停止/清理及跨 JVM 专项保持；[PR24 百万扫描报告](https://github.com/opensabre/iqc-platform/blob/bbec6e016ff23a3381dcaa5f0870905bb7ff9298/docs/iqc-million-selector-scan-validation.md)已有百万条合成元数据、owner/group/all/长零匹配尾段四轮生产扫描，各 1001 页，真实 SQL/参数/计划及完整计数证据 | 百万消息正文/实际分片物化、早中后强杀恢复、两个完整 worker 与普通任务混合负载、完整执行及真实吞吐/资源/成本/SLO；受控元数据扫描不等于完整容量验收，也不能再说当前证据仅两万夹具 |
| 资源注册与精确授权码 | 原 Controller/组织模型内已拆分冲突端点；实际 42 Controller/242 映射、91 固定契约、50 精确拒绝及原始映射完整性已有专项，见[全 Controller 验证](iqc-prd-b-all-controller-permission-validation.md) | 实际条件 Bean、组织资源 upsert、五类职责登录、成功写与跨服务撤权；不能以唯一权限码或按钮显隐替代真实授权 |

标签指标复核入口：[LabelEvaluationMetrics](../src/main/java/io/github/opensabre/iqc/quality/evaluation/LabelEvaluationMetrics.java)、[PipelineEvaluationService](../src/main/java/io/github/opensabre/iqc/quality/evaluation/PipelineEvaluationService.java)、[原 SQL 发布/评测场景](../src/test/java/io/github/opensabre/iqc/result/IsolatedMysqlMeasuredPublicationIT.java)。本次只读取既有源码和报告，未新增或执行测试。

自动候选的当前范围见[筛选专项](iqc-prd-b-mining-selection-validation.md)、[生产者 SQL 与发现/并发验证](iqc-prd-b-mined-producer-sql-validation.md)、[Gold 消费与事务](iqc-prd-b-mined-candidate-gold-sql.md)。生产者已有真实 discovery/enqueue/claim/settlement、无命中零账本、并发单候选及故障重试专项；不能继续将它标为“只有人工复核候选、自动生产者尚未实现”。旧 v1 人工复核来源与 v2 待标注来源仍各自保留。

批次证据见[原子物化](iqc-prd-b-bulk-materialization-sql-validation.md)、[跨 JVM 与 worker 恢复](iqc-prd-b-bulk-jvm-restart-validation.md)。其中父认领互斥、强杀后恢复和受控组织范围均有明确口径，不扩大为两套完整生产调度器或百万运行证明。

百万扫描 PR24 的生产/测试源码固定 `907b94897ded12498324944aa5199ce94e3833c0`，证据头 `bbec6e016ff23a3381dcaa5f0870905bb7ff9298`。opt-in 实测 JUnit 1 项、804.972s、零失败/错误/跳过；没有百万条消息正文。预读缓存、查询/断言/EXPLAIN采样、阶段RSS而非峰值均已注明，不能把四轮扫描时间当生产TPS/P95或把计划估计rows当实扫行数。普通CI不包含该百万IT。

## 下一阶段交付与验收

| 顺序 | 剩余工作 | 完成证据与约束 |
| --- | --- | --- |
| 1 | 后续小功能与其独立回归合流，继续核查私有副本、确认、写后回读和入口权限 | 原跨模块 PR56 已有同头完整 CI；现在需合流 PR57/58 标注表单及 PR59 报告展示、后端 PR25/27 独立专项与 PR28 新统计等后续固定头。逐项审阅，不把不同分支测试数相加或将旧组合绿色追溯给新头。 |
| 2 | 五类职责和当前组织授权整链 | 沿原角色配置表达质检管理员、业务管理员、质检员、模型工程师、系统管理员；证明网关/PDP、owner/group/all、同组/跨组、敏感副本、成功写与撤权。只读配置快照不代替登录；不创建账号或泛授 ADMIN/all 来通过验收，见[职责与只读基线](iqc-prd-five-duty-access-audit.md) |
| 3 | 外部运行契约 | I4 实际 broker/归档完成事件、提交后 ACK/重投/死信；I5 通知、工单、辅导、Webhook 的真实接收/幂等/失败补偿；I6/I7 供应商超时、降级、Token/费用/部署身份。复用原适配器，受控接收方不称真实渠道通过 |
| 4 | 指标语义与真实采集 | 已实现逐原始LLM声明人工来源标注及其明确分母/未标注/覆盖率；新增报告与UI还需专项和固定合流。Gold独立审核与完整语义幻觉指标仍待，引用错误率继续保其窄口径。原 PromQL 清单须核验真实 scrape、应用版本/标签与授权；空向量或 promtool 夹具不等于指标已采集，见[监控复核](iqc-prd-b-monitoring-promql-validation.md) |
| 5 | 容量与运行 SLO | 百万扫描已有受控实测；继续执行[容量协议](iqc-million-capacity-validation-protocol.md)的实际消息物化、恢复及完整执行切片，再证明并发/取消/混合负载与TPS/P95/资源/成本。稀疏授权、其它分布和生产基线仍待，不外推密集数据扫描。 |
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

固定证据分开审阅，测试结果不相加为合流结果：

| 固定交付 | 已核证据 | 不能据此证明 |
| --- | --- | --- |
| 后端 PR28 `3bea2f9` | [CI41167478759](https://github.com/opensabre/iqc-platform/actions/runs/41167478759) completed/success，包含来源支持报告源码 | 新报告计数/轮次/权限/SQL专项或部署 |
| 前端 PR56 `a7fba1b` | [CI41138656138](https://github.com/opensabre/iqc-platform-admin/actions/runs/41138656138)完整组合72文件/1000项；后续PR57 `5327fa9` 的[CI41167173138](https://github.com/opensabre/iqc-platform-admin/actions/runs/41167173138)亦success | PR58新增行为回归与PR59新报告已经合流、真实浏览器/PDP验收 |
| 前端 PR59 `b480338` | 本地最终类型/生产构建成功；[CI41168048859](https://github.com/opensabre/iqc-platform-admin/actions/runs/41168048859)最新核查completed/success | 新增报告专项、后续回归合流或真实运行验收完成 |
| 后端 PR24 `bbec6e0` | 上述百万扫描opt-in实测；最终[普通CI41142999999](https://github.com/opensabre/iqc-platform/actions/runs/41142999999)成功 | 普通CI已执行百万IT或百万物化/全执行完成 |
| 后端 PR25 `d10dd81` | 原兼容/来源/服务/HTTP相关51项专项与[CI41166579174](https://github.com/opensabre/iqc-platform/actions/runs/41166579174)成功；该CI1926项后另执行5项既有分片SQL | 新字段实际SQL保存、真实职责登录或报告新分母专项 |
| 后端 PR27 `8f27be0` | [固定报告](https://github.com/opensabre/iqc-platform/blob/8f27be01c23f8022b0267cb6e34c30cd9e8d001e/docs/iqc-claim-source-support-mysql-validation.md)记录实际MySQL三项保存/回显/幂等/范围拒绝/回滚通过；受控actor/model | 其[普通CI41167384640](https://github.com/opensabre/iqc-platform/actions/runs/41167384640)实际failure，不能称整头绿色：PipelineMonitoringTest的Gauge NaN断言失败，后续分片SQL skipped；B独立诊断/修复夹具，原失败保留 |

后端PR17及PR23–28在本次只读核查时仍open，不把分支交付当远端合并。运行验收前置参见[当次现场只读快照](iqc-runtime-readonly-baseline-20261031.md)：候选与当次运行镜像/迁移不属同一快照，该历史观察不能代替最新候选部署或业务验收。本次不合并、不部署、不执行共享SQL，不切默认v2或动作开关。

百万容量协议已有扫描切片实测，其余物化/恢复/完整执行和相应资源/成本测量继续。来源支持报告不自动成为Gold真值或完整幻觉率。上述新增证据没有缩减全部适用I1–I7、A–E和十条边界。
