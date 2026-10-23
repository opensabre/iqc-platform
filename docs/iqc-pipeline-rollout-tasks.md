# 灰度策略任务路由、幂等与冻结来源

## 适用边界

新增 POST /api/iqc/pipelines/{id}/rollout/tasks，首次请求读取当前保存的策略，实际选择基线或候选精确发布版本，并通过原 InspectionTaskService 物化生产任务。旧的固定版本、草稿试跑、Selector、周期/事件创建入口保持各自原协议；首批灰度入口支持显式会话IDs；2026-10-23增量支持已发布Selector，见后文，周期/事件自动灰度仍待集成。

新入口只创建任务，执行仍通过原任务执行授权、队列、worker和状态机。保存扩量、替换或ROLLED_BACK修订将影响后续灰度新请求；已有任务的分配、冻结输入、状态和版本不被重写。不默认迁移所有旧调用方到新入口。

## 复用与事务

已核对原Pipeline任务主键幂等、Selector重放、生产输入冻结及安全回执能力。直接扩展原物化器的可空内部灰度证据参数，不新增任务表、请求表、队列或新的执行器；Prepared的原序列化协议不变，新增证据放在原ruleSnapshotJson的rolloutSnapshot中。不修改framework。

PipelineRolloutTaskService在原Spring本地事务内取得原流水线FOR UPDATE锁，策略修订也持有同一锁，因此跨实例首次创建与策略改变串行。先按稳定账号ID、流水线ID和请求键计算独立任务主键，再锁定读取既有任务。无旧任务时才锁定读取当前最高策略修订、原资产及两侧发布版本（当前读），验证发布摘要、发布证据及单变量比较，计算确定性决定，调用原物化器。原资产活动状态直接取自锁定行，不回到外层RR旧快照检查；原修订重放读取同样使用当前读。

新任务主键为g+精确JSON命名空间/账号ID/流水线ID/请求键摘要的前63位。请求指纹独立于策略、版本与比例，包含名称、默认化并发、排序去重的会话IDs。请求键须1–128位字母/数字/冒号/下划线/短横线；名称非空且最长100字符，显式IDs为1–1000个且逐个有效，并发1–32。修改名称、并发或会话集合必须使用新请求键；调整集合顺序或重复项不改变规范意图。

## 冻结证据与重放

rolloutSnapshot保存evidence/evidenceHash信封。evidence包括协议、派生任务ID、请求指纹、原不可变策略Revision（含完整Snapshot与原contentHash）、Decision（原桶、模式、选中发布版本/hash及单变量比较摘要）。账号ID与请求键仅参与摘要计算，不以原文写入该信封。

原物化器在同一事务中冻结选定发布Prepared、灰度证据、生产输入和原任务属性，沿用原来源范围收窄及标签/动作负责人协议。内部灰度证据入口不是HTTP DTO；创建前校验任务ID/请求指纹、策略/修订hash、分桶/阈值/模式、选择版本/hash以及Prepared的实际release摘要。原任务自身幂等指纹也绑定灰度证据摘要。

重放先检查当前资产数据范围和旧任务范围，再校验原信封/修订/请求指纹、重算原分桶及阈值选择、核对任务冻结的发布摘要，最后按原修订号读取不可变历史并比较原修订ID/hash。不会读取当前比例或重新冻结实时会话，也不要求旧供应商此刻可用。资产停用后可返回有权读取且证据有效的原任务；新请求仍因原发布解析要求活动资产而拒绝。证据缺失/被破坏/原历史缺失或请求内容不同均拒绝，不重新创建或自动修复原任务。

重放只返回原状态，不重新开始、恢复或重排任务。任务实际执行继续沿用当前执行安全/依赖可用性检查，本批不承诺停用供应商后仍能继续执行旧任务。

## 接口、权限与响应

请求为 {name, conversationIds, concurrency?, requestKey}，不接受客户端Prepared、策略或分配决定。并发和请求键采用局部Jackson2/3精确树解码，整数拒绝小数/字符串/溢出。

