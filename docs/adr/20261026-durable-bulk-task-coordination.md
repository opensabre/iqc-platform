# 百万会话批次的持久任务协调

## 复用与缺口

已检查InspectionTask/TaskExecution/TaskItem、DurableInspectionWorker、InspectionExecutionService、周期调度与ScheduledActorAccessService。原任务已有持久队列、心跳/陈旧恢复、暂停取消、首次生产输入冻结和幂等创建；原Selector新增ALL游标扫描。单任务1000和输入16MiB限额继续保留。TaskItem以消息为单位，messageId不能冒充子任务ID；周期实例的时间槽不能冒充分片序号。现有模型缺少可索引的父子关系和与不可变快照分离的协调检查点。

不新建队列、执行表或租户/RBAC模型。最小扩展iqc_inspection_task：bulk_parent_task_id、bulk_shard_no，以及仅父任务使用的bulk_progress_json。父子二元唯一索引使恢复/多实例竞争不能产生同一片的两个子任务；普通任务三列NULL，不改变历史语义。字段不含用户令牌。进度不进入默认序列化/列表SQL。迁移只在本地代码登记，禁止直接应用共享数据库。

## 协调契约（待完整实现后开放）

父任务类型PIPELINE_BULK。原ruleSnapshot保存发布版Prepared、Selector定义、毫秒锚点和原负责人CreationBound，创建时冻结Hash。进度版本iqc-bulk-progress-v1单独保存lastScannedId、nextShardNo、scannedConversations、selectedConversations、scanComplete，计数不能借用totalMessages（消息单位）。父任务的TaskExecution继续管理租约与状态，不创建伪消息TaskItem。普通worker必须先分派父任务到协调逻辑，禁止将其空消息误判成功。

每片在短事务内锁父任务及当前execution，检查RUNNING/暂停取消和当前负责人授权，复用scanAllPageAt；最多1000候选，零匹配仍提交前进游标。选中会话通过原createUsingPipelineSnapshot冻结，不重复执行模型。子任务父ID与序号同事务落库；该片子任务、首次输入、游标与统计必须一起提交，失败一起回滚。以父ID+片序号派生幂等请求，重入先检查已冻结子任务；不得为已提交片重新读取可变会话。每轮只物化有界片并让出执行资源，不能在持有父锁时等待子模型调用。

子任务走原队列/执行器。父协调结束扫描后仍须等待子任务终态，才汇总成功/失败；扫描完成不等于质检完成。计数明确分片、会话、消息三个单位，不构造全池估算。失败子任务继续由原重试/恢复入口维护，其同一冻结输入不重选。父暂停停止新片与新调度并传播已在途子任务的暂停请求；取消停止新片并传播取消，待在途回收后终态。父恢复保留原execution/游标/子任务关系，不重建已提交片。删除父任务前必须处理子任务引用，禁止孤立或隐藏运行子任务。

当前组织权限每轮重新解析，固定要求由服务端选取，复用ScheduledActorAccessService.resolve(bound, grants)及IqcDataScope.asScheduledActor。原owner/group/all上界和当前授权求交集；不得用system身份扩大范围。父任务的来源可见组必须取流水线、选数证据和各子任务范围交集，不能只保留创建人的组。业务读取和恢复仍核验原功能授权。

每页RR与创建水位不等于百万数据跨页全局快照；发布版本固定、每片首次输入固定、已提交片不可变是明确语义。ALL之外全局抽样暂不走此协调入口，不能逐片随机近似。

## 发布和验证边界

V1.1.77先迁移再部署使用字段的应用；回退保留可空列和索引，不删除已运行协调证据。当前只实现持久字段基础，不开放父任务创建或声称百万功能完成。后续须完成事务物化、worker分派/心跳、父子状态传播、原API/UI与报告范围，以及跨重启/并发/撤权/重复恢复和百万容量证明。Root仅编译，测试由B独立负责。

## 原子物化实现进展

