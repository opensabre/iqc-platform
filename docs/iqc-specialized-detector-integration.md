# 专用检测引擎接入缺口与复用边界

质检入口是已处理好的会话，语音转换、ASR、解码和转录均不属于接入或验收范围。PRD 8.2 的 Small Model 和独立 Skill/API 已有独立协议与执行器；完整生产验收仍未完成。两者不使用 LLM 附加 Skill 指令替代。

## 当前代码证据

- `pipelinebridge/PipelineRuntimeConfiguration.java` 注册 RULE、DLS、LLM、METRIC、SESSION、SKILL_API、SMALL_MODEL；后两者分别沿skill-api-v1与small-model-v1协议执行。
- `pipelineasset/PipelineAssetService.java` 沿原规则/Agent/Finding发布入口冻结专用模型和Skill/MCP快照，按明确id/version从已发布Agent版本选取；新执行检查当前资产可用性，历史恢复保留原快照内容。
- `skill/IqcSkillService.java` 和原 Skill 版本表已有指令、输入/输出 schema 及不可变版本；继续使用这些资产，不新增第二套 Skill 表。
- `result/llm/SnapshotMcpToolProvider.java` 已提供冻结 MCP 配置、凭据引用与客户端生命周期。只读入口还要求部署端精确server/version/tool批准、原allowedTools、远端readOnly=true/destructive=false以及冻结schema一致。声明和本机fixture不能证明真实工具无业务副作用，实际工具拥有方契约仍须验收。
- `modelprofile/model/IqcModelProfile.java` 已管理连接、部署引用、秘密引用、超时和模型版本；现有 OpenAI 兼容聊天协议不能直接证明分类、NER 或嵌入协议可执行。
- `pipeline/ValueSchema.java` 是原节点变量校验器，现已公开受限schema校验入口，Skill/API与专用模型共用该方言、StructuredToolTransport及StructuredToolFindingCodec，未新增另一套校验器。

## 必须接通的实现链路

1. 原资产版本冻结明确的调用能力与输出协议，恢复必须使用同一版本，不访问当前最新配置替换历史快照。模型分类/实体输出与 Skill 指令执行须分别有实际可执行协议。
2. 检测 API 必须有受控的只读调用能力声明与校验；不能仅因 `EngineAdapter.sideEffecting()` 默认 false 就把任意 MCP 工具当作无副作用检测器。工单、辅导及通知继续沿原 Action 账本执行。
3. 节点沿原可信会话 manifest、执行身份与输入映射调用；输出经原 schema/FindingDefinition 校验后生成原 Finding、证据、版本和 inputHash。缺输入、超时及无法确认返回明确状态，不能造通过结论。
4. 沿原 Runtime 的并发容量、取消、变量隔离和评测副作用边界执行；沿原生产、评测、复核和纯政策重算消费事实，不增加第二评分或结果模型。
5. 原编辑器、版本发布与执行 Trace 可配置并呈现真实协议；验收覆盖发布冻结、版本变化后的历史恢复、实际受控调用、无副作用评测、结果持久化与浏览器流程。

## 已验证与剩余门槛（2026-10-15）

后台859ef70包含原资产冻结、专用模型网关调用及安全模型字段；发布/可用性/adapter回归14项通过。真实MCP SDK本机协议与adapter4项通过，网关为模拟对象，不代表数据库预算控制或真实模型部署。be6f19e的小模型复核9项测试覆盖冻结评分重算和来源，不代表复核队列持久化。前端e78dc22及复核合并fdb589a已完成类型检查与Vite构建；事实类型schema修正仍在验证，不能据此声称浏览器完成。

仍需逐项验证实际模型/工具只读契约、任务权限及取消、SQL预算和容量竞争、事实与Trace持久化、历史重算、真实评测/复核队列/导出、发布及浏览器流程。完整PRD其它需求保持原范围；本文件不能作为全部引擎或任务完成证明。
