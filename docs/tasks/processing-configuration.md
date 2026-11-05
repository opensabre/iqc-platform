# 任务类型与引擎计划

任务选择质检、打标或两者。每个操作配置 1–32 个引擎，数组位置就是该操作自己的执行顺序；相同类型可重复添加，同一操作中实例 ID 不重复。实例 ID 在两个操作之间可以相同。小模型暂不支持。

复用此前任务处理配置中的 `TaskProcessingConfiguration` 记录；旧 `TaskProcessingPipelineCompiler` 输出的是旧流水线节点，不能表达本轮逐引擎原始结果集，因此不接入旧编译器。`TaskEnginePlanService` 将这一配置映射到现有 `MultiEngineExecutor.Step` 和 REGEX / DLS / LLM 适配器，不增加执行队列、API 或数据库表。

## 参数与冻结

请求版本 `iqc-task-processing-v1`，示例：

```json
{
  "schemaVersion": "iqc-task-processing-v1",
  "inspection": {
    "engines": [
      {"id": "regex-1", "type": "REGEX", "parameters": {"targetId": "check-1", "ruleId": "published-regex-rule"}},
      {"id": "llm-1", "type": "LLM", "parameters": {"targetId": "check-1", "ruleId": "published-llm-rule", "agentId": "published-agent"}}
    ]
  },
  "labeling": {
    "engines": [
      {"id": "dls-1", "type": "DLS", "parameters": {"targetId": "label-1", "ruleId": "published-dls-rule"}}
    ]
  }
}
```

只接受上列引用字段：不能提交表达式、模型凭证、`agentSnapshot` 或 `conversationId` 覆盖。规则必须已发布且类型对应引擎；LLM 必须选择已发布且非 RULE_ONLY 的配置。复用现有规则校验器校验表达式。识别目标由后续结果处理配置校验归属，不能仅凭 targetId 认定它是可写标签。

服务端读取规则与模型配置后生成 `iqc-task-engine-plan-v1` 冻结计划。后续执行只使用此快照，不重新读取可变配置；真实会话 ID 由执行上下文补入 LLM 步骤。冻结计划和参数均防御性复制，可序列化进入现有任务快照。

## 执行和范围

质检与打标分别从顺序 1 开始；目前两个操作按质检、打标顺序执行，各引擎均读同一完整输入快照，不隐式消费或过滤上游输出。失败继续策略由现有多引擎执行器负责。冲突观察值全部保留，不在此处产生评分、写入标签或选出获胜引擎。

此提交完成引用配置到可执行步骤的连接，但尚未把新字段开放到创建接口，也未改造现有队列。原创建入口仍保留旧业务行为。后续必须一并接入四步向导、创建存储、队列和结果处理后才开放新任务提交，防止新任务误走旧规则执行路径。
