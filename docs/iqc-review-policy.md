# 独立复核政策

PRD11.5要求高风险强制复核、低置信度复核、中间置信度抽样与普通抽样。原系统已有三态Condition、选择器稳定sampleKey、融合选中原事实/校准声明、CREATE_REVIEW动作和原复核记录；缺少冻结后决策复核规则与可核对抽样轨迹。追加可选ReviewPolicy/ReviewDecision到原Release/Trace，不建表、第二条件语言或另一个创建服务。

规则含原Condition、可选Finding置信度半开区间、精确概率0..1（至多4小数）。风险/score/result状态已计算可读，禁止读取复核决定本身。置信度仅接受原CONFIDENCE融合、校准声明及全部选中已知事实的真实confidence；缺失或不可确认为UNKNOWN，不能用ASR坐标confidence或伪造0/0.9。当前适配器未普遍产生校准confidence，真实校准来源仍待验收。

复用选择器sampleKey，seed+ruleId无歧义组合与冻结inputHash确定10000桶，命中概率对应阈值。是稳定逐会话概率抽样，不承诺有限批次恰好20%/2%。重试/同输入重算不重新抽奖，改seed或输入产生新抽样。规则条件与置信度三态合取，桶未选中或概率0不要求复核；任一确定命中选中规则为REQUIRED；只有可能命中时按显式unknownHandling选择REQUIRE_REVIEW或KEEP_UNKNOWN。事实未知状态原样保留。

Review政策必须声明唯一直接REVIEW_REQUIRED=true的CREATE_REVIEW动作，沿原适配器/原owner bound/当前grants/幂等/失败策略创建实际记录；不能只增加展示标记或绕开账本。配置界面显式连到该动作。生产实际发送仍受原动作开关控制，评测/重算只投影，不创建复核。校验/来源证明需重算规则、置信度、桶及最终决定；旧JSON新null省略，旧构造兼容。

本批为实施前缺口与复用记录。真实队列/角色/SQL/API/浏览器/校准来源及完整人工修改/指派/二审/仲裁仍待，全部其它PRD继续；没有运行测试、SQL或部署。

## 分层与实现链路

普通规则可以显式标记fallback，最多一条。非兜底规则概率大于0且条件可能/确定成立时，兜底规则被抑制，即使特殊规则本次桶未选中；避免普通2%与特殊20%叠加为21.6%。未知特殊条件也不借用普通比例，按特殊规则抽样及未知策略处理。采样桶/原是否选中/是否让位/有效匹配全部保留，禁用概率0的规则不会占用特殊层。

原Release/Evaluation追加可选reviewPolicy/reviewDecision，旧9/8构造保留；Action.Context原6/8构造保留，新reviewStatus只供后续动作读取。Score/Risk禁止读Review避免循环，Review可读已完成风险/评分/result和原冻结输入。发布冻结/历史结构校验都核对输入引用及唯一直接CREATE_REVIEW路由；原source validator重新计算决定与trace逐项比较并给原动作上下文提供reviewStatus，评测校验同样重算。原DecisionRevision存原/目标决定，不调用检测/动作或创建队列。

配置界面组织抽样/置信度/种子/未知策略字段，复用原ConditionEditor与精确数字校验，原ActionPolicyEditor仍编辑实际CREATE_REVIEW配置。启用时显式连接复核创建动作，名称冲突或达到动作上限时不覆盖已有动作，保存会提示修正。取消政策只移除未修改的默认路由；自定义引用须显式解除。Trace显示决定、匹配/真实校准confidence/抽样选择及普通让位数据，原运行时reviewRequired不被覆盖。

42257首轮、17792来源/重算、95532分层最终离线skipTests test-compile均terminal exit0；UI21086首轮、1343决定显示、94003分层最终vue-tsc/Vite均terminal exit0。未新增运行测试/SQL/部署，真实CREATE_REVIEW队列、授权/并发/幂等/失败/历史、实际校准来源/抽样分布/浏览器/发布与完整PRD仍待。采样沿既有inputHash语义；历史JSON数字哈希的精度保真范围需单独审查，不以此实现证明所有高精度输入的采样身份已完备。

## 精确抽样身份缺口与实施取舍

旧运行inputHash沿PipelineAssetService.hash的浮点JSON读取，可能将99.999999999999999999与100归为同一身份；不能以稳定桶证明精确输入身份。改变全局算法会破坏旧快照解释。复用SelectorAssetService精确hash，新增iqc-review-policy-v2，盐含明确schemaVersion，完整原executionInput生成samplingInputHash；旧v1逻辑、盐与桶保留。决定记录独立采样身份，原inputHash关联不改。

生产/评测/来源从同一冻结消息+业务上下文生成精确身份，政策重算复用原冻结输入；旧历史缺少完整冻结输入时不读当前档案或借旧浮点Hash冒充精确身份。概率0/1可确定选择，其余选择/桶为null且匹配UNKNOWN，沿显式未知策略，不造false或0。新增字段null省略、旧构造保留。当前是实施前取舍，真实验证仍待。

### V2身份链路与历史降级

复用原executionInput的静态构造入口，全消息（含原坐标/置信度）与冻结businessContext交给SelectorAssetService精确canonical hash，不增加输入副本或新的hash实现。生产、Gold评测、动作来源及原DecisionRevision调用同一入口并核对samplingInputHash；原inputHash仍用于旧运行关联。V1及没有Review的生产/评测不增加精确hash计算，V1盐/桶与旧null字段省略保持原样。新界面默认V2，旧V1可显式升级，规则/种子/路由保留，新版本桶重新确定，旧版本解释不变。

V2历史没有完整冻结输入时samplingInputHash=null、bucket=null；probability=0确定selected=false，=1确定true，非确定概率selected=null。未知选择与条件/置信度按三态合取，NO_MATCH仍可确定不审，其余按unknownHandling决定；不为了采样读取可变当前档案、重跑检测或发送动作。UI显示身份未确认，后端来源核对拒绝不一致决定。保持旧evaluate/reviewDecision/Decision构造入口，新增精确身份路径供原实际调用链使用。

32792首轮backend skipTests test-compile和25081首轮UI build已terminal exit0；21035避免V1无谓计算的最终backend已exit0，升级界面16763最终build仍待终态。本批未新增运行测试/SQL/部署，真实高精度输入/保存恢复/实际队列/历史/权限/校准及完整端到端验证仍待。精确性针对实际冻结输入，不把此实现当作所有上游HTTP/导入/标注路径的精度保证。
