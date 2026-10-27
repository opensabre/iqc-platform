# 已配置分片自动重试与隔离库传递验证

2026-10-28，B基线d71d3ae、生产b163836。仅修改测试，未修改生产或framework。

原MeasuredPublication主链保留显式重试，再增加独立auto案例：真实批次创建及单会话物化；在子首次排队前由AutomaticRetryService.configure冻结最大2次、初始/最大延迟1秒策略；目标SQL触发器拒绝结果INSERT，真实executePersisted记录FAILED且无成功会话结果。finally移除触发器。父汇总失败后，显式启动父新轮次；清空用户上下文，等待真实1秒退避，由实际AutomaticRetryDispatcher.tick扫描、peek、开启尝试并调用fire事务排队子新执行。检查子QUEUED、DOMAIN_COMMITTED尝试恰好1条；执行子新轮次后父重新汇总成功，原父/子失败执行保留，子任务唯一、冻结全文和扫描进度不变。

组织resolve及scope回调仍受控；actual AutomaticRetryService、TriggerAttemptService、SQL、事务、执行服务及工具调用暴露查询均真实。这里直接调用调度方法，不证明真实cron或远程PDP。策略是显式配置给子任务，父不自动继承/下发策略，父重启仍显式进行；不能称完整批次自动重试策略已交付。

## 环境故障与证据

- 首轮28807退出1，`/tmp/iqc-b-bulk-child-auto-retry.log.exit=1`。诊断轮27616退出1，`/tmp/iqc-b-bulk-child-auto-retry-diagnostic.log.exit=1`；实际SQL及独立SHOW CREATE均为1812 Tablespace missing，原专用库iqc_worker_state_validation.iqc_trigger_attempt_event的ibd缺失。保留原库不删不修，不能把原轮次算通过。
- 新增测试属性`iqc.test.worker-schema`，默认仍iqc_worker_state_validation，限制为iqc_前缀的短小写字母/数字/下划线标识符。创建库和DataSource使用同一值，BulkRestartProcess与原WorkerProcess向子JVM传递该值，避免父子连到不同库。
- 昨日fresh调用未生成日志且handle消失；2026-10-28核对socket不存在、pgrep无旧MySQL/Maven/Surefire进程，才重新启动。原54826终态0、`/tmp/iqc-b-bulk-child-auto-retry-fresh-20261028.log.exit=0`，fresh主方法1项224.8秒、零失败/错误/跳过。全新iqc_b_bulk_retry_20261027_01实际58迁移至1.1.77，原跨JVM恢复/停止/优先级/生命周期/六组汇总/发布Finding MVC同次通过。
- 为覆盖原WorkerProcess属性传递，另一全新iqc_b_bulk_retry_state_20261028执行原independentJvmTransactionsGrantExactlyOneClaim、killedClaimingJvmLeavesDurableStateThatAnotherJvmRecovers、newJvmPreservesExpiredQueuedRowAndCanClaimIt。原42122终态0、`/tmp/iqc-b-worker-schema-native-20261028.log.exit=0`，新Surefire3项77.55秒零失败/错误/跳过。均复用专用34307，结束后socket不存在，diff检查通过。

主链日志中的受控Action receipt pending及预期SQL故障保留。没有真实模型中断/供应商故障/全量PDP/百万容量证据；未取A935cea7新高风险源，不能用本轮结果验证该变更。处理后会话范围不变，完整适用I1–I7仍未完成；无共享SQL、push或部署。
