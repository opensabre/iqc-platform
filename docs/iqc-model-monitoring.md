# 模型网关运行指标

复用应用的Actuator/Micrometer/Prometheus，计量入口为ModelGateway每次显式Supplier调用。无新采集服务、表或模型网络。

## 耗时与吞吐

Timer `iqc.model.gateway.invocation` 输出 `iqc_model_gateway_invocation_seconds_count/sum/bucket`。仅两个固定outcome：RESPONDED表示Supplier返回（不等于质检成功、有效JSON或账本核销成功），UNKNOWN表示Supplier抛出（不能断言远端失败）。每次实际重试/工具续调独立计量，包含调用适配器内部工作；无法观察legacy SDK隐藏的多个HTTP请求。准入拒绝不产生Supplier样本，耗时排除准入和后续账本事务。

直方图采用1ms至10min的预期桶范围，超范围样本仍记count/sum，最高分位可能仅能显示桶上界，不能宣称精确值。未完成/崩溃调用不计为已完成吞吐；缺采集数据保持缺失，不填0。未加入用户、Task、callId、端点或物理模型键标签。指标注册失败不改变业务返回与账本状态。

以下PromQL需在现有查询控制面固定并限制授权范围，浏览器不直连Prometheus，不接受任意用户PromQL：

```promql
# 已结束的显式调用/秒（不是Task TPS）
sum(rate(iqc_model_gateway_invocation_seconds_count[5m]))
# 本地Supplier结果未知比例（不是业务质检失败率）
sum(rate(iqc_model_gateway_invocation_seconds_count{outcome="UNKNOWN"}[5m])) / sum(rate(iqc_model_gateway_invocation_seconds_count[5m]))
# 已结束调用耗时P95（秒）；P50/P99更换第一个参数
histogram_quantile(0.95, sum by (le) (rate(iqc_model_gateway_invocation_seconds_bucket[5m])))
```

启动/重启会重置实例内计数，使用rate并按部署/环境隔离已有抓取标签。无流量或零分母不应展示“失败率0%”。该指标不提供GPU利用率、供应商内部排队、持久并发占用、业务失败率、每会话成本或Token覆盖；后者需分别沿真实来源实现。

## 验收状态

当前只有应用埋点与编译证据。未验证真实registry/Actuator序列、Prometheus Targets/抓取授权、控制面查询或浏览器；不代表已部署、指标可查或完整PRD系统层完成。已有共享监控配置由部署层管理，未修改framework源码。

## 准入、核销和Token覆盖

- `iqc_model_gateway_admission_total`：stage为CONFIGURATION/RATE/CAPACITY，outcome为DENIED/UNAVAILABLE/ACCEPTED。配置非法只记录CONFIGURATION拒绝，RATE通过后才进入CAPACITY（包括来源、并发、预算校验）；不可将不同阶段ACCEPTED相加作为请求数。RATE DENIED也包括本地未得到有效allowed决策，不证明远端治理服务正常。
- `iqc_model_gateway_settlement_total`：RESPONSE_CONFIRMED/RESPONSE_UNCONFIRMED/UNKNOWN_RECORDED/UNKNOWN_UNCONFIRMED表示本地账本方法是否完成。响应事实确认不等于业务成功；UNKNOWN记录成功仍不证明远端执行结果。手工对账/迟到后台账本动作不回填运行计数。
- `iqc_model_gateway_usage_total`：只有confirmed返回后记录，status为COMPLETE/PARTIAL/MISSING/INVALID。空usage归MISSING；无效usage不进入Token计数。
- `iqc_model_gateway_tokens_total`：kind为input/output/total/cache_read_input/cache_write_input；分别累加本次confirmed返回的已知合法nullable值。部分用量可贡献已知项，缺失项不记录，已知0允许记录。不同kind重叠，尤其total包含input/output，不能全部求和。缓存Token不代表免费或支持缓存计费。

Token趋势查询示例：

```promql
sum by (kind) (rate(iqc_model_gateway_tokens_total[5m]))
sum by (status) (rate(iqc_model_gateway_usage_total[5m]))
sum by (stage,outcome) (rate(iqc_model_gateway_admission_total[5m]))
sum by (outcome) (rate(iqc_model_gateway_settlement_total[5m]))
```

Micrometer计数采用浮点、随实例重启，不能作为精确账单/预算/持久审计依据；即使Token计数为0，也需usage覆盖/抓取状态才能区分无消耗与未知。注册/计数异常仅日志，不改变准入、网络、返回及账本事务。缺失覆盖、崩溃窗口和未采集实例仍待运行验收，不宣称全局完整计量。

## 查询转发实施前复用
复用现有OpenFeign starter的InternalTokenInterceptor逐跳签名及受信任角色上下文，不创建新鉴权或Prometheus客户端。IQC后端新增固定控制面Feign接口，仅固定base-gateway-admin服务、固定路径、受限range；browser权限仍在IQC产品，不改变应用产品唯一归属。查询失败返回明确错误，不伪无数据。

浏览器入口：`GET /api/iqc/model-gateway/monitoring/history?range=1h`，独立资源`iqc:model:monitoring:view`仅IQC_ADMIN。ModelMonitoringService通过原IqcDataScope再验证管理员，只允许15m/1h/6h/24h/7d/30d，再调用固定控制面Feign目标。检查标准Result成功、range/时间/step及九组series契约，单组返回限制1MiB，失败不透传底层异常/端点，不返回空值伪健康。保留控制面start/end/step与原matrix字符串，UI解析还未接入。内部Feign签名/角色传播及实际控制面结果未运行核验；必须先发布相应控制面接口才能可用。

