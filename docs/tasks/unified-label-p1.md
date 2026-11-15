# 统一标签框架 P1 增量

MVP 已由用户确认，继续按 PRD P1～P5 开发。本批复用标签、规则版本、绑定控制器与发布生命周期，补齐 P1 约束及 Action 定义/绑定，接入统一任务冻结快照。现有内嵌 Agent、旧评分与联合标签语义保持原状。

## 复用与新增依据

- 标签规则绑定已有 `iqc_label_rule_binding`；仅增加规则类型与数据库唯一约束，不建立第二张规则映射表。
- 未发现通用 Action 定义/标签动作绑定，现有 `LabelResultService` 是旧规则结果的物化器，不能表达可复用动作定义；因此在 IQC 内新增最小 Action 定义与关联表。新增主键使用既有 MyBatis 雪花 ID，Java Long / MySQL BIGINT；不修改 String 主键的框架基类。
- 动作只定义 RECORD_TAG / DEDUCT_SCORE，绑定与快照可查询；未确定的最终命中、扣分和冲突策略继续使用待处置占位，不把动作定义当作已实现执行能力。

## 迁移与升级

V1.1.35 增加可空类型 → V1.1.36 按绑定精确版本回填 → V1.1.37 校验缺项及每标签每类型唯一。脚本不删除、不替换任何存量绑定。升级期间停止标签/规则编辑，先执行以下预检；结果非空时由业务方明确修复后再升级：

```sql
SELECT b.id,b.label_id,b.rule_id,b.rule_version_no
FROM iqc_label_rule_binding b LEFT JOIN iqc_quality_rule_version v
 ON v.rule_id=b.rule_id AND v.version_no=b.rule_version_no
WHERE v.id IS NULL OR v.rule_type IS NULL OR CHAR_LENGTH(TRIM(v.rule_type))=0;
SELECT b.label_id,UPPER(v.rule_type),COUNT(*)
FROM iqc_label_rule_binding b JOIN iqc_quality_rule_version v
 ON v.rule_id=b.rule_id AND v.version_no=b.rule_version_no
GROUP BY b.label_id,UPPER(v.rule_type) HAVING COUNT(*)>1;
```

数据库约束作为并发最后防线。旧程序不能写 rule_type，升级后不允许直接回退到旧写入程序；应用回退前先停止相关写入，保留数据库和历史快照。若必须恢复旧写入，需独立评审后移除新增约束（保留列及内容），并核对 Flyway 失败记录；禁止自动执行 repair 或删数据。

## 后续依赖

P1（绑定/动作）→ P2（每次执行冻结、定时/周期与分页选数）→ P4（完整上下文与动作待处置状态）。MVP 的 P3 核心已实现，后续补真实 Agent 契约、可观测性及验收。P5 包含隔离 MySQL 新装/升级/重复迁移验证、旧任务回归及真实性能数据；未通过前不宣称全量完成。

## 接口和页面

- 沿用 `PUT /api/iqc/labels/{id}/bindings`，服务端推导规则类型，不接受客户端伪造类型；每类型重复在任何写入前拒绝。显式空数组允许解除全部规则绑定并保存草稿，缺失/null 请求拒绝；无规则标签不能发布。
- `GET/POST /api/iqc/label-actions`、`PUT/DELETE /api/iqc/label-actions/{id}`、`POST /api/iqc/label-actions/{id}/disable` 管理动作定义。
- `GET/PUT /api/iqc/labels/{id}/actions` 显示或整组替换动作绑定，空数组表示显式解绑。目录支持 page/size，每页最多 500。
- 动作复用 `iqc:label:view/manage` 及 IQC 数据范围校验、审计、雪花 ID 和元数据填充。被任何标签引用的动作不能停用/删除；动作类型不可原地变更。
- 标签中心原页面增加动作区域，不增菜单。动作修订和冻结版本可追踪，不暴露扣分参数。
- 统一执行由独立的 `UnifiedTaskResultProcessor` 消费全部引擎结果，保留标签动作快照和 `actionStatuses=PENDING_POLICY`。引擎适配器不再承担处置输出构建，也不执行任何动作。

## 验证命令

