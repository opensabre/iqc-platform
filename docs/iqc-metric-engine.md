# I6 转写时间指标

PRD第7节的Metric Engine需要可计算的静默、响应、抢话和说话占比。实施前检查原FrozenDetectorEngine、PipelineRuntime、NodeResult/Finding、ConversationMessage/TranscriptAlignment，以及拥有应用/共享服务的时长计算：当前只有RULE/DLS/LLM桥接；消息模型已含冻结的毫秒区间，没有音频VAD或真实抢话因果标注能力。复用原消息及对齐校验，不创建指标结果表、会话副本或模型调用。

首批确定性计算基于完整的单会话转写输入：TRANSCRIPT_GAP_MS只算首片段开始到最后片段结束之间区间并集未覆盖的时间，不推断录音首尾或真正声学静默；CUSTOMER_TO_AGENT_MEAN_GAP_MS计算按开始/结束/ID排序后直接相邻客户→坐席片段的非负间隙均值，不称完整客户等待时长；AGENT_TRANSCRIPT_TIME_SHARE使用各角色区间分别合并后的坐席时长/双方时长和，重叠时间会分别进入各角色分母；CROSS_ROLE_OVERLAP_PAIRS只数严格正时间重叠的异角色片段对，不把它称为真实抢话。比例/均值6位小数HALF_UP，所有时间整数计算，不从relativeTime或消息序号补坐标。

空输入或任一片段缺区间保持UNKNOWN，无匹配响应对/零分母也UNKNOWN；角色指标遇未声明角色UNKNOWN，坏坐标/重复ID/混合会话属于错误。最多10000片段，超限拒绝，不截断后冒充完整指标。区间并集和重叠使用排序/活动集合，避免二次方枚举。输入来自后续冻结NodeContext桥接，不接受模型输出冒充时间证据。

当前仅数值内核，Finding适配、资产发布冻结/目录、配置界面与实际运行验收仍待。真正音频静默/抢话、Session/小模型/Skill API/并行DAG仍保留原PRD范围，不以这些转写派生量替代完成要求。未新增或运行测试、未运行SQL/部署。

## Finding桥接首批

TranscriptMetricEngine固定transcript-timing-v1，严格配置metric/显式角色/原Finding引用及服务器冻结定义，NUMBER定义必须允许METRIC证据。只使用原NodeContext.__messages完整可信清单，mapped messages必须完全相同，不能靠裁剪/模型补时间形成全会话指标；原__task EID参与稳定事实身份，完整manifest hash/实际message IDs和单位/样本数作为METRIC证据。UNKNOWN只输出_state，不输出数值或假证据；无confidence=1声明，无评分/风险推断或业务副作用。

目前桥接未注册生产运行时，资产发布仍不接受METRIC；后续需要同一发布/版本冻结与配置界面接入，不能通过试运行临时config冒充已发布能力。真实源/数据范围/运行回放/容量验收待，核心音频Metric要求仍保留。

## 发布及运行时接入

原PipelineRuntimeConfiguration注册METRIC适配；原PipelineAssetService对METRIC分支复用原Finding目录已发布版本，覆盖客户端findingDefinition，固定精确transcript-timing-v1与显式角色/metric配置，在原PipelineAssetVersion发布/哈希/试跑门禁下冻结，不新建指标资产表或版本库。执行前检查引用可用性但保留已冻结body，共用引擎配置校验；历史只读来源仍沿原validateStructure路径。正整数引用额外拒绝超int整数，避免asInt截断成其它版本。

数字事实仍由原融合/评分/标签/动作政策决定业务意义，不在指标引擎中扣分或新建动作。生产/评测共用纯适配；配置界面与真实SQL/运行/引用停用/回放/容量验收仍待，完整I6不宣称完成。

数值条件接入：原PipelineRuntime EQUALS/NOT_EQUALS对两数值复用既有ActionPolicy的decimalValue比较语义，避免Int/Long/Double/Decimal节点类型差异把相同标量数值当成不等；缺失/null及文本/布尔/复合值保持原判断。此更正不回写历史trace或重新执行既有动作，真实分支/评测回放验收仍待。

## 本批配置界面及构建记录

管理端1580f43沿原PipelineReleaseEditor提供四指标、显式角色和原事实定义版本引用；METRIC切换时清除规则/阶段/Agent字段，服务端仍独立校验。最终后端81748离线skipTests test-compile退出0，前端91860 vue-tsc/Vite退出0。上述早期“仅内核/尚未注册”是阶段记录，当前已接通原运行时、发布冻结及界面；真实生产/评测/回放、引用停用、浏览器和容量验收仍待。未新增或运行测试、SQL或部署。
