# 动作状态与已确认失败率

## 复用与范围

PRD V1.0 18.2 要求 Action 失败率。原动作历史按单结果读取，业务报告已有明确任务、执行轮次、首次/最新去重及组织范围。复用 GET quality-operations/report 的 BUSINESS 视图，新增可选 includeActionMetrics，默认 false；不新增表、接口、权限、公共监控抽象或 Framework 改动。原 ActionExecutionMapper 只读分组沿已有 source_result_id 索引，最多原报告允许的 500 个结果来源，不加载冻结输入/政策/回执正文，不触发执行。

启用时，在读取任务和结果前通过 SensitiveReadAccessService 核验当前 report:view 与 action:history:view，以及当前组织范围未扩大/变化；再复用原 IqcDataScope 对每个所选任务的 owner/group/all 校验。SQL 按 taskId、executionId、conversationId、resultId、owner 五字段精确匹配已验证结果来源，不按任务扩大到历史全部轮次。空来源不调用聚合，SQL 自身空集合也限定 1=0。命令签名、旧 Report 五参数构造和旧 JSON 省略语义保持；包含可选统计的 HTTP 响应标记 private, no-store。

## 指标定义

Report.actionMetrics 使用 iqc-business-action-metrics-v1：

- selectedResults 为原统计口径选中的会话结果位置数；boundResults 为可精确绑定结果 ID 和执行轮次的来源数，unboundResults 为缺结果或轮次数。
- actionCount 为匹配的当前动作账本行数。没有动作行显示 0，但不据此称成功，也不能推断没有配置动作或发现已经完成。
- confirmedSuccessCount 仅 COMPLETE 且 outcome=CONFIRMED_SUCCESS。
- confirmedFailureCount 仅 FAILED 且 outcome 为 CONFIRMED_FINAL_FAILURE 或 CONFIRMED_RETRYABLE_FAILURE。后者已经结束重试并最终留在 FAILED 才进入分母。
- unresolvedCount 是其余行，包括 PENDING、RUNNING、WAITING_RETRY、各 BLOCKED、EXPIRED、REQUIRES_RECONCILIATION/UNKNOWN 及不一致的状态/结果组合；未确认不充当成功。
- confirmedFailureRate = confirmedFailureCount / (confirmedSuccessCount + confirmedFailureCount) × 100，BigDecimal 保留两位，HALF_UP；零分母为 null。
- states 按当前 status/outcome 分组，保留等待重试、阻塞、未知等完整分母背景。所有动作数量使用十进制字符串，前端不转 Number 求和。

这是当前已选范围内的账本快照，跨冻结标准只合计动作状态，不混合评分；不是逐尝试历史失败率、时间窗口吞吐率、外部业务履约结果或质量收益。当前无新时间窗口；原任务/轮次和首次/最新口径继续生效。

## 页面与验证

原业务报告增加默认不选的动作统计选项，仅 action:history:view 可见；报告仍需 report:view。沿原请求代次清理，在动作读取权限变化时重置选项、清报告并废弃迟到响应。旧后端不返回受支持的 actionMetrics 时显示无法确认，不伪造零指标。

Root 仅 test-compile 与前端 vue-tsc/Vite 构建，具体原句柄和终态见根协作记录。B 需独立验证零分母、状态/结果组合、等待/阻塞/UNKNOWN 不算成功、精确五字段与空来源/跨轮次/跨组拒绝、当前授权在 IO 前拒绝、旧响应和直接签名兼容、SQL 映射及浏览器撤权/迟到响应。本增量不代表整个 PRD18 或 I1–I7 完成；未启用动作开关或部署。

Root 原11097 test-compile 已终态0，/tmp/iqc-action-metrics-compile.exit=0，diff检查通过。仅编译，不是上述行为/SQL授权专项证据；前端与 B 独立专项另按原句柄记录。

## Service boundary verification

BusinessQualityReportServiceTest adds five action-metrics scenarios. Original 30210 compiled main/tests and exited 1 (25 passes, two fixture authorization errors): the new source helper changed owner to alice but retained the old null-owner scope stub. After adding the matching controlled alice visibility, original 88734 recompiled tests against the unchanged compiled main classes and exited 0; fresh XML: 27 tests, no failures/errors/skips, 5.742s. `/tmp/iqc-b-action-metrics-service-final.log.exit=0`.

Verified fixed report/history grants before task/result/ledger IO; default report skips metrics authorization and ledger and omits the JSON property; legacy Report constructor remains valid. Missing result sources skip ledger queries and retain null failure-rate denominator. All 36 combinations of nine statuses and four outcomes count only COMPLETE/CONFIRMED_SUCCESS and FAILED/confirmed failure as resolved; five source binding fields match exactly. Counts above JavaScript safe integer remain decimal strings and unresolved-only rates remain null.

Mocks control mapper and access responses. This does not prove real SQL source filtering, HTTP/cache headers, PDP or browser behavior. No production code changed in this validation.
