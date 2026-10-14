# 专用检测引擎接入缺口与复用边界

质检入口是已处理好的会话，语音转换、ASR、解码和转录均不属于接入或验收范围。PRD 8.2 的 Small Model 和独立 Skill/API 节点仍需实现，不能用 LLM 附加 Skill 指令代替。

## 当前代码证据

- `pipelinebridge/PipelineRuntimeConfiguration.java` 注册 RULE、DLS、LLM、METRIC、SESSION，尚无独立专用模型或 Skill/API 引擎。
- `pipelineasset/PipelineAssetService.java` 冻结原规则/Agent 与 Finding 定义，METRIC/SESSION 有专用版本协议；新引擎必须沿此发布、恢复、引用校验入口接入。
- `skill/IqcSkillService.java` 和原 Skill 版本表已有指令、输入/输出 schema 及不可变版本；继续使用这些资产，不新增第二套 Skill 表。
- `result/llm/SnapshotMcpToolProvider.java` 已提供冻结 MCP 配置、凭据引用与客户端生命周期。原 MCP 模型仅有 allowedTools，没有能够证明工具只读的能力约束；工具白名单不等于无业务副作用。
- `modelprofile/model/IqcModelProfile.java` 已管理连接、部署引用、秘密引用、超时和模型版本；现有 OpenAI 兼容聊天协议不能直接证明分类、NER 或嵌入协议可执行。
- `pipeline/ValueSchema.java` 是原节点变量校验器，当前包内可见且仅支持受限 schema 方言。接入前核对 Skill schema 的既有方言，优先最小扩展原校验入口，不能另建不一致的 schema 校验器。

## 必须接通的实现链路

1. 原资产版本冻结明确的调用能力与输出协议，恢复必须使用同一版本，不访问当前最新配置替换历史快照。模型分类/实体输出与 Skill 指令执行须分别有实际可执行协议。
2. 检测 API 必须有受控的只读调用能力声明与校验；不能仅因 `EngineAdapter.sideEffecting()` 默认 false 就把任意 MCP 工具当作无副作用检测器。工单、辅导及通知继续沿原 Action 账本执行。
3. 节点沿原可信会话 manifest、执行身份与输入映射调用；输出经原 schema/FindingDefinition 校验后生成原 Finding、证据、版本和 inputHash。缺输入、超时及无法确认返回明确状态，不能造通过结论。
4. 沿原 Runtime 的并发容量、取消、变量隔离和评测副作用边界执行；沿原生产、评测、复核和纯政策重算消费事实，不增加第二评分或结果模型。
5. 原编辑器、版本发布与执行 Trace 可配置并呈现真实协议；验收覆盖发布冻结、版本变化后的历史恢复、实际受控调用、无副作用评测、结果持久化与浏览器流程。

本文件记录待实施链路，不能作为引擎已经完成的证据。与 A 的 PipelineAssetService 发布冻结及界面评分区域协作后再改动共享入口。
