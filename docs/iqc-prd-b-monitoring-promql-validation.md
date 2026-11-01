# 固定监控查询真实引擎验证

2026-10-26只读检查opensabre既有Prometheus容器，promtool版本3.13.3。IQC固定job/application查询UP=1；按正式名称复核iqc_model_gateway_active、iqc_task_persisted_state、iqc_pipeline_graph_active，当前成功空vector，无新指标样本。最初探查曾使用不存在的iqc_pipeline_node_active名称，随后按正式graph名称复核；不以初次结果判断节点采集。没有部署新版本或修改采集配置，不把UP视作新功能已采集。

复用控制面独立固定工作树MonitoringQueryService.iqcModelHistory的六条查询：activeModelCalls/taskStates/taskCompletionTps/unmeasuredExecutions/taskMetricsHealth/taskMetricsAge。测试输入见validation/iqc-monitoring-promql.json，由当前源码中的六个queries.put字面量及原固定selector/production拼接生成，不修改base-gateway源码。

通过SSH将本地JSON（YAML兼容）送入docker exec -i opensabre-prometheus /bin/promtool test rules /dev/stdin，不写远端文件、规则、配置。原46925 terminal0，/tmp/iqc-b-monitoring-promtool.log.exit=0，SUCCESS。两组共12条表达式检查：双实例共享任务快照7不是14；完成600轮/300=2不是4；历史未测量4不是8；真实调用在途2+3=5；健康min=0、年龄max=30；外应用及评测状态不污染生产；完全缺测保持空vector不制造0。

这是实际PromQL求值引擎+受控时序，不是新应用版本真实scrape，也不证明所有24条查询、真实Feign/PDP或容量。当前线上新指标无样本的原因仍须核对部署版本/采集，不擅改服务。


## 2026-10-29 现网只读采集复核

SSH原62777 docker ps terminal0：IQC API容器4f664ad1f6d4运行10小时，管理端013f994713f7与组织79e7e84bb346运行3周；不从镜像ID推断源码提交。未部署、未更改采集配置/凭据或数据库。

原13657查询现有Prometheus /api/v1/targets成功，七目标均up。原28391再次成功核对，IQC目标标签为application=iqc-platform、instance=iqc-platform:18080、job=opensabre-applications，lastScrape=2026-10-29T13:08:01.052829483Z、lastError为空；其余六目标up。

原5086通过同一Prometheus实际执行 `count by (__name__) ({__name__=~"iqc_(task|pipeline|model).*",application="iqc-platform"})`，返回status=success、result为空（0个指标族）。该查询覆盖上文新增任务/流水线/模型指标名称前缀，目标标签核对排除本次application拼写不匹配。结论仅为查询时刻无匹配即时样本，不意味着任务计数为0，也不证明根因必然是旧镜像；条件启用、注册与运行配置尚需调查。不得把UP或历史promtool受控求值当新指标真实采集通过。

完整24条固定查询、真实业务样本与时序仍未验收；后续应先核对当前部署版本和指标注册条件，准备可审阅的部署差异后再执行授权范围内动作。此复核全程只读。


### 2026-10-29 部署内容只读定位

原32536 docker inspect给出镜像精确digest `sha256:4f664ad1f6d4f1e45a2c721552bd98b3d6e896cccec87bb992fcaff3e8b250a5`、工作目录/app、java进程；目录为Jib的classes/resources/libs（第二个尝试BOOT-INF路径不存在，因此命令终态2，不能据此判类缺失）。随后原96410核对jib-main-class-file为io.github.opensabre.iqc.IqcPlatformApplication，find真实/app/classes未找到三个目标类；该命令最后的可选libs glob无匹配而终态2。

最终原58829终态0：正确路径/app/classes/io/github/opensabre/iqc/IqcPlatformApplication.class存在，列出同包下task/result等目录，但先前原75739在该正确目录内明确检查的task/DurableInspectionWorker.class、pipelinebridge/PipelineConversationEvaluator.class、result/llm/gateway/ModelGateway.class均不存在。两候选git.properties未匹配提交标识，不能给出部署SHA或声称固定提交已部署。

