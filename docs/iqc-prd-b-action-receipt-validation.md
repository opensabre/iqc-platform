# B：动作回执执行边界验收

在B f229c25（已有幂等键阻断修复）合入A4b75605、9397dc9，集成c73b2c7；不纳入A未提交的query/controller，不修改framework。

新增ActionExecutionReceiptTest九项直接执行当前ActionExecutionService：没有领取不可调用adapter或结算；真实成功回执原样结算一次；可能已产生副作用后的异常、空回执、串请求回执均UNKNOWN；结算失败返回SETTLEMENT_PENDING而不重发；已隔离或token被替换的领取不发送；过期结算返回STALE_RECEIPT而不重发。

重要语义：Dispatch.SETTLED只表示回执结算（包括UNKNOWN），不是业务成功。测试明确核对receipt.outcome；UNKNOWN没有确认凭证。适配执行次数核对一次，结算故障不伪装失败回执。领取及settlement均模拟，不能证明Spring NEVER/REQUIRES_NEW、真实MySQL行锁或事务外网络时序；这些必须另用独立数据库与实际代理验收。

五类定向37项Maven exit0、0 failures/errors/skipped，日志 `/private/tmp/iqc-b-action-receipt-validation.log`。完整默认回归Maven exit0、91报告/761项、0 failures/errors/skipped；所有默认报告本次21:07:30..21:09:35刷新，不混历史IT。日志 `/private/tmp/iqc-b-action-receipt-full.log`。diffcheck通过，未重跑独立MySQL IT、推送/合并到A/部署。默认关闭的后台worker没有实际tick/外部网络发送；生产来源与执行发现、其他适配、完整尝试历史、人工核对及完整PRD验收继续。
