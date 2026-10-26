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
