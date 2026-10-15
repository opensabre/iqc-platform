# 冻结数据集单变量 Benchmark

状态：本地编码与构建，未运行数据库、模型、角色或浏览器验收；不代表I3或完整PRD完成。

## 复用和持久化

同一事务创建两份既有 PIPELINE_EVALUATION Task，benchmarkSnapshot保存schema、实验ID、请求摘要、side/peerTaskId、变量声明、比较摘要与双方精确目标。没有实验主表、第二套任务或执行器。基线/候选原始完整Release保留各自Task；任务身份复用 actor/request 派生函数，单评测旧ID算法不变。

创建不自动运行，两Task分别通过既有 run/pause/resume/cancel 执行。相同请求返回原配对，内容不同拒绝；任一侧缺失拒绝重新捕获。报告/配对查询校验两Task可见性、双向绑定、相同Dataset ID/版本/hash、固定并发、目标发布版本/草稿修订及完整Release摘要，并重新计算变量比较。历史读取不要求资产仍启用；新的创建/排队仍执行已有可用性约束。

## API

基址 `/api/iqc/evaluation/benchmarks`：

| 方法 | 后缀 | 权限 |
|---|---|---|
| POST | 空 | iqc:benchmark:create |
| GET | /{baselineTaskId} | iqc:benchmark:view |
| GET | /{baselineTaskId}/report | iqc:benchmark:report:view |

创建：`{name?,datasetId,datasetVersion,baseline:{pipelineId,pipelineVersion?或draftRevision?},candidate:{同基线},selection:{dimension,nodeId?,slot?},concurrency?,requestId}`。每侧发布版本/草稿修订二选一；name<=100、并发1–32、请求16–64字符字母数字下划线连字符。selection维度PROMPT/MODEL/SKILL/FUSION/PIPELINE；Agent维度需要同一个明确LLM node；MODEL仅主模型slot0/null且无备用模型，SKILL一个有效数组位置。

Pair返回实验身份、数据集身份/hash、selection、comparison及两Task。Report查询必须明确 baselineExecutionId/candidateExecutionId，不静默取最新；返回Pair、双侧完整Report、complete及deltas（候选减基线）。F1/召回/coverage/回归率正值较好，MAE/P95负值较好；零分母保持null。complete只表示双侧报告产物齐全，不是质量准入，不自动判胜者。缺失/错误/继承产物仍按照各自完整gold分母与原EID保存，不能筛出共同成功子集美化指标。

## 单变量边界

依照ADR 20261005-benchmark-single-variable。原始摘要保留在benchmarkSnapshot.baselineHash/candidateHash，Comparison releaseProjectionHash是额外稳定序列化投影摘要，不替代原始摘要；仅无序calibratedNodeIds排序，节点/模型/Skill数组仍保留顺序。Fusion id/version单独变化没有实际语义变化，拒绝。指定Agent版本/审计/展示字段允许定点归一；其他规则/Finding/来源身份与版本不全局删除。

MODEL不支持带备用模型的配置，直到实际选中模型/回退轨迹补齐。PROMPT/SKILL必须改变有效配置提示；selected节点的8000字符截断拒绝。PIPELINE是含检测资产的复合版本，不能称为纯编排实验。Skill当前是提示注入，不声称独立工具执行。没有新增模型一致性、结构化成功率、置信度校准或成本指标；这些缺口仍保留。

## 前端与权限

复用评测页资产目录与版本选择，新建实验时仅增加候选版本和唯一变量声明；两Task任一侧结果页面可加载同一配对报告，明确选择两轮EID。报告组件职责是展示配对与差值，复用原评测Report，不复制执行界面。

V20261004_10新增3个资源与按钮900198–900200；MENU900197使评测任务页具有真实组织菜单入口，将09按钮迁入而保留ID与角色关系。仅IQC_ADMIN/QUALITY_MANAGER创建，四角色查询；未授权系统ADMIN。迁移未执行，真实登录与业务数据范围验收未运行。

## 验证与后续

最终后端 `mvn -o -q -DskipTests test-compile` 成功（含最新主源码与既有测试源码，仅编译），前端 `npm run build`（vue-tsc+Vite）成功，临时依赖软链已移除。没有新增或执行测试。发布门禁、管理员例外原因/审计、完整各层指标及I4–I7仍待继续；本批没有推送、合并、部署或数据库写入。

## 独立Skill/API比较的实施前复用缺口

原比较器仅支持LLM节点中提示Skill数组；不能把独立SKILL_API版本比较当作已覆盖。沿原SKILL维度/双Task/报告增加独立节点投影。协议验证复用SkillApiDetectorEngine.validateConfiguration，核对冻结引用。Skill快照整体实际发送到远端，id/version/instructions/schema等属于该操作契约；Agent引用只可在相同id下换版本锚点，MCP/工具/参数/schema/其它政策与节点保持。无独立有效prompt，报告prompt hash为null；原LLM Skill仍必须改变有效提示。原无限定节点/多变量/纯显示配置变化规则保持现有边界。

### 独立Skill已接入代码

SKILL选择支持LLM提示槽位和SKILL_API单一引用。独立节点slot为null/0，比较器复用原引擎配置验证、显式核对skillReference与snapshot同id/version及正整数Agent版本；完整Skill快照是实际请求的一部分（含操作版本），对应变量hash。只mask指定引用id/version、快照和同Agent的版本锚点，未知额外字段仍为不变量。Agent id、MCP/工具/schema、输入输出映射、Finding/评分/标签/风险/Review及其它节点都保留在不变量中；候选改变其中任何字段即拒绝。两节点ID/类型与路径也须一致。

独立调用不使用有效LLM提示，两个prompt hash为null；原LLM Skill仍保留有效prompt差异与8000字截断约束，不以能力版本更名冒充提示变化。独立Skill整个请求快照变化不能证明质量提升，报告仍用原固定Dataset双Task/双EID实际结果。原其它LLM节点的工具限制不放松；SmallModel单变量及工具混合LLM对照仍待独立接入。

前端按维度过滤变量节点，SKILL可选独立Skill/API，自动省略其槽位；切换维度/资产时清除不再有效节点选择。26525 backend离线skipTests test-compile已terminal exit0，6361 UI构建仍在运行；不新增运行测试/SQL/模型或MCP调用/部署，实际双任务、权限、历史报告/发布门禁/浏览器仍待。

## SmallModel模型对照的实施前复用缺口

现有MODEL维度只解析LLM Agent配置，不能覆盖已注册SMALL_MODEL节点。复用MODEL选择、双Task、固定Dataset与报告复算，不新增实验模型/API。专用模型slot仅null/0，因为节点仅冻结一个实际选中模型；不根据Agent主/备用位置推断运行回退。

计划复用SmallModelContract验证冻结协议及Finding类型，核对modelReference与modelSnapshot精确id/version；同Agent id仅允许版本锚点移动。整个modelSnapshot是模型变量：远端收到id/version和安全路由字段，原网关还使用价格/输出限制/部署声明，因此不能只删除模型版本然后声称没有执行变化。仅变量引用、模型快照和Agent版本锚点可变；operation、MCP/tool、schemas、inputMapping、Finding、评分/风险/Review及其它节点不变。模型版本变化本身不证明质量提升，仍需双EID报告；无LLM prompt时prompt hash保持null。
