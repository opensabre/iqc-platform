# 并行提前满足策略实施契约

状态：设计已核实，尚未实现。对应 PRD 8.3 Parallel 和 Short Circuit；不替代完整 I1–I7。

## 已有能力与缺口

PipelineDefinition、PipelineRuntime、NodeResult、ValueSchema 已提供冻结节点、同一有界线程池、ALL 波次、祖先作用域、尝试期限及取消收尾。现有 executeParallel 按 started 的声明顺序阻塞收集，不能因后声明节点先满足而及时汇聚。不能另加线程池、调度服务或任务表。

PipelineAssetService 在冻结时两处用二参数构造重建 PipelineDefinition；新增政策必须同时保留，否则草稿存在政策但发布丢失。旧 schema/二参数构造/缺省 JSON 必须保持 ALL，历史 hash 不因 null 新字段改变。

## 目标契约

- 新显式版本提供可选 completionPolicy，列 requiredNodes 与复用原 Condition；缺省继续 ALL。只允许启用节点，列表非空、唯一、有界。所有 requiredNodes 成功且条件明确满足才提前完成；失败、缺失、null 不推断满足。
- 条件沿原 JSON pointer 和精确比较语义。发布校验条件引用仅来自可信 input 或 requiredNodes 已确认的输出；不能读未完成兄弟、任意保留变量或当前会话替代冻结输入。
- 真正按完成/期限观察同波次尝试，不能先等待声明顺序的慢节点才看已经完成的节点；继续计入排队期限、原重试/Fallback、预算等待和 progress/租约回调。不得忙轮询扩池或吞掉 deferral。
- 满足后停止提交，取消或观察所有已提交尝试，保留取消竞争中已成功的事实和完整 Trace。未启动节点明确 SKIPPED 和策略原因，运行中成功取消记录 CANCELLED；供应商取消不能宣称外部回滚。不得将这些正常策略终止误记执行错误或把缺失 Finding 当作明确未命中。
- 明确必需节点错误策略优先级：FAIL_PIPELINE 仍失败；CONTINUE/MARK_REVIEW 保留技术状态。政策满足不抹去此前已确认错误、复核标记或原始事实。
- Fusion、评分、风险、Review 继续使用已有纯投影与状态规则。所有未完成事实保留 UNKNOWN/NOT_EVALUATED 语义，不以策略满足伪造 PASS 或最终分。

## 集成与验收

需覆盖资产冻结/发布、Task 恢复、Evaluation、原生产及源证明/政策重算兼容；新政策成为原不可变快照/hash的一部分。UI 仍复用 PipelineReleaseEditor，不与 B 的图事件修改并行写同文件，先交接字段契约再集成。

完成证据须包括慢首节点/快后节点的真实完成顺序、未启动节点、取消竞争、超时/重试/Fallback、条件未知/撤销满足、预算等待、所有 progress 收尾、旧 schema hash、冻结不丢政策及实际任务/评测。Root 当前不新增或执行测试；编译和 B 独立授权的验证分别记录。未实现前不宣称提前满足可用。
