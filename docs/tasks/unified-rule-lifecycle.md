# 统一规则引用生命周期：实施边界

本次盘点基于 QualityRuleService、LabelTaxonomyService、QualityRuleSetService、SchemeDependencyResolver、InspectionTaskService 和 TaskWizardService 当前代码。没有复用到覆盖这些领域引用的现成删除/停用守卫；AgentAssetReferenceValidator 仅负责模型、MCP 和 Skill，不能替代规则引用检查。

## 当前交付状态

规则停用、删除 API 已接入 RuleReferenceGuard：先锁定当前规则并检查管理范围，再以 READ_COMMITTED 事务扫描全局引用，避免获取锁之前的旧一致性快照。扫描复用标签绑定、规则集当前/历史版本、方案草稿/发布版本、任务和执行配置快照，不受调用者可见范围过滤；错误只返回通用原因。任何保存的引用（包括历史快照）存在时均拒绝停用和删除，不改写历史内容。无引用时停用只改当前状态，删除在同一事务内删除当前定义及其版本。停用后审批/修订接口拒绝写入，不提供自动恢复或兼容回退。

新增 RuleReferenceGuard 的原因：现有 AgentAssetReferenceValidator 检查模型/MCP/Skill，现有标签引用查询只用于可见标签分页，均不能证明规则不存在跨领域引用。未新增引用表或通用平台；以每页 200 条、主键游标读取现有 JSON 并检查明确的 ID 字段。代价是停用/删除操作需要扫描现有配置总量，属于当前 MVP 的低频管理操作；尚未测量大数据量延迟，不能宣称索引查询性能。后续若有性能证据，再评估沿用现有存储的查询优化。

接口：`POST /api/iqc/config/rules/{id}/disable`、`DELETE /api/iqc/config/rules/{id}`；复用 `iqc:rule:manage` 和既有审计模块。管理端规则页提供确认框及服务端错误反馈，引用标签列表为空不视为允许删除。

验证：引用检查与规则服务 29 项单元/H2 数据库回归通过，前端生产构建通过（已有 bundle 大小提示）。隔离 MySQL 8.0.34 上 8 项事务与并发验证通过：2 项真实生命周期服务测试覆盖历史私有任务引用阻止操作、状态与快照未变、解除测试引用后的停用与定义/版本原子删除，以及实际停用事务与新任务引用竞争；另 6 项复验规则集、方案冻结、直接任务、统一标签计划、草稿和引擎计划引用写入路径。测试数据库经临时目录和端口保护核验，未访问共享应用数据库。尚未开展浏览器验收、HTTP 权限拒绝场景或大存量扫描压测，不能以服务测试代替这些证据。以下分次实施记录保留当时边界，当前状态以本节为准。

## 引用入口与改造顺序

| 引用来源 | 权威位置 | 写入事务与处理 |
| --- | --- | --- |
| 标签规则绑定 | iqc_label_rule_binding | replaceBindings 在事务内按规则 ID 排序取得当前规则行锁，再检查发布状态及权限、替换绑定；本轮已改 |
| 规则集及其版本 | QualityRuleSetService.validateRules / ruleIdsJson | 创建、修订、提交和发布的写入事务现已同序锁定规则；只读查询不加写锁，Agent 的规则集引用由这层继续保护 |
| 方案草稿与发布版本 | InspectionScheme.draftConfigJson / InspectionSchemeVersion.snapshotJson | 依赖冻结现已同序锁定 Item.rule、appliesWhen、execution.prefilter/candidate 及冻结标签规则；草稿创建、修订及派生现通过同一规则行锁检查直接检测、条件及所有策略阶段引用；不提前要求草稿可执行或规则版本已发布 |
| 未结束任务 | InspectionTaskService 的规则选择及 TaskWizardService 的执行计划快照 | 直接规则任务快照已按 ID 升序加锁并保留配置顺序，规则集选择沿用规则集校验锁；统一标签计划已在写事务中锁定并检查全部绑定规则，方案任务入口仍需核对，不能只检查任务上的单个 ruleIdsJson |
| 历史执行 | TaskExecution.configurationSnapshotJson 等 | 保留精确版本和原始内容，不改写历史快照；不能以当前可见引用标签列表为空作为可删除证明 |

