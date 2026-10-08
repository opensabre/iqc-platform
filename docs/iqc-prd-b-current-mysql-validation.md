# B：当前提交的隔离 MySQL 验收

基线：A 已提交 `4020981`，B 分支 `feature/iqc-prd-b-current-mysql-validation`。不含 A 未提交动作执行表和 V1.1.62，不改变 A 工作树。

## 复用与范围

沿用 `IsolatedMysqlMigrationIT`、应用正式 Flyway 迁移、已有 MySQL 8.0.34；没有新建迁移工具或读取共享应用数据库凭据。每条测试在建库前核对 `@@port=34307` 与 `@@datadir` 完全匹配 `IQC_MYSQL_VALIDATION_DIR`，只允许 `/private/tmp/iqc-mysql-validation.*`。

测试覆盖：

- 新库完整迁移、Flyway 校验、再次 migrate 零执行；保留原中文大载荷读回和联合更新回滚断言。
- 从 `1.1.31` 与 `1.1.44` 建立历史库后升级，执行数量匹配迁移前的 pending 列表，保存的方案版本内容不变，基线历史仍仅一条；再次迁移零执行。
- 三个路径均检查截至 `1.1.61` 的成功迁移历史，以及事件请求、周期归属、预算等待/核销/承诺字段类型与默认值和触发唯一索引。

已有测试把升级执行数量固定为一次；当前已新增多个迁移，故改为校验 pending 列表和保存的数据，不改历史 SQL 或生产源码。

## 环境与结果

本次专属目录 `/private/tmp/iqc-mysql-validation.lmgLZv`，MySQL 仅监听 `127.0.0.1:34307`，socket/pid/log 同属该目录。受限初始化在 Shared_spin_lock 中 signal 11；获准执行后初始化成功，独立服务器成功启动。

迁移布局检查已通过。迁移测试 3 项通过，0 failures / 0 errors / 0 skipped，Maven 退出 0。全新库应用 42 次迁移（B1.1.20 加 V1.1.21–61），1.1.31 历史库追加 30 次、1.1.44 历史库追加 17 次，均升级到 1.1.61；三条路径再次 migrate 均为零执行。日志 `/private/tmp/iqc-b-current-mysql-migrations.log`。

本批不证明真实模型/组织身份、完整质检生产链路、worker 跨进程恢复、浏览器或整个 PRD 验收。

## Worker 状态的真实事务验收

追加 `IsolatedMysqlTaskExecutionStateIT`，复用同一隔离服务器，在专属 `iqc_worker_state_validation` 库执行正式迁移。配置只加载原 Task/Execution Mapper、SqlSessionTemplate、DataSourceTransactionManager 和 TaskExecutionStateService 的 Spring 事务代理，不启动调度器/模型/组织客户端。

覆盖两个独立线程/数据库事务竞争一次领取（恰好一个成功）、旧 EID 无法领取/完成/失败当前轮次、Execution 写入真实数据库错误时回滚 Task 领取、结束状态及进度、取消优先和终态不被晚到回调覆盖。数据库错误由此临时库的 BEFORE UPDATE 触发器注入，不用 Mapper 返回值模拟。

5 项通过，0 failures / 0 errors / 0 skipped，Maven 退出 0，日志 `/private/tmp/iqc-b-current-mysql-worker-state.log`。首次新增测试编译发现旧 MyBatis 包名不可用，按本地 3.5.17 JAR 中实际 `com.baomidou.mybatisplus.spring` 包名修正后通过；不涉及生产依赖变更。线程竞争不等于完整 worker 跨进程崩溃恢复；预算等待与远端模型副作用不在本次范围。

## 复现

在全新的专属数据目录启动 MySQL 8.0，配置相同 loopback 端口和目录 socket。数据库名为固定验收专用名称，因此每轮需使用全新 datadir，不得对既有应用库复跑。本次两条命令串行执行，均退出 0：

```sh
IQC_MYSQL_VALIDATION_DIR=/private/tmp/iqc-mysql-validation.lmgLZv mvn -o -q -Dtest=IsolatedMysqlMigrationIT -DargLine=-javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar test
IQC_MYSQL_VALIDATION_DIR=/private/tmp/iqc-mysql-validation.lmgLZv mvn -o -q -Dtest=IsolatedMysqlTaskExecutionStateIT -DargLine=-javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar test
```

测试使用 IT 命名，普通 `mvn test` 不会自动连接本地专用 MySQL。共 8 项真实数据库测试，本批没有对当前 A 基线执行后端全量测试，不能沿用上批较旧基线的 704 项结论。

本次验收后通过专属 socket 正常关闭临时 MySQL；mysqladmin 与服务器进程均退出 0，server.log 确认 Shutdown complete。数据目录和日志保留供复核，未接触共享数据库。
