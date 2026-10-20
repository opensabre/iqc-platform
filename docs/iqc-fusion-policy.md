# IQC 跨节点事实融合协议

本模块对应 PRD I2 的基础 Fusion，输入为 `finding.Finding`，输出为 `FindingFusion.Result`。它是纯函数，不调用模型、不修改原始事实、不评分、不生成标签、不写数据库。

## 复用与职责

已经检查 Wiki 服务地图、现有 `label/LabelFactEvaluator.java` 和新的 Finding 契约。现有 LabelFactEvaluator 负责冻结标签定义、说话人、规则映射和文本证据校验；不能替换为宽松的通用融合。Fusion 负责不同检测节点对同一稳定语义 code 的观测冲突，复用 Finding 的类型、状态和证据对象。没有新增标签表或重复结果表。

## 冻结政策

`FusionPolicy(id, version, method, nodePriority, overrideNodeByCode, confidenceCalibrationId, calibratedNodeIds, nodeWeights)`（保留原7参构造） 必须作为方案依赖一起冻结。版本从 1 起；优先级数值越大越优先；未配置节点优先级为 0。

| 方法 | 判定语义 |
| --- | --- |
| PRIORITY | 选最高优先级的全部观测；同级不同值为 CONFLICT，错误或未知不被低级阴性掩盖。 |
| CONFIDENCE | 政策必须声明校准版本及已校准节点；任何未声明节点拒绝运行。最高校准置信度的全部观测共同判定；缺失置信度不做排序。 |
| VOTING | 一节点一票，严格超过全部节点数一半才接受已知值。重复运行必须先汇总，不能增加同一节点权重；重复节点直接拒绝。 |
| OVERRIDE | 每个 code 显式指定权威节点；指定节点没有观测时为 NOT_EVALUATED，不回退成 false。 |
| WEIGHTED | 一节点一个观测，支持同一已知值的权重严格超过该code全部输入源权重一半才确定；未决观测计入分母但不投阴性票。 |

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

原Pipeline发布/生产/评测/政策重算与配置编辑器复用该函数，本批不增加平行端点或结果表。CUSTOM扩展及统一finalConfidence投影仍待后续补齐。当前WEIGHTED仅编码/编译构建，独立行为与兼容验收见协作交接，不能据此认定完整I2完成。


## WEIGHTED 精确权重（2026-10-20）

PRD10.2明确要求WEIGHTED，原四种方法不能表达来源权重。因此扩展原policy/fuse，不创建第二套融合器。该方法选择已有观测值，不对数值事实做无业务口径的均值，也不把权重/支持率当作模型校准置信度。

配置示例：`"method":"WEIGHTED", "nodeWeights":{"rule_main":"2","llm_aux":"1"}`。权重必须为十进制JSON字符串（不接受JSON数字/布尔/对象/数组），大于0、不超过1000000、最多12位小数，输入字符串最长32字符；不接受指数、正负号和前导零。节点ID非空、最长128且无控制字符，最多200项。服务器规范化尾随零后保存，例如1.00归一为1，旧非weighted配置不允许残留非空权重。

PipelineRelease必须恰好配置全部节点的权重，未知/缺失节点在发布冻结前拒绝。纯函数按每个code的实际输入源计算分母；不存在于输入中的节点无法由纯函数凭空推断，执行器仍须输出预期跳过来源的显式NOT_EVALUATED。跨不同code不互相投票。

同一code每节点最多一条观测，重复源拒绝，不能通过多次调用增重。对KNOWN且语义相同的值求支持权重S，对全部输入观测求权重T，以精确BigDecimal判断2*S > T，无浮点数和除法舍入。缺任一输入节点的权重立即拒绝。

- true权重2、false权重1：接受true，保留false的原始事实及反证。
- true权重0.1+0.2、false权重0.3：精确平票，不选任意一侧，返回CONFLICT。
- true权重0.4、UNKNOWN权重0.6：UNKNOWN；将UNKNOWN当false属于错误实现。
- true权重0.4、ERROR权重0.6：ERROR；全部未执行为NOT_EVALUATED。

无严格多数时，不同已知值为CONFLICT，其余沿原incompleteState处理；全部原始Finding、selected/rejected与所有证据并集的原契约保留。结果解释记录精确支持/总权重及政策ID/版本，不新增不可解释的置信度。

nodeWeights缺省或空时不序列化，新字段不应改变旧7参policy/历史快照的JSON/hash；该兼容约束需双代序列化/旧发布与重算专项证明。原方法判定路径保持，本批不改变原Result协议。UI复用原融合区域，字符串编辑与BigInt边界验证避免大数/小数静默舍入；切回其它方法时不发送残留权重。

无新表/权限/框架改动，Root不自行编写/执行测试，不执行SQL/推送/部署。实际发布、生产/评测/重算、Benchmark五维与浏览器验收仍待独立验证。


### 政策重算的缺失来源

原生产执行器已为未产生观测的节点补NOT_EVALUATED。重算读取旧事实而不执行目标检测器，因此新增weighted目标节点可能不在原raw中。DecisionRevisionService仅对WEIGHTED复制原raw，并按目标冻结FindingDefinition为缺失的节点/code补透明NOT_EVALUATED（无value/置信度/证据、明确未重跑原因），再按原权重融合；未决权重不会从分母消失。原trace/原raw/原结果仍不写回，其它融合方式保持原重算路径。目标未给旧来源配置权重仍拒绝，不通过删旧事实来使新政策成功。
