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
