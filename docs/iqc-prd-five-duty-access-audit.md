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

本次先记录复用与取舍，尚未实施端点/组织迁移；避免交付只有新注解而无对应组织授权的半套改动。现有 PR 保持草稿，不执行共享 SQL，不变更 framework，不宣称五职责完成。

## 来源

- 工作区 IQC智能质检平台_PRD_V1.0.md 第3、19、23节。
- base-organization/docs/modules/organization-and-access.md。
- base-organization/src/main/java/io/github/opensabre/organization/service/impl/ResourceRegistrationService.java。
- base-organization/src/main/java/io/github/opensabre/organization/dao/ResourceMapper.java。
- base-organization/src/main/resources/db/migration/mysql/history/V20260829_03__add_iqc_business_roles.sql。
- 本仓库 src/main/java/io/github/opensabre/iqc/rest/{IqcModelProfileController,IqcSkillController,QualityOperationsController,PipelineAssetController,EvaluationDatasetController,PipelineEvaluationController,BenchmarkController}.java。
- 本仓库 shared/IqcDataScope.java、privacy/SensitiveReadAccessService.java、result/llm/gateway/GatewayCallService.java（均在原 Java 包根目录下）。
