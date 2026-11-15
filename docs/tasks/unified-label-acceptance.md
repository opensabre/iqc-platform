# 统一规则标签框架验收核对

2026-11-14。遵循用户“不用考虑兼容方案”：统一任务不新增旧模式适配、缺失快照回退或兼容验收。既有代码分支不因取消兼容要求而批量删除。

| PRD 范围 | 已有实现与证据 | 剩余验收 |
| --- | --- | --- |
| P1 / AC-01、02 | 精确版本绑定、规则类型唯一约束、动作定义和绑定；标签/规则计划及迁移测试；规则停用和删除全局引用保护、并发及 MVC 鉴权链测试 | 规则重新启用语义待确认；真实网关资源权限拒绝、大存量引用扫描耗时 |
| P2 / AC-03～05 | 触发计划、选择器、冻结快照、会话检查点、阶段只追加；调度、执行器、生命周期及管理端快照测试 | 整服务重启后的调度接管（执行器跨 JVM 检查点恢复已验证）、超长会话和海量会话运行 |
| P3 / AC-06～09 | MATCHED 过滤、零调用跳过、单请求批量、严格响应校验；UnifiedTagEngineAdapterTest | 独立 Agent 平台接口尚未提供；真实模型/版本/Token 联调 |
| P4 / AC-10、11 | 完整上下文和绑定动作交给 UnifiedTaskResultProcessor；保持 PENDING_POLICY，不生成分数、不合并冲突；生命周期与动作边界测试 | 扣分、去重和正式标签策略仍待业务确定 |
| P5 / AC-12 | 隔离 MySQL 新装/升级/重复迁移、事务及并发检查；前端构建、规则生命周期浏览器模拟验收 | 真实 HTTP 联调，10/100/500 标签 Token/耗时/吞吐/出错率，发布和回滚演练 |

浏览器记录位于管理端 `docs/qa/rule-lifecycle.md`，使用隔离 API 夹具，不能当作后端端到端验证。后端最近一批规则生命周期验证为 29 项单元测试和 8 项隔离 MySQL 测试通过，细节见 [规则生命周期](unified-rule-lifecycle.md)。各历史批次测试计数不可简单相加为完整测试集。

本次补充 AC-08 的精确 20 标签样例：检查单次请求包含全部标签提示词及会话、仅调用一次提供方、一个阶段输出全部 20 条明确状态。Java 21 下运行 `mvn -q -o -DforkCount=1 -DargLine=-javaagent:/Users/zhoutaoo/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar -Dtest=UnifiedTagEngineAdapterTest test`，最终 9 项通过、零失败。首次断言误用内部 expression 字段，修正为请求契约 prompt 后复跑通过，未修改生产适配器。

P3 继续复用现有内嵌 Spring AI 调用，不虚构外部平台契约。性能测试必须注明模型、样本、并发、过滤比例和统计窗口，提供方未返回的 Token 不用字符数冒充。当前没有真实业务负载数据，不宣称性能收益。

周期任务当前保存创建或重新启用时的授权范围。用户权限撤销后是否逐轮重验仍未确定，见 [P2](unified-label-p2.md)。该业务决策和评分/冲突策略均不由实现自行推断。

后续顺序：真实 HTTP 权限与执行链路联调 → 已确定接口下的 Agent 联调 → 真实性能测量 → 用户验收 → PR 与部署。缺少外部契约或业务策略只阻塞相应部分，不阻止其余已明确功能开发。

## HTTP 业务错误增量

RuleLifecycleWebSecurityTest 使用生产 IqcResourceServerConfiguration、真实 Spring Security 过滤链、控制器、IqcDataScope、规则服务及响应处理器，MockMvc 通过两条真实路由验证匿名 401、认证用户越范围拒绝、管理员引用拒绝和无引用成功，共 8 项。JWT Decoder、规则持久化与引用查询为 Mock，不启动数据库或真实网关，不证明 JWT 签名、网关管理权限或事务并发。事务并发由此前隔离 MySQL 检查覆盖，网关资源权限仍待真实联调。

发现并修复两个 IQC 层问题：领域异常的具体原因被默认处理器丢弃，改由 IQC 自有异常处理器按既有 Result 协议返回；规则删除显式返回 Result.success，避免共享响应器将 void 空值序列化为 JSON 字符串。未修改框架或基础服务。

