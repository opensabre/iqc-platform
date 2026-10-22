# 灰度任务重放与冻结证据验证

## 已集成范围

B 在 `fdda5fc` 上只集成 A 已提交 `c6f87ac`（本分支 `1ee833a`）与 `db86272`（`63feb37`）；不重复 A 对 B 测试的 cherry-pick，不动另一工作树或运行中进程。两个增量包含显式会话IDs物化、原请求重放及worker/历史证据守卫，不代表Selector/周期事件灰度、AB归因或全PRD完成。

## 重放专项设计

`PipelineRolloutTaskReplayTest` 运行真实 `PipelineRolloutTaskService`、Evidence、Policy、Comparator、hash和精确JSON读取；PipelineAssetService、RolloutService、InspectionTaskService和scope模拟。合成两侧RULE发布内容与实际对应hash。模拟物化边界再次调用真实Evidence.verify，并保存含选定Prepared及证据信封的模拟task，以便后续真实重放代码读取。它不调用真实任务物化器、不取会话、不落SQL，不证明事务或PDP。

覆盖首次冻结、稳定账号/流水线/请求键派生ID及不含身份原文；原请求跨扩量/回退/换盐仍返回原task且SUCCESS及快照不变，不读取当前策略或重新取源、不重复物化；会话顺序/去重及null并发等价1，名称/会话集合/并发变化拒绝；历史修订ID/hash不同或缺失拒绝；缺信封/错任务种类拒绝；未重算信封hash或重算后错桶、实际release被改均拒绝；首次两侧来源hash漂移阻止物化；空会话在锁前拒绝、不同账号派生不同ID。

原65241及最终报告待取得终态后记录。生产锁/CAS真实并发、RR外层旧快照、完整生产物化/worker启动领取恢复、真实角色/MVC/供应商与前端浏览器联调，仍需独立证据。无语音转换、共享SQL、推送、部署或framework修改。

## 首轮缺陷与修复

原65241 terminal1，`/tmp/iqc-b-rollout-task-replay.log`，26项中1失败/15错误。两类原因区分：旧Revision专项8项因新代码改走rolloutSourcesForTask而原mock仍接旧入口，已只更新fixture；新Replay专项8项均在真实Evidence.verify拒绝合法5000比例，并非为了测试而绕过校验。

实际缺陷是跨能力摘要协议不一致：预览decision.policyHash复用Selector hash，其数字规范化stripTrailingZeros把5000编码为5E+3；持久化revision.policyHash及冻结verify使用Pipeline hash保留整数5000。任务物化要求两者相等，合法常用比例因此无法创建。最小修复只将Policy.preview的策略摘要改为PipelineAssetService.hash，与已有修订/冻结协议一致；cohort哈希/桶、发布内容hash及历史修订内容完全不变。曾经能通过冻结的策略，其两侧hash原本已相同；新修复不要求重写既有历史。预览响应policyHash会随算法纠正而更新，消费者应使用当次返回值，不复用旧预览结果。

新增Policy断言核对0/10/1000/5000/10000/1234与持久化协议相同。修复后完整三类专项以原95716、`/tmp/iqc-b-rollout-task-replay-final.log`终态为准，尚未预报通过。

## 修复后终态

原95716 terminal exit0，`/tmp/iqc-b-rollout-task-replay-final.log`；三份本轮XML Replay9+Policy9+Revision9=27，0 failures/errors/skipped。修复后模拟物化边界继续调用真实Evidence.verify，未放松合法性校验以求测试通过。跨策略原请求只读旧修订、返回原任务，参数和坏证据拒绝均有此轮覆盖。

本轮没有运行当前组合完整默认后端套件、真实任务物化/worker或SQL。新增ExecutionGuard已经集成但尚无独立原来源/发布门禁测试；生产资产锁/CAS真实并发及RR旧快照等剩余门槛保持未完成。完整 I1–I7 继续推进。

## 执行守卫专项设计

新增 `PipelineRolloutExecutionGuardTest`，直接验证原Guard、Evidence、Comparator和正式发布门禁helper；只有原修订/发布版本mapper模拟。合成两侧真实JSON/hash及门禁来源，标记trial=false，5000比例选中baseline；故候选来源损坏也必须拒绝。RULE config包含9007199254740993.123456789012345678，成功路径额外核对源对象和完整快照序列化不变、精确值未被修改。

覆盖原修订缺失或ID/资产/修订号/hash/正文错误，两侧任一版本缺失或资产/版本/hash/正文错误，两侧门禁摘要缺失/错误及重算摘要后错误schema/资产/draftRevision/releaseHash；历史版本两门禁字段均null仍按原兼容协议可读。任务缺/null信封、缺流水线、混入evaluation或scheme、跨任务移植、错误trial值在原来源读取前拒绝；重新计算信封hash后篡改comparison的invariantHash仍须与原来源重算比较不一致而拒绝。

专项与原27项组合运行，终态另记。仍不证明数据库完整性、生产任务真实物化、worker的开始/异步/恢复接线、实际供应商可用性、PDP角色或容量开销。

