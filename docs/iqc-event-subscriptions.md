# I4 自动归档事件订阅

## 实施前复用与缺口

复用原 Conversation/Message 原子归档作为持久完成事实，Task 事件接收作为唯一消费凭据、Pipeline/Selector 不可变发布资产以及当前负责人授权解析。EDA 的 afterCommit 通知不具备持久确认/重试，不能承担恢复。Cron 的计划时刻/错过策略不表达归档事件匹配，原 Task 接收也不保存可复用的订阅配置；新增仅负责订阅草稿和不可变修订的两表，不复制归档、消息、授权或任务执行模型。无需新增框架代码或消息基础设施。

没有高水位游标：NOT EXISTS 任意历史状态的接收记录发现归档，避免早创建、晚提交遗漏。归档完成时间使用持久归档记录 createdTime 的 UTC 时间，不宣称是上游通话/ASR 的实际结束时刻。已完成 ASR 归档按原状态判定，新修订使用新输入身份；没有实现修改既有归档的 ASR 写回。

## 协议与配置

`/api/iqc/event-subscriptions` 提供目录、详情、创建、按 expectedRevision 修订、publish、availability、versions、instances 八项接口，权限分别为 `iqc:subscription:view/detail:view/create/update/publish/availability/versions:view/instances:view`。维护只授予 IQC_ADMIN/QUALITY_MANAGER，查看沿用四 IQC 角色；复用既有周期调度菜单，通过页面入口进入订阅页。

定义 `schemaVersion=iqc-event-subscription-v1`：

- eventType：ConversationCompleted（会话归档完成）或 ASRCompleted（归档已经携带完成 ASR 标记）。
- effectiveFrom/effectiveUntil：明确归档记录时间范围 `[from,until)`，until 可空；归档 created_time 是秒精度，配置规范化到整秒后重验区间。
- waitSeconds：1–604800，首次接收时生成未来等待截止时间；不按历史归档时间计算过期，支持明确选择旧归档范围。
- concurrency：1–32；pipeline 必须为明确已发布 pipelineId/versionNo；selector 可空，否则为明确已发布 selectorId/versionNo。

新建及发布后均 DISABLED，须显式启用。更新草稿不改变活动发布版本。发布冻结名称、定义、Pipeline/Selector 发布快照和负责人原范围；相同草稿修订重复发布返回同一版本。新版本拥有新的事件身份，因此会再次接收其有效范围内的旧会话；若只处理后续归档，应明确设置新的 effectiveFrom。停用只阻止后续接收，不撤回已接收的 Task。

## 持久发现与权限

`iqc.subscription.enabled` 默认 false，启用后每 5 秒发现至多 25 个订阅，每个至多接收 2 个归档（合计至多 50）。lastScannedAtUtc 只用于公平排序，不作为排除历史记录的高水位。使用已完成导入、messageCount>0、有效期、可见范围及可选 ASR 状态筛选；任意历史 Task 接收（含取消、删除、冲突、过期）都阻止同事件再次接收。

source 固定 `archive-subscription`，外部 POST triggers 禁止使用该保留来源。eventId 使用订阅修订 ID、会话 ID、输入 sourceFingerprint 的 UTF-8 字节长度分帧 SHA-256，参数化 SQL 与 Java 同一算法；输入不作为 SQL 表达式拼接。归档事务提交后才能被发现，无高水位意味着晚提交的旧 createdTime 不会遗漏。

后台先通过组织服务 fresh 投影检查订阅原负责人的稳定身份、有效账号和四项服务器固定权限（Pipeline 创建、Task 执行、trigger 接收、subscription 发布），数据范围取创建上限与当前范围交集；同步范围 callback 不保存用户令牌。订阅行锁内重验活动修订/hash和授权时效，沿同一事务写入原 Task WAITING_INPUT 及实例引用，成功后才记录本次扫描。实例分享不超出订阅、输入和资产的范围交集。旧 Frame 或启停/发布竞争不能给失效修订新增接收。

原 trigger dispatcher 继续进行目标修订/ASR/Selector 检查、冻结和入队；已接收订阅任务执行前仍要求当前订阅发布权限。缺少当前授权、资产不可用或存储失败回滚整轮，记录通用错误并退避 30 秒；没有自动移交负责人或伪造管理员上下文。

## 交付边界

V1.1.46 新增两张配置表及 Task 实例引用/归档发现索引；没有新 Inbox/归档/消息/执行表。运行订阅还须启用 `iqc.trigger.enabled` 和既有持久执行 worker，并按发布流程应用该 DDL 与组织权限 V20261004_16。

