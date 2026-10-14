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