规则停用/删除必须先取得同一规则行锁，再检查全局引用；所有引用写入者也要在事务内锁定当前规则并重新校验状态。仅在停用端做一次 count 检查会与新绑定竞态，不足以发布。多个规则按 ID 升序锁定，但业务引擎顺序和绑定显示顺序保持用户配置。

## 本轮交付

QualityRuleMapper.lockById 以 SELECT FOR UPDATE 读取当前定义。标签绑定使用该入口，参数、重复类型、发布状态及权限检查保持；锁顺序不改变保存顺序。未新增独立引用表、服务平台或规则合并逻辑。

早期提交仅保护引用写入路径，未开放生命周期接口。当前已接入守卫、权限审计和管理端入口，见上方当前交付状态；验收范围仍以实际测试记录为准。

## 后续验收

- 每一种引用存在时，停用/删除被拒绝，错误不暴露不可见资产详情。
- 新引用与停用并发：只有“绑定成功、停用拒绝”或“停用成功、绑定拒绝”两种合法结果，不能提交停用规则的新引用。
- 重复类型绑定、未发布规则、越权规则仍被拒绝，失败事务不留下部分绑定。
- 多规则不同请求顺序不改变执行顺序；并发锁顺序一致。
- 已冻结的历史配置、规则版本与结果可查询，不新增兼容回退路径。

## 本轮验证

- LabelTaxonomyServiceTest：10 项通过，新增稳定锁顺序及用户保存顺序校验。
- H2 真实事务并发用例：1 项通过，持锁停用事务提交前绑定请求等待，提交后按最新状态拒绝新绑定。
- IsolatedMysqlJointTransactionIT：2 项通过，同一并发用例和权限分页用例在正式 Flyway 迁移后的隔离 MySQL 8.0.34 上复验。测试端口 34307、临时 datadir 身份由现有测试保护核实，不使用应用数据库。
- 本轮未改前端、未推送、未创建 PR 或部署。

```sh
JAVA_HOME=/path/to/java21 mvn -o -DforkCount=1 -DargLine=-javaagent:/path/to/mockito-core.jar -Dtest=LabelTaxonomyServiceTest test
# 仅测试专用的临时 MySQL，现有保护会核验目录和端口。
IQC_MYSQL_VALIDATION_DIR=/private/tmp/iqc-mysql-validation.example/data \
  mvn -o -Dtest='IsolatedMysqlJointTransactionIT#newLabelBindingWaitsForRuleLifecycleLockAndRechecksCommittedStatus+ruleUsagePaginatesOnlyVisibleCurrentBindingsAndDoesNotLeakPrivateCounts' test
```


## 规则集与方案依赖冻结增量

QualityRuleSetService 在实际写事务中按规则 ID 排序取得行锁，校验后仍按输入顺序保存版本；草稿规则集也禁止新增停用规则引用。SchemeDependencyResolver 在实际写事务中先锁定全部规则，再按原依赖顺序读取精确已发布版本。无事务或只读事务中的查询、预览不取得写锁，不改变既有预览行为。

已通过 55 项针对性单元回归：规则集 5、方案依赖 29、路线计划 10、方案任务 11。新增覆盖稳定锁顺序、输出顺序保持、只读预览不加写锁和草稿拒绝停用规则。隔离 MySQL 8.0.34 并发验证 2 项通过：规则集写入和方案依赖冻结均在停用事务持锁期间等待，提交后读取最新状态并拒绝引用，失败不写入规则集。


## 直接规则任务快照增量

InspectionTaskService.snapshotAgentAndRules 在任务创建事务中按规则 ID 升序锁定当前规则，再按配置顺序生成快照。已停用或不存在的规则拒绝写入任务。该方法覆盖直接规则、规则集、定时与抽样创建共享的快照路径；此结论不扩大至统一标签计划、方案任务或全部任务入口。没有新增兼容分支或修改历史快照。

针对性任务服务单元回归 40 项通过，包含锁顺序、快照顺序、停用规则拒绝创建及既有任务模式回归。H2 真实事务并发回归 3 项通过，覆盖规则集、方案依赖冻结及新增任务创建：停用事务持锁时等待，提交后拒绝新引用。本轮新增任务路径尚未在 MySQL 上复验，不能以此前规则集与方案的 MySQL 结果代替。


## 统一标签任务计划增量