新增BulkTaskMaterializationService内部create/materializeNext，构造函数注入原mapper/asset/selection/task服务。create须先有固定原创建与执行grant、已校验原负责人上下文，冻结发布版本/定义/首次毫秒锚点；稳定请求返回原父任务与锚点，禁止换定义。父记录保守owner-only（无共同组），原totalMessages保持0，不混用会话单位。父冻结定义16MiB保护，进度4KiB保护。

每步RR事务锁父任务，核验当前EID及RUNNING执行/停止标记、固定授权与原scope上界，重新检查发布版本可用且Hash相同，再读一个扫描页。子任务复用原私有流水线物化路径（通过MANDATORY内部边界）冻结完整Selector证据/生产输入；父片引用、唯一索引和游标/计数在同事务提交。已提交片不会由当前游标重读；检查点指向已有分片时显式拒绝不一致，而不是重新冻结。零匹配页同样前进。子任务此阶段保留CREATED，未调用模型/排队，待后续调度在锁外执行。

普通queue/run/resume的共享前置明确拒绝PIPELINE_BULK，防止未接入协调分派时按空会话误判成功。本阶段没有HTTP入口，不代表完整父任务可执行。仍须接入原租约/心跳/分派、状态传播和公开体验，再做真实中断/并发/撤权与容量验证。

真实存储精度已由B确认：会话created_time为DATETIME(0)。Selector ScanPage v2额外返回createdBeforeExclusive整秒开界，排除整个锚点开放秒，保持首次锚点且不假装毫秒快照。每个有数据子任务原ruleSnapshot追加bulkShardSnapshot：父ID/片号/页面Hash/锚点/明确整秒截止/前后游标/扫描数/hasMore；保留原选数与输入Hash，不凭一个无法重构的页Hash声称有完整截止语义。

下一轮独立验证需要覆盖：幂等create保留首次锚点/快照，名称/定义/版本/owner变更拒绝；缺grant/过期上下文/错误EID/停止标记在会话IO之前拒绝；零匹配及空尾页不创建子任务但推进/终止；首次子任务完整选数与生产输入/知识库/校准快照沿用原验证；子insert、来源保存、父片引用或进度update任一步失败全部回滚；并发两步串行只消费各自一页，已提交片会话变更不改变原输入；移除资产/撤权后停止，不用system绕过；DATETIME(0)整个开放秒排除与原普通七采样不变。尚不能通过上述窄范围证明父任务已经能调度/恢复/传播状态或百万容量。

## 生命周期与原worker接入进展

BulkTaskLifecycleService复用原TaskExecution，为父任务创建或恢复执行轮次；worker构造函数保留原调用签名，通过ObjectProvider分派批次到BulkTaskCoordinator。没有第二个调度器/线程池/消息队列。父任务每个tick只处理一片，上一片未终态不继续扫描；片内仍原1..32并发。协调tick结束把同一执行轮次退回持久QUEUED，并更新心跳，不占线程等待模型。相同worker优先级/容量上限继续生效。

租约声明在Task→Execution锁顺序下确认QUEUED，再转RUNNING；每步同事务提交物化与原子子任务入队。yield仅更新父状态/心跳，不用旧实体覆盖刚提交的新游标。原陈旧执行回收对父任务重新入QUEUED而非错误地终结，保留EID/快照/游标及停止请求，重启后的tick重新取组织授权。

原暂停扩展为支持排队任务：普通排队子任务与执行轮次同事务PAUSED，运行中的仍请求后等待原worker回收。批次父暂停/取消需等待子任务静止/终态；父任务不能在孩子仍执行时直接声称暂停或取消。后台仅可在已有停止请求下撤回该父任务精确关联、同创建人且PIPELINE类型的子任务，不选数、不排队、不返回会话数据；因此创建权限撤销后仍可停止已授权工作。授权/资产/物化失败保留检查点并进入停止推进流程，待子任务静止后PAUSED供显式恢复，不新增预算自动恢复。

普通queue/run/resume仍拒绝批次，下一阶段必须为现有任务REST入口接精确类型分派及当前组织授权；当前没有公开批次创建入口。原单条删除暂拒绝父/子，防止清理父关系或隐藏运行子任务；后续需实现终态统一清理。父终态使用按状态的有界聚合，不读取百万原文；消息计数仍不拿片数冒充。

