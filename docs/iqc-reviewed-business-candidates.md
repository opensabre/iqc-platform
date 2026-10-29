# 已复核业务会话候选集成

## 复用与来源

适配开发者 B 独立工作树中尚未提交的候选交付，仅提取服务、原 API 创建分支、输入校验和 V1.1.37 扩容迁移。B 工作树保持只读；其测试和第二套 FrozenBusinessPolicy 不引入。旧消息样本请求及直接构造调用保持兼容。

复用 QualitySample、ResultReview、生产 ConversationInspectionResult、Task 锁及 A 的 BusinessItemReviewService.validatedProjection。Pipeline 来源采用冻结 PipelineRelease 的既有摘要/事实/融合/评分验证，Scheme 来源采用既有 SchemeResultEvaluator。没有平行政策、候选或审核表。

## API

`POST /api/iqc/quality-operations/results/{resultId}/samples` 沿用 iqc:sample:manage 与审计；请求 targetType=BUSINESS，reviewId、requestId（16–64 字母数字下划线连字符）、sampleType（POSITIVE/NEGATIVE/FALSE_POSITIVE/FALSE_NEGATIVE），可选 name/tagsJson。客户端不得提交 expectedJson。消息样本继续默认 MESSAGE。

服务校验已完成、来源摘要一致的业务复核，拒绝仍为未确认状态的项目和评测来源。按会话范围授权并持有任务锁；同用户/requestId 的相同内容返回首份捕获，不同内容拒绝。重试不重新抓取会话。

## 冻结输入与金标边界

保存完整 ordered segments 与会话元信息为 iqc-conversation-candidate-input-v1，保存复核结果、人工作出项目裁决、政策版本标识、源摘要和输入摘要为 iqc-reviewed-business-candidate-v1。生产角色规范化后重新计算原执行 inputHash；缺失轨迹、不完整或执行后变更的输入拒绝捕获。规范化用于对照，捕获仍保存原角色。

上限 10000 消息，与 Gold 导入一致；每个 JSON 4MiB、标签最多50项。超限拒绝，禁止截断。V1.1.37 仅扩大既有 content_snapshot/expected_json 为 MEDIUMTEXT，迁移未执行。

候选状态固定 CANDIDATE，messageId 为空。GoldSampleService 从已保存的冻结 envelope 读取，随后需要显式 Finding 标注与独立审核。业务 PASS/FAIL/NOT_APPLICABLE 不自动转换成 Finding 真值，也不自动批准金标。

## 验证范围

后端 `mvn -o -q -DskipTests compile` 与既有测试源码 `mvn -o -q -DskipTests test-compile` 已成功；不新增或执行测试，不执行迁移、数据库并发、模型、浏览器或部署验收。I3 完整 Benchmark/发布门禁及其余 PRD 仍待继续。