UnifiedLabelPlanService 在标签及权限解析后收集全部绑定规则（包含本轮未选择的引擎类型），按 ID 升序校验当前定义。实际写事务使用同一 SELECT FOR UPDATE 行锁，保持至任务或执行快照提交；单独只读预览使用普通查询。当前定义不存在或停用时拒绝冻结，已有已发布精确版本不绕过当前停用状态；仍由绑定版本决定规则内容，不以当前草稿或最新版本替换。TaskWizardService 创建和 InspectionExecutionService 首轮/新轮次冻结均在既有写事务中调用该方法，重试继承既有快照。

该变更不证明所有规则引用生命周期已完成；方案草稿、方案任务以及原任务引擎计划仍需核对，停用/删除入口尚未开放。

本轮验证：UnifiedLabelPlanServiceTest 9 项、UnifiedTaskScheduleServiceTest 6 项通过；H2 真实事务并发 4 项通过（规则集、方案、直接任务、统一标签计划）。统一标签并发用例使用真实规则 Mapper 和事务管理器，标签选择发现用 Mock 隔离，因此不作为完整标签解析或 MySQL 并发验证的证据。新用例证明持锁停用期间冻结等待、提交后拒绝引用；单元用例验证固定锁顺序、精确版本、只读不加锁与新草稿不替换已发布版本。本轮未部署、未创建 PR。


## 方案草稿增量

InspectionSchemeService 在新建/派生插入前、修订完成权限和版本校验后，调用已有 SchemeDependencyResolver 的草稿规则引用检查。收集所有 Item.rule、appliesWhen、当前阶段及所有允许策略阶段的规则 ID，去重后升序锁定当前定义；不存在或已停用则拒绝保存。没有增加新持久化模型，也不要求草稿规则已发布、Agent 可执行、模型可用或评分策略可执行。标签仍保存原版本引用，不在编辑草稿时重新解析为当前绑定，标签绑定自身的生命周期保护继续有效。

本轮验证：方案服务 28、依赖解析 31、方案任务 11、路线依赖 10，共 80 项单元回归通过；H2 真实事务并发 5 项通过，新增草稿引用检查持锁等待与提交后拒绝引用。首轮新增策略夹具错误使用合规语义，触发现有初筛约束；改为违规语义后定向重跑依赖解析 31 项全通过，生产约束未放宽。本轮尚未复验 MySQL 或派生草稿端到端流程。停用/删除 API 与全局引用守卫仍未交付，不能以草稿路径闭合替代整体生命周期验收。


## 任务引擎计划增量

TaskEnginePlanService 的写事务先汇总质检和打标两分支的全部规则 ID，去重并升序锁定，再按原业务顺序校验和冻结配置。相同规则跨分支只加锁一次，不按分支分别排序，避免相反配置顺序造成不一致的锁顺序。只读预览不请求写锁。停用规则仍由已有发布状态校验拒绝，不新增兼容或评分处理。

本轮验证：TaskEnginePlanServiceTest 9 项通过；H2 真实事务并发 6 项通过，新增 ENGINE_PLAN 用例确认停用事务持锁期间等待，提交后拒绝冻结。单元新增跨两分支排序、共享规则仅锁一次、执行顺序保持和只读预览不请求写锁。方案发布变体跨次依赖解析与全局生命周期守卫仍需核对；局部锁顺序验证不作为所有引用入口闭合的证据。未新增迁移，未复验 MySQL，未创建 PR 或部署。


## 生命周期接口验证命令

```sh
JAVA_HOME=/path/to/java21 mvn -o -DforkCount=1 -DargLine=-javaagent:/path/to/mockito-core.jar -Dtest=RuleReferenceGuardTest,QualityRuleServiceTest test
# 仅使用现有保护校验过的隔离 MySQL 实例：端口 34307、临时目录前缀及 @@datadir 必须匹配。
IQC_MYSQL_VALIDATION_DIR=/private/tmp/iqc-mysql-validation.example/data \
  mvn -o -Dtest='IsolatedMysqlJointTransactionIT#ruleLifecycleChecksHistoricalReferencesThenAtomicallyRemovesUnreferencedVersions+actualRuleDisableSerializesWithNewTaskReference+ruleSetAndSchemeWritersRecheckRuleStatusAfterLifecycleLock' test
# 管理端：pnpm build
```
