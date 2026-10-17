# 并行提前满足策略实施契约

状态：独立分支后端首批已编码并编译通过，界面及运行验收待完成。对应 PRD 8.3 Parallel 和 Short Circuit；不替代完整 I1–I7。

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


## 首批实现（2026-10-16）

显式 schema `iqc-pipeline-parallel-v2`，`completionPolicy` 形如：

```json
{"requiredNodes":["risk_detector"],"condition":{"pointer":"/variables/risk_detector_value","operator":"EQUALS","value":true}}
```

condition 可空，表示 requiredNodes 全部成功即满足；有条件时必须明确成立。requiredNodes 必须非空、唯一且属于启用节点。条件只能引用原 input，或 requiredNodes 声明生产的 variables/upstream 具体键，不能引用未完成节点或保留初始变量。旧 sequential/parallel-v1 禁止携带政策，缺字段仍保持旧行为和省略 JSON。

复用原有界 executor，用 FutureTask.done 通知调用线程，按实际完成或期限事件观察；重试和 Fallback 各自仍有原次数/期限。停止后取消/观察活动尝试并为未提交节点记录 SKIPPED。竞争中已经完成的成功事实保留；技术失败与 deferral 不被满足策略抹去。

Run 新增可空 completionReason=POLICY_SATISFIED，旧五参数构造和 null 省略保留。仅带该运行标记且具有协调器明确原因的计划取消，事实投影才为 NOT_EVALUATED；其它取消仍保持原技术状态。Fusion/评分/风险/Review继续用原纯投影，不新增决策模型。进度回调中断在下一次提交/重试前终止。

PipelineAssetService 两处重建 Definition 都保留 completionPolicy。第一轮82070离线 `-DskipTests test-compile` 已退出0；事实投影/租约检查修正的第二轮94451也已退出0（日志 `/tmp/iqc-parallel-completion-policy-facts-compile.log`）。未新增或执行测试、SQL或部署。UI配置、真实完成顺序/取消竞争/发布任务评测和历史hash验证尚待完成，不能声明完整高级编排可用。


源码审阅确认 Fusion.PRIORITY 使用冻结 nodePriority 的最大值，CONFIDENCE 使用显式校准及最大值，OVERRIDE 按声明来源，VOTING 要求严格多数；不会把完成顺序隐式当成来源优先级。Trace继续记录实际观察/完成与取消，事实未评估状态仍参与原融合的未知/缺失规则。此为源码证据，不是实际并发验收。

## 独立组合运行回归（2026-10-17）

在组合基线afd88be的独立验证树运行ParallelCompletionPolicyRuntimeTest与ParallelPipelineRuntimeTest，原Maven handle66720终态exit0，8项测试、0失败/错误/跳过。新增4项覆盖：真实双worker慢首/快后满足后取消及依赖节点未提交；缺失条件值不提前结束；五种非法策略在调用引擎前拒绝；旧构造序列化省略新字段且定义JSON往返不变。原ALL四项并发/依赖/超时/中断回归保持通过。

适配器完全本地，不调用供应商或共享数据库。日志/tmp/iqc-parallel-policy-b-regression.log。本批未覆盖取消完成竞争、重试/备用、预算等待、进度异常、资产冻结及真实Task/Evaluation/重算、历史固定hash和浏览器；JSON往返不是历史固定hash证明，不代表I6或完整I1–I7完成。输入仅为处理好的会话数据。
