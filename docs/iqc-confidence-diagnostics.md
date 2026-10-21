# Finding 置信度误差诊断

## 测量对象

PRD V1.0 §14.3 的置信度校准诊断，复用 FindingEvaluationMetrics 与既有评测 Report。协议 `iqc-finding-confidence-diagnostics-v1`，scope `FINAL_VALUE_CORRECTNESS_ON_KNOWN_GOLD`。

仅统计金标为 KNOWN 的 `(sample, finding code)`；eligibleKnownGold 是该总体。actual 必须 KNOWN、同类型、值合法且有 finalConfidence 才进入 scored。其余计 unscored；无置信度不视作零置信度，未知或错误不视作正确负类。覆盖率 scored / eligibleKnownGold；分母为零返回 null。

正确性 y：沿原 Finding 指标的完整值相等判断，正确为 1，错误为 0，已知 false 可正确。p 是既有融合结果 finalConfidence，表示所选完整值的置信度，不是 BOOLEAN 为 true 的概率。数值金标沿原 BigDecimal 比较，不另建相等规则。

## 算法

- Brier = sum((p-y)^2) / scored。
- 十个等宽分箱：[0,0.1)、…、[0.9,1]。p=1 明确进入末箱。
- ECE = sum_bins(abs(sum(p)-sum(y))) / scored，与箱占比乘置信度/准确率差等价；不先舍入箱均值再累计。
- 分箱给 count、meanConfidence、observedAccuracy。空箱均值/准确率 null；scored=0 时 Brier/ECE null。
- 所有后端计算 BigDecimal，输出六位 HALF_UP。列表和原融合值不变。

## 集成与兼容

原 Result 五参构造保留；confidenceDiagnostics 可空且 null 省略，旧读入结果不制造诊断。evaluate 自动生成新诊断，既有 Report 自然返回，无新表/API/运行器。原 aggregate/byCode/F1/发布准入条件不变。新生成 ReportEvidence 的报告摘要会包含新字段，既有发布证据 JSON 不重写；旧证据往返仍需独立验证。

前端评测报告显示覆盖率、未测量量、Brier、ECE 和分箱表；旧报告无字段不显示，不拿缺测填零。该诊断评估已报告置信度的误差，不证明部署校准工件真实性，不代替一致性、幻觉、吞吐或外部供应商验收，也未新增发布阈值。

## 证据状态

本批只编译/构建，无新增或运行测试。独立验证待：0/1/0.1边界、已知false、数值等值、缺/错类型与无置信度、空分母、精确Brier/ECE、源对象不变、旧Result/ReportEvidence JSON往返以及完整HTTP/页面。
