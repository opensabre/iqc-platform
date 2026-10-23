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

该路径提供 IQC 内部归档自动订阅；投诉/订单等外部事件已有直接 API 持久接收，真实 MQ 的消费者身份、消息确认和上游投递仍需后续适配。没有自动 ASR 转写、写回或实时语音；离线音频仍在完整 PRD 范围内。全局预算、自动失败重试和不可变失败尝试历史尚未完成，不能据此标记 I4 或全部 PRD 完成。

本批仅编译和前端构建，没有新增/运行测试、执行迁移、真实角色/浏览器/模型或部署；SQL anti-join 与多实例事务行为仍需运行验收。

恢复诊断补充：非法/缺失输入指纹按 MySQL8 固定 REGEXP_LIKE 规范排除，并仅在服务日志记录订阅 ID，不向共享订阅暴露私有归档 ID 或数量；避免毒源堵住后续合法会话。无法解析发布证据时同样仅更新 ACTIVE 订阅的公平时间和退避，不改发布指针或生命周期。

## 2026-10-23 外部MQ接收增量

应用内新增可选RabbitMQ适配，复用原外部TaskTriggerRequest/accept幂等事务和当前组织授权，不改变内部archive-subscription流程。配置固定来源/负责人/资产允许集合，提交后手动ACK，失败死信供原身份重投；默认关闭。详见iqc-rabbit-triggers.md及部署示例。当前仅编译，实际broker/SQL/角色/运行验证待完成，不将源码接入冒称I4全部验收通过。历史ASR/音频表述仍服从迭代页首当前范围：仅处理后会话，不开发语音转换。

## 归档订阅灰度目标（2026-10-23）

显式 `iqc-event-subscription-rollout-v1` 定义使用 `rollout.pipelineId`，与 `pipeline` 互斥；固定 `iqc-event-subscription-v1` 与原八/九参数构造保留。发布 `iqc-event-subscription-rollout-release-v1` 冻结路由意图、精确可选Selector及负责人，不提前分配流水线版本。发布及后台发现要求当前组织授权中已有 `iqc:pipeline:rollout:task:create`，同时保留原订阅/任务/触发/重试策略资源要求。

原订阅行锁及事务内，每个归档事件经原 `receiveSubscription` 和业务事件灰度接收器，第一次接受才读取当前策略并冻结选中Prepared和分配证据；重放先查原回执，不重新分桶。Selector仍与发布时的精确快照比较，停用或变化不能悄悄更换来源。原来源保留为内部 `archive-subscription`，外部API/MQ仍不能使用；revision/conversation/fingerprint派生事件ID、扫描反连接、输入修订、等待及自动重试不变。组范围继续取负责人、流水线、Selector及会话原范围交集。

本批实现与编译结果见协作记录。真实订阅发现/事务回滚/并发、发布后扩量回退及已接收任务不重选、当前组织撤权、物化工作器和页面权限须独立验证；不据此宣布I7或全PRD完成。
