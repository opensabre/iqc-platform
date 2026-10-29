# 分片选数的有界游标基础

## 确定缺口

V1.0 PRD23明确要求百万级批量任务可分片与恢复。现有单个InspectionTask最多1000会话、ProductionInputSnapshot最多16MiB、Selector最大完整候选池20000；这些保护不应简单调大。原Task/Execution/Item、周期/事件物化、Selector.materializeTargetsAt和小批输入冻结/幂等已有能力，但缺少超出完整候选池上限的逐片扫描及持久协调。当前普通批量/周期请求不能据此宣称支持百万规模。

本批先扩原ConversationSelectionService内部扫描原语；不新增Task、队列、表、HTTP接口或权限，不建立第二套谓词/标签/数据权限逻辑。后续仍须做持久协调、已有子任务物化、父子引用/进度、暂停取消/恢复与容量证明，不能停在选数函数就认定要求完成。

## 选择

新增scanAllPageAt：

- 仅支持ALL，scanSize为1到min(1000,definition.maximumCount)。其它六种全池采样仍走原完整池语义/20000限制，不把全局RANDOM/RATIO/均匀或风险优先变成逐片近似。
- 锚点精确到毫秒，复用原TimeWindow/时区解析，额外创建时间<anchor所在整秒起点，排除尚未结束的存储秒。基线DATETIME只有秒精度，不能用毫秒<=比较声称排除同秒晚到行。已处理会话状态、消息数、include/exclude及owner/group/all统一提取为同一个私有查询构造器；普通入口仍原谓词与返回协议。
- 对当前游标之后潜在授权候选先以已有created_time索引探测NULL创建时间（只读1个ID），发现则拒绝，不能在ALL中无提示丢弃无法证明锚点的旧数据。原创建时间窗口本就排除NULL时该探测自然为空。
- 候选按数据库ID顺序做keyset，不使用OFFSET，不对百万池COUNT或载入完整元数据。只读scanSize+1个ID，额外一行用于hasMore，不包含在本片。
- 对本片最多1000个ID复用原targeted select；相同RR事务内读取元数据、标签、最近可见结果、未知值/排除条件与字节限制，candidateCount必须与扫描ID数一致且不能limited。原定量/标签/源Hash/证据/共同组交集完整保留，不发模型调用。
- 游标是最后扫描ID，不能只以最后匹配ID推进。筛掉全部候选仍返回正scannedCount和前进游标；空扫描页hasMore=false/selection=null，不调用空IN后退到全池选择。
- ScanPage v2保留原定义Hash、毫秒锚点、明确的createdBeforeExclusive整秒水位、原SelectionScope、输入/输出游标、扫描数和hasMore；非空片的Selection仍使用原iqc-selector-selection-v1。内部调用者必须在持久协调器授权/锁/幂等边界绑定原负责人和游标，不能把任意客户端游标当生产证明。当前没有对外HTTP入口。

## 语义与边界

这是每页RR一致、创建时间有界的增量选择，不声称整个百万语料的所有可变业务字段处于同一跨页数据库快照。各子任务仍必须首次冻结其实际选中会话和原流水线版本，重试恢复要确认已存在的原子任务/原输入，不能重新选择已提交片。后续协调器需要记录此语义及每片来源，不能显示一个伪造的全池统计或全局随机样本。

LIMIT只限制传回ID数量；SQL索引执行计划、稀疏权限/过滤、NULL探测与大数据读取成本仍需真实数据验证，不把有界返回等同有界底层扫描成本或百万容量达标。根现有索引可复用，不先加重复索引。页内原64MiB候选/32MiB标签等保护不解除，过限必须显式失败而非截断。

## 验证/后续

Root仅test-compile，原句柄/终态见根协作记录。B需验证所有输入与非ALL在IO前拒绝、owner/group/all/空组、原时区窗口和创建锚点、NULL创建时间拒绝、LIMIT+1/末页/空页、零匹配推进、无全池COUNT/空IN、目标来源一致性和byte guard、原普通采样Hash/谓词不变，及真实SQL大小写排序/索引/超过20000源的连续覆盖。完整百万任务编排/恢复和原场景A–E另需实作与验收，不在本批宣布完成；单租户组织权限/处理后会话输入/语音处理排除不变，未部署。

Root原44661 test-compile终态0且/tmp/iqc-selector-shard-scan-compile.exit=0，主/测试编译及diff通过，Root未执行测试。公开HTTP/百万编排未开放；内部原语与原普通路径的行为/SQL索引仍待B独立专项。

## 真实SQL后的时间精度修正

B真实SQL确认基线created_time为DATETIME(0)，anchor+1ms入库与anchor可能同秒，v1的<=毫秒围栏不能证明排除。当前内部页协议升为iqc-selector-scan-page-v2，明确createdBeforeExclusive=anchor.truncatedTo(SECONDS)，SQL严格小于该水位；最后开放存储秒的全部会话（包括锚点之前的小部分）有意留到后续批次。相对业务窗口仍按原毫秒anchor解析。不是完整毫秒时点快照，不修改历史时间或正式schema。父定义保持首次anchor，子冻结来源同时保留明确水位/前后游标/扫描数，恢复不能移动截止时间。B应验证整个开放秒排除、上一秒包含与跨页稳定，不能只改原失败期望。
