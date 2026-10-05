# 会话 Selector 与选数预览

## 输入及任务接入

复用既有会话归属、ConversationMetadata、机器会话结果和标签值、Task及selectionFilterJson。复用检索与取舍见ADR `20261005-selector-reuse.md`。本批没有独立Selector资产/版本表或调度实例表。

POST `/api/iqc/selectors/preview`：`{definition,previewSize:10}`，权限`iqc:selector:preview`。预览数1–50。

定义schemaVersion=`iqc-selector-v1`，包含condition、excludeCondition、includeIds/excludeIds、timeWindow、sampling、maximumCount。条件是`{junction:AND|OR,children:[...]}`或`{field,operator,value}`；最多8层/100节点；未知字段拒绝，比较值保留JSON类型。叶字段包括会话ID、employeeId/employeeGroupId/employeeDepartmentId（复用坐席、团队、部门组织引用）、channel、businessType/businessNo、sourceType/externalId/fileName、status、asrStatus/customerLevel/complaint、durationSeconds、messageCount/errorCount、startedTime/endedTime/createdTime、tags、riskLevel、自定义`business:KEY`及既有`label:LABEL_ID:VALUE_CODE`。

操作为EQ/NE/GT/GE/LT/LE/IN/NOT_IN/EXISTS/CONTAINS。大小比较仅数值/时间；IN/NOT_IN为1–100个标量；tags用CONTAINS精确成员，不是子串；EXISTS需显式true/false。缺失值的普通比较是UNKNOWN，包括NE/NOT_IN，不能当成false/0/低风险。只有包含条件TRUE才命中；配置排除条件后，只有该条件FALSE才保留；TRUE与UNKNOWN均不进入选集，不能把未知状态当成已排除风险的证明。includeIds是与其他条件相交的限制，不会绕过条件或权限；excludeIds优先排除。

已有POST `/api/iqc/pipelines/{id}/tasks`增加`selector`字段，与显式conversationIds互斥；必须精确发布版本和稳定requestId。草稿试跑仍用手选IDs。创建时重新选数并冻结，预览不锁定随后输入。重试先检查既有相同请求及选数摘要/IDs，返回首份快照，不因新会话或时间移动重新选择。显式IDs入口原样兼容。Selector任务仍PIPELINE类型，沿用执行/取消/恢复，未建立另一个任务执行器。

## 时间与抽样

时间字段CREATED/STARTED；窗口ABSOLUTE（from<to）、LAST_HOURS（1–8760）、PREVIOUS_DAY、PREVIOUS_WEEK（周一开始上一完整周）。服务器记录Instant锚点，以指定zoneId计算日历边界，转为Asia/Shanghai选数墙钟，半开区间[from,to)。createdTime通过Date参数保持数据库驱动时区转换一致；无时区的会话startedTime按Asia/Shanghai业务时间解释。响应同时给请求zoneId与storageZoneId，不能把两者混标。LAST_HOURS保留绝对小时距离，包括夏令时切换。

抽样种子必填≤128字符：

| 方式 | 行为 |
| --- | --- |
| ALL | 全命中池，按创建时间/ID稳定排列 |
| FIXED_COUNT | 从稳定创建时间/ID排列取前N条 |
| RANDOM | SHA256([seed,id])排序，完整命中池均匀伪随机取N条 |
| RATIO | 同一随机排序取ceil(命中数×比例)，比例>0且≤1 |
| EMPLOYEE_UNIFORM | 每个员工池内部随机排序、轮询分配；池耗尽后其他员工补足 |
| TEAM_STRATIFIED | 按团队池数量比例分配，最大余数法补齐，平余数用种子排序 |
| RISK_PRIORITY | CRITICAL/HIGH/MEDIUM/LOW/NONE优先级，同级随机；缺失/UNKNOWN最后 |

count适用于除ALL/RATIO外方式，1–1000且≤maximumCount。ALL/RATIO超过maximumCount时另行标limited，保留抽样前数和最终数，不能说仍是全部；maximumCount1–1000。分布键`id:<真实ID>`与`missing:`区分，缺失员工/团队不会和同名真实ID合桶。随机预览从最终池独立种子序列抽取，不拿前N条冒充随机。

## 权限、来源与上限

条件树之外强制应用本人/同组或可信全量权限。新任务归属取完整授权候选池与所有查询来源任务的可见范围交集（含未选会话及影响计数/分布的私有源）；任何私有或不同组的候选/风险/标签证据会让新任务保留为本人可见，不能向所选会话团队扩大来源权限。候选仅为已导入（含有解析错误）且有消息的会话。查看会话不等于查看所有任务：风险/标签源只来自可见、未逻辑删除任务。先按attempt_no获取各任务当前轮次，再跨任务按结果时间/ID选最近机器决定，不能被旧高风险轮次或隐藏任务影响。

标签使用精确labelID/valueCode的覆盖协议v2 KNOWN值；旧版hit-only或UNKNOWN/CONFLICT/ERROR不冒充已知值，不把“没有标签行”当作false。人工覆盖的独立标签复核历史不混入本批机器选择条件。冻结所选条件值及其元数据摘要、result/execution/labelResult IDs，使后续能解释原选集。

授权窗口内完整候选池最多20000条，元数据JSON累计64MiB；超限明确拒绝，要求缩小窗口/include范围。标签读取按500个来源分批，单批≤50000行/16MiB，累计≤200000行/32MiB。正式选集≤1000，任务选数证据≤8MiB。源读取和物化使用重复读事务；没有容量实测，不宣称达到PRD TPS目标。

## 当前状态

元数据扩展V1.1.42和权限V20261004_12已编码，未执行数据库迁移。最新全部主源码与既有测试源码 `mvn -o -q -DskipTests test-compile` 退出0（仅编译），图形Selector最终前端构建退出0；导入表单补齐的最终构建另见交接记录。图形条件编辑器、预览和正式Pipeline任务已接入。未新增或执行测试、模型调用、角色/浏览器验收或部署。独立Selector资产、Cron定义/周期实例、事件/API触发、持久化worker、预算及I3完整指标/其他迭代仍待完成。
