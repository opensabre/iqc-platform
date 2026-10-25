# IQC PRD V1.0 当前覆盖复核（2026-10-20）

## 范围与证据口径

基线：后端d5cd5ce、前端f79c264，后续WEIGHTED批次以协作交接的提交/构建终态为准。需求源为工作区《IQC智能质检平台_PRD_V1.0.md》，不自动采用另一份V1.1文档。输入统一为已经处理好的会话；实时语音、ASR、音频解码和转写生成排除。处理完成后的业务事件/API触发、既有消息时间证据仍在范围。用户2026-10-20明确：单租户部署，沿用组织数据权限；不建设共享部署多租户模型，其它适用I1–I7不缩减。

本表是当前源码与已有证据的缺口复核，不是完成验收。源码路径仅为复核入口；只有覆盖该项要求的实际行为/数据库/角色/浏览器/供应商证据才能证明交付。已有领域文档与协作台账中的历史“待开发”不等同当前缺失，表中未证明项仍需补证或实施。

## 分章覆盖

| PRD / 迭代 | 当前源码与已有证据入口 | 当前结论与剩余工作 |
| --- | --- | --- |
| 1–5 定位/链路/对象 / 全部 | PipelineRelease、InspectionTask/Execution、Finding、Decision、原InspectionScheme | 主链路已有实现；领域名称按现有模型映射，不为PRD示意表复制主库。完整链路验收未完成。 |
| 6.1 批量/定时/事件/API / I4 | InspectionTaskService、PeriodicScheduleService、TaskTriggerController、EventSubscriptionController | 已编码；实际触发/恢复、真实身份与供应商联调待验。排除语音流，不排除业务完成事件。 |
| 6.2–6.4 表单/状态/幂等 / I1/I4 | 现有tasks页、Pipeline任务表单、TaskExecution、队列与自动重试、灰度TaskEvidence | 原任务/请求重放已编码；当前用户表单是否覆盖全部建议项、跨重启/并发和取消恢复仍须场景证明。 |
| 7 条件/7种抽样/预览 / I4 | selector包、SelectorAssetService、ConversationSelectorController及原SelectorEditor | 已编码，B有授权投影/精确版本专项；真实数据分布与调度联动待验。 |
| 8.1 节点协议/错误/映射 / I1/I2 | PipelineDefinition、PipelineRuntime、PipelineConversationEvaluator | 已编码；本批补完整PipelineRelease嵌套HTTP树/精确字符串接线（独立MVC验证待）；节点输入/输出、重试/超时/失败非阴性与恢复须完整行为证据。 |
| 8.2 Rule/Regex/LLM / I1/I2 | RuleMatcher、ItemRouteRunner、原LLM适配与Gateway | 已编码；实际模型输出/证据及旧路线对照待统一验收。 |
| 8.2 Metric/Session / I6 | TranscriptTimingMetrics、TranscriptMetricEngine、SessionAggregationEngine | 复用已处理消息时间/业务上下文，不生成语音转写；语义范围及真实会话指标验收待核对。不能把片段时间指标直接称声学测量。 |
| 8.2 SmallModel/Skill/API / I6 | SmallModelDetectorEngine/Contract、Skill API、原资产版本与引用 | 相关适配已编码，不能继续按旧迭代表称SmallModel未集成；真实外部服务/降级/成本契约待验。 |
| 8.3–8.4 串并行/条件/级联/短路/Fallback / I2/I6 | PipelineRuntime、ParallelCompletionPolicy、原条件与备用节点 | 已编码，局部回归不等于全部组合/真实并行运行通过。 |
| 8.5 设计器 / I6 | PipelineReleaseEditor、PipelineDependencyGraph、editGraphEdge | 列表、节点库/端口连线/属性及依赖图编辑已有；本批补节点主体拖动、缩放、键盘与落点，布局仅本次会话，不改变Release。真实SVG/browser及完整保存发布路径待验，不凭构建认定设计器验收完成。 |
| 9 Finding/Catalog/Evidence / I1 | Finding、FindingCatalogService、原轨迹与结果投影 | 已编码；source/version及证据反查、全部引擎一致性待场景验证。 |
| 10.2 基础四种融合 / I2 | FusionPolicy、FindingFusion | PRIORITY/CONFIDENCE/VOTING/OVERRIDE已有实现与正式语义。 |
| 10.2 WEIGHTED / I2 | 同一FusionPolicy/FindingFusion及原配置编辑器 | 复核确认原先缺失，本批补严格加权多数；尚须独立行为/协议兼容/发布重算验证。 |
| 10.2 CUSTOM扩展 / I2/I6 | FusionPolicy.custom、FusionExtension/Registry和UNANIMOUS v1、原编辑器与只读策略目录 | 本批已编码精确id/version/class摘要/参数绑定与真实一致同意实现，复用原fuse及缺失来源重算；可信部署SPI，不接受客户端代码。独立算法/协议/发布/重放/角色/UI验证待，不能凭编译算验收完成。 |
| 10.3 融合输出 / I2 | FindingFusion.Result保存原始/选中/拒绝/Evidence/解释与政策版本 | 结果可追溯字段已编码；本批新增可空finalConfidence，仅KNOWN且CONFIDENCE政策的已校准选中来源输出；其它策略及人工改判为空。旧结果兼容及当前行为仍待独立验证，校准工件真实性不由字段本身证明。 |
| 11 Score/Label/Risk/Review / I2/I5 | InspectionScoring、FindingLabelPolicy、RiskPolicy、ReviewPolicy、DecisionRevisionController | 高级评分、独立风险/复核与重算已有实现；完整原事实不变/不调模型/复核后重算及真实持久化仍需证明。 |
| 12 九种Action / I5 | action包、ExternalActionBeans、通知/复核/候选/风险适配 | 工单、辅导、Webhook代码已存在，不重复开发；外部真实渠道、接收方幂等契约、确认/失败补偿验收仍待。默认关闭不能称实际投递成功。 |
| 13 人工复核 / I5 | HumanFindingReviewEvaluator、BusinessItemReviewService、LabelResultReviewService及复核UI | 修改事实/证据/标签/评分与复核历史已有实现；逐操作/二审仲裁/申诉/原AI结果不变须核对当前行为与权限。 |
| 14.2 Dataset/Gold / I3 | EvaluationDatasetService、GoldSampleController、DatasetSnapshot | 复用原QualitySample，冻结版本已编码；审批、来源、全会话上下文和实际成员固化待统一验收。 |
| 14.3 五层指标 / I3/I7 | FindingEvaluationMetrics、PipelineEvaluationService、ModelMonitoringService | 基础指标已有；ASR WER/分离生成排除，其他指标定义与完整率/一致性/幻觉/校准/系统指标覆盖仍需逐项核对。不能用无金标统计证明质量。 |
| 14.4 Arena单变量 / I3/I7 | BenchmarkService、BenchmarkVariableComparator | 五维比较已编码；17项纯Policy/codec证据不能证明五维全真实执行，固定数据集/实际模型对照仍待。 |
| 14.5 门禁/强制原因审计 / I3 | PipelinePublicationGateService及原发布接口 | 已编码，真实金标/报告/角色拒绝与强制审计待验；示例95%/92%不是已取得指标。 |
| 14.6 反馈回归 / I3/I5 | BusinessReviewSampleService、原候选动作/审批/Dataset版本 | 相关闭环已编码；从误判修正到下一版本回归的场景E仍需端到端证据。 |
| 15 已处理会话 / I1 | Conversation/Message、ProductionInputSnapshotService、ConversationQueryController | 消息与时间证据/来源固化已有；语音转换不再列待办；输入/附件/业务上下文及授权证据待核对。 |
| 16 Registry/Gateway / I6/I7 | 模型资产、Gateway、预算/费用/限流/对账与调用诊断 | 已编码多项；实际供应商超时/Fallback、Token/成本和调用归属/并发预算待统一证明。 |
| 17 所有对象版本与发布 / I3/I7 | 原各资产版本、Snapshot、Pipeline/灰度修订 | 多数对象已有版本；Pipeline存储/摘要高精度数值及旧hash兼容须独立验证，HTTP桥接不替代该证据；独立Prompt/Knowledge Base版本如何复用原资产仍需明确核对，不能只凭Pipeline整体hash视为每项全部完成。 |
| 17 GRAY/FULL与P2 A/B / I7 | RolloutPolicy/Service/TaskService/ExecutionGuard、Benchmark | 显式IDs灰度新请求/原请求重放已编码；0/10000/回退为同一政策语义。调度/Selector灰度、生产AB归因及真实激活回退验收按实际需求继续，不能只做预览。 |
| 18 Trace/监控/审计 / I1/I7 | executionTrace、ModelMonitoring、公共审计starter | 节点/模型轨迹与部分监控已编码；Finding分布/人工修改率/Action失败率等运营指标与真实审计切面未整体证明。 |
| 19 功能/数据/敏感权限 / I7 | IqcDataScope、SensitiveReadAccessService、ResultReadProjection及组织迁移 | 多项会话/任务/样本/评测/Selector等投影已有；Pipeline/Scheme/Agent/Rule/Skill/Model配置与人工复核等剩余敏感副本仍须收口，真实四角色/网关验收待做。 |
| 19 功能/数据/敏感权限 / I7 | IqcDataScope、SensitiveReadAccessService、ResultReadProjection及组织迁移 | 多项会话/任务/样本/评测/Selector等投影已有；Pipeline草稿/历史/目录/命令回执及Skill指令/Schema/历史/回执已接原敏感投影，独立验证待；Agent配置/历史/比较/回执与独立能力元数据已编码，效果统计补可见任务范围；Rule表达式/历史/测试与DLS导入诊断已编码投影（独立验收待）；Scheme/RuleSet/Model配置与人工复核等剩余敏感副本仍须收口，真实四角色/网关验收待做。 |
| 20–21 逻辑表/API / 全部 | 沿现有领域表/明确新职责表与原Controller扩展 | PRD是逻辑建议，不复制已有表/API；逐项行为由对应功能验收，不要求照抄示意路径覆盖旧语义。 |
| 22 异步/背压/优先级/恢复 / I4 | 原持久化worker、任务/EID租约、预算等待与自动重试 | 已编码机制；真实重启/重复投递/跨实例恢复、外部MQ传输与压力结果待验。外部MQ不是文本完成事件/API的唯一实现方式。 |
| 23 性能/可用性 / I4/I7 | 原TaskItem/worker/分页索引及预算机制 | 显式批次上限1000；百万规模分片/恢复与容量基线尚无充分证据，不能声明指标达成。 |
| 23 部署边界 / I7 | 用户2026-10-20明确单租户；IqcDataScope按owner/group/all判定 | 沿用单租户实例内的组织数据权限，不新增tenant身份/表/请求头。真实组织撤权/跨组/各角色边界及部署配置仍须验收；多租户共享隔离不在本次交付范围。 |
| 23 PII/加密/调用落盘 / I7 | 原mask、StringEncryptor、GatewayCallDataService与TTL | 已有受控加密归档/读取及B隔离SQL部分证据；其它敏感配置与物理清理/真实授权须继续核对。 |
| 24 页面21项 / I1–I7 | 现有工作台/会话/任务/方案/流水线/目录/标签/风险/结果/复核/评测/模型/报表/RBAC | 多为既有页扩展；页面存在不等于可用，须逐页角色、读取失败/迟到、保存/发布及真实联调验收。 |
| P2 成本优化路由 / I7 | 现有Gateway预算/Token费用/路由与回退 | 成本计量不等于成本优化路由；是否有明确选择目标/策略/质量约束与效果证明仍须核对，当前不算达成。 |
| P2 高级运营分析 / I7 | 现有dashboard/monitoring/风险与结果统计 | 页与部分统计已有；趋势、人工修改、风险与成本归因/导出口径及当前真实数据证明待补。 |
| P2 自动候选样本挖掘 / I3/I5/I7 | 原ADD_TO_EVALUATION_CANDIDATE及Review候选 | 触发候选动作与人工回归闭环已有；是否覆盖自动挖掘策略/去重/审批与真实效果须单列核对，不凭候选表存在宣称完成。 |
| 13.3 反馈分类 / I3/I5 | 原人工复核/候选来源元数据 | 误报/漏报/证据/评分/数据/规则/业务标准七类须逐一核对，处理后输入的数据错误反馈不等于开发ASR。 |
| 13/15/19 已有媒体引用 / I1/I5/I7 | 原ConversationAudioController及消息时间证据 | 若处理后会话带音频引用，播放授权/审计/时间定位仍须验证；不开发实时语音、音频解码或转写生成。 |
| 25 P0/P1/P2优先级 / 全部 | 已接受I1–I7计划 | 保持全部适用范围；按真实缺失补功能，不因局部测试易通过而替代主链路或最终要求。 |
| 26 场景A–E / 全部 | 上述链路 | 五场景均尚未有当前组合的完整证明；B的领域/mock/单表SQL证据各有范围，不能合并冒称E2E。 |
| 27–29 边界与架构 / 全部 | 原始事实、独立Decision/Action、不可变发布、人工修订、共享评测运行时 | 有代码基础；十条边界必须逐条用真实场景/来源/副作用与原始数据不变证明，当前整体目标继续。 |

