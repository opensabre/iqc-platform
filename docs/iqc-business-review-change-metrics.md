# 业务结果人工修改率

协议 `iqc-business-review-changes-v1`，scope `LATEST_COMPLETED_BUSINESS_REVIEW_VS_MACHINE`。

## 来源与分母

复用 BusinessQualityReportService 原 task/组织查询、RunPolicy、冻结标准分组，以及 BusinessItemReviewService 对机器结果和最近完成复核的来源校验。每个所选业务结果最多比较一次；最新 pending 不抹去此前完成结论，原 pendingReviewCount 仍保留。

- reviewedResults：通过原完整来源校验且有完成复核的结果数。
- modifiedResults：下述任一语义变更的结果数，每个结果只计一次。
- modificationRate = modifiedResults / reviewedResults；无完成复核为 null。
- reviewCoverage = reviewedResults / conversationCount；无所选评估为 null。
- 未复核、缺失机器结果不充当未修改。比率为 0..1、六位 HALF_UP，页面显示百分比。

## 变更语义

1. 项目状态/匹配证据集合或计算评分改变。
2. Finding 的类型、状态、完整值、严重度、证据类型/引用集合改变。
3. 当前 APPLIED 人工覆盖有效数字与机器最终数字不同；机器无最终数字而有生效覆盖也算变化。STALE 不视作有效覆盖。

展示三个分类计数，可重叠；总 modifiedResults 取并集。项目及事实按 code 排序、证据按集合规范化；不比较原因、反馈分类、人工生成的事实/证据ID、解释或上游工作流metadata。数值比较复用 SelectorAssetService 已有精确规范摘要，1与1.0不制造差异，不新建hash算法。只为比较生成临时对象，源实体/原事实和JSON不改。

业务 Finding 变更会进入上述统计，后续风险/标签投影由原服务重算。本指标不混入独立标签值复核，旧消息复核仍走原报表，不能将其命名为所有复核对象的全局修改率。

## 集成与兼容

既有 PolicyGroup 增加可空 reviewChanges；旧17/18参构造保留且null省略。Group 汇总增加计数，原评分/项目/覆盖/手工改分汇总不变；无新表、API、角色、通用框架或生产写入。页面只在已知协议时显示。

## 验证状态

本批仅编译/构建，无新增或运行测试。待B独立覆盖：无样本/未完成/未修改/仅原因/状态及证据/数值等值/生效与过期改分/多轮及pending/分类重叠、精确值与源不变、构造和旧JSON兼容、实际SQL组织范围与HTTP/browser。模型一致性所需重复同输入同版本运行仍待，并未用旧方案投票伪造指标。

## B 组合验证

组合A5147226与Bb5044c5后，主/测试编译及BusinessQualityReportServiceTest、FindingConfidenceDiagnosticsTest、ResultReadProjectionTest通过（日志 `/tmp/iqc-b-review-change-combined.log`）。报表测试补未复核/缺失结果分母、空完成分母、旧17参构造与JSON往返；扩展pending保留完成结论、APPLIED/STALE数字修改与分类并集计数。原旧测试mock补上新增比较实际需要的original，不改生产校验。Finding语义边界/实际SQL角色与HTTP/browser仍待，不将单元测试作为完整验收。

B追加Finding语义边界测试，经实际报表入口检查：精确小数差异、严重度、UNKNOWN状态、证据reference变化进入修改计数；数值尾零、证据集合顺序/重复、证据ID与details解释及整体explanation不制造变更，原事实序列化不变。原10457 terminal0，testCompile/surefire，BusinessQualityReportServiceTest十九项全过，日志 `/tmp/iqc-b-finding-modification-boundaries.log`。该检查使用受控mock来源，未替代原服务来源校验/SQL组织角色/browser。
