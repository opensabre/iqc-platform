# IQC 跨节点事实融合协议

本模块对应 PRD I2 的基础 Fusion，输入为 `finding.Finding`，输出为 `FindingFusion.Result`。它是纯函数，不调用模型、不修改原始事实、不评分、不生成标签、不写数据库。

## 复用与职责

已经检查 Wiki 服务地图、现有 `label/LabelFactEvaluator.java` 和新的 Finding 契约。现有 LabelFactEvaluator 负责冻结标签定义、说话人、规则映射和文本证据校验；不能替换为宽松的通用融合。Fusion 负责不同检测节点对同一稳定语义 code 的观测冲突，复用 Finding 的类型、状态和证据对象。没有新增标签表或重复结果表。

## 冻结政策

`FusionPolicy(id, version, method, nodePriority, overrideNodeByCode, confidenceCalibrationId, calibratedNodeIds)` 必须作为方案依赖一起冻结。版本从 1 起；优先级数值越大越优先；未配置节点优先级为 0。

| 方法 | 判定语义 |
| --- | --- |
| PRIORITY | 选最高优先级的全部观测；同级不同值为 CONFLICT，错误或未知不被低级阴性掩盖。 |
| CONFIDENCE | 政策必须声明校准版本及已校准节点；任何未声明节点拒绝运行。最高校准置信度的全部观测共同判定；缺失置信度不做排序。 |
| VOTING | 一节点一票，严格超过全部节点数一半才接受已知值。重复运行必须先汇总，不能增加同一节点权重；重复节点直接拒绝。 |
| OVERRIDE | 每个 code 显式指定权威节点；指定节点没有观测时为 NOT_EVALUATED，不回退成 false。 |

政策中的校准标识是已经离线验证并写入版本快照的前置条件，不会替调用方校准模型自报置信度。生产接口应限制政策发布权限，并校验校准工件的真实性。本模块只实施契约检查。

## 不完整与冲突

KNOWN 包括显式 false，UNKNOWN/ERROR/NOT_EVALUATED 不对应阴性。相同 code 必须拥有相同类别和值类型，否则拒绝融合。选中观测的不同已知值或 CONFLICT 产生 CONFLICT；否则有 ERROR 保留 ERROR，有未知或部分未执行为 UNKNOWN，全部未执行为 NOT_EVALUATED。

VOTING 无严格多数时，有不同已知值为 CONFLICT；否则保留不完整状态。跳过节点不得合成值；如政策需要它参与覆盖率或投票分母，执行器须提供显式 NOT_EVALUATED 观测。纯函数无法推测输入列表中不存在的节点。

数值型比较按十进制数值比较，因此 1 与 1.0 视为同值；对象型按 JSON 结构比较。融合不计算跨引擎平均置信度，不默认严重程度为风险决策；输出 severity 是所选观测的最大级别，供下游政策解释。

## 可追溯输出

按首次出现的 code 排序；所有 rawFindings 原样保留。selectedIds 为参与最终判定的观测，rejectedIds 是其余观测，并不表示原始结果被删除。无多数投票和缺失置信度时，全部观测参与未决判断。所有来源的证据取并集，避免隐藏被拒绝来源的反证；相同证据 id 如内容不同直接拒绝，禁止静默覆盖。输出记录 explanation、policyId 和 policyVersion。返回列表和 JsonNode 均防御性复制。

## 集成位置

1. 节点执行成功或失败后，适配成 Finding；原始节点输出保持现有轨迹持久化。
2. 同一会话的完整节点观测经 `FindingFusion.fuse(findings, frozenPolicy)` 融合。
3. 决策层仅从 KNOWN 读取 typed value；其它状态进入待复核或不完整政策，不由融合层决定扣分、标签、动作。
4. 在会话结果快照内保存冻结政策、输入事实和融合结果；避免只保存终值丢失反证。当前模块没有新增持久化，调用方需接入既有结果事务。
5. 旧标签事实链保持其说话人和证据验证，再通过显式适配接入，不能把任意 Finding 自动视为合格标签事实。

本增量未增加端点、UI、WEIGHTED/CUSTOM 融合或策略发布门禁；这些需沿迭代计划由集成层补齐。未新增或执行测试，由协作主开发者统一编译检查。