## 单租户部署决定（用户2026-10-20明确）

用户选择“单租户部署，沿用组织数据权限”。本次使用既有身份、菜单/API资源、owner/group/all数据范围，worker仍沿原冻结负责人身份与当前授权解析；不创建tenant表/模型、不引入伪造租户头，也不修改framework。单租户边界由部署定义，组织/跨组/撤权与all权限仍须真实验收；这一澄清不取消其它安全、隐私或I1–I7需求。

此前只读复用检索：UserContextHolder为username/userId/roles/scopes，framework中的TENANT限流维度读取X-Tenant-Id；它不作为本次租户身份或数据隔离机制。原PRD23的部署歧义按上述用户决定落实，不再把共享多租户建设列为本次缺口。

## 接下来顺序

1. 补明确功能缺口：WEIGHTED、融合最终置信度语义、受控CUSTOM扩展；接入原生产/评测/重算与界面。
2. 收口剩余敏感副本与实际角色授权，按已明确单租户部署落实组织数据权限边界。
3. 完成其余指标/版本/运营与实际外部适配缺口，校正旧I5/I6迭代表状态。
4. 独立B继续任务/worker/并发/数据库/HTTP/browser专项；用统一当前组合验证A–E及重启/容量/真实供应商和角色。

未经上述要求逐项证明，不把任何单批构建、测试数量或源码清单升级为整个PRD已完成。