以上只为当前实现约定，编译不替代真实Spring事务/竞争租约/中断恢复/暂停取消传播及容量证明。B需专项独立验证，完整百万与PRD尚未完成。

## 原API与原界面接入（2026-10-27）

复用POST /api/iqc/pipelines/{id}/tasks的Run请求，可选bulk.scanSize显式启用流式ALL批次。只接受精确已发布版本+内联ALL，拒绝混合IDs/Selector资产，不改变原普通七采样路径。服务端固定要求原pipeline:task:create与task:execute两个grant；当前组织capture/resolveCurrent/asCurrentActor在事务之前，事务内仍核验原owner上界。关闭原worker时拒绝创建/启动批次，不把无人调度的QUEUED当成功。未新增HTTP资源码或权限模型，旧Run构造签名保留。

原run/resume先用受组织范围保护的少量metadata判定类型，批次走原持久worker协调，普通任务仍原queue/async。原任务目录可选parentTaskId，先核验父任务类型与可见性，再以原子任务owner/group/all范围分页查询，不因父可见扩大子范围。原列表继续排除进度大字段及冻结原文，详情/命令回执仅投影经共享有界解析器验证的扫描计数（十进制字符串）、页数和扫描完成标记，不返回游标/负责人/Selector/Prompt。损坏或缺失进度保持未知，不能显示0或100%。

前端复用发布版本任务表单的显式全量分片开关，只有内联ALL和原执行grant可启用；请求首次生成后冻结全部字段，沿原请求重试。任务目录区分父协调与消息进度，详情区分扫描完成/任务终态，分片入口复用原目录筛选。父重开终态仅重新汇总已保留子任务，不自动重新调用失败分片的模型；单独分片原重试仍保持首次输入。父/子统一终态清理与跨分片报告尚需继续，不能把界面入口当百万容量或整链验收通过。

## 终态统一逻辑清理与锁顺序（2026-10-27）

复核原TaskService.deleteTerminal、InspectionExecutionService.queue/resume及AutomaticRetryService.fire后，统一清理继续用原DELETE任务接口。父须原可见范围及终态，持父锁以仅ID/LIMIT1探测所有分片均终态（或已隐藏），再一次SQL隐藏终态分片并停其自动重试，重新仅ID探测保证没有未隐藏分片，最后隐藏父。整个事务失败一起回滚；不删除输入、结果、执行轮次或审计，无新表/服务/队列。单独删除子分片仍拒绝，必须从父批次处理。这里只是逻辑目录清理，不将其计为容量验收。

为关闭“探测后又启动分片”竞争，所有手动queue/resume、queueSystem、queueRetrySystem先以索引标量读取bulk_parent_task_id，按父→子锁顺序检查。父删除/取消/暂停阻止独立启动分片；标量读取与子锁之间归属发生变化时显式重试，不能跳过父锁。原普通任务多一个metadata标量，不读额外冻结原文、功能授权与源验证保持。父暂停恢复在同一事务先将父/EID置QUEUED，再恢复原子任务，失败整体回滚。

原自动重试fire自身先锁子再调用queueRetrySystem，会破坏新锁顺序，因此原AutomaticRetryService.lock复用同一个MANDATORY父锁边界，包含configure/availability/peek/fire；配置路径可在暂停中管理策略，但删除父禁止编辑，真正排队仍须可运行父。没有第二套重试或租约。后台回收/worker原claim只锁子，不反向请求父，已排队/运行分片会让清理探测拒绝。

独立验证必须覆盖父/片任一非终态拒绝且无修改、零片父、全体终态原子隐藏、清理中任一步失败回滚、两个清理者与手动/自动重试/恢复竞争、归属中途变化、父已删后不能借原分片ID启动、原历史结果Hash/输入/执行/审计保持、普通任务路径兼容。Root只编译，B负责测试。跨分片报告与真实百万/恢复/PDP等仍未完成。