接口同时刷新iqc:pipeline:rollout:task:create与原iqc:pipeline:task:create。写入不追加敏感正文grant，任务回执沿用ResultReadProjection.taskReceipt，隐藏全部冻结配置和原会话IDs。响应只增加经过证据/来源摘要验证的rollout公开展示字段：策略修订、基线/候选、选中发布版本/hash；不返回分桶身份或Prompt。no-store/nosniff，Audit无请求/响应正文，声明20次/60秒限流。

组织权限V20261019_03注册资源/按钮900269，默认ADMIN/QUALITY_MANAGER/INSPECTOR三类IQC角色；不授系统ADMIN或VIEWER。执行任务仍需原iqc:task:execute。迁移尚未执行，不代表真实网关授权已生效。

## 界面与验证范围

复用原流水线任务表单及ConversationPicker，增加“按已保存灰度策略分配”。新请求生成一次请求键，结果不明时原command保留名称/会话/并发/请求键以原请求重试。公开回执显示实际选中的策略修订及发布版本；重新获取原任务也能得到同一公开展示信息。

Root仅编译和构建，无新增/执行测试、SQL、推送或部署。首建/策略改变后重放/回退新旧请求/并发和事务回滚、坏证据/权限/MVC、真实数据库/角色/browser及真正供应商运行仍须独立B验证。Selector/周期/事件自动灰度、AB指标归因和完整I7运行验收仍待，不能将本批编码视为全PRD完成。


## Worker与历史来源一致性校验（2026-10-19）

复用PipelineRolloutTaskEvidence.verifyFrozen检查协议、派生任务格式、原修订/策略hash、两侧hash、保存的cohort摘要与桶、阈值/状态/选中版本，以及实际Prepared的release摘要。创建/请求重放在此基础上额外用当前稳定账号和请求键重算原cohort；worker与历史后台不需要这两个原文。

PipelineRolloutExecutionGuard只读原修订及两侧原发布版本，比较原修订ID/hash和完整存储摘要，复用原发布门禁证据协议/hash/source校验（保留旧版本无门禁证据的兼容），重新执行已有BenchmarkVariableComparator并逐字段比较冻结Comparison。不会取当前最高灰度策略、重新分桶、调用模型或检查候选供应商当前活动状态。

执行开始、异步领取后的实际执行、恢复三个边界传入实际task.id。g+63位十六进制是灰度任务保留格式，必须带有灰度信封；删除整个信封或把其它任务的信封搬到当前任务均拒绝。旧非灰度入口继续原协议。现有动作生产来源及历史产物来源只对灰度路径增加实际task.id绑定，继续原任务/执行/结果归属校验。

PipelineAssetService的读取验证按当前执行/历史读取分别处理：灰度证据与原来源一致性均校验；历史不重新要求供应商或资产活动，执行仍走原依赖活动检查。缺少守卫接线时灰度路径拒绝，不回退为普通Pipeline。原14/15参数构造保留，生产构造由Spring注入新守卫provider。

灰度worker和动作来源快照使用局部精确小数解析；不修改全局mapper。公开任务展示复用同一纯冻结校验，只提取版本/侧别等元数据，不进行数据库来源查询或返回原文。守卫不缓存来源，也没有新增状态表；重复校验的数据库与比较开销尚未做容量实测。

本批只编译，未执行Root测试或数据库/供应商/真实角色/browser验证；具体终态和B后续专项以协作记录为准。

## 已发布Selector灰度任务（2026-10-23）

复用同一POST /pipelines/{id}/rollout/tasks，新增可选selectorAsset={selectorId,versionNo}。与非空conversationIds互斥；只接精确已发布Selector，不接内联定义、客户端Prepared或选数结果。引用对象只允许上述两项，versionNo须精确正整数，拒绝小数/字符串/越界；原Create四参数Java构造保留，新字段null省略。权限仍为原rollout:task:create + pipeline:task:create，Selector和Task沿既有组织范围，不新增资源码。

新路径在原流水线锁下先计算同一稳定actor/pipeline/requestKey任务身份并读取既有任务。首次才分配当前策略、锁定已发布Selector版本，调用原ConversationSelectionService.materialize，再经原任务物化器冻结selectionFilterJson与productionInputs。共享原选数定义/时间锚点/分布/IDs/精确hash协议及来源组交集，未复制抽样算法。选集为空/超出批次上限沿原人工Selector创建约束拒绝，不伪造已完成任务。