## 2026-10-22 模型层置信度误差诊断增量

FindingEvaluationMetrics沿原金标/结果比较新增带协议的confidenceDiagnostics，经原Report进入评测页：coverage、Brier、ECE及10箱。没有finalConfidence的原结果不补零，空分母null。具体定义、旧Result构造/ReportEvidence摘要兼容风险及证据边界见iqc-confidence-diagnostics.md。当前仅代码/编译构建，数值/HTTP/旧JSON兼容待B验证；一致性、幻觉、真正校准工件真实性及其余层指标仍未因此完成。


## 2026-10-22 业务人工修改率增量

BusinessQualityReportService沿原选择/冻结标准/已验证最近完成复核，新增业务结果修改率和复核覆盖率、三个可重叠变更分类；细则见iqc-business-review-change-metrics.md。没有复核不填成未修改，无修改率分母时null，不混独立标签或旧消息复核，不改机器结果。当前仅编译构建，数值/来源/旧JSON/SQL/browser待独立验证。模型一致性仍缺真正重复同输入同版本Pipeline数据；旧方案投票轨迹不能替代，PRD其余指标及全I1–I7继续。


## 2026-10-22 模型一致性输入来源增量

实际Finding.Source.inputHash仅覆盖消息，不覆盖Prompt/上游；新增框架调用fingerprint经LlmEvaluation/原结构指标进入新轨迹，复用真实网关modelKey和精确摘要，不落正文、不改旧sourceHash。详见iqc-llm-consistency-input-evidence.md。当前仅来源采集且编译，独立重复运行比较/覆盖率/不可比原因报告仍待，不能把这一增量称模型一致性已完成。


