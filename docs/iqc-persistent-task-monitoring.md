# 持久任务状态与执行轮次吞吐

## 复用与时间证据

PRD18.2的任务积压不能由节点线程池队列代替；任务吞吐不能把检测图返回或BasePo.updated_time当完成时刻。检索原TaskExecutionStateService、任务回收/取消、TaskExecution/TaskItem、审计及worker后，复用原任务/轮次表与终态事务，新增V1.1.76的nullable completed_at_utc和(completed_at_utc,status,task_id)索引，不新增队列、执行副本、审计表或触发器。

原finish/fail在同一Task→Execution锁事务中，仅SUCCEEDED/PARTIAL_FAILED/FAILED/CANCELLED且无时间时写UTC；PAUSED（包括预算等待）不写。原陈旧回收按原条件更新执行行时用COALESCE保留首次完成时间；直接取消QUEUED/PAUSED轮次同样只补首次时间。错误/重复/旧EID不改变原状态语义。历史终态时间不从updated_time推断，NULL保留未测量。新增NULL字段不出旧JSON；已测时间属于执行元数据，不进入冻结输入或既有结果Hash。

## 固定只读快照

原InspectionTaskMapper一条UNION查询在同一读视图统计，语句超时2秒，不返回ID/负责人/组/正文：

- TASK_STATE：QUEUED、RUNNING、PAUSE_REQUESTED、CANCEL_REQUESTED、PAUSED、WAITING_INPUT、MATERIALIZING、SCHEDULED；PAUSED且已有预算等待码归BUDGET_WAIT。计划/暂停/等待分别显示，不把计划中的未来任务全称作可执行积压。
- COMPLETED_5M：已记录完成时间，位于数据库UTC当前时刻向前5分钟（含边界），且为四类终态的执行轮次数。通过原taskId关联分类，PIPELINE_EVALUATION为EVALUATION，其余PRODUCTION。取消/失败也属于结束轮次，必须按状态显示，不能合称质检成功。
- UNMEASURED_TERMINAL：终态但完成时间为NULL的历史轮次，按原任务分类/终态分别显示；不是最近5分钟数量，不假造旧时间。

查询复用既有任务status索引和新完成时间索引；历史缺测计数仍需扫描符合条件的索引范围/关联任务，实际大数据EXPLAIN与耗时待验。索引/2秒语句超时不是容量达标证明。时间使用UTC；应用与数据库时钟的一致性须在环境验收核对，未来时间不进入近5分钟计数。

## 采样与缺测

原DurableInspectionWorker增加定时缓存采样，无新线程池/服务/HTTP端点。只有worker启用且MeterRegistry存在才查询；原四参构造保持无注册器路径。默认首次延迟/固定间隔30秒，iqc.task.metrics-delay-ms可设1秒–1小时，metrics-max-age-ms默认90秒，至少2倍间隔且不超过3小时。查询在既有调度器执行，实际调度与数据库负载仍待容量验证。

有效完整SQL结果中缺少的固定状态分组为真实0；空/非法/重复维度、负数或DB异常使整个样本失效，不保留旧值冒充新值。默认超龄90秒各状态值返回NaN；Prometheus抓取只读缓存，不访问DB。注册器/采样RuntimeException不改变原分发状态或重试；失败只使监控不可测，原worker发现逻辑保持。

固定mode/status指标：iqc.task.persisted.state、iqc.task.execution.completed.window、iqc.task.execution.unmeasured。采样健康iqc.task.metrics.sample.success为有效且未过期的1，否则0；sample.age（秒）为距最近成功采样，未成功过为NaN。Gauge为运行观测的double计数，不替代精确账本或财务统计。

## 控制面和页面

原固定IQC历史增加完整可选taskStates/taskCompletionTps/unmeasuredExecutions/taskMetricsHealth/taskMetricsAge五系列，兼容原9、节点18或任务23系列；部分/未知系列拒绝。共享数据库快照跨实例按max聚合，不能sum翻倍；采样健康用min暴露任一实例失效，年龄用max。生产状态与轮次分类固定PRODUCTION；健康/年龄为采样器整体。

结束速率=近5分钟已测结束轮次数/300，不对会随删除变化的历史总数使用rate，也不称每次模型调用或会话吞吐。历史缺测、样本健康/年龄与计划/等待状态独立展示。目标UP仅证明Prometheus抓取，不能替代数据库采样健康。旧控制面未提供此组时明确无法确认，不伪造零；失效/NaN沿原图表保断点。

仍沿原IQC管理员全局监控授权；不是用户组织质量报表，不新增tenant维度，业务读写继续owner/group/all。采样没有任务/用户/组织ID标签，浏览器不能提交SQL/PromQL或应用选择。

## 发布与验证

V1.1.76仅编码，未在共享库执行。发布前先迁移76，再更新接受9/18/23的IQC后端，再控制面23，再页面。回滚保留新增NULL列/索引，须核对旧版Flyway校验；先控制面再旧后端，不能先把23系列发给只认识9/18的旧IQC。没有开启默认v2或新动作开关。

Root仅test-compile及vue-tsc/Vite，原句柄/终态见根协作记录。B需验证首次终态/重复finish/fail/暂停与预算恢复/陈旧回收/取消/事务失败回滚/旧JSON和Hash、真实迁移/SQL UTC边界/模式与预算状态/缺测、空结果真0与坏结果/异常/超龄NaN/无注册器不IO、抓取不访问DB、固定查询不跨实例求和、旧或完整可选系列与UI撤权/迟到。真实容量、百万任务/跨重启恢复、GPU与供应商性能仍未因此完成；全适用I1–I7继续。

Root原19473 test-compile终态0且/tmp/iqc-task-operational-compile.exit=0，主/测试编译及diff通过；Root未执行测试。迁移76尚未在共享库执行，旧schema不得直接启动此后端，真实迁移/终态事务/SQL采样专项仍待B。

## B 2026-10-26 缓存采样专项

新增DurableTaskMetricsTest 14项，连同原DurableInspectionWorkerTest 6项共20项通过。原89376 terminal1为测试Tag导入重名，终态后明确Micrometer Tag；原84642 compiler:testCompile surefire:test terminal0，/tmp/iqc-b-task-metrics-cache-final.log.exit=0，fresh XML零失败错误跳过。主源码未改，沿用原43060编译快照。

验证34个固定计数Gauge初始NaN/健康0、空SQL结果真0、mode/measure/status绑定与预算等待/取消/历史未测量、非法/重复/null/负数行整样本失效、数据库异常与恢复、90秒超龄变断点、抓取只读缓存不IO、旧无注册器不采样、采样配置边界。超龄测试仅在测试内部注入私有快照的单调tick和最后成功tick，未增加生产时钟接口，不等待实际90秒。mapper受控，不证明真实SQL/数据库时钟或调度器容量。

原38948调用base-k8s/scripts/validate-flyway-migrations.sh检查此worktree，terminal1且/tmp/iqc-b-completion-migration-layout.log.exit=1。唯一报告为旧ddl/V1.1.64__ddl_add_action_execution_events.sql，逐行检查确认INSERT位于CREATE TRIGGER BEGIN/END内，原行正则无法区分触发器定义与顶层DML。未改历史迁移/校验器，不能记为目录门禁通过；新76仅ALTER列/索引。后续需改进拥有仓库的校验器并重验，另需新库/升级真实SQL、首次完成时间与事务回滚验收。