Selector请求指纹使用明确iqc-pipeline-rollout-selector-task-v1协议，绑定名称、默认并发及Selector ID/版本，独立于当前策略比例和动态选数结果。任务ID命名空间仍与显式列表共用，因此同请求键从列表换到Selector或更改引用/名称/并发会因不同请求指纹拒绝，不能得到第二任务。

新Selector灰度证据schemaVersion同为iqc-pipeline-rollout-selector-task-v1，保留原Evidence五字段。verifyFrozen要求该协议绑定实际pipelineSnapshot.selectorRequestHash，原列表v1不得携带Selector marker；worker/历史来源guard继续通过同一verifyFrozen验证新旧协议。显式列表请求指纹/任务ID和原v1证据不重写，也不切换默认PipelineRelease版本。

重放先核对原灰度修订/请求/实际发布来源，再校验原selectionFilterJson信封、精确定义/hash、selected IDs与任务IDs一致，并通过原Selector.viewVersion核对不可变历史版本的contentHash/草稿修订。允许当前Selector停用，但仍要求当前有权读取该资产，保留原冻结ownerGroupId，不用当前分组或可用性展示字段替换旧证据。不读取当前灰度策略、不重新调用materialize，不从实时归档重新冻结输入；原任务执行仍遵守当前原有执行可用性检查。

页面复用原SelectorAssetLibrary：灰度模式可选手动会话或已发布选择器，内联Selector仍只属固定版本任务。原command冻结选择引用/名称/并发/requestKey，失败后原请求重试；任务回执沿原敏感投影与灰度展示。

当前仅编译/构建，无新增或执行Root测试、SQL、浏览器或部署。B待验证：原列表v1/旧构造兼容；首次Selector→实际持久化/生产输入→worker；原请求在扩量/回退、Selector停用及语料变更后不重分配/不重选；跨模式/更换引用拒绝；当前组织范围、组收窄、精确版本HTTP拒绝和回执隐私；坏选数/历史丢失/并发/事务回滚及新schema worker来源校验。周期/事件灰度和A/B效果归因仍待后续，完整I7不标完成。

## 周期实例灰度（2026-10-23）

原周期定义新增显式schema iqc-periodic-rollout-definition-v1，rollout={pipelineId}指定策略所属流水线；pipeline必须缺省/null，仍固定selector精确发布版本。旧iqc-periodic-definition-v1继续要求固定pipeline版本且不接受rollout，不给旧定义改含义。原PeriodicDefinition十/十一参数构造保留，新增rollout为空时省略。周期发布快照对应iqc-periodic-rollout-release-v1，仅冻结路由意图/Selector/负责人；每个计划实例首次物化时才读取当前灰度策略。

新模式保存/发布验证当前灰度资产与两侧发布来源；发布及每次后台执行的原负责人授权额外要求现有iqc:pipeline:rollout:task:create。继续交集原owner/group/all，发布时不扩大父周期来源组。所有周期API/权限路径不变，无新增表、执行器或公共抽象。

PeriodicScheduleService保留原事务/行锁、失火、自然实例键、游标、自动重试和空数据路径。新实例在同事务内进入原RolloutTaskService：按稳定负责人/流水线/周期ID/修订ID/计划时刻派生g任务键，在原灰度锁内确定当前侧，Selector.materializeAt固定计划时刻，原物化器冻结productionInputs/selectionFilterJson/periodicSnapshot/rolloutSnapshot。先锁流水线再锁Selector，复用统一分配/来源校验，避免另一套分桶或选数算法。已存在实例不再次分配或重选；下一新计划实例受当前扩量/回退影响。失火跳过不伪造灰度检测，空选集保留NO_DATA。

新周期灰度证据iqc-pipeline-rollout-periodic-selector-task-v1，绑定实例请求摘要与selectorRequestHash；摘要不含每次变化的授权checkedAt，不会因重新取得当前授权而变成另一请求。工作器与历史来源guard通过实际Task周期列、原不可变周期版本/hash、计划时刻、父定义的灰度目标/固定Selector、原负责人稳定ID及原请求键重新核对分桶和选数证据。原Selector重放校验提取为同一纯函数，两处复用，不复制持久化模型。新Guard/周期服务保留旧构造用于旧路径，生产DI使用完整依赖；旧列表/Selector/固定周期schema不迁移。

