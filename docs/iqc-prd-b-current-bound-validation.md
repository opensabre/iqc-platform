# 批次恢复当前授权与原范围交集验证

生产固定提交 2a8a475，B 合入 1f07947。输入为处理好的会话，语音转换不在任务范围。

## 已完成验证

原会话 36529 terminal 0，`/tmp/iqc-b-current-bound-scope.log.exit=0`。实际 IqcDataScope、受控组织响应：

- CurrentActorBoundScopeTest 12 项，0.379s：八组 all 升降/同组/异组/空组交集；稳定账号/owner/协议不匹配拒绝；过期与未来授权证据拒绝；当前 grant 缺失、异常后清理；原 asCurrentActor 严格入口不放宽。
- ScheduledActorScopeTest 5 项，0.094s；ScheduledActorAccessServiceTest 9 项，4.004s；IqcDataScopeTest 4 项，0.042s。

共 30 项零失败、错误、跳过。

原会话 61054 terminal 0，`/tmp/iqc-b-bulk-access-order.log.exit=0`。BulkTaskAccessServiceTest 4 项，3.311s，零失败、错误、跳过。普通任务沿原入口；当前授权失败不能读取冻结负责人或 queue；异创建人即使当前 all 也不能借用原负责人；成功路径先 metadata/capture/resolveCurrent，再 ownerForCurrent/asCurrentActorBound/queue。

## 限制

入口依赖受控，验证调用顺序；scope 测试使用真实应用范围实现，但组织服务响应受控。没有远程 PDP/Feign、真实角色与令牌传输、后台进程重启或百万会话性能证据。现有 SQL 生命周期证据同样不能替代这些场景。未修改生产源码、framework、共享数据库，未 push 或部署。完整适用 I1–I7 仍在推进。
