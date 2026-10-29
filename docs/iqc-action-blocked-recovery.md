# 阻塞恢复边界（正在实现）

现有claim路径已校验实际来源、稳定owner、完整冻结输入/政策、触发条件、剩余次数/有效期、适配及原业务权限。恢复入口必须复用这些检查；原始来源损坏、旧EID不能验证、原权限缺失或政策到期时继续阻塞，不能修改冻结字段绕过。

当前仅实现原账本recoverBlocked条件更新和待运行SQL用例，尚无授权服务/API/UI或恢复原因事务审计，不得作为公开恢复能力。三个BLOCKED状态可条件回队：零尝试且没有领取/回执返回PENDING；已有尝试仅CONFIRMED_RETRYABLE_FAILURE且存在领取/结算证据返回WAITING_RETRY。UNKNOWN、RUNNING、COMPLETE、FAILED、EXPIRED均不可恢复。二进制owner/id和版本围栏避免替换身份或覆盖赢家。

恢复不增加attempt，不清除既有领取/结果/证据/回执，不直接调用适配器；版本增加使原状态触发器保存此次回队。后续真正领取仍核验策略和次数。恢复请求必须保存原因且与状态同事务提交，原状态日志需最小扩展，不能依赖异步Audit宣称原子审计。当前SQL用例尚未执行，事务/并发及正常源校验均待真实验收。

本轮90454 exit0，生产和全部测试编译通过，既有MyBatis参数处理回归1项通过（解析含新增方法的mapper）。新增恢复SQL用例未执行，不称恢复行为已验收。真实SQL需验证零尝试恢复、确认可重试回执保留、版本并发/owner大小写拒绝和UNKNOWN即使误标BLOCKED仍不可回队。

## 冻结政策校验复用

ActionFrozenPolicy从原claim中提取完整生产政策序列化、来源/幂等key/冻结输入相等、条件MATCH、有效attempt和次数/期限判断，claim已接入。无数据库写入或适配器I/O，原Task→Execution→Result→Action锁序、期限阻塞和领取行为保持。恢复服务后续须复用这一校验，仍需原来源/稳定身份、适配器能力/业务权限/ready检查及原因事务写入；当前尚未公开恢复服务。

最终7100 exit0，幂等入队/回执执行/参数绑定三组24项零失败错误跳过，含改动冻结输入拒绝和次数上限不修改账本。新增helper及全部测试编译通过；未运行真实claim/recovery SQL，不以此声称事务/来源锁验收。

## 恢复原因存储（V1.1.67）

原状态事件新增可空recovery_reason，不回填旧事件；历史GET显式返回可空recoveryReason，与核对成功原因reconciliationReason区分。部署新后端前须完成V1.1.67，否则日志查询依赖缺失列。恢复原因写入只匹配原BLOCKED→PENDING/WAITING_RETRY事件、版本/前版本、来源和二进制操作人，并要求领取次数/回执/claim未改变，已有原因不能覆盖。

当前仅提供mapper边界和待运行SQL用例；恢复服务必须在同一状态事务中调用，写入非1或异常须抛出使状态回滚。不单独提交原因，不以异步Audit替代。公开恢复服务及UI原因展示仍待接入，真实迁移/SQL/独立审查仍待完成。

80510 exit0，历史查询8项+参数绑定1项通过，全部testCompile通过；V1.1.67迁移布局通过。恢复原因的实际SQL写一次/错误身份来源拒绝用例已编译但未执行，真实迁移与事务原子性尚未验证。

## 内部恢复事务服务（正在验证）

ActionRecoveryService要求已解析新鲜负责人及iqc:action:recover，沿原requireActor/requireBound及来源锁序，再锁定原动作。复用ActionFrozenPolicy完整相等/触发条件/次数期限，再检查适配协议、原业务权限、ready。曾有尝试时复用ActionExecutionContract.plan和原settledAt，要求适配器支持安全重试且原退避已到期；未知/错误领取/结算证据不允许恢复。

状态CAS成功后在同REQUIRES_NEW事务附加同版本原因，非1或异常抛出。服务不调用execute，不清除原回执/次数；实际回滚须真实MySQL验收。尚无调用该服务的公开入口、资源注册/受控授权或管理端恢复表单，不能宣称可用恢复功能已完成。

29770 exit0，幂等/共享冻结校验/恢复编排18项及参数绑定1项，共19项零失败错误跳过。恢复原因规范化、不发送、缺权限/旧观察/UNKNOWN拒绝、原因写入失败抛出通过。只是mock编排，不证明真实事务回滚；API、权限注册/受控授权、UI及真实SQL验收尚待完成。

## 恢复请求入口（正在验证）

POST `/api/iqc/conversation-results/{resultId}/actions/{actionId}/recover`只接受version和非空≤1000字符reason，返回RECOVERED或NOT_RECOVERED；NOT_RECOVERED不承诺具体失败类型，不自动重试。原Audit/限流独立scene和iqc:action:recover资源声明，后台开关未启用时拒绝。受控角色授权迁移与UI尚未接入。