周期页面显式选择“每实例使用当前灰度策略”，此模式只选流水线与固定Selector，切回固定模式需重新选发布版本；模板、历史、实例与原敏感读取生命周期复用。

当前仅编译/构建，无Root新增或运行测试/SQL/browser/部署。B待验证新旧定义JSON/hash与构造、发布时间/当前资源授权、实际Cron失火/空数据/游标和重试、同实例不重分配/新实例响应回退、计划锚点/组范围、并发锁序和事务回滚、worker/历史对坏周期版本/计划时间/负责人/Selector证据的拒绝及真实页面权限。事件/API/MQ灰度和A/B归因仍待下一层接入，I7/全部PRD不标完成。

## 业务事件API/MQ灰度接收（2026-10-23）

原POST /api/iqc/triggers及可选Rabbit接收器新增显式iqc-task-rollout-trigger-v1请求。新增字段片段为`rollout:{pipelineId}`，pipeline须缺省/null；source/eventId/eventType/occurredAt/expiresAt/name/目标或处理后正文、Selector、并发、重试政策等继续沿原TaskTriggerRequest协议。旧iqc-task-trigger-v1固定发布版本含义不变，不接受非空rollout目标。旧13/14参数请求构造、Accepted八参数和TaskTriggerService旧构造保留，新增空字段省略。

灰度分配时点为原REQUIRES_NEW持久接收事务：先按原稳定负责人/source/eventId查询旧记录，同载荷返回旧任务，不同载荷拒绝；仅首次读取当前策略/两侧发布来源并冻结所选Prepared及灰度证据到原Accepted。原t任务ID命名空间和旧payload/request hash算法不改，同事件切换固定/灰度目标属于载荷变更，不能创建第二任务。等待输入、重复API/MQ投递均不再读当前策略；后续新事件才受扩量/回退影响。

API新模式用当前负责人投影检查原触发/创建/执行及现有rollout:task:create授权；MQ仍由部署固定身份先resolve当前组织授权，再在收窄scope内接收。Rabbit允许pipelineIds集合通过统一targetPipelineId读取固定/灰度目标，事件类型/source/content限制与默认关闭不变。没有新增资源、接收表、执行器或传输确认算法。

物化继续检查当前组织授权、已冻结的所选精确发布版本/Selector可用性、目标输入修订和原发生时刻选数，继而原工作器排队。新Rollout证据iqc-pipeline-rollout-trigger-task-v1绑定原t身份、payloadHash、事件稳定请求键和原cohort/修订；原triggerSnapshot与productionInputs同时保存，检测时不重新分配。

工作器/历史来源guard读取原持久接收记录并共享readAcceptedEvidence纯校验，核对请求/载荷/负责人/原分配、triggerSnapshot时刻/类型/输入修订/过滤结果及冻结输入，Selector存在时复用原选数证据校验。已知t任务在无rolloutSnapshot时也读取原接收类型：固定请求正常继续，灰度请求缺标记拒绝，不能降级成固定任务。此事件来源校验需完整Guard依赖；旧简化构造仍供非事件旧路径。原三参数TriggerIdentity与旧触发物化调用保留，服务端不能丢失已接收的灰度证据。

接收回执仍为原标量Receipt；等待输入时不构造假的执行快照/分配展示，实际物化后的任务继续原安全灰度展示。当前Root仅编译/构建，不新增或运行测试、SQL/broker/browser或部署。B待验证旧JSON/hash/ID及构造、固定/灰度互斥、API/MQ当前授权、首次冻结/等待期间扩量回退/同事件重投不读新策略、载荷冲突/过期/失败回滚/ACK联合、实际物化worker/Selector/NO_DATA/组范围、丢失标记和坏接收/时刻/输入证据拒绝。归档订阅定义仍固定版本，下一层接入同一能力；I7和全PRD不标完成。

## 归档订阅接入（2026-10-23后续增量）

