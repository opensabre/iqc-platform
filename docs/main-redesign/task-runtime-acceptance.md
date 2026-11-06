# 组合任务与结果 HTTP 契约验收

复用 `JointLabelMaterializationTransactionTest` 的隔离 H2、真实 Spring 服务和随机端口 HTTP 环境。

- 同一会话执行两个质检引擎和一个打标引擎；结果处理给出 90 分并保存布尔标签 `false`。
- 首个质检引擎抛出异常后，后续质检和打标继续；评分待定，失败记录和标签均保留。
- 修改源消息及规则后重试，仍使用冻结输入与规则；复用成功引擎结果 ID，只重跑失败引擎，并保留历史失败结果集。
- 通过 `result-hierarchy?executionId=...` 实际 HTTP 响应读取完整结果、来源标识、标签与证据。

HTTP 响应在 IQC 控制器边界将持久化层 Jackson JSON 节点转成普通 JSON 值，避免 Spring MVC 的不同 Jackson 版本将节点序列化为 `nodeType`、`object` 等属性。外部字段结构保持一致，历史任务空引擎结果仍返回 null。

这项验收不替代完整浏览器联调、真实模型服务验收或正式数据库迁移验收。