## 2026-10-22 引用真实性来源增量

FrozenDetectorEngine增加过滤前的llmCitationGrounding计数，复用原LegacyFindingAdapter引用判定，失败节点也保留可用诊断。未知协议/丢失响应不填零，不根据过滤后合法引用宣称无幻觉；具体口径与待验见iqc-llm-citation-grounding.md。当前只采集来源，原报告聚合与页面尚待接入，广义事实幻觉仍不可冒称完成。

### 2026-10-23 引用报告聚合与页面

在原评测报告增加可选citationGroundingMetrics，基于已验证产物及重试继承来源，按冻结样本×LLM节点计覆盖、最后轨迹去重，展示部分/缺测/坏容器和已观测引用错误率。旧构造保留、null字段省略；新生成ReportEvidence摘要含新增字段，历史证据不回写。UI复用report:view与原报告加载，不增API/权限/表/模型调用。详见iqc-llm-citation-grounding.md；广义事实幻觉、真实SQL/HTTP/浏览器证据仍待，不以本批视为全PRD完成。

### 2026-10-23 标签SQL增量与单会话费用

固定集成B b5ae66f/85a27dc：真实标签解析/投影和隔离SQL重试来源专项及Gold地址提示；原测试证据属于B原提交的受控边界。原任务模型调用分页/全量费用统计新增可选会话筛选，沿用组织scope、EID归属及当前敏感投影，不新增API/表/计费算法。UI显示生效范围、未知费用与币种，未关联旧调用不可回填。见iqc-model-gateway.md；本批编译/构建，真实SQL/HTTP/browser/供应商新组合仍待。数据层完整率尚无上游应有消息数契约，不使用已保存计数冒充完整率。

