# B：自动重试策略和授权回归

沿 A `4020981` 基线及 B 冻结输入真实事务验收 `7f332c1`，补充 `AutomaticRetryServiceTest`。复用已有自动重试服务，不创建新的政策、授权或任务模型。

## 验证边界

调用真实 `peek`/`fire`，仓储、当前 scope、模型工具曝光查询、排队入口和尝试服务采用 Mockito。测试验证允许与拒绝分支及明确的调用参数，不证明真实组织投影、SQL 持久化、Spring 回滚或账本联合提交。上一批 MySQL 重试验收覆盖排队入口的事务边界，两批不可组合成已完成端到端验收的声明。

覆盖 20 项：

- 合格失败形成原 EID/count/失败时间/策略摘要/计划时刻观察。
- 退避来自真实夹具失败时间；达到总轮次上限停用，包括人工轮次，不重置预算。
- 到期立即停用；下一时刻等于截止期限也不能执行。
- 已有工具曝光拒绝发现；发现之后新出现的曝光在排队前再次拒绝并记录 NO_CHANGE。
- 发现之后停用、暂停或取消不排队。
- 用户身份替换、负责人变化、范围扩大、group 变化、缺少固定权限、授权过期或授权时间过度超前均拒绝，排队及成功完成服务无调用。
- 原输入不再可见拒绝；旧 EID 观察记零处理，不新增轮次。
- 有效当前身份/权限/范围只调用原 `queueRetrySystem(task, failed-eid, 1)` 并记录一项处理。

## 执行记录

首轮 16 项中 14 项通过，2 项期限夹具解析错误：独立 ObjectMapper 默认将 Instant 写为数字，策略自定义反序列化器明确要求 ISO 文本。夹具已关闭 `WRITE_DATES_AS_TIMESTAMPS`，保留所有原断言，随后追加四项发现后控制变化回归。未修改生产序列化器或协议。

```bash
mvn -o -q -Dtest=AutomaticRetryServiceTest \
  -DargLine=-javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar test
```

首轮日志 `/private/tmp/iqc-b-retry-authorization-tests.log`；有效复跑日志 `/private/tmp/iqc-b-retry-authorization-verified.log`。2026-10-09 最终 Maven exit0，当前 Surefire 报告确认 **20 项，0 failures/errors/skipped**。

完整 PRD 仍未完成；真实授权获取、后台扫描调度、依赖退避、尝试账本事务及模型/推理链路仍需继续验收。未修改框架或 A 工作树，未连接共享数据库、推送、合并或部署。
