# 节点与检测图运行监控

PRD18.2要求节点成功率与运行监控。现有PipelineRuntime.Trace/progress已覆盖串行、并行、重试、备用节点、条件/依赖跳过和取消；已有ModelGateway Micrometer、IQC Prometheus私有采集、gateway-admin固定PromQL及ModelMonitoring页面。复用这些能力，在原桥接与原执行器读取状态，不新增表/API/基础设施/监控服务，不修改Framework。

## 采集口径

PipelineConversationEvaluator的原progress回调先记录观测，再执行原任务/EID/RUNNING租约心跳；计量失败不能阻止心跳或改变检测结果。原三参构造保持无注册器路径，Spring构造通过ObjectProvider读取现有MeterRegistry。原图输入、Trace/结果及Hash不添加监控字段；历史重读/复核不重放计量。

- iqc.pipeline.node.observations：每个原Trace一次，mode=PRODUCTION/EVALUATION、engine为七类内置引擎或OTHER、status为NodeResult枚举，phase=ATTEMPT（attempt>0）或NOT_STARTED（attempt0）。重试和备用节点分别计数；逻辑尝试已发起但因取消未进入引擎也沿原Trace计为尝试，不能用来推断模型实际调用或GPU执行。
- iqc.pipeline.node.duration：仅attempt>0且原Trace.durationMs非负，单位毫秒输入Timer；以逻辑结算耗时为准，未开始的跳过不填零耗时。超时与取消不证明底层线程已经终止。直方图预期范围1ms–5min，超过范围仍保留总计及+Inf桶。
- iqc.pipeline.graph.duration：每次桥接中的runtime.execute逻辑图执行计时，使用单调时钟，包含调度/进度心跳，排除随后融合、评分与持久化。按mode及图结束status计数，执行抛异常为ERROR；不是整个任务耗时或已持久成功吞吐。直方图预期1ms–1h，超长保留+Inf，不能把非有限分位数画成有限值。
- iqc.pipeline.graph.active：每个mode逻辑在途图，finally归还；取消逻辑结束不保证非协作底层任务结束。
- iqc.pipeline.executor.active / queued：原共享ThreadPoolExecutor.getActiveCount / getQueue.size，包含评测及已取消但尚未退出/出队的物理工作，不增加查询或线程池，不等同持久任务待办、供应商并发或GPU使用量。

不使用任务、会话、节点、发布版本、用户、组织ID、Prompt、错误正文等作为标签。全局运行监控仍沿原IQC管理员/管理端口授权；组织业务报告继续原owner/group/all，不从全局序列推断某组织质量。

## 控制面与页面

原IQC模型历史端点增加完整可选九系列：pipelineTps/pipelineP95/pipelineActive、nodeAttemptTps/nodeSuccessRatio/nodeP95/nodeSkippedTps、nodeExecutorActive/nodeExecutorQueued。查询固定application=iqc-platform；图/节点仅PRODUCTION，线程池明确含评测。浏览器不能提交PromQL、应用或实例选择。

尝试成功率=SUCCESS尝试速率/全部尝试速率，ERROR/TIMEOUT/CANCELLED等不计成功，attempt0跳过不进入分母。若已观测尝试而没有SUCCESS序列，用同分母构造0成功分子；完全缺测不填0，零速率分母保留NaN断点。所有rate为5分钟窗口；图TPS是完成逻辑图而非任务/业务持久成功TPS。

旧控制面只有原九系列仍受支持，页面明确节点指标不可确认；新九系列必须全有或全无，部分/未知系列拒绝。空矩阵、丢样、NaN/Inf沿原解析器保断点，不补零、不连接断点。权限/范围变化同步清旧图与请求代次。未部署时没有这些新采样是正常状态，页面存在不证明Prometheus Targets健康或PRD容量达标。

## 验证边界

Root只执行test-compile和前端vue-tsc/Vite；原句柄和终态见根协作记录。B需要独立验证原串并行/重试/Fallback/跳过/超时/取消每Trace不重计、度量失败不影响心跳/状态、活跃计数异常与取消归还、原构造/结果JSON/Hash不变、物理池状态、固定查询/可选系列/旧控制面、零成功与缺分母、组件撤权/迟到响应及真实采集链路。GPU、持久任务积压、跨重启容量和真实供应商性能仍需各自证据，完整I1–I7未完成，未部署。

## 发布兼容顺序

尚未部署。准备发布时先更新可同时接受原9/新18系列的IQC后端，再更新返回18系列的控制面，页面可在后续更新；旧IQC后端严格只接受9系列，不能先更新控制面。回滚时先恢复控制面，再回滚IQC后端。新页面读取原9系列只显示节点指标不可确认，不要求伪造采样。监控请求增加固定查询量，实际控制面/Prometheus查询耗时及浏览器大窗口还需验收，不以编译证明性能。

Root 原97282 test-compile 终态0，/tmp/iqc-pipeline-monitoring-compile.exit=0，主/测试编译与diff通过；控制面原87731 test-compile同为0，固定2060de0。Root未执行测试，真实回调/注册器故障/PromQL/采集与UI专项仍待B，不能据编译认定运行验收完成。

## B 独立节点监控验证（2026-10-26）

新增 PipelineMonitoringTest 使用真实 PipelineRuntime 和受控 adapter/心跳 mapper，覆盖串行与并行每次重试/备用/条件跳过的 Counter、Timer 与心跳次数，未开始跳过无耗时，取消和异常图 active 归还、未知引擎固定 OTHER 标签、注册器故障隔离、旧构造输入摘要和业务投影兼容，以及生产和评测图共享物理池。

首轮 /tmp/iqc-b-pipeline-monitoring.log.exit=1 为测试注册器不实现 AutoCloseable 的编译问题；修复测试资源封装后，/tmp/iqc-b-pipeline-monitoring-final.log.exit=1 原回归15通过、新5项 FindingDefinition 夹具缺证据类型导致 error。补合法证据类型并扩至8项后，/tmp/iqc-b-pipeline-monitoring-fixed.log.exit=1 共23项22通过，排队例错误假定一个图会超过 worker 数投递；原执行器按图限制 ready 批次，因此改用生产/评测两个并发图制造真实共享物理队列。生产代码未修改，最终复跑结果另记。

这些证据不覆盖持久任务迁移/SQL、真实 provider、PDP、Prometheus 抓取与实际 PromQL 求值，也不证明容量 SLO。

最终原7018 terminal0，/tmp/iqc-b-pipeline-monitoring-verified.log.exit=0；fresh XML PipelineMonitoringTest 8、ProcessedConversationPipelineTest 11、ParallelPipelineRuntimeTest 4，合计23项零失败错误跳过。双图夹具验证共享队列，未改执行器限流策略；仅测试与文档，主源码沿用22ba02f已编译快照。先前失败记录保留。
