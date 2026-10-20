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
| 19 功能/数据/敏感权限 / I7 | IqcDataScope、SensitiveReadAccessService、ResultReadProjection及组织迁移 | 多项会话/任务/样本/评测/Selector等投影已有；Pipeline草稿/历史/目录/命令回执及Skill指令/Schema/历史/回执已接原敏感投影，独立验证待；Agent配置/历史/比较/回执与独立能力元数据已编码，效果统计补可见任务范围；Scheme/Rule/Model配置与人工复核等剩余敏感副本仍须收口，真实四角色/网关验收待做。 |
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
