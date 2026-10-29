# I4 周期调度的授权与计划时刻

## 实现前复用取舍

已有单次ScheduledInspectionDispatcher与Task不能保存独立Cron定义/不可变修订，不能原地重写Task选集。先实现周期执行必需的当前负责人授权边界和明确计划时刻；本批接入独立周期定义/修订，实例继续复用Task。代码落地与运行验收分开记录。

授权继续由base-organization拥有。普通用户/角色/资源查询含缓存，不满足撤权后下一轮触发的当前授权证据；组织服务新增仅接受框架已验签IQC服务身份的实时只读投影，复用所有原身份/授权表。无新授权表、静态管理员或保存的JWT，Framework源码不改。共享HS256内部签名沿用平台既有服务信任边界，入口校验服务subject/issuer/source/audience/destination/首跳和空用户权限声明。

IQC新调度授权适配只在后台无用户上下文时解析该投影。范围上限只记录创建者稳定userId、当时owner/all/group；每次触发重新读取账号状态、角色和ACTIVE资源，要求仍有创建Pipeline任务、执行和周期发布权限。实际all仅在创建时允许且当前仍允许时成立；group仅保留创建与当前一致的group，否则只允许owner。账号失效、改名与原owner不符、接口失败或证据超时均拒绝。

`IqcDataScope`继续作为应用唯一的数据范围入口；只增加有界后台callback覆盖，写入负责人UserContext供原BasePo审计归属，并在finally恢复原上下文。不会绑定虚构已验签JWT，Feign仍使用框架默认无用户服务身份。覆盖只用于同步选数/物化事务，不传播给模型执行线程。

选数复用ConversationSelectionService。内部materializeAt以不可变计划Instant作为相对时间窗锚点，保留毫秒协议/原预览和即时创建行为；不把恢复后的当前时间冒充计划时刻。

## 当前状态及剩余范围

本批新增周期草稿/不可变修订、Cron/时区/有效期、三元组唯一触发实例、三类错过策略、NO_DATA/跳过证据、后台物化入队及周期管理UI/九项权限。尚未完成I4：文本事件/API触发、重试/速率/成本预算和不可变失败授权/物化尝试证据仍待开发。只编译源码及既有测试源码，不新增或执行测试，不运行SQL、模型/浏览器/真实登录验收或部署。

## Cron 实现前持久化取舍（2026-10-06）

既有Task代表一次冻结运行，Selector/Pipeline资产分别只负责筛选/引擎版本；Framework/共享服务没有可直接接入的周期业务资产。本批新增仅负责周期草稿/不可变发布修订的两表。触发实例不另建主表，扩展原Task的periodicScheduleId/periodicRevisionId/plannedAtUtc唯一约束，以原Task状态、选数证据和Execution追踪。

周期负责人固定创建者，草稿更新只允许本人，避免其他维护者将其私有资产写入他人的可见历史。周期目录/历史可见范围取负责人及发布引用资产的交集。发布冻结精确Pipeline/Selector版本和创建范围上限，触发重新解析当前资产可用性/负责人授权。

错过策略SKIP以一条原Task的SKIPPED_MISFIRE证据记录跳过区间；FIRE_LATEST只执行最新到期计划时刻并记录此前跳过区间；CATCH_UP每轮有上限地逐计划时刻补齐。所有时间计算采用显式zoneId，游标/唯一键用UTC datetime(3)，原相对窗口用真实计划Instant。无数据留下NO_DATA原Task，不入执行队列。选数、Task、Execution入队和游标推进须同事务；外部授权RPC在加锁事务前获取，事务入口复核当前周期修订/游标，陈旧帧不触发。

## 当前运行协议

- 六字段 Spring CronExpression + ZoneId/ZonedDateTime；生效范围为 `[effectiveFrom,effectiveUntil)`，预览从给定时刻之后计算，最多50项。时间限制在MySQL datetime支持的1000–9999年，超出上限无下一次触发。
- 延迟不超过30秒作为调度宽限，按原计划执行；更老的游标应用错过策略。SKIP证据的missed区间为半开 `[missedFrom,missedUntil)`，until为最后跳过时刻加1纳秒。FIRE_LATEST记录此前半开跳过区间，并执行最新已到期时刻；CATCH_UP逐计划物化。
- 发布每次新修订都会停用并重设游标至定义的首个计划时刻；必须显式启用。编辑草稿不改变正在运行的已发布修订。幂等再次发布同一草稿返回原版本，不倒退游标、不改变启停状态。停止后重新启用沿用原游标并应用错过策略；到达结束范围自动停用。
- 总开关 `iqc.schedule.enabled=true` 才注册后台发现器，默认关闭；`iqc.schedule.delay-ms` 默认5000。每轮最多25个到期定义，轮流各触发一次后再补跑，全局最多50个实例；每定义最大补跑1–100为上限，不保证一轮达到该数量。
- 当前授权RPC在事务之前读取，30秒内且未来偏差最多5秒。取得周期行锁后及入队前再次校验时效、稳定负责人、范围与必要资源；慢锁等待/慢选数令证据过期时整个事务回滚，保留原游标等待重新取得授权。
- 当前失败处理为游标不前进、30秒退避、周期lastError及服务日志；尚未保存每次失败的不可变尝试历史。成功物化Task含发布摘要、计划时刻、错过区间与当前授权证据，空选集NO_DATA不建Execution，正常Task进入原QUEUED持久化worker。
- 可见范围逐层收紧：周期草稿引用资产交集，新发布与当前负责人组交集；实例与当前负责人、周期、Selector和完整选数证据交集。nullable游标/错误/组字段采用显式ALWAYS更新，确保清除真正写入数据库。

迁移：IQC `V1.1.44__ddl_add_periodic_schedules.sql`；Organization资源 `V20261004_14`。本批未执行迁移或启用总开关；编译不是数据库、DST或角色链路的运行验收。
