# 统一规则标签框架验收核对

2026-11-14。遵循用户“不用考虑兼容方案”：统一任务不新增旧模式适配、缺失快照回退或兼容验收。既有代码分支不因取消兼容要求而批量删除。

| PRD 范围 | 已有实现与证据 | 剩余验收 |
| --- | --- | --- |
| P1 / AC-01、02 | 精确版本绑定、规则类型唯一约束、动作定义和绑定；标签/规则计划及迁移测试；规则停用和删除全局引用保护、并发及 MVC 鉴权链测试 | 真实网关资源权限拒绝、大存量引用扫描耗时 |
| P2 / AC-03～05 | 触发计划、选择器、冻结快照、会话检查点、阶段只追加；调度、执行器、生命周期及管理端快照测试 | 实际进程中断恢复、超长会话和海量会话运行 |
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
