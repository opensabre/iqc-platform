# 持久化队列发现与执行状态

## 实现前复用取舍

沿用原Task/Execution/Item、QUEUED状态、执行租约、iqcTaskExecutor及Spring scheduler。已有异步投递不是持久化任务发现；没有发现通用调度基础服务。源码取舍见20261005-i4-dispatch-reuse ADR。

既有run/executeAsync分别直接更新Task和Execution，结束/异常存在检查后再写的窗口，不能作为新worker的状态安全边界。本批增加IQC领域事务状态服务，集中取得当前EID运行权、结束及异常转换，不新建任务、队列、执行或worker表，不增加基础设施/公共starter。原检测、结果提交及评测隔离仍复用。

## 已编码的执行边界

数据库现有 QUEUED 行是待执行队列。worker 按优先级、创建时间和 ID 发现任务，沿用 iqcTaskExecutor；本实例最多保留指定数量的提交/执行请求。重复投递必须先在同一事务中锁定 Task、校验 currentExecutionId 并锁定 Execution，只有当前 QUEUED 轮次可以取得运行权。

结束和异常写回也校验当前 EID、合法状态并事务更新两行；写回数量不符合预期时回滚。暂停和取消请求保持原意，不被异常改成 FAILED。超时回收只处理已运行及暂停/取消请求，等待中的 QUEUED 不按执行心跳超时处理。旧轮次在恢复后不能覆盖新轮次状态。

REST 与单次定时调度仍提供即时异步唤醒；执行器拒绝提交时保留 QUEUED，由 worker 后续发现。worker 开关独立于原单次调度开关。

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| iqc.task.worker-enabled | true | 开启持久化发现；关闭时使用原投递/超时回收路径 |
| iqc.task.worker-delay-ms | 2000 | 两轮发现间隔 |
| iqc.task.worker-max-in-flight | 8 | 本实例 worker 提交及执行上限，允许 1–64 |
| iqc.task.execution-timeout-minutes | 30 | 已运行轮次心跳超时，必须为正数 |

## 限制与剩余范围

本实例上限不等于全局模型调用预算；原 REST 唤醒仍受现有线程池约束。多个实例靠数据库当前 EID 和状态取得运行权。远端模型已发出的调用不能保证撤销或只计费一次，不能把状态防重宣传为模型调用恰好一次。

失败任务仍由显式重试/恢复路径创建新轮次。本批没有新增表、基础设施、API 或框架修改。Cron/周期资产、自动失败重试预算、速率/成本预算及运行验收尚未完成。只编译源码及既有测试源码，不新增或运行测试，不执行迁移或部署。
