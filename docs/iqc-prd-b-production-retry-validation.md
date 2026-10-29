# B：冻结生产输入的重试事务验收

基线为 A `4020981`，沿 B 当前 MySQL 验收分支，包含工具准入并发修复 `a23cb7c`。PRD 任务调度要求失败重试；本批验证已有 `InspectionExecutionService.queueRetrySystem` 的持久化边界，未新增重试模型或生产实现。

## 实际调用边界

`IsolatedMysqlProductionRetryIT` 使用真实 Task/Execution/Item Mapper、InnoDB、Spring 事务代理及 `ProductionInputSnapshotService.prepare`。复用已有隔离服务器 datadir/port 校验、正式 Flyway 迁移和专用 `iqc_worker_state_validation` 库。只在这个已核对的测试库清理夹具数据，不读取应用部署凭据。

Pipeline/Scheme 的资产依赖校验用 Mockito 替代，Pipeline 标记仅作为测试依赖边界，不是完整发布资产。未调用组织授权、模型、生产推理或结果提交服务。消息 Mapper 是 mock，明确断言重试排队不读取当前归档消息。成功检查点引用 `original-result` 是夹具引用，只证明原引用继承，不证明该结果正文有效。

## 覆盖场景

1. 从 PARTIAL_FAILED 创建下一轮，完整冻结清单仍含成功和失败两项；成功项继承原 resultId/attempt，失败项转 PENDING，保留 attempt，清理旧错误，累计进度为一项。
2. 连续重试继续保留更早的成功引用和快照，轮次推进至三；第一轮旧观察不能重新创建轮次。
3. 两个真实线程/独立事务竞争同一旧 EID/count，仅一个创建新轮次；数据库中只有两轮。
4. 旧 EID 或错误 count 不创建任何新轮次。
5. 缺少冻结清单检查点，在写新轮次前拒绝。
6. 成功项无 resultId，在写新轮次前拒绝。
7. 修改冻结文本但未更新摘要，真实输入解码拒绝。
8. MySQL 触发器在第二个新工作项 INSERT 时拒绝，Task 排队状态、Execution 插入及第一个已复制成功项全部回滚；移除触发器后同一观察可正常重试。

## 运行证据

首次单组执行：8 项，0 failures/errors/skipped，Maven exit0，15.188 秒。日志 `/private/tmp/iqc-b-production-retry-tests.log`。`mvn -o -q -DskipTests test-compile` exit0。

联合回归命令（两组共享数据库，禁止并行执行）：

```bash
IQC_MYSQL_VALIDATION_DIR=/private/tmp/iqc-mysql-validation.FVGdkd \
mvn -o -q -Dtest=IsolatedMysqlProductionRetryIT,IsolatedMysqlTaskExecutionStateIT \
  -DargLine=-javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar test
```

联合日志 `/private/tmp/iqc-b-production-retry-combined.log`；本次新 Surefire 报告确认 **35 项，0 failures/errors/skipped，Maven exit0**：重试 8 项（10.178 秒），原执行状态/预算/跨 JVM/工具并发 27 项（71.274 秒）。按顺序运行，两个测试类共享隔离数据库但各自重建夹具和关闭 Spring 上下文。

## 未覆盖范围

本批不证明后台发现器、fresh 组织权限、冻结重试政策及有效期/次数检查、尝试账本与排队联合提交；不证明完整生产推理使用原文本/业务上下文、成功结果正文来源校验或外部工具副作用幂等。原自动重试服务承担政策与授权限制，内部 queueRetrySystem 不替代这些检查。完整非实时语音 PRD 仍继续，离线音频保留。
