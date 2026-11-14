# 统一规则引用生命周期：实施边界

本次盘点基于 QualityRuleService、LabelTaxonomyService、QualityRuleSetService、SchemeDependencyResolver、InspectionTaskService 和 TaskWizardService 当前代码。没有复用到覆盖这些领域引用的现成删除/停用守卫；AgentAssetReferenceValidator 仅负责模型、MCP 和 Skill，不能替代规则引用检查。

## 引用入口与改造顺序

| 引用来源 | 权威位置 | 写入事务与处理 |
| --- | --- | --- |
| 标签规则绑定 | iqc_label_rule_binding | replaceBindings 在事务内按规则 ID 排序取得当前规则行锁，再检查发布状态及权限、替换绑定；本轮已改 |
| 规则集及其版本 | QualityRuleSetService.validateRules / ruleIdsJson | 创建、修订、提交和发布的写入事务现已同序锁定规则；只读查询不加写锁，Agent 的规则集引用由这层继续保护 |
| 方案草稿与发布版本 | InspectionScheme.draftConfigJson / InspectionSchemeVersion.snapshotJson | 依赖冻结现已同序锁定 Item.rule、appliesWhen、execution.prefilter/candidate 及冻结标签规则；草稿直接写入路径仍需单独核对，不能把冻结器覆盖范围扩大到所有草稿操作 |
| 未结束任务 | InspectionTaskService 的规则选择及 TaskWizardService 的执行计划快照 | 后续覆盖直接规则、规则集及统一标签快照的引用冻结入口；不能只检查任务上的单个 ruleIdsJson |
| 历史执行 | TaskExecution.configurationSnapshotJson 等 | 保留精确版本和原始内容，不改写历史快照；不能以当前可见引用标签列表为空作为可删除证明 |

规则停用/删除必须先取得同一规则行锁，再检查全局引用；所有引用写入者也要在事务内锁定当前规则并重新校验状态。仅在停用端做一次 count 检查会与新绑定竞态，不足以发布。多个规则按 ID 升序锁定，但业务引擎顺序和绑定显示顺序保持用户配置。

## 本轮交付

QualityRuleMapper.lockById 以 SELECT FOR UPDATE 读取当前定义。标签绑定使用该入口，参数、重复类型、发布状态及权限检查保持；锁顺序不改变保存顺序。未新增独立引用表、服务平台或规则合并逻辑。

本轮尚未开放规则停用、删除接口，因为方案草稿及任务等写入路径尚未全部统一到同一并发保护协议。后续必须一起补齐守卫、权限审计、管理端入口及并发验收，不能将标签路径测试作为整个生命周期完成的证据。

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