```sh
# Java 21；根据本机环境配置 Mockito agent。
mvn -o -DforkCount=1 -DargLine=-javaagent:/本地仓库/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
 -Dtest=LabelActionServiceTest,LabelTaxonomyServiceTest,UnifiedLabelPlanServiceTest,UnifiedTagEngineAdapterTest,UnifiedTaskLifecycleTest,LabelResolutionServiceTest,JointLabelMaterializationTransactionTest,IqcPlatformApplicationTest test
# 仅显式隔离的测试 MySQL，端口 34307，datadir 必须匹配 IQC_MYSQL_VALIDATION_DIR；禁止应用数据库。
mvn -o -Dtest=IsolatedMysqlLabelBindingIT,IsolatedMysqlMigrationIT test
bash ../base-k8s/scripts/validate-flyway-migrations.sh .. iqc-platform
# 管理端目录
pnpm test -- src/views/labels/rule-bindings.spec.ts src/components/LabelActionBindings.spec.ts src/components/TaskCreateWizard.spec.ts src/components/TaskWizardResults.test.ts
pnpm build
```

隔离 MySQL 验证包含正常升级、新装重复 migrate、同类型重复拒绝、缺失精确版本拒绝、绑定数据保留、动作关联唯一约束和 BIGINT 精度。失败升级样本保持原始行用于核对，不运行自动 repair。旧归档迁移测试分开验证 V1.1.32 与升级到当前版本，避免把历史阶段的迁移数量作为未来版本的固定假设。

## 仍未交付的范围

本批未宣称完成所有 P1～P5：规则资产的全部引用生命周期、真实 Agent 平台契约和性能验收仍须继续；P2 调度、分页、轮次隔离和执行快照查询进展见 [P2 文档](unified-label-p2.md)。新增动作及绑定使用 BIGINT/Long 雪花 ID，复用实体不因本 PRD 强制迁移存量 ID。未知的扣分、冲突、自动正式标签规则不由本批制定；V1 已提供动作配置、快照和处置接口占位。

## 本批验证结果（2026-11-14）

后端针对性验证累计 88 项通过，另有应用启动、权限和 OpenAPI 装配 4 项通过；管理端 16 项通过，生产构建和 TypeScript 检查通过。Flyway 布局检查通过，MySQL 8.0.34 使用独立临时 datadir、端口和测试库验证新装、升级、重复迁移与预期拒绝场景。未使用共享或生产数据库；尚未做真实 LLM 调用与性能验收。


## 规则解绑增量

标签编辑允许清空规则选择，PUT bindings 的空数组明确表示解除全部规则绑定。发布标签被修改时按既有流程转为下一版本草稿；历史任务执行的规则内容和版本快照不改写。发布仍要求至少一条已发布规则，空绑定草稿不能进入新的统一任务快照。此项补齐解绑语义，不代表规则资产删除、停用及全部引用生命周期已交付。

解绑增量验证：LabelTaxonomyServiceTest 9 项、UnifiedLabelPlanServiceTest 5 项通过，管理端规则绑定校验 4 项通过，类型检查及生产构建通过；未执行线上部署。


## 当前规则引用查询

复用 LabelTaxonomyService、标签和规则绑定表，新增 GET /api/iqc/labels/using-rule/{ruleId}?current=1&size=20，沿用 iqc:label:view。先校验规则数据范围，再按标签创建人/组织权限在数据库中筛选、计数并分页；每页最多 100。EXISTS 绑定条件使用参数化规则 ID，不拼接用户 SQL。

规则管理页新增“引用标签”弹窗，展示当前可见标签的名称、编码、版本和状态，支持分页、失败重试及切换规则时的旧响应隔离。这里的空结果仅表示无可见引用，不能据此判定全局无引用或允许删除规则；历史任务引用通过执行快照查看。未增加表、菜单或独立引用模型。

规则引用查询验证：标签服务 9 项回归通过；真实 H2/MyBatis 集成用例经定向复验通过，验证本人/同组织/不可见/无绑定标签的筛选、分页和计数及规则访问拒绝。前端 4 项测试、TypeScript 检查和生产构建通过。未将窄范围查询测试作为整个引用生命周期完成的证据。

## 规则生命周期并发前置

标签规则绑定现已按 ID 升序取得规则行锁，再按用户原顺序校验和写入。完整引用来源、后续事务改造及验收边界见 [规则生命周期实施说明](unified-rule-lifecycle.md)。尚未开放规则停用、删除接口；必须先统一规则集、方案与任务引用写入协议。
