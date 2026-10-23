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
