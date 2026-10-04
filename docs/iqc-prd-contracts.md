# IQC PRD 协作接口契约

状态：I1 第一个增量契约；尚不包含通用 Pipeline 编辑、Finding 目录或评测 API。

## 现有路线执行轨迹

复用 `GET /api/iqc/tasks/{taskId}/conversations/{conversationId}/result-hierarchy`。权限和数据范围沿用现有接口：Controller 先通过会话结果查询校验任务/会话可见性，再返回结果层级。

响应在 `conversation` 上增加可空字符串 `executionTraceJson`，JSON schema 为 `iqc-route-execution-trace-v1`。旧记录、非逐项路线记录为空，前端显示“此执行未记录节点轨迹”，不得补造运行数据。

```json
{
  "schemaVersion": "iqc-route-execution-trace-v1",
  "taskId": "task-id",
  "executionId": "execution-id",
  "conversationId": "conversation-id",
  "planSchemaVersion": "iqc-item-execution-plan-v2",
  "inputHash": "sha256",
  "ruleSnapshotHash": "sha256",
  "agentSnapshotHash": "sha256-or-null",
  "nodes": [{
    "nodeId": "frozen-context-key",
    "runIndex": 1,
    "ruleId": "rule-id",
    "ruleVersion": 1,
    "engineType": "LLM",
    "phase": "DIRECT",
    "inputScope": "CONVERSATION",
    "upstreamNodeId": null,
    "executed": true,
    "executionStatus": "SUCCESS",
    "resultStatus": "NOT_HIT",
    "factState": "NEGATIVE",
    "reason": "检测未命中",
    "inputHash": "sha256",
    "durationNanos": 1234567,
    "startedAt": "2026-10-04T00:00:00Z",
    "rawFinding": {},
    "evidence": []
  }]
}
```

- nodes 保存各原始轮次，不以多轮投票聚合替代原始检测输出；每个轮次内保持现有执行顺序。
- executionStatus：SUCCESS/FAILED/SKIPPED。成功执行可以得到 UNKNOWN，不等于业务通过。原路线未返回合规输出时仍用 REVIEW_REQUIRED。
- factState：POSITIVE/NEGATIVE/UNKNOWN/ERROR/NOT_EVALUATED。被规则初筛跳过的下游节点可以有 resultStatus=NOT_HIT，但 factState=NOT_EVALUATED，不能冒充执行得出的阴性事实。
- durationNanos 是实际 detector 调用的单调时钟耗时，包含适配器执行，不是 provider token 延迟；跳过节点为 0、startedAt/inputHash 为 null。
- rawFinding/evidence 为既有输出协议，不把 ruleId 当作业务 Finding code。后续 I1 增量会补统一 typed Finding。
- 原始输出在持久化前脱敏；格式无效时对应值为 null 并带 rawFindingParseError/evidenceParseError=true，不泄漏无效原始字符串。
- 模型配置通过 agentSnapshotHash 绑定；这不是供应商不可变模型部署版本声明。
- 轨迹同现有会话投影在事务中保存。当前只保存已完成会话的轨迹；进程崩溃前未完成的节点记录与独立持久化检查点在后续 worker 改造实现，不宣称现已支持。

## 前端实施边界（建议另一位开发者领取）

复用现有 result-hierarchy API 与结果页面：新增安全解析 executionTraceJson 的纯函数和独立轨迹组件；按 runIndex 分组，展示节点/版本/状态/耗时/原始事实与证据。未经确认不要编辑后端拥有的文件。

使用文本渲染 JSON、reason、引用，不使用 v-html；解析未知 schema 或无效 JSON 时显示不可用。原始/融合/人工结果明确区分，不能将此轨迹描述为通用流水线已上线。
