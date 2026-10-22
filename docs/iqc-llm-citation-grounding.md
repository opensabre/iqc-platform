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