归档订阅新增显式灰度定义与发布协议，复用上节事件接收分配：订阅发布冻结路由意图，每条新归档事件首次持久接受时冻结当前策略。原固定协议与已有任务保持原含义，详见 [归档订阅](iqc-event-subscriptions.md)。此前章节的“归档订阅待接入”是历史阶段记录；本增量代码不替代真实运行验证或A/B效果归因验收。
### Selector / 周期灰度组合兼容验证（B，2026-10-23）

固定 A 后端 b2765c4 无冲突并入 B a7bc903，UI 99b467f 并入 73e89d7。
原 96504 offline test-compile+surerfire 终态 0，日志
`/tmp/iqc-b-periodic-rollout-integrated-tests.log`：PeriodicDefinitionTest 8、
PipelineRolloutTaskReplayTest 9、PipelineRolloutExecutionGuardTest 8、
ScheduleConfigurationHttpPrivacyTest 19、PipelineRolloutHttpPrivacyTest 31，
fresh XML 共 75 项零失败错误跳过。UI 原 44202 type-check 终态 0，日志
`/tmp/iqc-b-periodic-rollout-ui-typecheck.log`，仍有既有 Node/pnpm 声明差异。

本轮复用既有兼容/重放/guard/受控 MVC 测试，不能替代新周期灰度真实 tick、计划时刻、
扩量回退/同实例重放、事务回滚/并发游标、组织授权及 worker 来源冻结专项，也不是 UI
浏览器交互或实际 PDP 验证。Rabbit 注解容器 5 项独立结果绑定此前 d7cddb9，不外推本组合。

### 周期 Selector 灰度持久化专项（B，2026-10-23）

在既有 IsolatedMysqlRolloutServiceIT 中接入原 SelectorAssetService/版本 Mapper 与真实
ConversationSelectionService/Conversation Mapper，复用原灰度修订服务、事务与任务物化器。
新增三个 SQL 用例验证：

- 首实例基线版本 1/修订 1；扩量后新实例候选版本 2/修订 2；回退后新实例基线版本 1/修订 3。
  选数锚点与任务计划时刻一致。Selector 停用和会话正文变化后，旧实例重放保留原任务、
  ruleSnapshot/selectionFilter 与原会话正文；新实例拒绝已停用 Selector。
- 真实 UPDATE trigger 注入写入失败，外层事务回滚后不留实例；解除 trigger 后同实例成功，
  再次调用仍为原任务。
- 空选数持久化 NO_DATA/零消息；策略扩量后原空实例仍沿用原冻结快照。

PeriodicDefinitionTest 增加 3 项：新灰度协议往返、计划算法一致及额外既有 grant；旧 10/11
参数构造 JSON 一致且不新增 rollout；新旧目标/协议混用及坏 ID 拒绝。

原 91050 首轮 24 项 23 过、1 项测试时间数值节点类型比较失败；改为反序列化 Instant 的
语义比较，并补回退/协议断言。原 16457 compiler:testCompile+surerfire 终态 0，日志
`/tmp/iqc-b-periodic-rollout-sql-final.log`，fresh XML SQL16+协议11=27 零失败错误跳过。
随机 schema 完整迁移/75 校验/二次 migrate 无变化后测试并删除，专用 34307 自动关闭，socket 消失。

本轮实例身份直接进入原 createPeriodicWithSelector，尚未经过 PeriodicScheduleService.fire
的 tick/游标/入队，也未验证新周期 worker 来源 guard。scope 与 pipeline 发布比较源仍受控；
Selector 发布版本、选数、输入、任务与灰度修订为真实 SQL，不外推实际 PDP 或供应商链路。

### 周期 fire / 游标事务专项（B，2026-10-23）

在同一隔离 SQL 夹具注册原 PeriodicScheduleService 及周期/修订 Mapper，加入五项测试。
周期修订快照由夹具 SQL 写入，fire、选数、分配、任务写入和游标更新走生产代码与真实事务：

- FIRE_LATEST 迟到 615 秒时冻结原计划 +600 秒，选数锚点一致，游标到 +660 秒；
  实际物化采用本轮扩量后的候选。旧 Frame 再调用返回 false，不再调用入队。
