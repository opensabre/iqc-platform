# 模型一致性：框架调用输入证据

## 实际缺口

FrozenDetectorEngine 的 Finding.Source.inputHash 只摘要传入消息，未包括规则/Agent实际Prompt以及 REVIEW/VERIFY 的上游裁决。不能仅凭此字段把两次模型调用称作同输入。旧调用日志正文为可选加密/TTL诊断，也不能成为必须存在的统计来源。

## 复用原链路

SnapshotChatModelRouter 在实际选定主/备用模型、进入原 Gateway 前生成测量摘要；不新增表、API、hash算法或调用运行器，沿用 SelectorAssetService 精确规范摘要。输入包含：实际框架消息（角色/文本/工具调用与工具响应）、实际选中冻结模型profile、路由器生成的标准Prompt参数、实际工具定义。实际 modelKey 来自原网关Handle，不猜默认模型或按首选模型冒认Fallback。

`iqc-llm-invocation-fingerprint-v1` 仅输出 modelKey/inputHash 两个64位摘要；输入协议为 `iqc-llm-invocation-input-v1`。不保存Prompt、上游正文、工具结果、密钥引用或凭据明文；不启用可选原文归档。

指纹的含义是框架层消息+冻结profile+工具定义一致，不声称与供应商HTTP字节完全相同，也不证明远端部署工件真实性。

## 工具与兼容

- 正常工具循环把最终模型调用的指纹带出，摘要覆盖完整框架工具历史；工具直接returnDirect没有最终模型输出，不造指纹。
- 未管理的默认模型、未经过原网关的调用、未知Prompt选项类型或序列化不可用，返回无测量证据，不改变原模型结果/异常/预算/重试和结算。
- LlmEvaluation 保留旧3/4参构造，新增字段null省略。Spring AI provider传递元数据，原逐项检测结构指标追加invocationFingerprints，聚合保留有序列表；logicalInvocations/validOutputs原口径不变。
- 旧产物不回填、不重写hash；旧结构指标无此数组时只能未测量。没有修改旧Finding.Source.inputHash的协议语义。

## 下一步一致性报告

复用原任务/EID、冻结数据集/Release及评测result来源校验，比较真正相同模型和上述输入的多份独立产物。不能把同一Task/EID重复引用、不同上游输入、缺指纹或失败输出填成一致/不一致。报告需同时给比较覆盖率和不可比较原因，不把未测量写成0或100%。当前只有来源采集，不宣称一致性指标已完成。

## 验证状态

本批仅skipTests test-compile；没有新增或运行测试。B待验证：相同输入跨Task稳定、Prompt/上游/工具定义/实际Fallback变化、工具继续与直接返回、未管理/无网关/未知选项、序列化失败、旧构造JSON、结构指标计数不变及原调用/预算/结算/副作用不变。真实供应商、SQL与浏览器仍待。

## B 组合验证与媒体边界修复

组合固定A3a4cc6d与Babba771。发现文本摘要不覆盖MediaContent附件，修正为带媒体调用不生成指纹，避免同文本不同附件的伪相等；模型请求和返回仍照常，未增加音频转换或原文落盘。未来若需测量媒体输入，应单独定义稳定且覆盖实际内容的协议。

新增InvocationFingerprintTest通过实际router公开调用检查跨Task稳定、系统Prompt/上游文本/冻结profile/工具定义变化、Handle的实际modelKey、无Handle/未知选项/媒体缺测、旧LlmEvaluation JSON兼容；工具定义变更测试验证工具未被执行，来源对象保持原处理。模型与网关为受控mock，不是供应商或真实结算测试。

原13690 terminal0，主/测试编译及首轮12项通过，`/tmp/iqc-b-invocation-fingerprint-first.log`。追加工具/profile后原33875 terminal0，testCompile/surefire，InvocationFingerprintTest7、SnapshotChatModelRouterTest3、SpringAiLlmQualityProviderTest4共14项零失败错误跳过，`/tmp/iqc-b-invocation-fingerprint-tools.log`。计数不跨轮累计。

工具历史继续/returnDirect、完整Fallback及预算结算副作用仍需独立验证。一致性聚合、多独立产物选择、覆盖率/不可比原因及页面仍未实现；本批不以指纹采集代替模型一致性指标。
