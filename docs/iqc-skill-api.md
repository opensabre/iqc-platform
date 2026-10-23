# 独立 Skill/API 检测节点

`SKILL_API` / `skill-api-v1` 复用原已发布 Agent 3.0 中的 Skill 和 MCP 快照，经明确批准的只读工具执行专项检测。质检输入为已处理会话；不包含语音转换。它不调用 LLM 附加提示，也不承担通知、工单或辅导任务等业务动作。

配置包括 `agentReference`、`skillReference`、`mcpReference`、`findingReference`、`toolName`、`toolInputSchema` 和 `toolOutputSchema`。所有引用须含 id 和正整数 versionNo。发布时从指定 Agent 版本选择唯一且版本相符的 Skill/MCP；覆盖调用方提供的快照，冻结原 Finding 定义，不请求远端工具。Skill 的原 inputSchemaJson/outputSchemaJson 须使用原 ValueSchema 支持的方言。

部署配置 `iqc.pipeline.read-only-mcp-tools` 是逗号分隔的 `serverId:versionNo:toolName` 批准列表，默认空列表。服务器 ID 使用字母、数字、下划线或连字符，最长128字符；工具名另允许句点，最长128字符。批准须由工具拥有方的只读行为契约支持，不能仅凭远端 hint 认定无副作用。运行还要求冻结 allowedTools 允许、远端明确 readOnlyHint=true/destructiveHint=false，以及远端输入/输出 schema 与冻结契约一致。拒绝时不调用工具。

请求为 `schemaVersion=iqc-skill-request-v1`、冻结 `skill`、映射 `input` 和 `inputHash`。哈希基于未加哈希字段的请求与可信完整消息 manifest；执行身份来自原 __task，不允许映射输入替换可信消息。Skill 输入 schema 校验映射输入，工具请求 schema 校验完整请求。

MCP 回执必须 isError=false 并携带对象 structuredContent。该对象同时匹配工具及 Skill 输出 schema，并含 `schemaVersion=iqc-skill-response-v1`、同一 inputHash、skillId 和整数 skillVersion。事实字段为 state、value、数值或空 confidence、有限 reason，以及可选 evidence 数组；证据含外部 reference 和 details。原 Finding/FindingDefinition 继续验证状态、类型、置信度和证据要求。不匹配或错误不能产生通过事实。

应用拒绝超过64KiB的序列化请求/回执、超过100条证据及过长原因或引用。这是应用层检查，不能当作 SDK 网络响应流在分配前的大小限制。客户端生命周期和认证仍由原 SnapshotMcpToolProvider 管理；原 Runtime 负责超时、取消、重试与失败政策。

已通过节点与只读入口 fixture 测试7项、原发布冻结/节点/授权组合15项；fixture 中仅 MCP 客户端被替代，未证明真实服务器协议、权限或持久性。界面版本加载、选项和竞态/无效草稿8项组合测试已通过，完整构建及追加协议拒绝测试另见协作日志。完整历史恢复、实际 MCP 服务器、Task/结果 SQL、API 与浏览器验收仍需完成，SmallModel 为独立待办。

## 2026-10-15 复核链路集成缺口

集成后确认原复核ItemContract仅接受METRIC/LLM的真实非规则节点绑定，Skill/API原ruleId为空/version=0会被误拒，阻断评测/复核/导出。沿原节点/引擎版本及Finding来源核对增加SKILL_API，不制造规则ID/版本，原Scheme/RULE约束保留。前端Trace同样接受受支持的SKILL_API非规则来源。与Review V2字段、精确采样身份及A/B Editor区域一同合并，真实发布/任务/队列/SQL/API/浏览器仍待。

本次27161 backend离线skipTests test-compile已terminal exit0，81530最终UI构建仍在运行。另只读核对BenchmarkVariableComparator：PROMPT/MODEL/SKILL路径目前只识别LLM/agentSnapshot，独立SKILL_API不能被现有单变量报告证明，须后续在原比较器扩展并保留除声明变量外全部冻结配置，不能以PIPELINE复合差异冒充Skill单变量。未新增运行测试/SQL/部署，真实发布/Task/评测/复核/CSV/角色/浏览器继续。

## 2026-10-24 指令版本作为系统Prompt来源

Agent 3.0可使用promptAsset={skillId,versionNo,contentHash?}选择原Skill指令资产的精确历史版本。该引用只取instructions作为systemPrompt，不把Skill的工具路由、输入/输出Schema或名称自动作为LLM能力启用。已有Skill版本更新、启停、回滚和敏感投影继续承担管理；不增加平行Prompt表或版本接口。取舍见ADR 20261024-prompt-instruction-version-reuse。

版本号须为正整数JSON数字，字符串、小数及超出int范围的值拒绝。新草稿保存时从明确Skill版本解析正文，要求当前资产与所选历史版本启用、正文1–8000字；有调用方正文或摘要时必须与来源一致。服务端生成固定域/skillId/versionNo/正文的JSON数组摘要，冻结到promptAsset.contentHash。旧内嵌Prompt无新字段；旧构造保留。提交审批再次核对来源，不跟随Skill当前版本换文字。已冻结Agent/Pipeline包含实际正文及来源ID/版本/Hash，来源变动或停用不能静默替换历史正文。PipelineAssetService仅在需要当前执行可用性时对新绑定调用同一validatePrompt，Skill停用/原版本正文变动拒绝新建或执行，不在历史解释中读取可变指令代替冻结正文。

PROMPT Benchmark将来源及正文视为同一变量，原其他不变量继续核对；旧无引用实验的比较协议/paths保留。只换来源但有效Prompt不变会被原语义差异门禁拒绝，MODEL/SKILL等维度不能顺带改Prompt来源。

页面复用原Skill目录及/skills/{id}/versions，明确选择精确版本、绑定后正文只读，切回内嵌保留原文字并移除来源。权限不足、受限正文、旧响应及关闭后不会安装来源版本。编译/构建及独立验证终态以协作记录为准；数据库/角色/真实发布和任务/Benchmark、权限生命周期仍需专项。KnowledgeBase语料独立版本仍另行实施，本批不将指令资产当知识库。

### Prompt精确版本专项验证（B，2026-10-24）

固定同步d2fe179后，PromptAssetVersionTest验证历史版本正文冻结、不修改调用方或
来源对象、冻结配置JSON往返、历史正文篡改拒绝，以及停用/缺失/错ID/错版本/
超长正文/坏Hash拒绝。JSON字符串、小数、非正数、越界数与布尔版本均拒绝；
旧构造序列化省略promptAsset，内嵌配置不访问版本解析依赖。

原14934离线test-compile+surerfire终态0；新增4项及原Agent/Benchmark组合共27项，
零失败、错误、跳过。日志`/tmp/iqc-b-prompt-version-validation.log`。
Mapper为受控适配，此证据不替代真实组织权限、SQL发布/工作器及浏览器验收。

### Prompt单变量实验验证（B，2026-10-24）

PromptBenchmarkVariableTest通过内嵌到版本绑定及反向比较，确认仅提示词改变可形成
PROMPT实验，并且不修改两侧原配置。正文相同仅更换来源不能形成有效实验；
PROMPT同时改变模型、MODEL夹带Prompt来源变化、正文摘要被篡改均拒绝。
原77068 compiler:testCompile+surerfire终态0，新增4项与Prompt版本/SkillAPI/
SmallModel组合共17项零失败、错误、跳过。日志`/tmp/iqc-b-prompt-benchmark-validation.log`。
此为原比较器的冻结配置验证，不替代同Dataset真实评测任务和供应商调用验收。