执行守卫专项终态：原25903 exit0，日志 `/tmp/iqc-b-rollout-execution-guard-final.log`；Guard8 + Replay9 + Policy9 + Revision9，共35项，fresh XML failures/errors/skipped均0。首95618因测试lambda捕获非final局部变量编译失败，修正夹具后复跑；未执行成功的首轮不计通过。仅mapper模拟的守卫验证，不替代真实worker、数据库事务及角色验收。

旧构造接线专项：原65771 exit0，`/tmp/iqc-b-rollout-guard-wiring.log`；GuardWiring3+ExecutionGuard8共11项，fresh XML零失败错误跳过。两种旧构造下灰度缺接线均拒绝、旧非流水线入口兼容；不证明真实Spring DI或完整worker运行。

执行入口专项：原68645 terminal exit0，`/tmp/iqc-b-rollout-worker-boundaries.log`。InspectionExecutionServiceTest共35项，fresh XML零失败错误跳过，新增3项逐一检查queueSystem/resume/run在g保留ID缺信封/null信封时将实际ID传给校验入口。拒绝后无checkpoint/message读取、模型或Pipeline evaluator调用；排队/恢复不写执行记录，run沿既有流程先claim再校验。入口真实，PipelineAssetService/DAO/state/provider模拟，不能替代真实守卫+DB/领取事务及完整worker运行验收。

worker异常收尾专项：原38902 terminal exit0，`/tmp/iqc-b-rollout-worker-failure.log`；InspectionExecutionServiceTest37+TaskExecutionStateServiceTest7共44项fresh XML零失败错误跳过。新增persisted/async两项精确验证领取后灰度拒绝调用state.fail(actualTaskId,executionId,true,error)，无budget-wait及会话/模型调用。runner的state服务仍模拟；原state7项为真实状态服务+mapper模拟，两层不能合称真实数据库失败回写或异步代理验收。

真实服务串联专项：原8695 terminal exit0，`/tmp/iqc-b-rollout-worker-real-services.log`；Execution38+State7+Wiring3共48项fresh XML零失败错误跳过。新增一项以真实runner、真实PipelineAssetService旧构造及真实TaskExecutionStateService串联，仅mapper模拟；缺/null信封的保留g任务均因缺守卫被拒绝，任务和执行轮次转FAILED、保留原因、原ruleSnapshotJson不变，未读取消息/checkpoint或调用模型。日志中的拒绝异常为预期路径，不是测试失败。不证明真实DB提交、Spring事务或异步代理。

## 2026-10-20 隔离MySQL真实任务物化

扩展既有IsolatedMysqlRolloutServiceIT，复用随机schema和正式迁移，加入真实Spring代理PipelineRolloutTaskService、InspectionTaskService、ProductionInputSnapshotService及任务/会话/消息Mapper；保留真实资产锁、修订Service/Mapper。只有scope和发布来源解析使用测试桩，来源是合成RULE release，不证明真实发布依赖可用。

原46286 terminal exit0，`/tmp/iqc-b-rollout-task-mysql.log`；本轮6项fresh XML零失败错误跳过，包含前轮修订3项及新任务3项：

- 两连接同请求并发，真实证据校验和输入冻结后返回相同任务ID及完整JSON，最终该ID恰一行、CREATED/1消息，文本为原处理好会话。
- 修订1创建后扩量至修订2、停用原资产、修改活归档，旧请求仍精确返回原任务与冻结JSON/修订1；新请求停用拒绝，同key改名拒绝。
- BEFORE UPDATE触发器在任务INSERT后的消息数更新注入失败，外层事务回滚后该ID不存在；移除故障后相同key创建成功且恰一行。

MySQL8.0.34临时目录 `/private/tmp/iqc-mysql-validation.QC89ap`；随机schema `iqc_rollout_service_a56fe1ec9a3b` finally删除且目录不存在，server Shutdown complete，pid/socket不存在。正式迁移至1.1.74/validate75文件/再migrate0。无共享数据库、framework、push或部署操作。

这证明上述实际物化SQL/事务和重放边界，不证明真实授权、实际发布来源、Selector/周期事件自动灰度、领取执行/恢复、真实模型与浏览器或容量。默认全套1414项对应此前3625fcc，不将此次独立IT相加或宣称当前全PRD完成。

## 2026-10-20 物化与扩量锁顺序 / 旧RR快照

原92857 terminal exit0，`/tmp/iqc-b-rollout-ordering-mysql.log`；IsolatedMysqlRolloutServiceIT本轮8项fresh XML零失败错误跳过，其中新增2项、前轮6项复验，不累加重复数量。

1. 外层事务物化修订1任务后暂不提交，另一连接扩量；通过performance_schema.data_lock_waits联结data_locks实际观察到该schema的iqc_pipeline_asset锁等待，并确认扩量future尚未完成。物化提交后扩量才成功；新任务冻结修订2，原任务仍修订1且重放完整JSON不变。
2. 外层REPEATABLE_READ先读到修订1，另一连接扩量提交修订2；原一致性读仍1，真实任务创建却通过当前锁读冻结修订2/9000比例，数据库恰一任务。

