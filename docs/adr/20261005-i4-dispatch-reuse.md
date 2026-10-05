# I4 周期调度与持久化 worker 的下一步复用边界

本页是实现前取舍，不代表周期调度或worker已实现。

## 当前源码证据

- `task/InspectionTaskService.createScheduled`、`materializeDue`：单次时间、冻结旧扁平条件、原Task.claim物化；不是Cron定义或周期实例。
- `task/ScheduledInspectionDispatcher`：恢复超时任务、物化单次定时，随后queueSystem/executeAsync；不能依赖一次内存投递证明持久化worker。
- `result/InspectionExecutionService.queue`：现有Task/Execution/Item原子生成QUEUED及当前EID；`run`对Task/currentEID/QUEUED条件取得运行权，已有节点/会话边界心跳和提交防护。
- `executeAsync`异常分支仍按旧对象ID直接写FAILED，需要新worker复用前修正为当前EID/状态条件写，不能把旧消费者异常写到新轮次。
- `InspectionTaskService.recoverStaleExecutions`：超时标FAILED以供人工重试；没有自动扫描持久化QUEUED任务或周期触发记录。
- `selector/ConversationSelectionService`集中条件、时间窗、抽样、来源与派生可见范围；后续调度应复用，不能再写一套筛选器。当前materialize使用请求的可信上下文，system dispatcher不能拿system身份直接代替创建者。
- Framework、base-sysadmin源码检索没有发现业务Cron/Quartz/XXL-job基础服务；不新增调度基础设施或改framework。后续使用已有Spring scheduler唤醒及数据库业务状态。

## 下一批实现要求

1. worker扫描既有QUEUED/currentEID，在现有run条件取得运行权；提交/异常/恢复必须同时校验当前EID和状态。进程丢失内存提交后仍能重新发现QUEUED，不能用另一个消息/任务主表复制执行模型。限本实例并发、去重本地投递和队列背压；多个实例不能重复调用模型。
2. 周期定义不是现有Task实例，不应给已冻结Task原地换Selector/版本。需独立周期配置及不可变修订/触发证据，以周期ID/修订/计划时刻唯一生成既有Task。新增持久化前记录职责差异和正式契约；能复用已有字段就不另建触发任务/执行表。
3. cron计划时刻、时区、生效/失效和错过策略固定；实际运行时计算相对窗口，以明确锚点冻结。不能把恢复后的当前时间冒充原计划时刻。no-data同样留下实例证据，重复触发不重新选数。
4. creation-time范围只作上限，触发时需按有效创建者权限重新核定，禁止把已撤销角色/部门授权永久保存在system scheduler里。先设计可信服务身份与组织查询方式；不扩大原会话/来源数据权限。
5. 任务/评测都走原结果隔离和执行器；评测不能触发生产动作。取消/暂停、失败重试预算、速率/成本预算与生命周期变更须一致，并有UI实例/恢复轨迹。

最终仍须数据库并发、重启、角色撤权、取消/恢复及实际容量证据；当前没有执行这些验收，也未新增或执行测试。