该路径提供 IQC 内部归档自动订阅；投诉/订单等外部事件已有直接 API 持久接收，真实 MQ 的消费者身份、消息确认和上游投递仍需后续适配。当前输入均为处理好的会话；ASR 转写生成、音频转换、说话人分离及离线音频处理不属于本任务范围。全局预算、自动失败重试和不可变失败尝试历史尚未完成，不能据此标记 I4 或全部 PRD 完成。

本批仅编译和前端构建，没有新增/运行测试、执行迁移、真实角色/浏览器/模型或部署；SQL anti-join 与多实例事务行为仍需运行验收。

恢复诊断补充：非法/缺失输入指纹按 MySQL8 固定 REGEXP_LIKE 规范排除，并仅在服务日志记录订阅 ID，不向共享订阅暴露私有归档 ID 或数量；避免毒源堵住后续合法会话。无法解析发布证据时同样仅更新 ACTIVE 订阅的公平时间和退避，不改发布指针或生命周期。

## 2026-10-23 外部MQ接收增量

应用内新增可选RabbitMQ适配，复用原外部TaskTriggerRequest/accept幂等事务和当前组织授权，不改变内部archive-subscription流程。配置固定来源/负责人/资产允许集合，提交后手动ACK，失败死信供原身份重投；默认关闭。详见iqc-rabbit-triggers.md及部署示例。当前仅编译，实际broker/SQL/角色/运行验证待完成，不将源码接入冒称I4全部验收通过。历史ASR/音频表述仍服从迭代页首当前范围：仅处理后会话，不开发语音转换。

## 归档订阅灰度目标（2026-10-23）

显式 `iqc-event-subscription-rollout-v1` 定义使用 `rollout.pipelineId`，与 `pipeline` 互斥；固定 `iqc-event-subscription-v1` 与原八/九参数构造保留。发布 `iqc-event-subscription-rollout-release-v1` 冻结路由意图、精确可选Selector及负责人，不提前分配流水线版本。发布及后台发现要求当前组织授权中已有 `iqc:pipeline:rollout:task:create`，同时保留原订阅/任务/触发/重试策略资源要求。

原订阅行锁及事务内，每个归档事件经原 `receiveSubscription` 和业务事件灰度接收器，第一次接受才读取当前策略并冻结选中Prepared和分配证据；重放先查原回执，不重新分桶。Selector仍与发布时的精确快照比较，停用或变化不能悄悄更换来源。原来源保留为内部 `archive-subscription`，外部API/MQ仍不能使用；revision/conversation/fingerprint派生事件ID、扫描反连接、输入修订、等待及自动重试不变。组范围继续取负责人、流水线、Selector及会话原范围交集。

本批实现与编译结果见协作记录。真实订阅发现/事务回滚/并发、发布后扩量回退及已接收任务不重选、当前组织撤权、物化工作器和页面权限须独立验证；不据此宣布I7或全PRD完成。

### 灰度归档任务的不可变父来源校验（2026-10-23）

EventSubscriptionService将原发布快照读取/Hash/schema/目标/Selector核对提取为同一纯readPublishedEvidence，原订阅工作流继续调用；发布正文按UTF-8字节检查既有16MiB上限，JSON null明确拒绝。新增validateReceiptParent供原灰度执行guard复用，不建立第二份订阅快照或查询当前订阅定义。

归档灰度任务除原Accepted及分配证据外，读取其eventSubscriptionRevisionId指向的原不可变版本，核对版本ID/父订阅ID/版本与源草稿修订、原负责人完整CreationBound、目标模式/流水线、名称/并发/重试配置、精确Selector及冻结来源。事件必须为原内部archive-subscription、显式conversation/inputRevision目标、无正文，eventId按原revision/conversation/fingerprint算法重算；事件类型及requireASR必须与订阅一致，occurredAt落入原有效区间。既有ASRCompleted只是已处理输入状态，不产生转写。原接收与实际任务名称/并发也要一致。

生产完整guard构造增加原EventSubscriptionRevisionMapper；旧三/五参数构造继续支持其原非归档路径，归档灰度缺父Mapper时拒绝。运行/历史/生产统计均通过原guard获同一来源保护；父修订被删除或篡改时不降级成普通事件任务。当前订阅停用或改版不改写旧版本，校验不读取当前组织权限或当前比例；当前主体/worker授权仍由原外层工作流独立负责。本批后端编译终态见协作记录，无前端协议/表/端点/权限/框架变化，实际SQL/worker与坏父版本专项待独立验证。