### 2026-10-23 I4外部RabbitMQ接收

新增默认关闭的RabbitMQ应用适配，固定部署身份/来源/资产允许集合，复用当前组织授权及TaskTriggerService原接收幂等事务，提交后ACK、失败死信、不在监听线程执行检测。无Framework/基础设施/新Task或Inbox表/API/权限变化。详见iqc-rabbit-triggers.md；当前只编译，关闭默认/真实broker/SQL/身份/丢ACK与死信重投待独立验证，I4和全PRD保持进行中。

### 2026-10-23 上游声明消息完整率

复用businessFields三个显式标量声明/生产与Gold快照，在原评测报告接有来源分母的数量完整率、测量覆盖、不足/超出/无效/缺测。无声明不根据已保存messageCount伪造完整率；按全部冻结输入统计，不依赖执行产物。MESSAGE清单小扩展沿当前来源，BUSINESS沿不可变输入；不改变无清单旧形状，旧Report15/16构造保留，新摘要包含新指标而历史不回写。UI报告/API接入说明配套，详见iqc-input-completeness.md；当前编译/构建，独立实际SQL/来源/JSON/角色/browser仍待。

### 2026-10-23 已发布Selector灰度入口

原灰度创建接口/任务物化器接可选精确已发布Selector，复用原锁/分配/选数/输入冻结及组织范围。新证据schema绑定selectorRequestHash，原列表v1不重写；原请求重放检查历史Selector与原选数，不跟随当前策略/语料重新分配。页面复用资产库，无新表/API/权限/框架，详见iqc-pipeline-rollout-tasks.md。当前仅编译/构建，真实SQL/worker/权限/browser待B验证；周期/事件灰度及整体I1–I7仍继续。

### 2026-10-23 周期实例灰度

显式周期灰度定义/发布协议接原调度与Rollout物化链路，保留固定版本v1含义、计划锚点/失火/空数据/游标/重试与当前组织授权。每实例首次冻结当前策略，原实例不重分配；worker来源guard核对原周期修订、实际实例列、负责人、Selector与原分桶。页面增加目标模式，无新表/API/权限/框架。详见iqc-pipeline-rollout-tasks.md；本批编译/构建，真实SQL/角色/并发/worker/browser待B验证，事件灰度及整体I1–I7仍继续。

### 2026-10-23 业务事件API/MQ灰度

显式触发灰度请求复用原t身份/接收事务/冲突与工作器，首次接收冻结分配，等待和重投保持原策略；MQ允许集读取统一目标ID，当前授权加原rollout:task:create。新事件来源guard绑定原接收/时刻/选择/输入，并检查已知t任务缺失灰度标记，旧固定协议保留，无新表/API/权限/框架。仅编译/构建，真实API/队列/SQL/worker/角色联合待B验证；归档订阅灰度及其它I1–I7继续。

### 2026-10-23 归档订阅灰度增量

新增独立订阅灰度schema及发布快照，发布冻结路由意图，每个事件在原订阅事务与接收链路首次分配，冻结后不重选；保留原归档事件身份、精确Selector、组织范围和当前负责人授权，无新表/端点/权限/框架。页面增加灰度目标模式，固定版本模式保留。编译构建结果以协作记录为准，实际发现/回滚/并发/撤权/物化worker及浏览器验证未完成；其余适用I1–I7继续。

