# 待标注候选 Gold 消费与事务验证（2026-10-25）

基于 bdd8934 的协议与Gold读取，扩展原 IsolatedMysqlEvaluationCandidateActionIT，接入真实Spring事务代理GoldSampleService、QualitySampleMapper和QualitySampleVersionMapper。输入均为处理后的会话。

## 实际验证

新增两项与原候选动作八项共同执行：

- 受信任存储中构造待标注候选，修改当前会话档案文本后，标注与独立审核仍持久化原冻结文本和挖掘策略上下文；标注仅产生PENDING_REVIEW，审核版本为0。同一标注人审核拒绝，另一人审核后版本为1；从数据库反读内容/Hash，同一决定重试返回同一版本且不重复插入。原冻结输入原文保持。
- 样本表UPDATE触发器主动抛错，审核版本INSERT后状态更新失败，真实Spring/InnoDB事务回滚：审核版本0、样本仍PENDING_REVIEW。移除故障后重试审核成功，只有1个审核版本。

原45445终态0，`/tmp/iqc-b-mined-gold-sql-final.log.exit=0`；fresh XML 10项零失败/错误/跳过。Flyway校验76迁移，专用库当前1.1.75，migrate无新增。`git diff --check`通过。

原82612终态1：临时库旧规则结果引用会话结果，原夹具清理父记录触发外键拒绝，10项初始化均失败，未算被测流程失败。终态后先清规则子记录再清父记录，保留外键，不调整生产源码。

## 隔离与边界

本机34307，datadir `/private/tmp/iqc-mysql-validation.LuvVgU`，使用既有专用 `iqc_worker_state_validation`，不是随机库，也没有本轮DROP库。测试启动前验证隔离实例，脚本退出自动shutdown。未访问共享应用或生产数据库。

待标注来源由测试夹具显式构建：复用既有真实人工复核候选的冻结文本，再设置v3输入及待标注协议。它证明消费者和事务，不证明未交付的自动筛选/创建者。旧八项仍覆盖真实人工复核候选动作与恢复。

IqcDataScope为真实实现，actor为受控当前投影；不代表真实组织/PDP、HTTP/浏览器组合或SQL并发审核。生产资产解析仍沿原受控夹具。本轮不证明完整自动挖掘、并发幂等生产或全I1–I7完成。
