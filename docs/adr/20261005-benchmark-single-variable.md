# I3 Benchmark 单变量契约准备

状态：只读设计结论，尚未实现Benchmark API、比较器或发布门禁。

需求来源：PRD 14.4，同一Dataset比较Model、Prompt、Pipeline、Skill、Fusion，并支持只改变一个变量。

## 复用与缺口

已检索应用源码、Framework与Wiki，没有既有Benchmark执行能力。复用现有PipelineEvaluationService创建任务/校验冻结输入/逐样本产物/报告，不能再建评测执行框架。缺口是实验绑定、单变量语义比较和配对报告；是否需要独立实验主记录在实现前按幂等、不可变身份和审计要求决定。

参考源：PipelineAssetService.freeze、PipelineRelease、QualityRuleVersion、QualityAgentVersion、AgentConfiguration、AgentAssetReferenceValidator、SpringAiLlmQualityProvider与SnapshotChatModelRouter。

## 服务端约束

双方必须是服务器解析的完整冻结Release、同Dataset ID/版本/hash和全部成员goldHash，固定并发/执行预算。保存完整原快照/版本/摘要；比较采用额外语义投影，不能修改原快照或全局删除id/versionNo。Agent configJson是JSON字符串，须安全解析；数组顺序保留。

| 声明维度 | 唯一允许变化 | 保持 |
|---|---|---|
| PROMPT | 一个明确LLM node的systemPrompt | 模型主备及顺序、Skill、规则/Finding、编排、融合/评分/业务映射 |
| MODEL | 一个明确node及主模型或单个备用槽位的引用与完整快照 | 其余模型及顺序、Prompt、Skill、规则、编排及政策 |
| SKILL | 一个明确node和Skill槽位的引用与快照 | 其余Skill及顺序、Prompt、模型、规则、编排及政策 |
| FUSION | release.fusion | pipeline、scoring、items |
| PIPELINE | release.pipeline作为一个复合版本变量 | fusion、scoring、items |

PIPELINE包含内嵌检测资产，不能称为纯拓扑实验；纯编排比较另须保持公共node的检测资产一致并禁止引入新检测资产。所有维度都拒绝额外scoring/items改变。无实际语义变化、仅展示元数据或版本号递增不构成实验。

允许剔除审计字段及Agent展示字段，但不剔除规则name（可能参与提示）、规则行为字段、Finding版本或Skill name。所选维度引起的Agent版本变化需定点绑定，默认Agent ID不变；不能忽略其他资产引用变化。

## 运行边界

仅Agent schema3.0且无MCP，保持当前Pipeline约束。Skill当前按顺序以名称和instructions拼入提示，未执行独立技能代码。总配置指令截断为8000字符；实验应记录实际有效提示摘要，或拒绝发生截断的配置，不能忽略截断的连带变化。

当前模型路由可回退但尚无实际选中模型/回退轨迹；MODEL实验暂须禁止备用模型，或先补选中模型身份记录。未补齐时只能描述配置变化，不能声称每次调用都使用声明模型。缺少结构化输出/置信度校准/实际成本指标，缺指标不能视为发布门禁通过。

发布门禁、管理员原因与审计另批接入真实评测报告，不能用complete或试跑SUCCESS替代质量阈值。上述只读结论没有宣称实现、运行或验证成功。
