# B：动作集成基线完整后端回归

分支 `feature/iqc-prd-b-action-integration`，A `6fcddb4` 加 B 七项当前验收/修复及动作验证 `bda7557`。本批同步八份既有测试夹具，没有修改生产代码、API、迁移或 framework。

## 夹具变更

- 复用 B 历史 `e6b740a` 的六份测试修正，进一步按当前六参数会话执行入口同步 ReflectionTestUtils 调用。
- Task/Execution 行锁查询明确设置返回值，排队 INSERT/绑定明确返回成功行数；原并发、成功累计、停用依赖和未新增轮次断言保留。
- LLM mock 使用当前显式 ModelCallContext 参数。原无初筛路径明确匹配 null，原带初筛路径继续检查非空证据，避免将两个旧重载的调用计数混为一谈。
- 两份现有 H2 任务表各补16个当前重试/触发/预算字段；会话元数据和 executionTrace 等既有旧字段修正一并复用。不另建并行测试数据模型。
- JSON API 导入 mock 同步到现有 persistVerified，文件上传入口不变；角色规范化和批次隔离断言保留。
- 模型连接测试通过当前 callProfile 通道提供响应或异常，保留成功诊断和敏感失败细节隐藏断言。
- 无关 Spring Boot 测试关闭持久后台 worker，让夹具与 HTTP 请求控制任务执行时刻。

## 执行过程与范围

首轮默认完整 `mvn test` 实际运行 **619项，30 failures、57 errors、0 skipped，exit1**。集中失败为上述旧夹具缺口。日志 `/private/tmp/iqc-b-action-full-suite.log`。

首轮同步后针对七组运行137项，12 failures、3 errors，exit1；进一步补锁/写入返回值及区分无初筛匹配。日志 `/private/tmp/iqc-b-current-fixtures-tests.log`。剩余四组复跑日志 `/private/tmp/iqc-b-current-fixtures-refined.log`。

四组复跑122项：0 failures、29 errors。一个恢复工作项 mock 未返回成功行数，现已补齐；28项联合事务错误由本机 Snowflake 时钟回退引发，报告中回退量从1898ms递减到35ms，第一项并发错误内层同样记录时钟异常。未改变生产ID或系统时间，重新执行完整回归。

最终完整回归命令：

```bash
mvn -o -q \
  -DargLine=-javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar test
```

默认 Surefire 不执行 `*IT`。真实 MySQL 动作及新装/升级10项已由 `docs/iqc-prd-b-action-integration-validation.md` 单独证明，不能加入默认测试总数。旧分支704项含其他B历史测试，不能作为当前分支测试数或自动证明其变更已经纳入。

2026-10-09 最终完整串行回归 **Maven exit0，619项，0 failures/errors/skipped**；有效日志 `/private/tmp/iqc-b-action-full-verified.log`。当前报告排除独立IT后汇总，含联合事务49项、HTTP审核/权限7项、执行服务32项、任务服务32项。最终没有再次出现时钟回退。`git diff --check` 通过。

此回归不证明动作业务入队/worker/真实适配、外部模型调用、组织服务真实授权、浏览器或完整PRD完成。未读取共享数据库凭据、推送/合并/部署；A原工作树未修改。
