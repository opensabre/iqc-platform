# B：动作策略和授权前置验收

在B76629d4独立工作树，合入A已提交ccdb908、0443962、d3ea600、701779d、65d87b9，集成提交b68f15c。没有读取/迁入A未提交的领取、结算或执行服务；没有修改framework。

新增两份服务逻辑验收，17项定向测试Maven exit0：

- 条件树NOT UNKNOWN不变确定；ALL需完整正证据且明确false可否决；ANY仅明确正证据可满足。
- 缺失风险或评分为UNKNOWN，所有非FINAL评分即使有低分也不可触发；数字值比较不因scale产生差异，未知Finding状态不能触发值条件。
- 发布引用必须有声明，动作ID不可重复，配置防御复制，旧版本固定解释1；注册表无latest回退，重复或运行中变更协议拒绝，配置校验不执行动作。
- 入队缺演员、过期/未来授权、三个固定grant任缺、owner/all/group与当前作用域不一致均在来源读取或账本写入前拒绝。

首轮10项中1项测试夹具误用业务项状态QUALIFIED，修正为实际PASS后最终两类17项全部通过，无生产修改或断言弱化。日志 `/private/tmp/iqc-b-action-policy-auth-verified.log`。

这些测试直接调用服务并模拟依赖，证明本地判断/拒绝行为；不能证明Spring事务传播、真实数据库锁、重复键竞争、当前组织权限获取或远端动作发送。合入五提交后的完整默认回归Maven exit0：88份报告/741项，0 failures/errors/skipped；报告均本次08:30:13..08:32:36刷新，排除历史独立IT。日志 `/private/tmp/iqc-b-action-enqueue-integration-full.log`。diffcheck通过。此次没有重跑MySQL IT。

完整非实时语音PRD继续，离线音频保留。生产实际调用链、领取/执行/结算、完整尝试历史、其他八类真实适配、独立Risk Policy及UI/浏览器验收尚不能由此次测试证明。
