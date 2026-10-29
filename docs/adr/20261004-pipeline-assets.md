# Pipeline 资产与现有任务复用

PRD 要求独立可复用流水线版本、跨引擎事实融合与政策重算。现有 SchemeDefinition 的四种有限项目路线无法表达变量/输入映射/任意条件和节点错误策略；现有 Agent 资产表示模型能力，不能作为通用 Pipeline。

新增领域资产 PipelineAsset/Version，冻结节点引用的现有 RuleVersion、AgentVersion、FindingCatalogVersion。既有业务方案仍是业务入口，流水线资产是其可引用检测能力，不新造质量方案主表。运行直接复用 InspectionTask/TaskExecution/TaskItem/会话结果，无新任务模型。

兼容任务使用独立 pipelineSnapshot 与执行策略 schema 标记；旧策略读取器会拒绝新标记，防止回滚旧代码后把新任务当旧规则任务执行。评分沿用 InspectionScoring，节点不计分。
