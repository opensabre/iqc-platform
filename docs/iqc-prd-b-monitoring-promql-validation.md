# 固定监控查询真实引擎验证

2026-10-26只读检查opensabre既有Prometheus容器，promtool版本3.13.3。IQC固定job/application查询UP=1；按正式名称复核iqc_model_gateway_active、iqc_task_persisted_state、iqc_pipeline_graph_active，当前成功空vector，无新指标样本。最初探查曾使用不存在的iqc_pipeline_node_active名称，随后按正式graph名称复核；不以初次结果判断节点采集。没有部署新版本或修改采集配置，不把UP视作新功能已采集。

复用控制面独立固定工作树MonitoringQueryService.iqcModelHistory的六条查询：activeModelCalls/taskStates/taskCompletionTps/unmeasuredExecutions/taskMetricsHealth/taskMetricsAge。测试输入见validation/iqc-monitoring-promql.json，由当前源码中的六个queries.put字面量及原固定selector/production拼接生成，不修改base-gateway源码。

通过SSH将本地JSON（YAML兼容）送入docker exec -i opensabre-prometheus /bin/promtool test rules /dev/stdin，不写远端文件、规则、配置。原46925 terminal0，/tmp/iqc-b-monitoring-promtool.log.exit=0，SUCCESS。两组共12条表达式检查：双实例共享任务快照7不是14；完成600轮/300=2不是4；历史未测量4不是8；真实调用在途2+3=5；健康min=0、年龄max=30；外应用及评测状态不污染生产；完全缺测保持空vector不制造0。

这是实际PromQL求值引擎+受控时序，不是新应用版本真实scrape，也不证明所有24条查询、真实Feign/PDP或容量。当前线上新指标无样本的原因仍须核对部署版本/采集，不擅改服务。