## 前端历史解析复用与缺口
已有opensabre-admin/history-chart.ts提供ECharts line配置，但原matrix函数解析异常返回[]、Number(NaN/Inf)不区分协议错误；IQC应用尚无ECharts依赖。先沿该配置形状接入IQC固定history API，域内适配严格校验Prometheus success/matrix/序列标签/时间网格和数值，真实空matrix保持空、NaN/Inf及缺采样转null且connectNulls false。控制面源码queryRange用Instant.getEpochSecond，因此前端网格按返回start/end秒取整与step对齐；不臆造零值，不将解析失败当无数据。图表组件/依赖接入和交互仍待实现，本批只有API与解析适配。未新增采集服务或持久模型。

## 图表界面接入
模型管理页增加ModelMonitoring，独立iqc:model:monitoring:view权限。沿现有opensabre-admin ECharts组件适配：保留core/line/grid/legend/tooltip/Canvas，IQC应用显式引入同一echarts依赖；native ResizeObserver替代未安装VueUse，仅加载折线图，保留源MIT声明。更新options全量替换，卸载清理observer/实例。固定7组图展示九组查询，六时间范围、手动刷新、请求代次/卸载围栏；错误独立提示，解析成功但全无有效点显示未知/无样本，不补0。未知ratio用百分比、latency用毫秒，Tokenkind保留区别；界面不宣称业务失败率/GPU/账单。真正指标、鉴权和浏览器显示仍未验证。

## 数据来源和IQC采集状态
浏览器GET `/api/iqc/model-gateway/monitoring/status`，独立IQC_ADMIN资源`iqc:model:monitoring:status:view`。沿同一内部Feign转发控制面/monitoring/iqc/models/status；复用MonitoringDataSourceStatus，仅固定`up{job="opensabre-applications",application="iqc-platform"}`。控制面查询不可用返回availablefalse，IQC内部调用失败为明确查询错误，成功但空vector不等于Targets已健康。IQC不透传底层错误信息，targets最多1MiB。
前端与历史查询并行读取并保留同一请求代次围栏：严格success/vector/sample，展示Prometheus报告的UP/DOWN/未知个数，查询失败/解析失败/成功无IQC目标分别显示。UP只代表Prometheus采集状态，不证明模型业务成功或预算核销正确；不展示其他应用目标或凭据。实际状态、抓取授权和浏览器仍未验证。

## 显式模型调用在途（2026-10-26）

PRD18.2模型并发复用原ModelGateway实际调用边界，新增iqc.model.gateway.active Gauge；不新增预算/并发控制或调用账本。原容量/预算准入后、请求诊断采集后，进入invoke.get前递增，Supplier返回/RuntimeException/Error离开时在原finally首先归还，再记录原调用Timer；随后归档/usage/结算不计在途。未准入不递增，结算失败保留账本占用的原语义不变；计量不能触发重发或修复副作用。

该值是本应用正在显式调用边界内的数量，内部SDK/工具循环、异步供应商行为和服务器资源并非同一口径；嵌套显式调用各自计数，不宣称GPU占用或远端物理并发。单实例固定一个无ID标签Gauge，跨实例sum，不使用共享数据库快照的max规则。注册器不可用不影响业务；旧无注册器构造保持。实例重启/采集缺失不能补0或当作远端没有调用。

原控制面增加固定application=iqc-platform的activeModelCalls单系列，页面可选显示。兼容原9/18/23系列以及各自增加该单系列（10/19/24）；部分节点/任务组、未知系列、空值仍拒绝。旧控制面没有该字段时明确无法确认在途；实际注册了Gauge的0才是已测0。仍需先更新能接受可选系列的IQC，再控制面/页面；迁移76发布前置仍适用于当前IQC版本。未部署。

Root仅test-compile/前端build，终态见根协作记录。B需验证挂起Supplier并发/嵌套、成功/抛错/Error归还、准入拒绝/归档和usage/结算等待不充在途、账本与重试/工具副作用不变、坏注册器隔离、固定sum与旧/扩展系列、页面无字段/非有限/撤权及实际采集。没有GPU或供应商实际权重/性能证据，完整I1–I7仍未完成。

Root原78963 test-compile终态0且/tmp/iqc-model-active-compile.exit=0，主/测试编译及diff通过，Root未执行测试。三仓固定后的在途/结算副作用/采集专项仍待B，不将编译当运行验收。

## B 2026-10-26 监控门面独立验证

固定同步83d2349（含持久指标b894d36），ModelMonitoringServiceTest 24项与PipelineMonitoringTest 8项共32项通过。原43060 test-compile surefire:test terminal0，/tmp/iqc-b-monitoring-facade.log.exit=0，fresh XML零失败错误跳过。

覆盖每次当前管理员校验先于控制面IO、9/10/18/19/23/24六组合与返回map独立不可变、各必需/可选组缺键和未知键拒绝、持久组依赖节点组、六range白名单/非法range不IO、错range/空倒序时间/非法step/空map与matrix/1MiB边界、上游不可用错误消敏、available=false不返回targets。矩阵原文由界面解析，这里不冒充Prometheus矩阵求值。

权限scope和控制面均受控，不证明真实PDP/Feign/PromQL/抓取、完成时间迁移或持久SQL语义，也不证明模型在途新Gauge生命周期。生产源码未改，后续独立验收仍需推进。
