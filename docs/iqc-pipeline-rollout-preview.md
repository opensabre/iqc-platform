# 流水线灰度与回退分配预览

## 当前边界

本批提供真实预览 API 与流水线详情中的预览界面，不保存策略、不创建任务、不改变实际任务路由。完整 I7 灰度发布仍未完成。本批不新增表，不修改 framework。

已搜索 IQC、公共框架及 Wiki；现有发布版本、Prepared 与 BenchmarkVariableComparator 可以满足精确来源和五维单变量验证，直接复用。没有发现现成 IQC 灰度分配器，因此新增无 IO 的 PipelineRolloutPolicy，避免建立第二套版本或权限模型。

## 协议与来源

`POST /api/iqc/pipelines/{id}/rollout-preview` 接收 `{policy, requestKey}`。policy 字段为 schemaVersion（iqc-pipeline-rollout-v1）、pipelineId、baselineVersionNo、candidateVersionNo、candidateBasisPoints、cohortSalt、state、variable。variable 复用 Benchmark Selection 的 dimension/nodeId/slot，支持 PROMPT、MODEL、SKILL、FUSION、PIPELINE。

两侧必须是同一资产的不同精确已发布版本；服务端检查当前数据范围、资产活动状态、原始存储摘要及发布证据，不接受客户端 Prepared 或草稿。比较器拒绝语义未改变及声明范围外的变化。版本、比例和槽位使用局部 Jackson 2/3 精确解码，拒绝小数、字符串数字及溢出；策略和变量中的未知字段被拒绝。请求键必须是 1–128 位字母、数字、冒号、下划线或短横线字符串。

ACTIVE 检查两侧执行依赖可用性；ROLLED_BACK 仍检查候选冻结结构与原发布来源，但不要求候选执行依赖当前可用，基线依赖仍须可执行。这只是回退预览，不能据此宣称紧急回退已实现。

## 确定性分配

使用 SHA-256 对命名空间 iqc-pipeline-rollout-cohort-v1、流水线 ID、服务端稳定账号 ID、请求键、分桶盐逐项 UTF-8 长度分帧。完整摘要作为无符号整数对 10000 取模。ACTIVE 且 bucket < candidateBasisPoints 时选择候选，其他情况选择基线；0 不选择候选，10000 全选候选，ROLLED_BACK 全选基线。

比例、状态、发布版本与策略摘要不参与分桶；保持账号、流水线、请求键和盐不变时，扩量保持原桶。更换盐会重新分桶。policyHash 使用既有精确 JSON 摘要，cohortHash 使用文本分帧摘要，两者用途不同。输出仅含版本、比例、桶、摘要及单变量比较元数据，不回显账号 ID、请求键、Prompt 或冻结配置正文。

## 授权与界面

服务端先刷新固定资源 iqc:pipeline:rollout:preview 的当前授权，再访问服务；沿用既有数据范围，不接受客户端身份。响应 no-store/nosniff，审计不记录请求和响应正文，声明每 60 秒 30 次限流。权限草案由组织服务 V20261019_01 提供资源和按钮 900265，仅 IQC_ADMIN / IQC_QUALITY_MANAGER；SQL 未执行，真实角色与切面仍待验收。

界面通过原版本列表加载并只保留版本号和摘要，逐项核对预览返回的来源、版本、桶、模式与选择摘要。切换输入、资产或关闭时丢弃旧结果，阻止迟到请求覆盖。列表加载仍需原 iqc:pipeline:versions:view。该功能不代表旧流水线配置接口的敏感正文改造全部完成。

## 后续必须完成

1. 策略当前指针 CAS 与不可变修订历史，明确启用、扩量和回退状态迁移。
2. 实际新任务路由；在策略变化后重放原请求仍返回原决定，原任务不被重写。
3. 冻结策略修订、账号归属、请求键摘要、桶和两侧精确发布来源，校验任务证据与选定发布摘要。
4. 真正激活和回退、历史审计及指标对照，验证并发、重启和失败恢复。
5. 默认 MVC、实际数据库、真实角色及浏览器验收。当前仅做编译和构建，没有新增或执行测试、执行 SQL、推送或部署。
