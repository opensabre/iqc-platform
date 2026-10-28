# 会话当前授权应用链验证

2026-10-28，基线8812fcb。新增CurrentConversationAccessChainTest，使用真实ConversationQueryController、SensitiveReadAccessService、ScheduledActorAccessService和IqcDataScope，组织客户端及最终业务查询受控，无生产修改。

原99275终态0，/tmp/iqc-b-current-access-chain-20261028.log.exit为0。离线compiler:testCompile/surefire，显式ByteBuddy javaagent，新增11项、原授权解析9项、MVC31项，共51项零失败错误跳过。

先让会话API统计成功返回，再把当前授权降为旧conversation:view，第二次读取被拒，组织当前投影重新查询两次，未再次调用业务查询。其余十组覆盖旧码、ADMIN缺少精确基础码、过期/未来证据、停用账号、稳定ID不匹配、用户名变化、group变化、原all范围撤销与缺少组织响应；全部在业务查询前拒绝。每组确认当前认证与UserContext不变，且未调用后台schedulerAccess入口。

本次补齐应用内多服务组合边界，未把SensitiveReadAccessService直接mock为拒绝。组织返回仍为受控响应，未执行Feign HTTP、组织数据库、网关/PDP或真实登录。只直接验证统计入口；不能推断全部Controller成功路径及写权限。完整适用PRD I1–I7仍待逐项验收，不包含语音转换。
