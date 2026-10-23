# 处理后会话的上游声明消息完整率

## 契约与现有能力复用

PRD14.3数据层完整率需要来源分母。归档messageCount只是已保存条数，不能证明上游本应有多少消息。本增量复用ConversationMetadata.businessFields标量字段、原生产输入快照、候选/Gold审批及冻结数据集，不建清单表、不增加API/权限，也不执行ASR或转写。

上游有真实消息清单时，可在原businessFields传入以下三项：

```json
{
  "iqcInputManifestVersion": "iqc-input-count-manifest-v1",
  "iqcExpectedMessageCount": 12,
  "iqcInputManifestRef": "export-batch-42-manifest"
}
```

这是协议片段示例，不能直接把示例12当作真实业务预期数。预期计数的单位是已处理会话中的业务消息，不是原文件行数、ASR片段数或消息字符数。清单引用由上游保留供审计，本应用不自动请求该引用，不把声明当作已验证的上游真实事实。

- 仅显式存在iqcInputManifestVersion时激活。缺版本时，即使有相似计数字段也不推断来源或分母。
- 版本必须精确匹配，计数必须是JSON正整数1..2147483647（不接受数字字符串/小数/负数/零），引用为非空、无首尾空白/控制字符且最长128字的字符串。
- 三项继续占用原businessFields 64项/标量/总字节限制。未知版本和显式坏声明在新导入规范化时拒绝；有效声明与实际数量不符可以导入并作为质量问题测量。
- 历史冻结Gold中坏声明显示INVALID，不根据缺失预期数填零，也不自动修复历史数据。

## 来源冻结与权限

生产输入快照本来就携带businessFields；业务复核候选继续用已校验的机器输入快照。在MESSAGE候选生成Gold时，已有来源会话/消息校验后，从当前归档业务元数据只复制显式清单三个字段到相同的conversation.businessContext.businessFields路径，不引入其他业务字段或正文副本。不含清单的旧来源保持原Gold上下文形状。

消息候选取创建Gold时的归档元数据；业务候选取其原不可变输入，不将二者都称为原检测时的元数据。之后均沿原独立复核、Gold版本与Dataset冻结路径保存。标注人的annotation字段不是清单来源，报告不递归搜索任意JSON，不使用request.annotation中的伪造清单。读取仍沿用原组织和敏感权限；报告仅返回计数和比例，不回显清单引用或业务正文。

## 原评测报告

Report可选inputCompletenessMetrics：schemaVersion=iqc-input-completeness-report-v1，scope=FROZEN_REVIEWED_INPUT_UPSTREAM_DECLARED_MESSAGE_COUNTS。按全部冻结Gold成员统计，在查找执行产物前计入，尚无产物/执行失败不影响输入分母，也不把重试轮次重复累计。

- expectedSamples为全部Gold成员数；measuredSamples是声明有效者；unmeasuredSamples=expected-measured，invalidManifestSamples是其中坏声明子集。
- completeSamples要求实际消息数恰等于声明数；deficientSamples不足，excessSamples超出。数量相等不证明消息身份、内容、语义或清单真实。
- expectedMessages/observedMessages只累计有效声明样本的预期/实际条数；missingMessages逐样本累计max(expected-observed,0)，extraMessages逐样本累计max(observed-expected,0)。多余消息不抵消其它样本缺失。
- measurementCoverage=measured/expected；completeConversationRate=complete/measured；declaredMessageCoverage=(expectedMessages-missingMessages)/expectedMessages，等价于每样本min(observed,expected)的覆盖。超出样本可有100%消息覆盖，但不是数量完整，须结合excess/extra看。
- 比例使用BigDecimal六位HALF_UP。零分母null；UI显示未测量，未知样本仍在测量覆盖分母。

原15/16参数Report构造均保留，新增字段null省略，旧JSON缺字段可读。新生成报告和门禁ReportEvidence摘要包含新增指标；历史存储证据/摘要不回写。没有新增发布阈值或改变原检测、评分、标签及回归条件。

UI原报告页显示测量覆盖、数量完整会话率、声明消息覆盖、不足/超出/未测量/坏清单，API接入页说明同一输入字段；不新增请求或编辑金标来源的入口。

## 验证范围

本批仅编译/前端构建，没有新增或运行测试、共享SQL、浏览器或部署。仍交B独立验证：

1. 无声明/单独计数字段保持旧JSON/hash路径；有效/未知版本/null/字符串或小数计数/上下界/引用格式/64项限制。
2. MESSAGE当前归档与BUSINESS原输入两条来源到Gold/独立审批/冻结dataset/真实SQL报告，源对象不变，修改归档后冻结结果不漂移，annotation伪清单忽略。
3. 数量相等/不足/超出混合、其它样本多余不抵消缺失、缺产物/错误产物/重试继承不影响输入统计，零有效声明null。
4. 原Report构造/两代JSON/历史ReportEvidence摘要兼容、当前组织权限/敏感读取、UI空分母/切换/旧响应。

这只交付有来源声明的数量完整率，不等于ASR质量、消息身份覆盖、全部语义数据完整性或整个I1–I7完成。
