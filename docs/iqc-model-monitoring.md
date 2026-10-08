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
