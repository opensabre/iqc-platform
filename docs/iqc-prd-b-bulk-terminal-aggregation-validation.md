# 批次父终态汇总隔离 SQL 验证

2026-10-27，固定生产 b163836、B 基线720d521。仅扩展原
`IsolatedMysqlMeasuredPublicationIT#realMeasuredPublicationMaterializesAndExecutesFrozenProductionInput`，无生产源码变化。

实际 Spring 事务代理、MyBatis 和本机专用 MySQL34307；实际创建父任务、claim、物化已处理会话的冻结子任务，再调用原生命周期 tick。六组子结果与父/执行预期：

| 子终态 | 父及执行终态 |
| --- | --- |
| SUCCEEDED | SUCCEEDED |
| FAILED | FAILED |
| CANCELLED | FAILED |
| PARTIAL_FAILED | PARTIAL_FAILED |
| SUCCEEDED + FAILED | PARTIAL_FAILED |
| SUCCEEDED + CANCELLED | PARTIAL_FAILED |

每组检查完成时间非空、重复 tick 和过期执行 ID 不改变终态/完成时间、终态不能重新 claim、原父执行 ID 与唯一执行不变、子数不增加、扫描进度和真实物化子的冻结输入全文不变。这里的 CANCELLED 是子任务结果；父显式取消仍由既有生命周期场景验证为 CANCELLED。

证据：原进程70583终态0；`/tmp/iqc-b-bulk-terminal-aggregation.log.exit=0`；新 Surefire 报告1个主链方法、133.2秒、零失败/错误/跳过。此方法同时重跑原跨JVM claim与崩溃恢复、真实worker接线、满载停止传播、优先级、暂停恢复取消、统一清理及发布/Finding/MVC链路。隔离MySQL退出后socket不存在，diff检查通过。

边界：子终态由测试写入，混合案例第二子是最小终态夹具，隔离验证原SQL汇总规则；不是两个分片完整执行或真实模型调用失败证据。组织授权仍受控，不能证明真实PDP/Feign/角色、运行中模型中断、双完整scheduler或百万容量。已有日志中的受控Action receipt pending警告保留，不能称真实外部通知已交付。完整适用I1–I7继续；不涉及语音转换，不涉及共享数据库、framework、推送或部署。
