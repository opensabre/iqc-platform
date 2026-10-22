# 当前交付缺口复核（2026-10-22）

基线：后端58b22f0，前端abdd53f。完整I1–I7保持进行中，不以本表替代PRD逐项最终验收。

输入为已经处理好的会话；ASR、转写生成、音频转换和说话人分离不属于当前工作。单租户部署复用组织范围。默认release v1未切换，精确v2已有实现与专项验证但不等于默认入口已启用。

## 下一项功能交付：标签准确率

PRD14.3要求业务层标签准确率。当前 `PipelineEvaluationService.report` 统计 GoldDecision.labels 数量，但 BusinessMetrics 构造仍固定 comparableLabels=0、labelAccuracy=null，前端也明确提示标签输出未接入。这是功能缺口，不是仅缺测试。

复用路径：冻结 PipelineRelease.labelPolicy → FindingLabelPolicy.projectionBinding/bindingAddress → FindingLabelProjection.project，输入使用已校验持久化 actual.fusion。生产已有同一投影能力，不新增标签表、模型调用或第二套投影算法。

实施前明确并写入Gold协议：标签键采用现有 bindingAddress 的 JSON Pointer 地址（labelId/valueCode转义），不按显示名猜测绑定。旧自由文本标签键没有可证对应关系时保持未测量，不补假阴性。标签值使用原精确JSON语义比较；KNOWN才可比，UNKNOWN/CONFLICT/ERROR/NOT_EVALUATED保留Gold分母但不作为可比值。

验收须覆盖：正确/错误值、缺绑定和无标签政策、已知与未知状态、旧Gold键、精确数字/对象、完整Gold分母和重试继承，以及报告UI对可比数量/缺测的表达。不可将“可比准确率”冒称全量覆盖率；评测不得触发UPDATE_LABEL或其它业务动作。

## 已有较强组合证据，但仍有边界

| 能力 | 当前证据 | 尚不能据此宣称 |
|---|---|---|
| 模型一致性 | 真实Provider/Router构造指纹→原worker→隔离MySQL→真实MVC控制器，SQL重试继承排除；27项测试通过 | 真实供应商/网关账本/PDP/网络HTTP与浏览器整链通过 |
| 一致性页面 | 真实浏览器注入受控身份/报告，四身份参数、缺测/排除展示、改参考身份清旧报告 | 页面已读取上述SQL后端，真实角色权限通过 |
| 发布/复核回流 | 隔离SQL真实目录/Gold审核/数据集/worker/门禁/动作结算故障恢复/复核/回流回归拒绝 | 外部通知渠道、自动发现调度、全部角色和容量SLO通过 |
| 人工修改率/置信度诊断 | 已实现专项及相应测试，见领域文档与根协作台账 | 完整业务层/模型层指标全部交付 |

来源：`docs/iqc-model-consistency.md`、UI `docs/iqc-evaluation-ui.md`、`docs/iqc-prd-b-measured-publication-worker.md`、根目录 `IQC_PRD_COLLABORATION.md`。测试日志和XML各自绑定提交，不将不同分支/轮次通过数叠加为统一全量通过。

## 仍需实施或逐项验证的范围

- 模型层幻觉指标：当前源码未发现独立实现；先区分无来源引文、引用不匹配与业务事实错误，复用既有证据校验/Gold，不凭F1推导幻觉率。
- 处理后会话完整率及系统层TPS/延迟/Token/GPU/单会话成本：已有部分报告/网关能力，须明确分母、测量范围和缺测；容量目标需实测，不用单元测试推断。
- I4外部MQ、I5实际外部通知/工单/辅导/Webhook接收与幂等、I7真实身份/PDP与四角色页面、collector/监控及默认入口激活：迭代清单仍列待办；尚未逐项给出满足PRD的实际运行证据。
- I1/I2/I6及其它既有功能仍须对照正式PRD验收；不能因为本轮优先处理指标而缩小原任务。

以上后三项属于待核实/待验收集合，不据简单源码搜索判断整个能力不存在。新增基础设施或并行实现前，仍必须搜索拥有仓库、starter、共享服务和Wiki；framework/base-gateway修改需用户明确批准。本轮仅文档复核，不更改生产源码或部署状态。

## 标签指标实施进展

本轮在既有报告接入冻结标签投影：`LabelEvaluationMetrics`仅对规范绑定地址且KNOWN的合法标签值计数；正确数/可比数计算准确率，零可比保持null。无政策/目录、旧名称键、缺输出及未知状态保留Gold总数。回归样本须所有Gold标签均可比且正确才能通过，评测不调用业务动作。标签类型仍服从现有投影校验（不支持对象标签），没有扩展目录类型或切换默认release版本。

验证：原66104 terminal1（新增夹具误用仅接受整数的DURATION_MONTHS），修为支持小数的PERCENTAGE后原11353 terminal0，`/tmp/iqc-b-label-metrics-final.log`。fresh XML：LabelEvaluationMetricsTest4、PipelineEvaluationReportTest20，共24项无失败/错误/跳过。首轮完成主源码编译；末轮testCompile+surerfire。测试覆盖纯标签计算和既有报告回归，不代表已补齐绑定标签的真实报告/SQL重试组合、真实PDP或浏览器整链。以上组合仍为下一验收项，完整I1–I7继续进行。
