# 模型引用真实性：过滤前观测

## 口径与缺口

模型层“幻觉”不能直接用未标注Finding、错误率或过滤后合法证据冒充。当前标签Evidence过滤会跳过错误引用；若只统计过滤后的引用会遗漏分母。本增量在FrozenDetectorEngine收到适配输出、进入错误返回/引用过滤前记录明确引用声明，仅提供“引用真实性”来源，不宣称广义事实幻觉指标已完成。

## 协议

节点metrics.llmCitationGrounding：schemaVersion `iqc-llm-citation-grounding-v1`、scope `RAW_EXPLICIT_CONVERSATION_QUOTE_CLAIMS`。

- outputs：桥接的逻辑输出记录数；不是供应商所有物理尝试数。
- recognizedOutputs / unmeasuredOutputs：明确可处理的引用协议与未知输出记录。
- claimedQuotes / invalidQuotes：引用声明次数与未通过来源匹配的次数。
- malformedContainers：已知协议却缺少合法facts/evidence/quotes数组的容器数；无法猜出其中有多少引用，不伪造引用分母。
- state：MEASURED / PARTIAL / UNMEASURED。空引用数组可以是已识别输出，但引用错误率分母为零时只能null，不能填0。

只读指定协议位置：iqc-stage-observations-v1/iqc-rule-observations-v2的finding；iqc-label-facts-v2的facts.evidence；iqc-item-candidates-v1的quotes；既有hit/reason判断中显式evidence数组。不递归搜索任意JSON，不把单纯hit/reason判断当已经测量的引用。

## 共享规则与业务边界

复用LegacyFindingAdapter.citationGrounded判定messageId及逐字引用包含，保留原兼容text/matchedText语义，不改匹配行为。明确声明的角色/会话身份也要匹配实际传入节点的冻结消息；标签事实要求其subjectRole一致。重复ID输入与原适配一样取首条，来源对象不修改。

诊断先于ERROR返回且随NodeResult失败metrics保留；观测无法解析或序列化时缺测，不把观测异常变成业务判定。只保存计数，不增加原文/PII/密钥落盘，不改NodeResult状态、评分、标签或Action。先前provider已经丢失的原始失败响应无法回填，未知协议仍未测量。

引用合法不证明事实判断正确；逐字不匹配、未知消息或身份错误也不自动证明模型有意编造。本批不新增API、表、发布阈值或运行器，不修改B正在处理的标签评测指标。

## 后续与验证

需接入原评测报告显示可测覆盖率、引用错误率及坏容器/缺测，独立列出语义金标准确率。当前仅采集来源及编译，没有新增/运行测试。B待验证原适配行为不变、合法/未知ID/非原文/空/坏容器/角色会话错误、未知协议、错误节点仍保留指标、源字节不变及真实provider→SQL路径。完整“幻觉”需求与整体I1–I7未因此完成。

## 原评测报告聚合（2026-10-23）

`PipelineEvaluationService.Report.citationGroundingMetrics`复用原GET /report及report:view，schema为`iqc-citation-grounding-report-v1`，scope为`LAST_TRACE_PER_FROZEN_SAMPLE_LLM_NODE`。只读经validatedRow校验的持久化产物，保留原重试来源选择；不调用模型或业务动作。

- expectedNodeSamples = 冻结Gold样本数 × 冻结LLM节点数，包括尚无产物的样本。每样本每LLM节点仅取run.trace最后记录，不把内部重试重复计数。
- observedNodeSamples：最后记录存在且attempt>0、非SKIPPED的样本节点，包括执行失败。
- measuredNodeSamples / partialNodeSamples：来源协议分别为MEASURED / PARTIAL且通过协议校验的样本节点。
- unmeasuredNodeSamples = expected - measured - partial，包含缺失、跳过、旧产物、未知输出及无效诊断。invalidDiagnosticNodeSamples是已存在但协议/整数计数/状态关系不合法的诊断子集。
- measurementCoverage = measured / expected；无LLM节点或零样本时为null。部分测量不冒充完整覆盖。
- 输出/引用/坏容器计数只累计合法来源诊断。observedQuoteErrorRate = invalidQuotes / claimedQuotes；零引用为null。该比率允许在部分测量时展示，但名称和UI明确它只覆盖已观测引用，不能外推至未知输出/所有模型请求。

新增字段为可选且null时省略；原15参数Report构造保留，历史JSON缺字段可读。新生成报告与ReportEvidence摘要将包含该诊断，历史存储的ReportEvidence和报告摘要不回写。发布门禁条件未新增或放宽，既有完整性、金标与回归判定不变。

UI原报告页引用卡片展示覆盖、部分/未测量、已观测错误率、坏容器与无效诊断；沿用报告现有加载/清理/授权流程，不增加异步请求或原文。当前证据为编译/构建，尚无本批SQL/HTTP/浏览器运行证明。B后续应验证旧Report构造/JSON、零LLM/零引用、缺产物、SKIPPED、最后重试轨迹、未知/部分/无效协议、继承源、错误节点保留以及新旧门禁报告摘要兼容。