### 2026-10-23 生产灰度观测统计增量

原业务统计增加按不可变灰度修订/侧别分组的公开来源投影，复用原证据guard、显式任务/EID/组织范围、评分/项目/人工修改率及缺失分母；页面沿原统计入口展示。不同修订和普通任务不混合，无新表/API/权限。当前是生产观测归属，不替代同Dataset Benchmark受控配对，费用/时延关联和完整统计验收继续。编译/构建终态以协作记录为准，真实SQL/worker/角色/browser专项待独立验证，全适用I1–I7未完成。

### 2026-10-23 观测费用/调用耗时增量

业务统计可选includeModelUsage，当前report/view及model-calls/view授权后，按最终结果精确任务/EID/会话集合复用调用账本与币种费用汇总，增加确认调用接收至结算平均耗时/测量分母。默认旧报告不查调用，缺结果/EID不扩大历史，无新表/API/权限/框架。费用未知/无调用与零成本区分，继承结果历史总成本和端到端延迟/统计显著性等不据此宣称完成；真实SQL/权限/浏览器专项及全适用I1–I7继续。

### 2026-10-23 归档灰度父修订来源收口

原灰度guard复用订阅发布纯读取器，核对Accepted对应不可变父版本、原负责人/目标/Selector及配置、事件派生ID/输入/原有效区间与实际任务名称/并发；不读取当前订阅替代历史。生产DI增加既有订阅版本Mapper，无表/API/UI/权限/框架变化。当前仅后端编译，真实worker/SQL/来源篡改与独立组织授权验收继续，未据此宣布I7或全PRD完成。

### 2026-10-24 Prompt精确独立来源

复用原Skill指令资产独立版本/启停/回滚，Agent 3.0可选promptAsset明确ID/版本/正文Hash，与实际systemPrompt共同冻结。UI复用原历史端点，PROMPT Benchmark接同一变量与来源校验；原内嵌Prompt/JSON/构造保持。无新表/API/权限/框架，当前实现和编译构建结果见协作记录；真实SQL/角色/发布任务/Benchmark及KnowledgeBase独立映射、校准与其余适用I1–I7继续。

### 2026-10-24 Knowledge语料版本基础（KB1）

已建立独立参考语料协议与知识库草稿/不可变版本领域服务，复用原MySQL/BasePo/Mapper/IqcDataScope/摘要/CAS发布，新增V1.1.75唯一领域持久化模型，见iqc-knowledge-corpus.md及事前复用ADR。当前只有内部服务，未新增未配套授权的HTTP接口；KB2管理/敏感投影/资源注册及精确引用、KB3任务授权正文冻结与有界LLM上下文仍待实施。共享Agent目录只存引用/Hash，不能嵌入私有语料绕过组织范围。根任务/完整适用I1–I7继续，编译不代替真实SQL/权限/worker验收。

### 2026-10-24 Knowledge管理入口

KB2管理端点与原ResultReadProjection敏感投影、组织资源脚本/角色矩阵、知识库文档表单页面及菜单已编码。元数据列表/回执不返回正文，独立详情/版本当前授权后按既有三敏感grant投影，历史元数据直接排除大正文；写后独立重读和撤权/旧响应清理。迁移脚本未执行，编译构建终态见协作记录；真实HTTP/SQL/角色/browser专项待，Agent/Pipeline引用和任务冻结/运行上下文及完整I1–I7继续。

### 知识库模型上下文接入（2026-10-24）

KB3原任务授权正文冻结、负责人/组交集/引用全集/原版本摘要校验已接入保存与执行边界；两种真实模型适配器现沿类型化ModelCallContext消费当前节点的精确版本参考资料，来源信息进入参考消息，调用元数据JSON不含正文。256KiB参考JSON上限、缺少原Gateway或未核验上下文均在模型I/O前拒绝，旧七参数调用与无引用消息保持。具体约束见iqc-knowledge-corpus.md；实际SQL/撤权/worker/模型Token预算及容量验收仍待补齐，未据此完成KB整链或I1–I7。

### 原始模型节点置信度诊断（2026-10-24）