真实业务错误是 HTTP 200、非成功 code、mesg 且省略 null data。管理端响应拦截器原先漏识别这类错误；已修复并加入 4 项真实拦截器测试，API 回归 25 项和生产构建通过。浏览器在新生产构建上复验两种引用拒绝（无错误成功提示）、成功停用和成功删除，全部使用 API 夹具。

Java 21 使用前述 Maven/Mockito 参数，运行 `-Dtest=RuleLifecycleWebSecurityTest,IqcExceptionHandlerAdviceTest,QualityConfigControllerPermissionTest test`：8+3+1 共 12 项通过。初次测试编译修正泛型重载和 Jackson 版本；首次运行发现 void 响应问题，修正应用端后最终复跑通过。

## P3 可观测性增量（2026-11-15）

SpringAiLlmQualityProvider 保留提供方实际返回的模型名称、输入/输出 Token 和尝试次数；框架 EmptyUsage、空计数及负计数不作为有效 Token 写入，也不将缺失模型名替换成配置名冒充提供方报告。UnifiedTagEngineAdapter 将这些数据复制至阶段 `metadata.provider`，与框架自己的过滤数量、是否调用和阶段耗时分开；完整提供方响应仍保留在 providerResponse。复用已有阶段原始输出存储，不新增表或 API。

管理端引擎记录显示提供方模型及输入/输出 Token；调用发生但提供方未报告时显示“未知”，真实零值仍显示 0；跳过阶段仍显示未调用。统计是最后一次成功返回的提供方用量，不包含失败重试未报告的消耗；阶段 elapsedMs 包括本阶段等待和重试，不能据此推导模型服务吞吐。

Java 21 运行前述 Maven/Mockito 参数及 `-Dtest=SpringAiLlmQualityProviderTest,UnifiedTagEngineAdapterTest test`，8+9 共 17 项通过。管理端 `pnpm test -- src/components/TaskWizardResults.test.ts`，10 项通过，其中覆盖实际计数、真实零值、缺失用量及提供方模型显示；`pnpm build` 类型检查和生产构建通过，保留既有包体大小提示。测试中的 123/45 是 Mock 数据，不是实际模型性能或 Token 节约证据。真实模型、数据集和联调环境仍需指定后验收。

## 执行详情计数与 AC-04 核对（2026-11-15）

管理端按各统一引擎的原始 observations 分别显示命中、未命中、错误、未知和跳过数量，满足 PRD 10.4 的阶段统计要求。只统计已记录的逐标签状态，不把未绑定规则、上游命中过滤或阶段整体失败自行转换成标签状态。相反结果在不同阶段各自保留；没有新增结果合并策略、表或接口。

`pnpm test -- src/components/TaskWizardResults.test.ts` 11 项通过，包含同一标签跨引擎相反结论及五类状态计数。`pnpm build` 类型检查与生产构建通过，仍有既有包体提示。这里只验证展示，不宣称已通过真实服务端到端验收。

AC-04 补充跨计划冻结与真实正则适配器的样例：选定三个标签，两个复用同一正则规则，第三个仅绑定 DLS。完整快照保留三个标签，但唯一 REGEX 阶段只有两个适用标签及两个结果，第三个标签不生成 NOT_MATCHED。

Java 21 使用前述 Maven/Mockito 参数运行 `-Dtest=UnifiedLabelPlanServiceTest test`，最终 10 项全部通过。解析标签、规则版本读取仍为 Mock，冻结计划和正则匹配为真实实现；该用例不替代真实数据库和已部署任务链路验证。

## 规则重新启用缺口（2026-11-15）

PRD 10.1 要求启停。当前已有停用，但没有重新启用路由；QualityRuleService 的提交、发布、拒绝和修订均明确拒绝 DISABLED。不能将这些拒绝移除来代替启用，否则会弱化生命周期与并发保护。

需明确重新启用是新建草稿重新审批，还是恢复此前已发布版本并保留原审批。当前只有规则 DISABLED 状态及版本历史，不能无歧义还原停用前的在审/草稿/生效组合。已向用户提出这两个选项，未收到决定前不新增状态回退或默认批准行为。该缺口与扣分、冲突策略独立，也不阻止会话失败隔离等验收继续进行。