本地当前源码中ModelGateway构造注册iqc.model.gateway.active、PipelineConversationEvaluator构造注册iqc.pipeline.graph.active及线程池Gauge；DurableInspectionWorker在iqc.task.worker-enabled=true或缺省时注册任务观测。现网没有对应class意味着当前部署内容不能承担这些实现的实际采集验收，缺口首先在部署内容。尚未逐一调查其它指标、依赖jar或所有24条查询，也未尝试重启/热补丁/部署。应在确定包含数据库迁移与当前应用源的可审阅发布方案后再验证，不仅修改Prometheus标签。

启动classpath进一步只读复核：原27698仅提取受限启动字段，java -cp @/app/jib-classpath-file及正式主类，无-jar；原64056读取该classpath文件，258项，非/app/libs/仅/app/resources与/app/classes，未出现名称含iqc的应用jar。没有观察到外部热补丁classpath；仍未扫描每个第三方jar的内容，定位不扩展为完整依赖审计。两命令均terminal0，无环境/其他启动参数输出。


## 2026-11-01 全部24条固定查询的引擎验证

复用原 `validation/iqc-monitoring-promql.json`，沿控制面固定源码 `5baa1fdc4da8883f785201755c011b1a0f175952` 的 `iqcModelHistory` 扩齐原先未覆盖的18条查询。源码字面量、selector和原辅助函数展开后的24条表达式与源码SHA-256记录于 [source map](validation/iqc-monitoring-promql-source.json)；正向/缺测两组分别与该完整表达式集合逐条比对，无修改网关源码。

四组共53条断言：24条多实例/隔离/正常计算，24条完全缺测保持空vector，一条全失败attempt且没有SUCCESS序列时成功率为0，四条零分母结果存在但不伪造为0的检查。模型覆盖TPS、UNKNOWN比率、P50/P95/P99、按kind Token、usage、admission及settlement；流水线覆盖PRODUCTION图吞吐/直方图/在途、节点attempt状态/成功率/延迟/未开始跳过，以及原含评测的共享线程池在途与排队。所有新增族均加入外应用干扰；有PRODUCTION约束的族同时加入EVALUATION干扰。原共享DB快照max、健康min、年龄max断言保留。

原49414首轮exit1仅有五个浮点最后bit差异，例如0.1与0.10000000000000002；没有改查询或业务预期。按 [Prometheus官方规则测试格式](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/) 设置 `fuzzy_compare=true`，仅容忍浮点尾bit差异，原35915同表达式/同输入/同预期复验exit0、SUCCESS。两轮日志分别保留 `/tmp/iqc-b-monitoring-promtool-expanded-20261101.log` 和 `...-expanded-fixed-20261101.log`，没有将首轮写成通过。

复验命令：

```sh
ssh root@opensabre 'docker exec -i opensabre-prometheus /bin/promtool test rules /dev/stdin' < docs/validation/iqc-monitoring-promql.json
```

仅stdin传夹具给既有容器中的真实PromQL引擎，不写服务器规则、文件、配置，不执行部署或业务SQL。受控输入通过不证明候选应用实际scrape、PDP/Feign授权、真实provider或容量SLO；这些仍须按完整PRD独立验收。

本轮版本读取原19618 exit0：promtool 3.13.3，revision `b273ae3adeb64ad630d65ef7f16440df95658410`，linux/amd64。夹具SHA-256：`a6910adbfd930766d84679e48a35f0a74650f4ab1b60d4be44634f614949b77f`。


## 持续验证入口

原 `.github/workflows/docker_publish.yml` 新增独立 `Fixed IQC PromQL` job，使用与上述实际引擎相同的 `prom/prometheus:v3.13.3` 一次性运行原夹具，不启动监控服务或挂载服务器目录。`pipefail` 保留pull/解析/断言的失败退出码，原输出以artifact保留14天；不使用continue-on-error或自动重试。既有发布镜像job依赖此校验，PR仍不发布镜像。

本地完成YAML/job结构、原53断言夹具一致性与Bash语法检查；新Docker执行方式及GitHub job须由此固定提交的实际自动CI证明，不以之前SSH容器内SUCCESS替代。实际scrape/PDP/provider/容量缺口不受此自动化入口改变。
