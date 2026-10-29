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

## B 独立报告专项（2026-10-26）

固定同步bfaa7f7。原94663 test-compile surefire:test终态0，/tmp/iqc-b-finding-distribution.log.exit=0；fresh XML BusinessFindingDistributionTest11 + BusinessQualityReportServiceTest27 + BusinessItemReviewServiceTest23，共61项零失败错误跳过。

新增11项使用真实报告聚合、真实冻结Pipeline发布解析与受控validatedMachineData/当前权限：三grant在任何任务/结果读取前拒绝；默认老调用无敏感授权/数据回调且NULL字段省略；重复声明按code去重；五状态及KNOWN严重度/BOOL真假守恒；缺结果/有来源但缺事实分开；无完整复核不填，已完成无Finding改判沿用机器事实；重复/未声明/错类型/非法BOOL拒绝；原值/证据/正文/解释/来源选中ID不出JSON。

机器来源验证在新类中受控，本轮原复核23项仅既有回归，不足以证明新增回调与真实SQL来源完整组合；Finding改判有效投影、HTTP、实际PDP与浏览器整链继续待验。未改生产代码或默认开关，不将61项外推完整PRD完成。

## B 正式worker/真实SQL/复核/HTTP组合（2026-10-26）

复用IsolatedMysqlMeasuredPublicationIT主集成方法：真实目录/数据集/门禁发布、已处理会话冻结、正式worker落库、动作创建复核及业务裁决、Gold独立审核/回流保持。新增真实Spring代理报告与原BusinessItemReviewService/mapper，无mock机器来源解析或报告聚合。在复核前后验机器NUMBER事实/KNOWN严重度、无复核不填、已完成项目改判但无Finding改判时沿用机器事实；分布无任意数值/证据/正文。SQL临时篡改business_item_results_json后原机器校验拒绝返回报告，finally复原。原结果trace/评分/项目JSON保持，默认报告不出Finding字段。相同SQL结果经真实MVC控制器返回一致JSON，Cache-Control private,no-store。

首轮28983终态1为新增断言把内存DecimalNode与HTTP DoubleNode直接比较（80与80.0）；仅将预期也经JSON序列化/解析，未修改生产语义。原40519重跑terminal0，/tmp/iqc-b-finding-source-sql-final.log.exit=0，fresh XML主集成1项零失败错误跳过52.935s，专用34307/datadir身份核验，结束socket不存在。

本轮只执行主集成方法，不声称该类另一模型方法已重跑；scope/当前grant/资产validator仍受控。这里只验项目改判后有效事实沿用，尚不能证明Finding值改判组合或真实PDP/browser。完整I1–I7继续，未部署。

## B 人工事实改判真实SQL补充（2026-10-26）

原主集成完成候选/独立Gold/回流后，经原findingContext/request/decide追加两轮事实裁决：KNOWN数值由1000改为0且严重度HIGH→NONE，下一轮设UNKNOWN。读取原冻结上下文的expectedFindingHash与原证据，不mock投影或结果。待处理新轮期间报告仍取最新完成来源；相同完成命令重放返回原裁决；KNOWN改判后machine仍HIGH、reviewed为NONE，reviewChanges记录findingChanged；UNKNOWN来源仍可用但KNOWN与全部knownSeverityCounts为0，BOOL计数保持0。原机器trace/评分与之前冻结候选的内容/来源JSON均未改变。

原51016 terminal0，/tmp/iqc-b-finding-override-sql.log.exit=0，fresh XML原主集成方法1项零失败错误跳过；隔离MySQL脚本身份核验后运行、结束关闭，无共享库。完整耗时以本轮XML为准。此项扩展原SQL/HTTP整链，不将1方法冒称全类重跑；scope/grant仍受控，实际PDP/五角色/浏览器与容量尚未证明。未改生产实现、未部署。
