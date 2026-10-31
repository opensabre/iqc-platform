# 模型事实来源支持的人工标注

状态：基础标注协议；尚未交付完整语义幻觉指标。复用现有 BUSINESS Finding 复核的事务、身份、权限、冻结投影、幂等键和历史记录，不新建表/API/角色/Gold 模型，不改 framework。

## 为什么扩展原人工事实裁决

原 GoldFinding 描述期望值，Finding 准确率不等于模型事实是否有来源支持。原引用真实性仅检查引用 ID/逐字内容。HumanFindingReviewEvaluator.Decision 已用 sourceResultId + findingCode + expectedFindingHash 绑定冻结原始融合事实，BusinessItemReviewService 已负责当前授权、完整会话/PII、不可变复核版本与重放校验。沿此机制增加可选 sourceSupport，不解析自由文本原因作为机器标签。

## 输入与校验

原 findingDecisions 中每条 Decision 可附 sourceSupport 数组，每条包含 rawFindingId、verdict、evidenceIds、messageIds、reason。verdict 为 SUPPORTED、CONTRADICTED 或 INSUFFICIENT_SOURCE；均是复核人的明确裁决，不是系统自动推断。SUPPORTED/CONTRADICTED 须有至少一个来源引用；INSUFFICIENT_SOURCE 可以没有引用，但必须有原因。

rawFindingId 必须在该冻结融合事实的 rawFindings 中唯一存在，状态为 KNOWN 且 source.engineType=LLM；不得将规则观察、失败/未知观察、融合结果或人工纠正值当作模型声明。evidenceIds 仅可引用该原始观察自己的证据，messageIds 仅可引用该结果冻结会话消息，原预期事实哈希必须仍匹配。每条最多 100 个来源引用，每个 Decision 最多 1,000 条且原始观察 ID 不重复；原因有界并去首尾空白。证据引用只证明身份合法，语义判断仍由复核人承担。

本批仅支持 LLM 的结构化原始 Finding；供应商已丢失/适配过滤掉的声明、自由文本多命题拆解、非 LLM 节点、没有人工标注的模型输出均未因此完成语义评测。

## 历史、审计和隐私

旧构造器保留，缺字段归为空数组，空数组不序列化，历史 Decision JSON/哈希不因默认空字段改变。非空标注进入原复核请求指纹及 reviewedResultJson，由原版本/复核人/时间和 sourceHash 审计，重放时重新校验冻结来源。原 reason/value/evidence 的人工修正与评分机制保持；sourceSupport 作为附加标注不修改原始 AI Finding、校准置信度、融合规则或动作。

需要仅标注来源时仍提交完整原 Decision 协议，可保留原事实状态/值/严重度及证据，不创造一个绕过原复核准入的入口。读取和导出沿原 BUSINESS 复核完整会话+PII门禁：受限读者的整个 reviewedResultJson 仍被清除，标注原因不会成为额外公开字段。

## 后续与验证

尚需独立验证历史 JSON/精确数字/幂等指纹、正常标注保存/读取/重放、未知/重复/跨事实/非LLM/不完整观察拒绝、证据归属及隐私/撤权。Root 不新增或执行本地应用测试；编译和自动同头 CI 各只证明对应范围，专项由 B 在固定源头后独立补充。

报告/UI、Gold/独立审核接入、声明覆盖率和明确可审计分母仍待后续小 PR。不能将 CONTRADICTED/INSUFFICIENT_SOURCE 在未确定统计口径前直接合并为广义幻觉率，未标注更不能当作零；也不能用引用合法、Finding F1 或本标注协议宣布 PRD14.3 已完成。

本批本地 `mvn --batch-mode --no-transfer-progress -DskipTests compile` 已实际 BUILD SUCCESS（54.037s）；日志 `/tmp/iqc-claim-source-support-compile-20261031.log`。未编写或运行本地应用测试，保留原弃用警告。


## 原 Finding 上下文扩展

原 FINDING_CONTEXT 响应每个 FindingEntry 增加可选 modelClaims 和 sourceSupport，供原编辑器读取当前可标注的原始模型声明与最近已完成复核的有效标注。仅选取冻结融合事实中的 KNOWN LLM rawFinding，包含 rawFindingId、精确 valueJson、reason、节点/版本/运行序号/inputHash 和该观察自己的证据 ID/type/reference。不会返回任意 provider 配置、请求头、模型密钥、校准数据或 Node metrics。空数组省略，原 FindingEntry 构造器保留。

上下文仍先校验 task 数据范围、流水线/生产输入快照、原评测轨迹及上一轮不可变复核投影，不查询当前可变消息。原 ResultReadProjection 的 findingReviewContext 在缺完整会话/PII时清空整个 findings/messages，因此新增嵌套字段与原因受同一门禁保护；没有新读取入口或权限。此增量不是隐私或真实PDP验收结果，须由原HTTP隐私回归和B专项证明。

上下文增量 `mvn --batch-mode --no-transfer-progress -DskipTests compile` 实际 BUILD SUCCESS（2:01），日志 `/tmp/iqc-claim-support-context-compile-20261031.log`；没有运行本地应用测试。
