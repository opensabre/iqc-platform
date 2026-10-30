# 原分片扫描 SQL 专项自动 CI

## 缺口与复用

原 Maven verify 未选取命名为 IT 的 IsolatedMysqlSelectorShardIT；Flyway 共享工作流只检查迁移，不执行本应用 Java 事务与分页断言。已检查原 docker_publish.yml、base-k8s 的 service-database-migrations.yml/test-flyway-fresh-install.sh 和该专项的数据源保护。共享迁移 job 的临时数据库不跨 job 暴露，而现有测试明确要求 loopback:34307、空密码及特定 datadir 身份，不放松这些约束。

在原 Maven job 安装同版本 framework、执行原 verify 后，追加独立临时 MySQL 8.0 容器生命周期，复用既有 Docker/MySQL 能力和原测试，不新增生产服务、框架代码、表、API 或测试断言。每次运行使用 run ID/attempt 的容器及 /private/tmp/iqc-mysql-validation.* 目录；仅绑定宿主 127.0.0.1。TCP 实查端口和 datadir 后才运行原测试，避免初始化临时 mysqld 被误判就绪。Java 内原身份 guard 再次核验；失败不跳过。EXIT 清理容器和匿名卷，保留容器日志及原测试报告上传。

## 验证范围

CI 显式运行原 IsolatedMysqlSelectorShardIT，保留 20,137 条/21 页/137 匹配、scope/锚点、开放秒排除、缺创建时间及无效采样/游标边界断言。此项不是百万场景、真实 PDP、双 worker 或完整执行验证。百万验收按[独立协议](iqc-million-capacity-validation-protocol.md)继续；不会将新增 CI 测试数与历史分支相加。

Root 仅检查 YAML、shell 语法及 diff，不新增/执行本地应用测试。实际同头自动 CI 结果见 PR；尚未执行时不称通过。原 verify、共享 Flyway、push-only 镜像门禁和权限均保持，PR 不发布镜像或部署。
