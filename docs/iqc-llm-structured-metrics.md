# Pipeline LLM 结构化输出指标

状态：已编码与编译；发布门禁尚未接入。

复用 LlmQualityProvider 的结构化判断契约和 NodeResult.metrics，不新增指标表。逻辑调用在调用provider前记1；受支持、真实structuredJson完整JSON、布尔hit与返回hit一致且reason为非空字符串才记valid；标签事实路线另需既有iqc-label-facts-v2/facts数组协议。Spring AI解析同步复用判断契约，拒绝非字符串reason及尾随垃圾JSON。unsupported、异常、null JSON或合成legacy Finding不当作结构化成功。

InspectionResult只追加@JsonIgnore/@TableField(exist=false)的临时诊断字符串，不存储到旧结果表或外部消息结果响应。已有阶段聚合汇总诊断，FrozenDetectorEngine将llmStructuredOutput写进NodeResult.metrics，包括失败节点；指标独立于事实/证据有效性，结构化正确但证据无效仍会执行失败。元数据均服务端计数，不能由模型自报或替代Gold指标。

指标schema iqc-llm-structured-metrics-v1：logicalInvocations、validOutputs、invalidOrFailedOutputs。完整Evaluation轨迹连同原摘要保存；既有历史产物不修改、不反推计数。报告StructuredMetrics scope=LOGICAL_LLM_INVOCATION_OUTPUT，包括声明LLM节点数、调用/valid/failure、缺诊断的非SKIPPED节点attempt数和successRate。无调用或存在未测量attempt时rate=null；没有LLM声明时前端明确不适用。超时/取消丢失adapter回传属于未测量，不能拿Node SUCCESS推断。

逻辑调用包括provider缓存命中；内部重试与实际主/备用模型网络调用没有逐请求记录，不能声称本指标是实际网络/模型请求成功率。缺失样本仍按原报告名单与Finding分母处理；结构化率仅描述已测逻辑调用，发布时还必须检查整集合覆盖。Benchmark追加同范围结构化率差值，任一侧缺分母/未测量时delta=null。

不新增或执行测试、数据库迁移、模型/浏览器运行或部署。最新主源码与既有测试源码 `mvn -o -q -DskipTests test-compile`、前端vue-tsc+Vite构建均退出0；后续发布门禁、管理员例外审计和完整各层指标继续。
