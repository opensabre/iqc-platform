# 灰度策略修订规则与数据库约束验证

## 基线和服务专项

基于 B `f50c70f`，包含 A 已提交 `99dcd2e`（本分支 `a8927d9`）的修订服务与 V1.1.74；不混入 A 正在开发或后来提交的灰度任务物化/worker增量。

`PipelineRolloutRevisionBehaviorTest` 使用真实 service、policy、comparator、JSON/hash，模拟 PipelineAssetService、IqcDataScope 和 mapper存储。9项覆盖首次 ACTIVATE、EXPAND/REDUCE/ROLLBACK/RESUME/REPLACE、历史前hash链接及旧行不变、CAS冲突在源验证/写入之前拒绝、重复策略拒绝、首次回退和更改比例/盐的伪回退拒绝、无效修订/跨流水线在锁前拒绝、损坏历史证据阻止读取和追加、历史规范范围/分页及不重新检查执行依赖、源校验失败不写入。

原21443终态0，`/tmp/iqc-b-rollout-revisions.log`；本轮XML Revision9+Policy8=17，0 failures/errors/skipped。该专项的mapper是模拟存储，不证明SQL并发/CAS串行锁、数据库回滚或真实角色。没有修改生产服务。

## 隔离 MySQL 验证入口

`IsolatedMysqlRolloutRevisionIT` 复用已有 `IsolatedMysqlMigrationIT.verifyIsolatedServer`，执行前要求环境目录匹配 `/private/tmp/iqc-mysql-validation.*`，并从实际连接核对datadir及端口34307。只在这个临时服务器创建随机 `iqc_rollout_*` schema，按正式Flyway目录迁移，validate后再次migrate无增量，结束删除该随机schema。原MigrationIT末版本断言从1.1.73更新到已集成1.1.74；不修改旧迁移。

5项仅针对正式表：UPDATE/DELETE（含无变化UPDATE）触发器SQLSTATE45000且原行不变；同流水线修订唯一、不同大小写资产不混淆；正修订号及有效JSON检查；事务rollback撤销insert；两个独立连接竞争同资产/修订，恰一成功另一1062且最终一行。它不调用生产修订service，不证明生产资产FOR UPDATE锁或外层RR快照行为；这些仍需独立IT。

本轮runner为 `/tmp/iqc-rollout-mysql-validation.sh`，实际日志 `/tmp/iqc-b-rollout-mysql.log`，服务器目录 `/private/tmp/iqc-mysql-validation.Fv4bCZ`；终态以随后记录为准。无共享数据库、部署、推送或framework修改，数据源仍为已处理会话。

## 隔离执行终态

原61165 terminal exit0，`/tmp/iqc-b-rollout-mysql.log`。本轮XML MigrationIT5+RolloutRevisionIT5=10，0 failures/errors/skipped。随机schema实际应用55个迁移到1.1.74，validate75个迁移文件，再migrate0。原迁移IT同时覆盖新库与历史升级路径，其末版本已核对1.1.74。

服务规则专项17与SQL10是不同验证层，不合称一次27项完整端到端。随机 `iqc_rollout_7e0d00346a6e` 已删除（对应datadir子目录不存在），服务器日志明确 Shutdown complete，mysql.pid/mysql.sock均不存在。临时datadir保留日志用于审计；未访问共享数据库。

后续：生产service的资产锁/CAS真实并发、外层REPEATABLE_READ旧快照、实际灰度任务幂等/冻结/回退、默认MVC与真实授权仍缺证据，不能由本轮直接SQL唯一竞争替代。

## 2026-10-20 真实Spring事务与资产锁专项

新增IsolatedMysqlRolloutServiceIT，真实Spring代理PipelineRolloutService、PipelineAssetService原资产行锁、MyBatis两Mapper及正式Flyway迁移；仅发布来源解析和scope使用测试桩。跨包复用既有IsolatedMysqlMigrationIT.verifyIsolatedServer，将测试类和该方法设为public，没有复制服务器校验或改生产源。

原6565 terminal exit0，日志 `/tmp/iqc-b-rollout-service-mysql.log`；3项fresh XML tests=3/failures=0/errors=0/skipped=0：

- 两连接并发expectedRevision=0，恰一成功，另一收到“灰度策略修订已变化，请重新读取”的领域拒绝，最终恰一修订；不是用唯一约束SQLException冒充CAS。
- 外层REPEATABLE_READ事务先读修订1，另一连接提交修订2；外层一致性读仍为1，而append锁定读拒绝expectedRevision=1，最终修订2与原修订1摘要不变。
- 外层事务主动回滚撤销新修订，随后相同expectedRevision=0能够成功生成修订1。

MySQL8.0.34，临时目录 `/private/tmp/iqc-mysql-validation.CURCFa`；随机schema `iqc_rollout_service_a533b3f2d06f` finally删除且对应目录不存在，server日志Shutdown complete，pid/socket不存在。仅本地34307，不访问共享数据库。正式迁移至1.1.74并validate，再migrate=0。

本次只证明上述服务/锁/CAS/RR/回滚边界，不证明真实发布来源验证、真实授权、灰度任务物化唯一性/整笔回滚或完整worker。默认全套1414项为此前3625fcc基线，不将新IT或不同轮次相加称全量E2E。完整I1–I7仍进行中。
