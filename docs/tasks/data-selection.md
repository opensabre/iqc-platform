# 任务数据筛选：第一项交付

`POST /api/iqc/conversations/selection-preview?current=1&size=10` 复用现有会话查看权限，返回真实、可见且已导入并有消息的会话。只模拟取数，不创建任务或执行引擎。

请求 `mode` 为 `FILTER` 或 `RANDOM`；`filter` 支持 fileName/employeeId/customerExternalId/channel/businessNo/startedFrom/startedTo。随机模式另传 sampleSize（1–1000），可带返回的 sampleSeed 复现同一候选范围的预览。缺省生成随机标识。

响应含 mode、matchedTotal、selectedTotal、sampleSeed、conversations（records/current/size/total）。条件模式为服务端分页；随机模式完整返回至多 1000 个样本，前端在此集合分页，不重新抽样。样本不足返回现有数量；匹配总数与抽样结果数量分开。

抽样采用完整合法范围的游标扫描和有界候选堆，不从前 N 条候选中抽样，不需要新增存储或预览缓存。读取使用事务一致性快照；标准会话查询与预览共享元数据筛选及数据范围构造。

用户已确认：随机和条件任务正式数据在执行时确定，创建时只保存参数；数据不足有多少处理多少。这个 PR 只交付数据预览与选择请求模型，不宣称已接通新任务创建/运行。
