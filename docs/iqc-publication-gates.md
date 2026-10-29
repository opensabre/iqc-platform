# Pipeline 发布准入

## 复用与协议

复用现有发布版本、真实试跑、PIPELINE_EVALUATION Task/EID、冻结Dataset及Audit。没有另建门禁任务、审计表或模型调用通道；V1.1.41只给既有版本增加准入JSON与摘要。历史版本两字段均为空时仍可读取，不伪造历史准入。

GET `/api/iqc/pipelines/{id}/publication-gate` 接收 expectedRevision、qualityTaskId、qualityExecutionId、regressionTaskId、regressionExecutionId，返回服务端计算的准入。POST原publish增加qualityEvidence和regressionEvidence（各含taskId/executionId）；旧请求不能绕过新门禁。POST `/{id}/force-publish` 另需forceReason。预览不调用模型。

两份证据必须属于可见评测任务的终态执行轮次，流水线ID、冻结内容摘要完全匹配；草稿证据还须精确修订及trial标记。回归数据集必须为REGRESSION，每份至少有真实产物。普通发布保留原真实成功试跑和人工确认，并检查完整报告、全样本成功、样本数、质量高风险召回/Overall F1、回归通过率、双方结构化输出成功率和计算P95。

## 默认值与测量边界

默认阈值：风险召回0.95、Overall F1 0.92、回归通过1、结构化成功0.995、P95 5000ms、至少1个金标样本。可在草稿配置，随Release冻结；编辑阈值也改变修订/摘要，必须提供匹配的新试跑及评测。

结构化成功是逻辑LLM调用输出契约成功率，不是网络请求成功率。无LLM节点明确不适用；有LLM但未测量/分母缺失不能普通发布。P95为评测计算墙钟，不含队列、输入准备与持久化，不代表端到端请求SLA。缺失分母不会默认达标。

## 管理员例外与留档

接口资源只授权IQC_ADMIN，服务端再检查可信IQC_ADMIN/SUPER_ADMIN角色；前端按钮不是授权依据。原因须1–1000字。例外可覆盖测量项失败，不能覆盖无效来源、错误冻结摘要、非终态或无真实产物，也不能绕过真实试跑。操作者、时间、原因、阈值、测量报告与摘要保存到不可变发布版本；复用Audit记录请求及响应。版本读取及幂等发布核对准入摘要、schema和版本来源；重复发布不覆盖首次证据。JSON上限8MiB。

## 交付状态

代码与前端表单已实现。最终 `mvn -o -q -DskipTests test-compile`（主源码及既有测试源码，仅编译）与前端 `npm run build` 均退出0。迁移V1.1.41与组织权限V20261004_11需随部署执行，本轮未执行SQL、模型、浏览器、角色登录或部署验收；没有新增或执行测试。完整PRD、离线音频、I4–I7和其他指标仍待继续。