MySQL8.0.34临时 `/private/tmp/iqc-mysql-validation.CYVp1v`，schema `iqc_rollout_service_a59606d6f240` finally删除且目录不存在，Shutdown complete、pid/socket不存在。正式迁移74/validate75/再migrate0。仍只有scope与发布来源解析模拟；未验证真实发布依赖、HTTP角色、worker/供应商、Selector/周期事件自动灰度或完整I1–I7。本轮无生产源/框架修改或共享SQL、push、部署。

## 2026-10-20 默认MVC与安全回执专项

新增PipelineRolloutHttpPrivacyTest，复用默认MockMVC/框架响应与异常Advice、真实ResultReadProjection及真实Policy/Evidence夹具，授权投影和持久化服务模拟。原20880 terminal exit0，`/tmp/iqc-b-rollout-http-privacy.log`；新31+TaskCommandHttpPrivacyTest原10共41项fresh XML零失败错误跳过。

覆盖四组fullConversation/PII授权下回执保留合法policyRevision/selectedVersion，不返回任何冻结正文、Prompt、筛选原文、cohortHash、盐、requestFingerprint，源对象不变；跨task移植证据不输出公开rollout声明。创建明确传入两个固定base grants；四个端点grant撤销均早拒绝、不调用服务。concurrency小数/string/bool/overflow/array/object、requestKey非string及expectedRevision负数/非精确整数/overflow均拒绝；null concurrency保留服务默认值。策略保存使用默认HTTP codec，读/历史带no-store/nosniff，审计声明request/response=false。

本次只证明默认MVC codec与真实投影（默认HTTP Jackson3），不额外宣称通过两代HTTP codec；此前纯Policy双代codec是不同专项。审计注解检查不代表真实AOP审计存储，mock grants不代表实际组织PDP，mock service不代表持久化事务。本次无需监听网络端口，无生产或framework源修改、共享SQL、push、部署。完整PRD I1–I7仍进行中。

## 2026-10-20 原来源守卫真实数据库补充

在301a38b基础上复用同一个IsolatedMysqlRolloutServiceIT，注册真实PipelineAssetVersionMapper和PipelineRolloutExecutionGuard，追加3项（本轮共11项，原8项一并复验）：

- 真实灰度任务创建后扩量、停用资产、修改活归档，守卫仍只读验证原修订与两侧原发布版本；任务JSON、修订及版本数据库内容保持不变。
- 比例0明确未选候选版本，候选快照损坏或候选版本删除均拒绝；恢复原内容/版本后校验通过，证明未选侧也必须有完整原来源。
- 给原本无门禁记录的候选版本补入可自洽hash但错误协议的门禁JSON，守卫拒绝；恢复原历史空门禁后通过。

原43844 terminal exit0，日志`/tmp/iqc-b-rollout-guard-mysql.log`，本轮fresh Surefire XML 11 tests / failures=0 / errors=0 / skipped=0。临时MySQL8.0.34目录`/private/tmp/iqc-mysql-validation.K2c6MW`，随机schema `iqc_rollout_service_a6da8bae83d4`已删除（目录无），服务器日志Shutdown complete，pid/socket均无。正式迁移55项至1.1.74、validate75、再次migrate0。

范围：真实Spring代理任务/修订服务、InnoDB、Mapper及只读执行守卫；scope与发布来源解析继续为mock，发布版本为与合成Prepared一致的数据库夹具。未执行真实发布准入/完整worker/模型/角色/HTTP验收，不能把这11项升级为全链路完成。未改生产源码、framework、A的加权融合文件或共享数据库。

## 2026-10-20 worker拒绝与真实事务状态

新增2项（其中缺失/空信封同测两种情况），原36997 terminal exit0，`/tmp/iqc-b-rollout-worker-mysql.log`，本轮fresh XML共13项，零失败/错误/跳过。直接调用真实InspectionExecutionService.executePersisted，使用真实AssetService/ExecutionGuard/Task/Execution Mapper及经过Spring代理的TaskExecutionStateService；检查当前轮次从QUEUED领取后在三个拒绝场景实际落FAILED，并保存原来源错误原因。旧SUCCEEDED轮次完整数据库行及任务冻结JSON不变，任务failed_messages=1；消息Mapper、TaskItem Mapper、LLM与PipelineEvaluator均无调用。

拒绝场景为reserved灰度任务ID缺失信封、信封null、原未选候选发布正文损坏。管线源解析与scope仍mock、pipeline evaluator仅前置拒绝探针；worker为直接实例调用，状态服务有真实事务代理，未证明worker异步代理、成功检测/完整生产或真实模型链路。

临时MySQL8.0.34目录`/private/tmp/iqc-mysql-validation.MwB1UM`，schema `iqc_rollout_service_a7ba93514fd5`已删除，pid/socket无且日志Shutdown complete。正式迁移55至1.1.74、validate75、remigrate0。注入的预期异常有ERROR堆栈，以进程exit0与本轮XML证明测试通过，不将日志堆栈视作未处理失败。无生产/framework/共享SQL/push/部署。
