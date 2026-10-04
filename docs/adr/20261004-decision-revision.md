# 政策重算记录

已检查 InspectionScoring、PipelineConversationEvaluator、BusinessItemReviewService、ResultReview 及现有会话结果。评分算法可直接复用；ResultReview 是人工裁决轮次，不能把机器政策重算伪装为人工复核；会话结果承载原始检测，不能原地覆盖。

新增 append-only DecisionRevision 保存源结果摘要、发布政策快照及重算投影。复用现有发布 PipelineRelease 的 fusion/items/scoring 政策，不新增平行政策资产。基于已保存原始 Finding 重融合和评分，不调用检测、不生成缺失事实、不发动作。源结果、人工复核与重算分别读取。后续 Scheme/标签/RiskPolicy 扩展不声称已交付。