原报告新增nodeConfidenceMetrics：复用已校验的原评测trace、冻结Gold与原十箱Brier/ECE公式，分别统计LLM/SMALL_MODEL节点的原始Finding置信度。每个节点仅对其声明事实代码的已知Gold计分母，最后尝试恰好一个已知且类型/来源匹配的有置信度输出才计测量；缺失样本、跳过、无置信度、类型不符、重复输出均计未测量，不填零或挑选高分。没有已知Gold或没有测量时分母/误差为null。旧融合诊断语义保持，旧Report构造器保留。界面提取原置信度卡片共享展示融合与节点数据，无新表/API/权限。

这是原始置信度与完整值正确性的诊断，不是正类概率或校准产物有效性证明；未上报原始置信度的LLM桥接仍显示未测量，不推断1.0。可验证校准拟合/产物来源/运行转换，以及真实模型、worker、权限和容量验收仍待继续，完整I1–I7目标未完成。

### LLM 自报置信度轨迹（已编码）

可选confidence表达当前hit判定正确的自报置信度，仅接受0到1有限JSON数值；缺失/null保持未测量。原detectorMetrics及llmStructuredOutput轨迹保存reportedConfidence，只有单个有效逻辑判断才向节点聚合结果保留；多话术判断合并时不以最大/最小/平均值伪造组合概率。原始LLM节点诊断从唯一末次成功轨迹、严格整数的单调用/单有效输出读取，要求对应事实本身已知且类型/来源匹配；不把该值写入Finding.confidence或改变融合选择。SMALL_MODEL继续沿其原始Finding值统计。

真实模型输出及校准转换仍待验证；统计来源不代表概率经过校准。HTTP与Spring AI统一使用可选字段结构验证，最终编译和协作专项以记录为准。

### 可溯源经验校准候选（2026-10-24）

复用原始节点精确分箱计数，仅SUCCEEDED执行且完整、全部样本成功的评测报告可生成calibrationCandidate：十箱、每箱至少20个已测量样本、经验完整值正确率保留六位；不足或空箱转换返回null，不插值/外推。计数、正确数、方法/事件、原任务/执行、数据集/流水线/节点定义与已校验样本Gold/实际产物来源摘要共同形成内容Hash。没有测量的节点不生成候选。旧Node/报告构造器及无候选显示保持兼容。

候选和纯转换函数已编码，但未发布、未进入融合/条件决策，不能仅设置confidenceCalibrationId就声称该候选已经生效；拟合内统计不代表留出集改善。后续继续原来源授权、引用版本冻结/来源范围交集、节点与实际模型绑定、独立验证与发布执行链路。ADR 20261024-confidence-calibration-evidence记录复用与取舍；无新表/API/基础设施，整体目标仍未完成。

### 校准精确引用与原事务来源读取

新增四字段Reference(taskId,executionId,nodeId,contentHash)，不接收客户端系数。原PipelineEvaluationService提供MANDATORY单条/批量读取：最多200个不同目标节点，按原来源任务ID加锁，使用锁定行检查已认证负责人/owner-group-all范围；核验原SUCCEEDED执行及完整成功报告、精确候选来源与Hash。冻结检测图须与来源一致，Fusion/评分策略不纳入检测图对比，避免校准启用时因融合方法变化无法复用原拟合源。同一任务执行报告在一批内只计算一次，结果列表不可变。

这是内部任务冻结前置能力，没有新增HTTP接口，也没有修改FusionPolicy或让候选自动参与排名。调用方仍须固定资源的当前授权、各来源组交集与系数信封冻结，以及独立验证和运行转换；当前未把本步当作校准整链完成。

### 校准引用与任务私有系数信封

FusionPolicy可选精确引用只保存四字段来源，不保存系数；在原保存边界/定时创建路径，经当前evaluation:report:view与原来源任务范围检查，批量获取原产物并冻结独立calibrationInputs（1MiB上限），任务组与KB和全部评测来源取交集。纯校验绑定负责人、组上界、检测图、引用全集和节点/候选Hash，严格拒绝未知/重复/尾随及浮点计数截断。旧构造器、空引用JSON和旧无引用任务路径保留；当前运行转换未接入，新引用任务在执行边界明确拒绝，不以原始值冒充校准值。

