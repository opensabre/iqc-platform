# B：动作幂等键修复与入队逻辑验收

基于cf1d0c6（A65d87b9已合入）。未修改A工作树或framework；A后续4b75605领取/执行/结算尚未合入本树。

新增ActionEnqueueIdempotencyTest 9项后，实际首次运行9项0 failures/5 errors，五个正常匹配或重复内容场景在生成键时抛出“Selector摘要输入无效”。日志 `/private/tmp/iqc-b-action-enqueue-idempotency.log`。

根因：Source.idempotencyKey组装v1长度前缀文本，错误传给SelectorAssetService.hash；后者对JSON执行readTree和规范化，长度前缀文本不是JSON。正常MATCH不能写入动作。后续适配Request校验也调用同一键入口。

最小修复：对原字段顺序/版本和UTF-8长度前缀直接SHA-256，不改变Selector的JSON摘要语义，不增加公共摘要抽象。ActionSourceIdempotencyTest 2项确认精确字节摘要（含多字节负责人）、稳定性和字段分界/类型/负责人差异。

入队9项覆盖：MATCH冻结来源/输入/政策、attempt0且无outcome；UNKNOWN不入账；稳定userId变化拒绝；相同请求保留已完成状态与attempt；同键输入不同拒绝；模拟DuplicateKey后核对相同胜者；没有胜者拒绝；不可用适配只阻塞其动作；insert0拒绝。

测试直接调用服务、仓储与来源校验模拟，仅证明本地入账/核对逻辑，不证明真实Spring事务、MySQL并发/唯一键、当前组织权限或真实生产来源。后续真实数据库验收仍需独立进行。定向四类28项全部通过（Maven exit0，0 failures/errors/skipped），日志 `/private/tmp/iqc-b-action-idempotency-fixed.log`。完整默认回归Maven exit0，90份报告/752项，0 failures/errors/skipped；所有默认报告本次20:42:47..20:45:49生成，排除历史独立IT，日志 `/private/tmp/iqc-b-action-idempotency-full.log`。diffcheck通过。未重跑MySQL IT、推送合并或部署。完整非实时语音PRD继续。
