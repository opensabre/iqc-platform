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


## 规则与智能体最后五组同码复查（2026-10-28）

QualityConfig 与模板物化仍共用agent:view/approve与rule:view/manage/approve。沿原Controller及组织资源模型拆分16个端点：Agent历史/比较/效果与驳回，规则历史/提交/新版本与驳回，规则集列表/历史/创建/新版本/提交/批准/驳回，以及模板物化。原Agent/规则列表、创建规则、Agent/规则批准保留原码；approve派生端点只继承原approve关系，manage派生只继承原manage，模板物化仍由原rule:manage派生，不从只读template:view授予。配置响应敏感前置同步自身新码；模板物化本批仅修网关注册声明。配套V20261028_07保旧ID/状态及ACTIVE显式关系，新环境缺失原资源保持PENDING。原审批状态/组织数据范围/服务逻辑不改，无新模型、用户、租户或平行RBAC。静态字符串复查与实际全Controller注册/PDP证明分别记录，不能互相代替。


## 五职责组织库只读快照（2026-10-29）

本批没有执行迁移、DDL/DML、角色或账号授权变更。SSH读取运行容器清单后，在源码
`base-organization/src/main/resources/application.yml` 第38行指定的默认库
`os_base_organization` 中使用 `START TRANSACTION READ ONLY` 查询角色、关系计数与资源状态。
不读取账号名称、个人身份或逐用户角色明细；凭据仅在原MySQL容器内部使用，不输出或写入文件。

原59400 terminal0，`/tmp/iqc-org-role-readonly-audit-20261029.exit=0`；
原24555 terminal0，`/tmp/iqc-org-role-readonly-detail-20261029.exit=0`。
首轮客户端未显式指定UTF-8，中文名称显示问号，不能引用该轮中文名称；第二轮显式utf8mb4
核对名称和资源范围。角色code、计数及状态使用原查询实际结果，不根据中文标签推导职责。

| 现有角色code | 用户角色关系数 | IQC菜单关系数 | IQC API ACTIVE / PENDING / STALE关系数 |
| --- | ---: | ---: | --- |
| IQC_ADMIN | 3 | 65 | 45 / 7 / 7 |
| IQC_QUALITY_MANAGER | 1 | 37 | 37 / 7 / 0 |
| IQC_INSPECTOR | 1 | 24 | 18 / 3 / 0 |
| IQC_VIEWER | 1 | 18 | 14 / 3 / 0 |
| ADMIN | 25 | 0 | 1 / 0 / 0 |
| DEV | 2 | 0 | 0 / 0 / 0 |

这里计数的是关系，不代表互斥岗位或不同自然人。资源严格限定
`application=iqc-platform, product_code=iqc`；该库全部IQC资源按状态为45 ACTIVE、8 PENDING、7 STALE。
不能把非ACTIVE关系算作已可调用能力，也不能把菜单可见性算作API授权。
ADMIN的一条既有IQC关系具体是`iqc:agent:manage` ACTIVE；本批记录现状，不自动撤回或扩展它。
DEV名为“开发人员”，但没有IQC菜单/API关系，不能把名称直接当作模型/算法工程师已经交付。

选取的能力中，方案创建/发布和任务执行在IQC_ADMIN与IQC_QUALITY_MANAGER有ACTIVE关系；
标签值裁决在这两个角色有ACTIVE关系。IQC_INSPECTOR有任务执行及业务结果读取，但选取的裁决能力
没有ACTIVE关系，不能以“质检员”名称证明PRD人工复核职责。`iqc:review:create/view/decide`
资源为PENDING；`iqc:report:view`的既有角色关系为PENDING；model/skill原manage在IQC_ADMIN为STALE。
这些是升级前基线事实，不能据此判断尚未部署的新源码错误，也不能以草稿中新增资源码替代线上激活验收。

### 部署和职责映射的后续要求

1. 复用原角色配置页/API表达五类职责，保留历史角色ID、代码和既有关系；不能将VIEWER当工程师，
   也不能直接把现有DEV用户统一授予IQC。实际用户的职责组合与所属组织需要单独核对。
2. 质检管理员保方案/任务/评分配置；业务管理员组合团队报表/风险/整改读取，不能因此得到模型写入或裁决；
   质检员需要真实可用的复核/修正/申诉/证据能力；模型工程师组合模型/Prompt/Skill/数据集/Benchmark；
   系统管理员继续原组织/RBAC、连接、审计与配置。单租户边界不增加租户主数据模型。
3. API/menu迁移与对应源码部署后，按新资源实际ACTIVE状态、显式角色关系、菜单树和fresh当前授权逐项验收。
   缺失或PENDING/STALE保留原组织审批流程，不用数据库全量激活、全角色授权或全局ADMIN来使页面通过。
4. 数据范围仍按`IqcDataScope`原实现：可信ADMIN/SUPER_ADMIN或`iqc:result:all`提供all读取，
   其余按当前组织group及owner；例外发布的可信管理员检查另行保持。此处不是改变数据范围规则的授权。

### 证据边界

这是指定组织库的只读配置快照，不是五职责登录验收。容器直接配置键中未发现datasource schema，
按容器IP查询数据库连接未返回映射，未继续读取Nacos凭据；因此未证明运行服务最终解析的数据源与本库绑定。
即使后续确认同库，也仍须真实网关/PDP、同组/跨组、敏感正文及成功写/撤权请求。
本批没有查询JWT、发登录令牌、读取个人账号或运行测试；不将关系计数替代这些行为证据。
完整适用I1–I7继续进行。


## 2026-10-31 运行关联证据续查

[只读运行基线](iqc-runtime-readonly-baseline-20261031.md)补充当前镜像/迁移末条及网络namespace源端口关联：组织20条连接与os_base_organization唯一对应，IQC10条与iqc_platform对应。它增强默认库快照关联，但不是NAT表/最终配置解析或真实登录PDP证明。现场仍为旧镜像/迁移基线，不能把候选PR的CI当作现场五职责已验收；本轮没有共享写SQL、账号授权或部署。
