# 任务数据筛选

`POST /api/iqc/conversations/selection-preview?current=1&size=10` 复用现有会话查看权限，返回真实、可见且已导入并有消息的会话。只模拟取数，不创建任务或执行引擎。

请求 `mode` 为 `FILTER` 或 `RANDOM`；`filter` 支持 fileName/employeeId/customerExternalId/channel/businessNo/startedFrom/startedTo。随机模式另传 sampleSize（1–1000），可带返回的 sampleSeed 复现同一候选范围的预览。缺省生成随机标识。

响应含 mode、matchedTotal、selectedTotal、sampleSeed、conversations（records/current/size/total）。条件模式为服务端分页；随机模式完整返回至多 1000 个样本，前端在此集合分页，不重新抽样。样本不足返回现有数量；匹配总数与抽样结果数量分开。

抽样采用完整合法范围的游标扫描和有界候选堆，不从前 N 条候选中抽样，不需要新增存储或预览缓存。读取使用事务一致性快照；标准会话查询与预览共享元数据筛选及数据范围构造。

用户已确认：随机和条件任务正式数据在执行时确定，创建时只保存参数；数据不足有多少处理多少。最初的 #59 交付预览与选择请求模型；下面记录后续正式创建与运行接线。

## 正式创建与运行

原 `POST /api/iqc/tasks` 增加可选 `dataSelection`，不另建任务 API。示例：

```json
{"name":"售后抽样","dataSelection":{"mode":"RANDOM","filter":{"channel":"WEB"},"sampleSize":100},"ruleIds":["published-rule-id"],"executionMode":"RULE_ONLY"}
```

新字段不能与旧 taskType、conversationIds、selectionFilter、sampleSize、sampleSeed 混用，也不能提交预览种子。BATCH 在创建时验证明确名单；RANDOM/FILTER 创建时不查询匹配数据，保存条件并处于 CREATED。

首次用户执行成功取得队列状态后，在队列事务内解析条件或抽样，保存实际名单、总消息数、取数时间和随机种子，再建立原有 TaskExecution/TaskItem。过滤扫描使用同一重复读事务及稳定 ID 游标，不从前 1000 条截取候选；随机样本不足按实际数量处理。完全无数据返回明确 NO_DATA，队列事务回滚，不产生假成功或空执行。失败重试沿用已保存名单，不重新抽样。

这部分沿用现有单操作规则/标签任务配置及执行器，不代表多操作、多引擎向导和统一结果处理已经接通。新数据选择暂用于用户主动执行，不支持后台定时上下文；旧定时和旧 SAMPLE 接口保持原语义，前端新向导不得混用。

新增 IQC 迁移 V1.1.34 将既有 conversation_ids_json 扩为 LONGTEXT，避免全量条件名单被旧 TEXT 大小限制截断。不新增持久化模型或调度系统。
