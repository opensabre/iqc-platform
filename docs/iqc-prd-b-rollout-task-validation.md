# 灰度任务重放与冻结证据验证

## 已集成范围

B 在 `fdda5fc` 上只集成 A 已提交 `c6f87ac`（本分支 `1ee833a`）与 `db86272`（`63feb37`）；不重复 A 对 B 测试的 cherry-pick，不动另一工作树或运行中进程。两个增量包含显式会话IDs物化、原请求重放及worker/历史证据守卫，不代表Selector/周期事件灰度、AB归因或全PRD完成。

## 重放专项设计

`PipelineRolloutTaskReplayTest` 运行真实 `PipelineRolloutTaskService`、Evidence、Policy、Comparator、hash和精确JSON读取；PipelineAssetService、RolloutService、InspectionTaskService和scope模拟。合成两侧RULE发布内容与实际对应hash。模拟物化边界再次调用真实Evidence.verify，并保存含选定Prepared及证据信封的模拟task，以便后续真实重放代码读取。它不调用真实任务物化器、不取会话、不落SQL，不证明事务或PDP。

覆盖首次冻结、稳定账号/流水线/请求键派生ID及不含身份原文；原请求跨扩量/回退/换盐仍返回原task且SUCCESS及快照不变，不读取当前策略或重新取源、不重复物化；会话顺序/去重及null并发等价1，名称/会话集合/并发变化拒绝；历史修订ID/hash不同或缺失拒绝；缺信封/错任务种类拒绝；未重算信封hash或重算后错桶、实际release被改均拒绝；首次两侧来源hash漂移阻止物化；空会话在锁前拒绝、不同账号派生不同ID。

原65241及最终报告待取得终态后记录。生产锁/CAS真实并发、RR外层旧快照、完整生产物化/worker启动领取恢复、真实角色/MVC/供应商与前端浏览器联调，仍需独立证据。无语音转换、共享SQL、推送、部署或framework修改。

## 首轮缺陷与修复

原65241 terminal1，`/tmp/iqc-b-rollout-task-replay.log`，26项中1失败/15错误。两类原因区分：旧Revision专项8项因新代码改走rolloutSourcesForTask而原mock仍接旧入口，已只更新fixture；新Replay专项8项均在真实Evidence.verify拒绝合法5000比例，并非为了测试而绕过校验。

实际缺陷是跨能力摘要协议不一致：预览decision.policyHash复用Selector hash，其数字规范化stripTrailingZeros把5000编码为5E+3；持久化revision.policyHash及冻结verify使用Pipeline hash保留整数5000。任务物化要求两者相等，合法常用比例因此无法创建。最小修复只将Policy.preview的策略摘要改为PipelineAssetService.hash，与已有修订/冻结协议一致；cohort哈希/桶、发布内容hash及历史修订内容完全不变。曾经能通过冻结的策略，其两侧hash原本已相同；新修复不要求重写既有历史。预览响应policyHash会随算法纠正而更新，消费者应使用当次返回值，不复用旧预览结果。

新增Policy断言核对0/10/1000/5000/10000/1234与持久化协议相同。修复后完整三类专项以原95716、`/tmp/iqc-b-rollout-task-replay-final.log`终态为准，尚未预报通过。

## 修复后终态

原95716 terminal exit0，`/tmp/iqc-b-rollout-task-replay-final.log`；三份本轮XML Replay9+Policy9+Revision9=27，0 failures/errors/skipped。修复后模拟物化边界继续调用真实Evidence.verify，未放松合法性校验以求测试通过。跨策略原请求只读旧修订、返回原任务，参数和坏证据拒绝均有此轮覆盖。

本轮没有运行当前组合完整默认后端套件、真实任务物化/worker或SQL。新增ExecutionGuard已经集成但尚无独立原来源/发布门禁测试；生产资产锁/CAS真实并发及RR旧快照等剩余门槛保持未完成。完整 I1–I7 继续推进。
