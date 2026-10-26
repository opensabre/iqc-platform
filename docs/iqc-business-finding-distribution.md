# Finding 运营分布

PRD18.2要求Finding分布。复用原BUSINESS报告的任务/执行轮次、首次或最新去重、冻结标准/灰度来源分组及机器/已完成复核验证，不新增表/API/页面或持久事实副本。原FindingFusion、目录和评测指标没有相同的生产运营汇总；评测准确率不能代替没有Gold的生产频次。

## 来源与读取门禁

新增默认false的includeFindingDistribution。当前report:view、完整会话和PII读取权限在任务/结果IO前由原SensitiveReadAccessService核验：BOOLEAN频次在单来源可还原事实值，因此不能借“聚合”绕过原Finding敏感读取。任务仍沿owner/group/all及原20任务/500会话/50000质检项界限。命令不执行模型、不重算新产物、不采集当前会话正文。

原BusinessItemReviewService机器验证增加内部可选回调，只捕获同次已解析、已复核输入/校准/业务投影的原trace.fusion；最终存储评分一致性通过后才返回机器数据。原方法/默认路径保持，无额外SQL或重复解析。统计只保存代码、类型、枚举计数；不保存/输出任意STRING/ENUM/NUMBER/OBJECT值、证据、正文、原始观测或身份。

## 口径

Report.findingDistributions按原groupKey关联各冻结标准，协议iqc-business-finding-distribution-v1。无流水线Finding协议的业务方案不生成条目，页面明确不适用。不同发布/试跑/灰度来源不混合。

每组独立machine/reviewed两份分布：

- machine使用受验证的原始融合事实。
- reviewed使用最新已完成且通过原来源校验的业务复核后有效事实；有Finding改判时取该有效投影，没有Finding改判则沿用原机器事实。它不代表每个事实都被逐项人工确认。未完成/缺失复核不以机器事实填充。
- expectedResults为本组所选会话位置数；sourceResults为该侧有受验证来源数，unavailableResults为其差。机器侧无结果、复核侧无已完成来源分别保留，不充当阴性。
- 每个声明code/type只计一次融合结果，重复节点同code不扩分母。presentCount为有该事实的来源数，missingFindingCount=sourceResults-presentCount；“无来源”与“有来源但缺事实”不重复合并。
- 五状态KNOWN/UNKNOWN/CONFLICT/ERROR/NOT_EVALUATED分别计数，总和=presentCount。缺失不伪造UNKNOWN，UNKNOWN不伪造FALSE。
- 严重度仅统计KNOWN事实，合计=KNOWN；不把未评测时目录默认严重度当实际风险。事实严重度不是任务风险等级。
- BOOLEAN仅KNOWN且确实布尔值才计trueCount/falseCount，合计=KNOWN。其它类型不返回任意值直方图，真/假不适用。非法/重复/未声明/类型不符的事实拒绝统计，不静默跳过。

频次不是准确率、召回率或金标真值；不会改变原机器结果、复核、评分或输入/结果Hash。计数由原500来源限制，每事实不超过500，按固定声明代码排序，使用有限元数据计数而不持有额外原值集合。

## 页面与兼容

原业务报告增加需三项读取权限的可选项，在每组展示机器与复核后有效事实。有值/未知/冲突/错误/未评测、缺失、BOOL真/假、已知严重度及来源分母分别呈现。新响应对五状态、严重度、真/假守恒、声明两侧对齐及计数范围做校验，缺协议/非法分布明确无法确认，不补0。权限变化清报告、代次和选项，恢复需重新选择/查询，迟到响应隔离。

默认不启用、不增加验证/IO，旧Report五/六参构造、服务/控制器原直接签名与旧API调用参数保持；可选字段NULL省略。原含可选统计响应private,no-store。没有新权限码、租户模型、Framework修改或默认v2/动作开关变更。

## 验证

Root仅test-compile及vue-tsc/Vite，原句柄/终态见根协作记录。B需验证三权限在IO前拒绝、默认无回调/字段省略/旧签名、同标准分组/轮次去重/缺结果缺事实、重复事实拒绝、五状态/BOOL真假/严重度守恒、有效复核改判与沿用原值/未完成不填、方案不适用、来源校准/评分失败不返回统计、任意值/证据/正文无泄漏、旧Hash/输入不变、HTTP/组件权限撤销恢复/迟到/坏协议和实际PDP/SQL/browser组合。完整适用I1–I7未完成，未部署。

Root原65065 test-compile终态0且/tmp/iqc-finding-distribution-compile.exit=0，主/测试编译及diff通过；Root未执行测试。三权限、原验证回调/统计分母、复核沿用语义及SQL/PDP/界面组合仍待B专项。