组合冻结同时修复KnowledgeTaskSnapshotService的普通浮点树解析：复用原v1/v2协议分派解析器，使带KB引用的v2任务保持精确数字，并保留重复键/尾随拒绝；不修改全局Mapper或框架。编译结果不是数值/事务/运行专项完成证明，后续由固定提交协作验证。当前仍未完成校准发布、独立验证和运行应用，也未开启默认v2或部署。

### 校准节点运行转换与原轨迹复核

LLM/SMALL_MODEL节点在结果进入upstream和融合前按原任务冻结产物转换置信度，保持事实值、证据、来源、状态和原变量不变。iqcConfidenceCalibration轨迹记录原始值、转换值、产物摘要和supported；未映射或未知状态保持null，不填0或默认1。分支可读取upstream事实confidence，或supported判断缺乏分箱支持，未新增输出变量或改变原输出Schema。

运行后、生产/评测持久化与重读、业务/事实复核原轨迹入口重算转换，绑定节点定义/类型/版本、尝试、事实ID及原调用值，并核对run.findings与成功trace。小模型诊断读取转换证明中的原始值，LLM沿原逻辑调用统计，不对校准后的值再次拟合。旧无引用保留原始路径；合法冻结信封的阶段性执行拒绝已解除。

此为运行链路编码，不代表独立留出集改善、校准发布准入、实际模型/部署绑定或运行容量验收完成。源数据/真实worker/模型及回放专项待固定提交协作验证，完整I1–I7目标继续，未部署。

### 2026-10-25 校准当前状态更新

此前按阶段记述的“尚未运行转换/引用执行拒绝”已由后续实现替代：69559c1运行转换、e272f75配对诊断、34e1d8f发布验证样本/会话隔离、046a8bc校准测量门禁及UI569083a均已固定。校准引用不是自动发布：普通发布逐节点、逐质量/回归验证集检查显式门槛；拟合/验证重叠或引用不符直接拒绝。5cc8cf8修复Provider原始JSON小数解析，当前继续补节点指标聚合/重读精度。各阶段实际专项范围以根协作台账为准，不把编译或受控桥接结果扩展为SQL/PDP/远端供应商与正式发布证明。

后续已编码模型/部署来源与校准适用性绑定、精确信号聚合重读、校准条件级联及成本/Token对照，详见下方当前状态。仍未完成：供应商不可变部署真实性、极近边界与真实worker组合、隔离和门禁真实事务专项、容量/运行SLO与本文件其它适用I1–I7验收。处理后会话输入、语音处理排除、单租户组织权限及默认v1边界不变，未部署。


### 2026-10-25 当前补缺与证据边界

- 校准节点 Source 绑定唯一模型 key/部署引用，匿名或混合调用只保留诊断；原 HTTP/Gateway/SmallModel 轨迹记录实际选择身份，运行转换核对同一身份。配置标签不证明供应商实际权重不可变。已有固定编译与 B 专项，真实供应商及完整发布运行验收继续。
- 成本优化沿原依赖/Condition 增加已校准置信度门限：合法支持且达到门限时跳过升级；未知/未测量/低值仍升级，原依赖失败策略保持。原 Benchmark 和业务报告可选展示实际账本费用、调用延迟与三项 Token 独立覆盖；未知不补零、不跨币种估算节省。已有受控桥接/隔离 SQL/组件与部分浏览器证据，不能据此宣称质量不退化或容量达标。
- 自动候选 b9a4e81 已接原动作 v2 的筛选、发现前未命中过滤、冻结来源校验及幂等创建/只读恢复，支持 RUNNING 中已提交会话。UI 79f50b4 已接显式配置，原候选池展示未分类；Gold 沿原人工标注与独立审核。此前“只有来源协议/没有自动生产者”的阶段说明已被该实现替代。B 738ed0e 的 25 项筛选/来源/Gold专项与 b87f204 消费端隔离 SQL 已固定；真实生产者 SQL、发现/claim、并发幂等、当前组织授权及完整反馈回归还需各自终态证据。
- Root 整合后原43000编译终态0，原64159 UI构建终态0；没有 Root 执行测试。新的候选池撤权清理/原因说明仍以本轮后续固定提交及构建终态为准。所有专项范围详见根 IQC_PRD_COLLABORATION.md，不能用原基线通过代替后续变更验证。

处理后会话输入、排除语音处理、单租户组织数据权限继续；未启用默认 v2/动作开关，未执行共享 SQL 或部署。全部适用 I1–I7 尚未完成。