## AC-12 失败会话隔离（2026-11-15）

新增同任务、同次执行的双会话样例，复用真实 MultiEngineExecutor、UnifiedTagEngineAdapter 和 H2 EngineResultSetStore。第一条会话的 Mock 提供方抛出异常，检查点保存 FAILED / LLM_CALL_FAILED / ERROR；随后第二条会话正常匹配，保存 SUCCEEDED / MATCHED、独立消息身份、规则版本及提供方模型元数据。数据库保存两份可分别查询的结果，两次模型请求互不串用输入或结果。

Java 21 使用前述 Maven/Mockito 参数运行 `-Dtest=UnifiedTaskLifecycleTest test`，2 项全部通过。另一项原有用例同时复验运行时检查点、待处置结果、恢复和轮次隔离。新增用例为顺序双会话，不代表并行负载、真实模型或实际进程中断验收；没有修改执行或结果处置策略。

## 集中回归（2026-11-15）

管理端修正两处仍读取旧任务向导 rules 参数的测试，改为验证当前统一向导的已发布 Agent 及标签配置传递、加载失败隔离和显式重试，不恢复旧接口。最终 `pnpm test`：42 个文件、233 项全部通过。

后端 Java 21 使用前述 Mockito 参数执行默认 `mvn -q -o -DforkCount=1 -DargLine=... test`。首次沙箱运行因本地端口禁止而无法完成 HTTP 集成测试；允许本地测试端口后完整运行 771 项，769 项通过，两项生命周期事务测试因 H2 夹具缺少 iqc_inspection_scheme_version 表失败。补齐夹具，复用原有 createIsolatedSchemeVersionTable；生产代码和正式迁移不变。定向运行 `-Dtest=JointLabelMaterializationTransactionTest#ruleLifecycleChecksHistoricalReferencesThenAtomicallyRemovesUnreferencedVersions+actualRuleDisableSerializesWithNewTaskReference`，两项通过、零失败。修正后未再重跑整个后端测试集，不将分批结果描述为一次完整全绿运行。

默认 Surefire 测试不包含 IsolatedMysql*IT；本轮不替代先前隔离 MySQL 迁移和并发验证，也不证明真实 Agent、网关联调、进程中断恢复或性能验收。未推送、创建 PR 或部署。

## P2 执行器跨 JVM 检查点恢复（2026-11-15）

EngineCheckpointProcessRecoveryTest 在 JUnit 临时目录创建 H2 文件数据库，使用正式 V1.1.33 检查点 DDL、真实 EngineResultSetStore、MultiEngineExecutor、统一 REGEX 与 LLM 适配器。第一子 JVM 执行正则，保存结果后 Runtime.halt(86)，绕过关闭钩子；第二个全新 JVM 从同一数据库恢复。断言已提交阶段逐字段不变、冻结会话输入不变、正则不重跑，LLM 只收到尚未命中的标签 20，调用文件最终仅 REGEX/LLM 各一次。两个子进程均有超时和清理，数据库不连接共享实例。

Java 21 使用前述 Maven/Mockito 参数及 `-Dtest=EngineCheckpointProcessRecoveryTest test`：1 项通过、零失败。模型响应是测试替身，不代表实际模型用量；测试主动在检查点提交之后中断，不覆盖外部模型已返回但检查点未提交时的重复请求，也不声称外部调用恰好一次。该验证覆盖执行器与持久化层的真实进程边界，整服务重启后的调度接管、真实 MySQL 崩溃恢复及发布环境验收仍未完成。没有修改生产重试或结果处置策略。

## 发布与恢复操作说明（2026-11-15）

已在既有 [部署文档](../deployment.md) 补充统一任务的迁移顺序、受控投递、权限及场景验证、进程超时后的人工重试、周期新轮次区别、停止投递和备份回滚边界，以及真实性能记录字段。按当前源码核对：停用定义也会拒绝手动 run；一次性定义已经触发后不能重新启用，因此不能将“停用后直接重试”写成可执行恢复步骤。新 retry attempt 只继承统一阶段的成功/跳过连续前缀，不声称失败阶段恰好调用一次。文档步骤尚未在部署环境执行，也没有增加旧版本翻译器或自动重试策略。
