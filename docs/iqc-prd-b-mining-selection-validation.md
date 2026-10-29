# 自动候选 v2 筛选专项（2026-10-25）

固定合入 b9a4e81，复用原动作适配器 v2、MinedCandidateSelection 和候选池。输入是已处理会话；未新增语音处理。

新增 MinedCandidateSelectionTest 六项使用真实筛选器与真实 adapter.ready：

- 18位小数阈值比较：低于才命中，等于/高于不命中；合法零置信度与零阈值保持。
- 缺事实、UNKNOWN/CONFLICT/ERROR/NOT_EVALUATED 和缺置信度保留各自原因，不转成已确认误判。
- 原始观测的精确小数、大整数对象与类型不同产生分歧；1.0与1.00按原精确JSON数值语义等价，无误报。
- 多事实原因排序去重，输入序列化不变。
- 数字阈值、重复/空事实集、夹带expected、超范围/指数/NaN/超精度阈值拒绝。
- adapter.ready接受RUNNING及终态的已验证来源，暂停/取消状态拒绝；未命中返回false且不调用创建服务。

原39132 terminal0，`/tmp/iqc-b-mining-selection.log.exit=0`。fresh XML：新筛选6、来源协议4、Gold审核15，共25项零失败/错误/跳过。离线Maven完成test-compile与surefire；diff检查通过。

这不是完整自动挖掘验收：context及Validated source为受控输入，创建服务未执行。没有证明SourceValidator锁、发现/入队零行、claim/settlement、SQL幂等或并发冲突、权限撤销、4MiB及当前档案改变后的真实生产捕获。下一项应沿既有隔离SQL候选动作/原SourceValidator组合验证，不能把上轮合成待标注存储Gold验证当成新生产者证明。完整适用I1–I7仍active。
