# 加权融合与最终置信度独立验证（2026-10-20）

## 基线及通过结果

在B c9d6869基础上接收A已提交266f725、dd0ef29（本树等价6135717、2905ce8），不读取其未提交变更作为验证基线。新增WeightedFusionBehaviorTest 10项、FinalConfidenceContractTest 9项，同时回归ActionPolicySafetyTest 10项、HumanFindingReviewEvaluatorTest 7项。

最终原26832 terminal exit0，`/tmp/iqc-b-weighted-confidence-contract-terminal.log`。4份本轮fresh Surefire XML共36项，failures/errors/skipped均0。主源码先由13050完整Maven test生命周期编译；后续只修改测试，复用已编译主代码、执行compiler:testCompile surefire:test。不将此前1414默认全套外推到此新组合。

## 已证明的契约

- 支持权重严格超过总输入权重一半才确定；false是合法值，0.1+0.2与0.3精确平票。UNKNOWN、ERROR、CONFLICT和NOT_EVALUATED计入分母但不投阴性票；全未执行保留NOT_EVALUATED。
- 一来源一观测、缺权重拒绝，数值1与1.00同值；保留原raw、被拒来源反证和选中/未选ID，不修改原观察。
- 权重为明确的十进制字符串，校验范围/精度/表示，规范化尾随零；两代policy codec拒绝数字/布尔/null/数组/对象值。旧7参政策序列化原字节相同，空权重不新增字段。
- 仅CONFIDENCE已知校准来源给出精确finalConfidence；同置信度一致来源共同选中，冲突、缺失、未校准及其他融合方法不产生概率。权重支持率不充当概率。
- 新概率Jackson2存储往返精确，Jackson3输出新增概率标量精确；空字段在两代输出均省略。旧12参结果往返完整JSON树及序列化原字节不变。
- reproduce兼容旧记录缺字段形状，同时保留其他完整字段比较；改变旧值或新非空概率不会被兼容逻辑吞掉。
- ReviewPolicy公开evaluate对新结果/旧缺字段结果均从原校准来源读取；概率字段与来源不一致时confidenceMatch/status均UNKNOWN。

## 失败轮次与测试边界

原13050 exit1，34项0fail/1error：测试用默认Jackson3直接反序列化内部Jackson2 JsonNode型Result，实际HTTP接口不接收整个融合Result作为输入；真实存储/重算用Jackson2，人工复核接收独立Decision DTO。改为存储完整往返与HTTP新标量输出验证，未新增生产桥接。

原41356 exit1，36项1failure0error：测试比较Java record identity/equality，受可选JsonNode null表示影响；正式ActionProductionSourceValidator144、DecisionRevisionService110按完整valueToTree检查原来源。改为完整JSON树及原序列化字节保持，未改生产代码或忽略实际字段。原91759 exit1为测试assertThat泛型重载编译歧义，明确JsonNode类型后才运行最终26832。失败日志分别保存在`/tmp/iqc-b-weighted-confidence-contract.log`、`-final.log`、`-verified.log`。

本轮为纯融合/codec/公开复核政策与既有动作安全测试，未证明真实校准工件、完整Pipeline发布/生产/评测/重算、数据库、HTTP整个融合Result往返、浏览器或容量。未运行共享SQL、模型调用、push、部署或framework修改。完整适用I1–I7保持进行中，语音转换不在任务范围。
