# 监控测试Gauge生命周期修复（2026-10-31）

PR27固定8f27be0的原CI run41167384640失败：1918项中1项失败，PipelineMonitoringTest第83行graph.active期望0而实际NaN，后续分片IT跳过。保留原失败，不靠重跑宣称解决。

真实PipelineConversationEvaluator构造器把activeGraphs计数器注册为Micrometer弱引用Gauge；生产Spring singleton持有这些计数器。原测试却临时构造evaluator，evaluate返回后没有强引用，GC可在Gauge断言前回收owner及计数器。实际最小探针没有执行节点、并发或重复注册：

| 模式 | 三轮结果 |
| --- | --- |
| evaluator保留至采样后 | 均0.0 |
| 释放evaluator，WeakReference确认已回收 | 均NaN |
| 修复后的真实TestRegistry持有evaluator，经GC后采样 | 均0.0；close均清空owner集合 |

原探针64403、扩展探针51988均正常退出0。首个探针草稿因SimpleMeterRegistry不实现AutoCloseable编译失败，采用原测试的registry子类模式后才实际运行。临时诊断源码/private/tmp/IqcGaugeLifetimeProbe.java，结果/private/tmp/iqc-gauge-lifetime-probe-fixed-20261031.log，未向生产插入诊断代码。

修复只让TestRegistry持有其创建的evaluator至close，关闭时清空引用，匹配Spring bean生命周期；不改生产Gauge为强引用，不改监控数值/标签/心跳断言，也不改运行时、框架或POM。

原67855正常退出0，JUnit XML实际PipelineMonitoringTest八项全部通过，0失败/错误/跳过，6.409秒。Java21离线命令：

```sh
mvn -o -q -Dmaven.gitcommitid.skip=true -Dtest=PipelineMonitoringTest \
  '-DargLine=-Xmx1536m -javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar' test
```

本机日志/tmp/iqc-b-monitoring-lifetime-test-20261031.log及exit文件；完整同头CI另行观察。这里修复的是测试fixture生命周期，不是生产真实采集/Prometheus链路验收，不关闭原PRD其它缺口。
