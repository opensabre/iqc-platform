# 待标注挖掘来源与 Gold 边界验证（2026-10-25）

固定合入 A 的 bdd8934（B merge 002c50a），不读取并行开发者未提交实现。输入为处理好的会话；无语音转换工作。

新增 MinedCandidateLineageTest 四项使用真实协议校验、候选输入 Hash 与 GoldSampleService：

- 有效来源可读；候选组从来源组收窄为 owner-only 允许，改到其它组拒绝。
- 原结果、会话、owner、输入Hash不匹配，以及错误样本类型、非空messageId、内容改变和旧输入协议拒绝。
- 重复JSON键、尾随对象、额外expected字段、数字身份、小数版本、重复原因和数字原因拒绝；每个变形先断言不同于原文，防止替换未命中造成假通过。
- 人工标注使用原冻结消息，不查询当前会话/消息mapper；来源与输入原文保持，产生PENDING_REVIEW草稿且不写审核版本。同一标注人审核拒绝，另一审核人才能批准。没有从AI来源复制Finding为Gold。

最终原38339 terminal0，`/tmp/iqc-b-mined-candidate-lineage-final.log.exit=0`。fresh XML：新增4项、既有GoldSampleReviewTest15项，共19项零失败、错误、跳过；包含旧候选标注、精确数字、审核独立性等原回归。运行使用离线Maven `test-compile surefire:test` 与本地ByteBuddy javaagent。

初次原52360 terminal1：19项中新增人工标注夹具使用空Finding集违反原Gold规则，1 error，其余18通过。终态后补一条有效人工标注，不放宽生产校验，复跑得到上述结果。

范围限制：sample mapper、scope、审核持久化受控，不能证明SQL事务、PDP账号、生产结果来源真实性、自动筛选/幂等创建或自动候选挖掘已交付。原动作仍要求已完成业务复核；A正在独立工作树开发新筛选/创建逻辑，本轮未触碰。完整适用I1–I7继续。
