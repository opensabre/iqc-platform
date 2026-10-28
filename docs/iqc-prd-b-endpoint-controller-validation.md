# 端点权限拆分的 MVC 注册与当前权限前置验证（2026-10-28）

B 在风险证据固定提交 `3c988df` 后合入 A 固定 `258138c`，包含模型/Skill `59a7bf7`、任务 `1f024c7`、结果 `258138c` 及风险归因 `863649b`。没有读取 A 的未提交文件。仅新增专项测试和修正三份旧测试所期望的资源码，不修改生产代码。

原进程 18522 退出 0，`/tmp/iqc-b-endpoint-controller-20261028.log.exit` 为 0。离线 `test-compile surefire:test`，ByteBuddy 显式 javaagent；共 72 项，零失败、错误、跳过：

| 测试 | 项数 |
| --- | ---: |
| EndpointPermissionSplitTest | 26 |
| InspectionTaskControllerTest | 3 |
| InspectionResultControllerTest | 3 |
| InspectionResultSensitiveControllerTest | 1 |
| RiskPolicyEvidenceTest | 6 |
| BusinessItemReviewServiceTest | 23 |
| BusinessItemReviewEvaluatorTest | 10 |

命令还列出了不存在的 PipelineDecisionEvaluatorTest，未产生报告，未计入上述 72 项；不以 Maven 的组合退出成功声称该类通过。

新增专项使用五个真实 Controller 实例、实际 standalone MVC RequestMappingHandlerMapping 和框架 MappingInfoHandler，逐项检查七个原能力及 22 个拆分能力：每个 code 只有一个注册记录、URL/HTTP 方法与独立清单一致、declaredPermission 为真。没有只扫描 Java 字符串来推断运行时注册。

25 个具有当前权限前置的 Skill、任务、结果及反馈入口分别直接调用真实 Controller 方法；受控 SensitiveReadAccessService.current 拒绝所有集合，随后精确核对请求的是该入口自己的新 code，所有 owning service/usage mock 均无交互。此方式能捕获误用旧 view/manage/execute code，但没有模拟完整网关或组织授权响应。模型四个入口在本次只验证注册，未证明网关拒绝后业务不可达。

旧测试按实际接口职责分别使用 task:executions:view、result:hierarchy:view、result:conversation-history:view，业务返回/投影断言保持。既有业务结果 MVC 两场景及空 hierarchy 返回契约同轮回归通过。

证明范围：MVC 注册和受控当前授权失败时的调用边界。未证明登录令牌/PDP/Feign/网关、真实角色、成功写操作授权、审计切面或全部 IQC Controller 资源唯一性；其它重复资源码仍按正式权限审查文档推进。风险纯领域/复核回归通过不替代风险归因完整 SQL/生产 trace 验证。完整适用 I1–I7 保持进行中，无推送/部署/共享 SQL/框架修改。

## 会话与资产历史扩展

固定基线 `be37ff5`（合入 A `e1142fd`），扩展原专项至 11 个真实 Controller、45 项独立契约。新增已处理会话查询/导入、MCP 创建修改启停、方案草稿/历史、总览及基础状态；沿用实际 MVC 与框架 MappingInfoHandler，每个 code 精确一个 URL/HTTP 方法。Dashboard 仅注册，依赖未调用。

原进程 18580 终态 0，`/tmp/iqc-b-45-mvc-20261028.log.exit` 为 0，离线 `test-compile surefire:test`，fresh EndpointPermissionSplitTest 共 31 项、零失败错误跳过：注册 1 项检查45契约，当前授权拒绝30项（原25项加会话3项、方案草稿/历史2项）。拒绝时核对精确自身资源码，业务服务及usage无交互。

新增导入5项、MCP4项、bootstrap和dashboard各1项仅验证注册，未把它们宣称为当前权限前置拒绝证据。模型4项仍仅注册。受控 SensitiveReadAccessService 不证明真实当前角色撤销、网关/PDP或成功写授权。标签域下一批固定拆分尚未合入，完整适用I1–I7仍进行中。