ActionRecoveryRequestService为NEVER，当前稳定身份capture和固定工作流grant projection都在事务外。应用层resolveCurrent复用组织账户/稳定ID/新鲜grant投影，原后台resolve继续requireBackground。asCurrentActor在进入事务前核对当前owner/all/group，保留SecurityContext/UserContext不改，callback期间本地范围读避免groupId组织网络在锁内发生，finally清理同步范围。既有后台路径没有放宽。真实组织HTTP、角色登录、事务与迁移验收仍待完成。

同步原会话候选91a3680/18fd4e0：领取和恢复在ready返回后重取最终UTC毫秒时间并重检冻结期限；恢复入口和SQL为领取、结算保留三个版本增量，观察版本必须<=Long.MAX_VALUE-3。共享范围方法保留A587f54b的requireScheduledBound，当前用户授权入口保留真实认证，不模拟后台。

同步后的定向回归78740 exit0：当前授权9、当前/后台范围5、普通范围4、入队/领取/恢复28、参数绑定1，共47项零失败错误跳过。期限测试使用可控时钟覆盖期限前、恰好到期、跨期；版本测试覆盖MAX-3至MAX。它们不证明真实SQL条件、服务代理回滚或HTTP资源授权；既有独立815项不属于本提交的完整回归。

本地随机端口授权运行31028 terminal exit0，IqcPlatformApplicationTest6项通过：原4项及新增恢复OpenAPI/匿名401、事务代理/REQUIRES_NEW/READ_COMMITTED/runtime rollback属性/独立资源注解。默认沙箱首次1353因监听端口被拒而context启动失败，获准重跑后通过。这仍不证明带资源的实际用户授权正反例或数据库已执行SQL回滚。

隔离MySQL62243 terminal exit0，已严格核验原34307端口及数据目录。Flyway从原1.1.64实际应用65/66/67到测试库iqc_worker_state_validation；IsolatedMysqlActionExecutionIT21项零失败错误跳过，覆盖实际账本CAS/事务、原因写一次及回滚、attempt持久终态与分页等既有用例。此为升级及mapper层证据，不替代新库完整迁移、重复执行、受控权限DML或ActionRecoveryService代理事务验收。

恢复服务代理事务验收：编译87772 exit0；隔离MySQL23195 exit0，IsolatedMysqlActionRecoveryIT3项零失败错误跳过。使用真实服务Spring代理与账本/原因SQL：原因零行导致状态及新事件回滚；实际原因写入后异常同样整体回滚；外层事务回滚不撤销REQUIRES_NEW恢复提交，重复旧观察拒绝。来源/适配器为替身，仅证明恢复事务边界，不证明生产来源锁序或业务副作用。Flyway实际validate68份迁移且本轮migrate0（版本67），并非A的V68已整合。并发/正尝试回执/版本极值SQL仍待扩展。

扩展恢复代理验收85600 exit0，10项零失败错误跳过：并发同版本一个赢家、实际领取/结算历史回执在恢复成功及原因失败回滚两路径保持、MAX-3真实恢复/领取/结算达MAX，以及MAX-2/MAX-1/MAX服务与SQL拒绝。并发来源校验使用屏障替身，真实竞争位于Action行锁；不证明生产Task/Execution/Result锁序。真实SQL版本围栏已验证。

修正人工授权的组织调用：resolveCurrent走原组织权限投影的严格当前用户入口currentActionAccess，后台resolve继续走service-only schedulerAccess，不清认证或修改framework。旧CurrentUserService仅网关JWT、ResourceService.query缓存资源，不足以证明新鲜账号/范围的一致快照，故最小扩原组织controller入口复用同Access服务。应用unit40562授权9项及更新后真实签名HTTP36145九项通过；后者组织/状态为替身，不证明真实Feign调用或组织数据库授权。组织端controller身份7项3559通过，实际令牌签发/验签另验收。

实际网络补验：CurrentActionAccessFeignTest 使用生产 IqcOrganizationClient、SpringMvcContract 和原 FeignInternalTokenInterceptor 请求本机随机端口，真实签发及验签确认 current/user 路径、用户身份、hop2、父令牌链及出站敏感头清理。Surefire 1 项零失败、错误及跳过（日志 /private/tmp/iqc-b-current-access-feign-test.log）。响应与 decoder 为测试夹具，未覆盖组织 MVC Filter 或组织数据库，不替代跨服务全链路验收。

A 集成状态：标签核对后端4b37a9c/UI92cab3c、恢复/日志后端b6a6381及管理端5dbd8e8已本地提交；组织当前用户入口已集成edae027。以上历史段落保留当轮证据，整合后完整网络恢复、实际角色与生产来源锁序验收仍待；部署顺序见 deployment.md。
