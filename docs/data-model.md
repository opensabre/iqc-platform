# 数据模型与 ER 图

本文档对应当前完整建库脚本 [`iqc-platform-ddl.sql`](../src/main/resources/db/iqc-platform-ddl.sql)。业务聚合之间主要采用应用层逻辑外键；规范化质检结果内部使用物理外键保证会话结果、规则结果和证据的一致性。

## 标签洞察领域

标签采用固定三级结构：`iqc_label_category`（分类）→ `iqc_label_group`（标签组）→ `iqc_label`（业务标签）。标签本身不保存检测表达式，而是通过 `iqc_label_rule_binding` 绑定既有已发布规则；结构化值由 `iqc_label_value_definition` 定义。`iqc_label_collection` 与成员表用于复用业务选择范围，任务创建时会展开并固化为标签、值、规则及版本快照。

检测仍以 `iqc_inspection_conversation_result`、`iqc_inspection_rule_result` 和 `iqc_inspection_evidence` 为规范结果。`iqc_inspection_label_result` 只是从规范规则结果投影出的业务洞察，保留标签版本、来源规则结果、置信度和值。AI 自动扩展写入隔离的 `iqc_label_candidate`；人工批准只创建标签草稿，不能直接进入发布树。

```mermaid
erDiagram
    iqc_conversation ||--o{ iqc_conversation_message : conversation_id
    iqc_conversation ||--o{ iqc_inspection_task : conversation_id
    iqc_quality_agent ||--o{ iqc_quality_agent_version : agent_id
    iqc_quality_agent ||--o{ iqc_inspection_task : agent_id
    iqc_skill ||--o{ iqc_skill_version : skill_id
    iqc_quality_rule ||--o{ iqc_quality_rule_version : rule_id
    iqc_quality_rule_set ||--o{ iqc_quality_rule_set_version : rule_set_id
    iqc_quality_rule_set ||--o{ iqc_inspection_task : rule_set_id
    iqc_inspection_task ||--o{ iqc_task_execution : task_id
    iqc_task_execution ||--o{ iqc_task_item : execution_id
    iqc_conversation_message ||--o{ iqc_task_item : message_id
    iqc_inspection_task ||--o{ iqc_inspection_result : task_id
    iqc_task_execution ||--o{ iqc_inspection_result : execution_id
    iqc_conversation_message ||--o{ iqc_inspection_result : message_id
    iqc_quality_rule ||--o{ iqc_inspection_result : rule_id
    iqc_inspection_result ||--o{ iqc_result_feedback : result_id
    iqc_inspection_result ||--o| iqc_result_review : result_id
    iqc_inspection_result ||--o{ iqc_quality_sample : source_result_id
```

| 领域 | 表 | 说明 |
| --- | --- | --- |
| 会话 | `iqc_conversation`、`iqc_conversation_message` | 导入/API 会话及有序消息 |
| Agent 与能力 | `iqc_quality_agent`、`iqc_quality_agent_version`、`iqc_skill`、`iqc_skill_version`、`iqc_mcp_server`、`iqc_model_profile` | 质检 Agent、技能、MCP 与模型配置 |
| 规则 | `iqc_quality_rule`、`iqc_quality_rule_version`、`iqc_quality_rule_set`、`iqc_quality_rule_set_version` | 规则和规则集的当前态及版本快照 |
| 执行 | `iqc_inspection_task`、`iqc_task_execution`、`iqc_task_item` | 任务、重试执行和消息级工作项 |
| 结果闭环 | `iqc_inspection_result`、`iqc_result_feedback`、`iqc_result_review`、`iqc_quality_sample` | 命中结果、反馈、复核和样本沉淀 |

`rule_ids_json`、任务快照等 JSON 字段用于保证执行可重现，不等同于实时外键。`owner_group_id` 是来自组织服务的数据范围快照。数据库演进以 Flyway 目录为准。
