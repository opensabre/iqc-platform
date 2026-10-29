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

A初次交付仅编译/前端构建。初始独立验证清单：

1. 无声明/单独计数字段保持旧JSON/hash路径；有效/未知版本/null/字符串或小数计数/上下界/引用格式/64项限制。
2. MESSAGE当前归档与BUSINESS原输入两条来源到Gold/独立审批/冻结dataset/真实SQL报告，源对象不变，修改归档后冻结结果不漂移，annotation伪清单忽略。
3. 数量相等/不足/超出混合、其它样本多余不抵消缺失、缺产物/错误产物/重试继承不影响输入统计，零有效声明null。
4. 原Report构造/两代JSON/历史ReportEvidence摘要兼容、当前组织权限/敏感读取、UI空分母/切换/旧响应。

### B独立验证（2026-10-23，后端固定b739e18合入857ffd1）

- InputCompletenessMetricsTest 4项：混合缺失/超额不抵消、未知及非法声明保留样本分母、零分母null、显式字段冻结深拷贝与坏声明拒绝。
- PipelineEvaluationReportTest 24项中新增缺产物完整率检查：所有冻结成员仍计入输入分母，不依赖执行产物。
- GoldSampleReviewTest 15项中新增2项：业务候选沿原输入冻结清单并独立审批；annotation伪造清单不能覆盖来源，也不能给无声明来源制造分母。三类合跑43项零失败/错误/跳过，原69257 terminal0，日志`/tmp/iqc-b-manifest-freeze.log`。
- IsolatedMysqlMeasuredPublicationIT扩展原真实SQL链：归档声明3条/实际2条，MESSAGE只冻结清单三个字段；独立审批、风险/回归数据集冻结、worker及报告均测得缺失1、声明消息覆盖0.666667。实际业务复核候选保存原生产输入后，将归档消息及声明改为99；BUSINESS审批和新冻结数据集仍采用原3/2，原MESSAGE报告也不漂移。候选正文/来源摘要保持原值。
- SQL轮原2095 terminal0，日志`/tmp/iqc-b-manifest-sql.log`，fresh XML SQL2+一致性计算5+报告24=31项零失败/错误/跳过；使用专用本地MySQL34307，脚本退出自动关闭。复用已有75项迁移和原应用服务/Mapper，不连接共享数据库。

两轮包含重复报告用例，数量不累加成独立用例总数。当前组织scope/身份、供应商模型及部分网关边界仍受控；不是实际PDP/外部上游/浏览器整链证据。本轮未覆盖入库metadata边界、旧报告构造/hash和新UI；其后续证据见下节。未推送或部署。

这只交付有来源声明的数量完整率，不等于消息身份覆盖、全部语义数据完整性或整个I1–I7完成。语音转换、ASR生成和说话人分离按用户要求排除。

### B入库与报告兼容续验（2026-10-23）

ConversationInputManifestMetadataTest 5项沿实际normalized入口验证：无/空扩展保持旧fingerprint及JSON，单独计数字段不激活测量；正整数1与2147483647通过，显式null/字符串/小数/零/负数/溢出及未知版本拒绝；引用格式/128字符上界、清单共用64字段预算及归一化后调用者map修改隔离。未调用ASR或转换音频。

PipelineEvaluationReportTest新增15/16参数兼容构造、缺字段历史JSON读取、新增可选字段省略、序列化及hash兼容断言。原17602因测试泛型歧义编译失败；原83072因测试夹具用valueToTree改变小数尾零而失败。改为保留原序列化数字文本后，原19758 terminal0，日志`/tmp/iqc-b-manifest-compatibility-verified.log`；fresh XML metadata5+报告25+原发布门禁11=41项零失败/错误/跳过。没有修改生产序列化、历史证据或发布规则；这不是对所有历史存储证据的实际数据库抽样。

UI固定007b09d合入B为5a548b2后，独立验证发现报告权限撤销再恢复会复活旧在途响应。UI9fd3684修复撤权清空、请求代次作废及显示权限检查，页面5+费用4项通过，类型检查与生产构建通过。详见前端docs/iqc-evaluation-ui.md。真实上游清单、PDP与新页面的浏览器后端整链仍未验证，完整I1–I7继续。
