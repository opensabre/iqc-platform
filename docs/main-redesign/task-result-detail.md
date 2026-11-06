# 向导任务结果详情

沿用 GET `/api/iqc/tasks/{id}/conversations/{conversationId}/result-hierarchy`。既有任务权限和会话归属校验仍在读取记录前执行。可选 `executionId` 精确查询历史执行；指定执行不存在时不回退到其他执行。

新增字段 `executionId`、`engineResultSet`、`processedResult`。引擎结果集包含冻结输入、配置、原始输出、证据及失败记录；汇总字段直接返回已保存的结果处理器输出，不在查询时裁决。处理未完成时 `conversation` 和 `processedResult` 可以为空，页面应显示执行中记录。

默认优先当前执行的检查点；重试未涉及该会话且无当前记录时，返回该会话最新完成结果，并标明实际来源执行。缺失证据或来源引用不一致时拒绝展示最终结果。旧任务保留原有 rules/evidenceByRuleResult 字段。