- 末端入队边界抛错，实例插入和游标一同回滚；同 Frame 可重新成功。
- SKIP 保存 SKIPPED_MISFIRE、空选数保存 NO_DATA，均前移游标且不调用入队。
- 两个真实事务竞争同游标，仅一次返回 true、一次物化及一次入队调用，游标仅前移一格。
- 缺少原 rollout:task:create 或授权 checkedAt 过期，均不留实例、不前移游标、不调用入队。

原 53757 首轮 30 项 29 过，1 项测试将 JDBC LocalDateTime 与 Timestamp 比较失败。
修正类型断言并补并发/授权用例后，原 54008 compiler:testCompile+surerfire 终态 0，日志
`/tmp/iqc-b-periodic-fire-sql-final.log`，fresh XML SQL21+协议11=32 零失败错误跳过。
随机 schema 完整迁移、75 校验及二次 migrate 无变化后测试并删除；34307 自动关闭，socket 消失。

InspectionExecutionService 为受控入队边界，成功调用没有真正建立执行轮次，不能将其称为
QUEUED/worker 成功。本轮不覆盖周期 create/publish、真实 PDP 或新周期 worker 来源 guard。

### 周期实际入队验证准备与审计字段诊断（B，2026-10-23）

在原隔离 SQL 用例内接入真正的 queueSystem、TaskExecution/TaskItem Mapper 与生产输入快照，
PipelineAssetService 的依赖适配受控，但 validateTask 委托原 PipelineRolloutExecutionGuard。
新增用例目标为实际 QUEUED/EID/冻结消息检查点、旧游标不重复入队，以及检查点写入失败时
任务/执行/游标共同回滚；另核对坏周期来源由原 worker 拒绝，不能据此宣称 worker 成功执行。

初次诊断原 58001 失败在周期 guard 第 84 行；原 28767 明确数据库任务 createdBy 为 null，
而原周期负责人为 actor-1。隔离配置在 addMapper 后才设置审计填充器，改为解析 Mapper 前
注册原 PoMetaObjectHandler。原 6070 又因提前注册的 GlobalConfig 缺少 DbConfig，SQL 上下文
启动失败，协议 11 项通过；修正为 GlobalConfigUtils.defaults() 保留完整默认配置后继续。
这些均为测试配置诊断，未修改框架或生产来源校验，尚不能记录新增 SQL 用例通过。

当前复验原句柄 34657，日志 `/tmp/iqc-b-periodic-real-queue-audit-defaults.log`；需读取原终态
及 fresh XML 后再追加结论。负责人上下文为受控 actor-1，不替代真实 PDP；周期发布快照仍由
SQL 夹具提供，且未覆盖完整 Pipeline 依赖校验或实际供应商成功执行。

### 周期实际入队与回滚验证终态（B，2026-10-23）

原 34657 终态 1，SQL23+协议11中33通过、1错误；实际入队/检查点及回滚断言已走过，
后续 worker 拒绝辅助方法固定插入 attempt1/2，与真实入队 attempt1 冲突。改为读取任务已有
MAX(attempt_no) 后追加独立拒绝轮次，不改生产代码。原 50769 compiler:testCompile+surerfire
终态 0，日志 `/tmp/iqc-b-periodic-real-queue-attempt-fixed.log`，fresh XML SQL23+协议11=34
零失败错误跳过。专用 MySQL 自动关闭，socket 消失。

新增两项证明：原 fire 事务调用真正 queueSystem 后保存 QUEUED 任务、attempt1/EID 与冻结
消息的 PENDING 检查点；旧 Frame 不再新增执行；实际来源 guard 接受原快照、拒绝被篡改的
周期修订列。独立构造后续轮次经过原 worker，持久 FAILED 且不调用模型/会话评估，保留
旧轮与规则快照。检查点 INSERT trigger 抛错时任务/执行/游标共同回滚，解除故障后同 Frame
可以真正入队。真实已入队轮次的成功 worker 运行、完整依赖校验、实际 PDP 和周期发布流程
仍不在本轮证据范围；不将独立拒绝轮次称为原入队轮次的成功执行。
## 生产灰度观测统计（2026-10-23）