### 原父来源实际SQL及物化入队验证（B，2026-10-23）

在原IsolatedMysqlRolloutServiceIT连接EventSubscriptionRevisionMapper和完整六依赖guard。
新增归档模式通过真实订阅/不可变父修订SQL、receiveSubscription MANDATORY事务、原
TaskTriggerService物化与实际queueSystem：首次接收后灰度0→10000且当前订阅停用、活动
指针改变，仍保持原版本1和原父来源；重复物化返回false、接收重放同ID，只有一个执行及
原消息检查点。随后篡改原父content_hash，完整guard拒绝。

原68610 compiler:testCompile+surerfire终态0，`/tmp/iqc-b-archive-trigger-json-fingerprint.log.exit`
为0；fresh XML SQL28+周期协议11=39项零失败错误跳过，专用34307自动关闭且socket消失。
原44622因两会话同指纹唯一索引冲突失败，原4259因夹具摘要传入非JSON失败；修正为独立
会话ID经原JSON序列化和摘要生成指纹，未改生产代码或唯一约束。

发布Prepared和组织scope为受控适配，SQL记录由测试初始化；不替代发布UI、真实PDP、
扫描anti-join、多实例扫描、实际MQ投递或worker成功执行。完整适用I1–I7继续，输入仅处理后会话。

### 归档扫描去重与事务回滚（B，2026-10-24）

原71040 compiler:testCompile+surerfire终态0，`/tmp/iqc-b-archive-scan-sql.log.exit`为0；
fresh XML SQL30+周期协议11=41项零失败错误跳过，专用34307已关闭且socket消失。
新增两项使用真实EventSubscriptionService.fire/Spring事务、原会话查询anti-join及
receiveSubscription：重复扫描和DELETED回执阻止同事件再接收，旧活动修订返回0；
实际稳定事件ID/目标/原version1/无需ASR得到核对。扫描末尾订阅UPDATE注入SQL失败，
已写入接收记录和公平扫描时间均回滚，移除故障后重新接收一次且随后去重。

父发布记录由测试初始化，Prepared与当前scope适配受控，每例使用独立历史秒区间和
独立会话指纹；本轮不证明发布UI、真实组织PDP、多实例或模型worker成功执行。

### 实际扫描回执到入队的连续链路（B，2026-10-24）

原20049 compiler:testCompile+surerfire终态0，`/tmp/iqc-b-archive-scan-queue-sql.log.exit`为0；
fresh XML SQL30+周期11=41项零失败错误跳过，专用34307已关闭且socket消失。
在原扫描用例直接读取fire实际写入的SQL回执，灰度0→10000后经原materialize/queueSystem
入队，完整六依赖guard验证原父及冻结版本，只有一个执行及原消息检查点。重复物化false，
后续扫描0，queueSystem仅调用一次；DELETED去重/旧修订拒绝/故障回滚仍随组合通过。

本轮增加原用例的连续链路断言，未新增测试数量；Prepared依赖及组织scope仍受控，
不将到QUEUED的证据称为模型worker成功执行、真实PDP或跨实例验收。

### 固定版本归档接收到真实工作器成功（B，2026-10-24）

复用IsolatedMysqlMeasuredPublicationIT完整目录、Gold独立审核、冻结数据集、真实试跑及
发布门禁产生的已发布METRIC流水线，在原集成场景加入内部receiveSubscription事务→
materialize/queue→executePersisted。PipelineAssetService/Runtime/Evaluator/HierarchicalResultService
及任务/检查点/结果SQL均为原真实实现，父订阅与原发布引用由测试初始化。处理后会话
无需ASR，最终SUCCEEDED，两消息检查点成功，结果80分/HIGH、原1000ms轨迹，单执行，
同事件重放返回原ID。

原30131 compiler:testCompile+surerfire终态0，`/tmp/iqc-b-archive-measured-worker.log.exit`为0；
fresh IsolatedMysqlMeasuredPublicationIT两项零失败错误跳过，93.869秒，专用34307关闭且
socket消失。新增断言扩原第一项，不新增测试数量；原第二项评测部分失败后重试继承与
一致性/标签组合随本轮通过，不当作归档任务自身重试证据。

本轮为固定版本归档内部接收到实际工作器成功；不冒称灰度worker或扫描到worker整链。
组织scope、Agent资产校验及另一模型场景供应商适配仍沿原受控夹具；真实PDP/发布UI、
跨实例重启恢复、实际供应商和其它适用I1–I7继续。
