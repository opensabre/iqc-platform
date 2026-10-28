# PRD 五类职责与组织授权实施审查

2026-10-28，源码基线 beff8bd。单租户，数据范围沿用 owner/group/all；本文是实施缺口与验收入口，不是授权已验收证明。

## 复用的能力

正式 PRD 第3节定义五类职责。组织服务已有用户—角色、角色—菜单、角色—资源关系；无需增加岗位权限库或租户模型。菜单/按钮、URL+方法授权、数据范围、敏感内容读取分别验收。

| 职责 | 现有能力与入口 | 必须证明的边界 |
| --- | --- | --- |
| 质检管理员 | InspectionSchemeController 的方案维护/发布/使用，PipelineAssetController 的任务与发布资源 | 管理权限不自动获得全组织数据；例外发布另需 force:publish 资源及可信管理员身份，不能用 all 读取范围代替 |
| 业务管理员 | QualityOperationsController 的 iqc:report:view、RiskCenterController 的 iqc:risk:center:view、业务结果分页 | 同组/本人及明确 all 范围；读取团队质量不能默认获得配置、裁决、模型密钥或完整会话 |
| 质检员 | QualityOperationsController 的 review:create/view/decide 及独立 label 复核资源 | 指定复核职责必须实际可裁决；二审/仲裁按原规则，原AI不变，证据读取仍走敏感授权 |
| 模型/算法工程师 | IqcModelProfileController、IqcSkillController、EvaluationDatasetController、PipelineEvaluationController、BenchmarkController；原资产内 Prompt 版本 | 模型/Skill 管理、数据集冻结、精确评测与 Benchmark 分别授权；不因工程师身份绕过数据来源范围或发布门禁 |
| 系统管理员 | 原组织/RBAC、模型连接与预算、平台审计 | 组织管理员身份与 IQC 内可信管理员不是同一布尔量；GatewayCallService 和强制发布核对原可信角色。按实际职责组合现有角色资源，不为通过验收扩大 canViewAll |

历史 V20260829_03 只有 IQC_QUALITY_MANAGER、IQC_INSPECTOR、IQC_VIEWER，另有原 IQC_ADMIN；不能用这四个种子证明五类职责，不能把 VIEWER 当工程师。新业务角色可由组织原配置能力表达；先查实际环境映射，不创建账号或自动授予全局 ADMIN。

## 已确认的端点注册缺口

组织源码 ResourceRegistrationService.register 按声明 code 查找单个资源；ResourceMapper.upsertRegistration 在唯一键冲突时更新 url/method。一个 code 不能表达多个不同端点。正式组织文档在方案权限章节已明确这一约束。

当前两个 Controller 仍有重复：

| 当前 code | 冲突端点（前缀均 /api/iqc/agent-assets） | 原能力内的最小修正 |
| --- | --- | --- |
| iqc:model:manage | POST /models；PUT /models/{id}；POST /models/{id}/enable；POST /models/{id}/disable | 保留 manage 给创建，分别引入 model:update/enable/disable |
| iqc:skill:manage | POST /skills；PUT /skills/{id}；POST /skills/{id}/enable；POST /skills/{id}/disable；POST /skills/{id}/versions/{versionNo}/rollback | 保留 manage 给创建，其余分别用 skill:update/enable/disable/rollback |
| iqc:skill:view | GET /skills；GET /skills/{id}/versions | 保留 view 给列表，历史版本用 skill:versions:view |

此为静态源码与实际注册 SQL 共同证明的缺口；尚未读取共享环境资源行，不能断言线上当前保留了哪一个冲突端点。不能通过修改 framework 或关闭资源鉴权解决。

## 兼容实施要求

1. 修改 owning IQC Controller 的独立资源码。Skill 的敏感读取前置检查也必须使用同一端点新码；按钮仍可沿原 manage/view 表达操作组，不能把按钮权限作为后端授权替代。
2. 在组织 owning repository 的原 Flyway DML 中做精确资源种子与旧授权迁移，使用原表和自然键去重；保留已有注册资源 ID。旧 manage/view 资源回到上述稳定端点；新资源绑定其唯一方法与 URL。
3. 升级仅从原资源已有角色关系复制对应能力到拆分资源；不得向所有角色、用户或系统 ADMIN 泛授。新环境沿原初始化与既有角色种子策略补齐，同步正式组织文档；不修改历史已执行迁移。
4. 与原资源状态策略一致：未激活/未拥有旧能力不能因迁移获得已激活新能力。拆分前后差异必须在真实注册快照与 SQL 权限专项中验证，不以单纯编译认定兼容。
5. 协作方在原在途验证终态后，负责定向 HTTP/敏感读取及隔离 SQL：全部拆分端点各有唯一 code、旧 ID/关系保留、重复执行、新库与升级、原允许/拒绝、撤权后拒绝；随后接真实网关/PDP 与五职责登录验收。Root 不写或运行测试。