复用原业务统计接口和BusinessQualityReportService。显式选择1–20个任务及原执行轮次口径，保留组织可见范围、500个任务会话和50000项目上限；正式业务与评测任务隔离。灰度任务读取原rolloutSnapshot，复用PipelineRolloutExecutionGuard核对实际任务、不可变修订/发布来源、原选择及周期/事件专用证据。缺证据的已知灰度任务不能当普通固定任务统计；坏证据拒绝整份报告，不静默跳过。不会读取当前灰度比例替代历史分配。

PolicyGroup新增可空rollout，schema为iqc-business-rollout-attribution-v1，只返回修订ID/号/Hash、策略Hash、侧别、原状态/候选比例、基线/候选/所选版本与所选发布Hash；不返回分桶盐、原actor/requestKey、cohortHash或完整比较配置。原DTO及服务构造保留，普通组不增加JSON字段。组身份由原冻结标准加该分配投影派生，拆开不同修订/侧别及非灰度来源；FIRST/LATEST去重同样在该组内，不能跨修订抹去另一组。

评分、项目失败率、机器/人工对照、人工修改率与缺结果/复核覆盖复用原计算。页面显示生产灰度观测及冻结来源摘要。分桶以任务为单位，任务包含的会话数可能不同，所选范围也非全部生产流量；候选配置比例不代表实际观测会话比例。跨版本评分标准或语料不一致不能直接解释为因果提升。同Dataset受控配对对照继续使用现有Benchmark，不复制另一套评测。生产费用/时延关联、统计显著性及完整A/B验收继续。

本批仅编译/构建，终态见协作记录；独立验证须覆盖新旧JSON/构造、同修订两侧和不同修订/非灰度拆组、FIRST/LATEST分母、缺失结果与复核覆盖、旧修订回退不改归属、坏证据/撤权/跨组拒绝、SQL/实际worker产物与页面隐私生命周期。

### 事件/订阅/生产归因组合兼容验证（B，2026-10-23）

固定 A 后端1c29c49与B4f9b3c1合并为64f7582，文档追加冲突保留双方；UI固定d73fd41
与B73e89d7合并为5d6ae7c。后端原41290 offline test-compile+surerfire终态0，日志
`/tmp/iqc-b-trigger-attribution-integrated.log`，fresh XML报告19/guard8/重放9/周期协议11/
订阅精确快照1/调度HTTP19/灰度HTTP31共98项，零失败错误跳过。UI原34590 type-check
终态0，日志 `/tmp/iqc-b-trigger-attribution-ui-typecheck.log`，既有Node/pnpm声明差异仍提示。

这是合并后的既有兼容/受控MVC回归，尚非新增事件/API/MQ灰度接收、归档扫描首次分配、
生产归因双侧/修订分组和FIRST/LATEST的专项，更不是真实身份或供应商端到端验收。
此前周期SQL34项仍绑定4f9b3c1，不能自动外推合并后的代码。

### 生产归因真实来源 SQL 专项（B，2026-10-23）

基线2fe3ca8扩原隔离SQL测试两项，真实灰度服务/任务/版本/修订与完整guard/报告实现：
0→10000→0产生原修订1/2/3，修订1同标准两任务。SELECTED保留4个任务会话，FIRST/LATEST
在同修订内去重为3个且不跨修订吞掉历史；各组保留原所选版本/侧别。结果Mapper受控为空，
所有任务会话均记缺结果，机器/人工resultCount=0、均分null；公开报告无salt/cohortHash/
requestFingerprint/requestKey/actor-1。已知g任务缺灰度标记及未选候选原发布内容损坏，均
在结果Mapper读取前拒绝报告。

原20406 compiler:testCompile+surerfire终态0，日志 `/tmp/iqc-b-rollout-attribution-sql.log`，
fresh XML SQL25+周期协议11=36项零失败错误跳过，含合并后周期入队回归。专用34307自动
关闭且socket消失。没有实际worker结果/复核数据、同修订双侧、普通来源同配置、真实PDP或
浏览器证明，不将空结果适配称为完整生产A/B验收；费用统计a8073d1和归档父守卫7427f4a
尚未合入本轮基线。