已按上述清单拆分两个 Controller，Skill 敏感读取同码；组织权限工作树配套 V20261028_01 迁移。已有资源状态不覆盖，缺失原能力的新环境创建 PENDING 种子，沿原组织流程激活和授权；若新代码先注册出 PENDING 拆分资源，也须显式审批，不自动激活。代码和迁移仍待独立验证，不将编码视作授权验收。现有 PR 保持草稿，不执行共享 SQL，不变更 framework，不宣称五职责完成。

## 来源

- 工作区 IQC智能质检平台_PRD_V1.0.md 第3、19、23节。
- base-organization/docs/modules/organization-and-access.md。
- base-organization/src/main/java/io/github/opensabre/organization/service/impl/ResourceRegistrationService.java。
- base-organization/src/main/java/io/github/opensabre/organization/dao/ResourceMapper.java。
- base-organization/src/main/resources/db/migration/mysql/history/V20260829_03__add_iqc_business_roles.sql。
- 本仓库 src/main/java/io/github/opensabre/iqc/rest/{IqcModelProfileController,IqcSkillController,QualityOperationsController,PipelineAssetController,EvaluationDatasetController,PipelineEvaluationController,BenchmarkController}.java。
- 本仓库 shared/IqcDataScope.java、privacy/SensitiveReadAccessService.java、result/llm/gateway/GatewayCallService.java（均在原 Java 包根目录下）。


## 任务端点同码复查（2026-10-28）

同一注册约束也影响 task:view 的列表/详情/执行记录和 task:execute 的运行/恢复/暂停/优先级。按原组织资源模型拆分，保留 view 给 GET /api/iqc/tasks、execute 给 POST /api/iqc/tasks/{id}/run；其余用 task:detail:view、task:executions:view、task:resume、task:pause、task:priority。敏感读取前置使用对应端点码；configuration 保留原 task:view+敏感内容复合要求，批次恢复仍保留原 BulkTaskAccessService 的当前负责人及 GRANTS 校验。配套组织 V20261028_02 只继承旧有效授权，原资源ID/状态保留；仍待独立SQL/HTTP及当前撤权验证。静态清单另发现结果、会话、标签、规则、MCP等旧接口重复，尚未完成全量修正，不能从本批推导全部端点已授权。


## 结果端点同码复查（2026-10-28）

继续沿原资源模型修复 result:view 在结果列表/单条详情/任务汇总/标签列表/标签分布/批次会话/层级结果/会话全历史/反馈列表间的冲突，保留原 view 给 GET /api/iqc/results。其余分别为 result:detail:view、summary:view、labels:view、label-insights:view、conversation:view、hierarchy:view、conversation-history:view、feedback:view；export 保留普通结果导出，标签导出使用 result:labels:export。每个读取和导出沿原敏感前置检查对应新码，导出仍要求完整会话和PII，组织范围与业务结果独立端点不变。配套组织 V20261028_03 继承旧有效授权并保留原ID/状态；真实SQL/HTTP及源副本敏感字段验证仍待。没有为修复注册新增查询服务/存储模型。


## 会话端点同码复查（2026-10-28）

原 conversation:view 保留列表，详情与 API统计分别为 conversation:detail:view / conversation:api-stats:view；详情保持预览投影，统计也检查当前资源。完整会话和PII入口继续原 view+对应敏感权限的复合要求。原 conversation:upload 保留单TXT导入，批TXT/ZIP/单JSON/批JSON分别使用 conversation:batch-upload、zip-upload、ingest、batch-ingest。沿原已处理会话解析和导入服务，不新增语音处理或并行入口；TaskTriggerService 内嵌已处理内容原 upload 复合授权保持。配套 V20261028_04 按原有效资源关系迁移，不授予敏感权限或扩大组织范围；导入侧此批仅拆分网关资源声明，不能声称已证明真实网关拒绝。


## MCP、专家历史和基础状态同码复查（2026-10-28）

复用原资产/方案/总览服务，不新建API或权限模型。mcp:manage保创建，更新/启用/停用分别为mcp:update/enable/disable；scheme:manage保草稿列表，专家历史为scheme:versions:view且原敏感读取前置同码；dashboard:view保总览，基础状态为bootstrap:view。配套V20261028_05按原ACTIVE资源已有角色关系继承，保留原ID/状态与新环境PENDING策略，不默认给额外角色/用户授权。MCP与bootstrap此批仅修资源注册，真实网关正反授权待验；方案历史仍按原数据范围及敏感投影读取。


## 标签域同码复查（2026-10-28）

标签树/详情、分类和群组创建修订停用、标签创建修订值绑定停用，集合列表详情和创建修订成员发布停用，以及候选列表相似查询与批准拒绝合并仍有同码多端点。沿原三个Controller及组织资源表逐端点拆分，不新增标签模型/审核服务。原label:view保树，label:manage保标签创建，label:approve仍只标签发布，不从manage推导approve；collection:view保列表、manage保创建，candidate:view保列表、review保批准，其余精确端点新码见配套V20261028_06。旧ID/状态及ACTIVE显式授权继承规则保持，无默认新角色/敏感/all授权；此批仅资源声明与配套迁移，不声称网关或实际成功写权限已验。原数据范围和业务审批约束不变。
