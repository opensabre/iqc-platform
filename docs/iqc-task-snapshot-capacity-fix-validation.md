# 任务冻结字段容量修复验证（2026-11-01）

新增 `V1.1.78__ddl_expand_task_selection_snapshots.sql`，仅将原任务表的
`selection_filter_json` 和 `conversation_ids_json` 从 TEXT 扩至 MEDIUMTEXT，继续允许 NULL。
现有 8 MiB UTF-8 Selector 证据、最多 1,000 个会话及 64 字符会话 ID 契约保持。
不新增表、API、框架组件或第二套快照，不修改历史迁移或现有业务 Java 文件。

## 从原始失败到实际通过

[PR33 的原失败报告](iqc-task-snapshot-capacity-validation.md)保持不变：原生产路径在百万实际会话、
百万实际消息导入后，首个 1,000 会话子任务因 TEXT 容量不足失败。随后三个独立 SQL 回归均复现截断。

本次使用**同一测试源码**，SHA256
`28ba6ecdeec0942bf8c6ff16b96a09d587f28b021e600926ae693158417088a4`；只增加正式应用迁移，
没有手工 ALTER 验证表、降低上限或修改断言。原运行 `18012` 退出 **0**，JUnit **3 项、
0 failures、0 errors、0 skipped，14.780 秒**：

- 新库准确 8 MiB 合法 JSON 全文、UTF-8 字节长度及 JSON 有效性读回通过。
- 新库 1,000 个唯一 64 字符 ID 的 67,001 字节 JSON 完整、保序读回通过。
- 从 1.1.77 升级后，历史 NULL、中文 ID JSON 和旧选择 JSON 原值保持，多字节大证据读回通过。
- 新库实际应用 59 个迁移至 1.1.78，校验 79 个迁移；升级路径先应用 58 个至 1.1.77，
  再只应用本次 1 个至 1.1.78。两条路径的第二轮 migrate 均无变化。

环境：MySQL 8.0.34、Java 21，专用 loopback 34307，datadir
`/private/tmp/iqc-mysql-validation.hWl3v3`，原退出 trap 停库。命令沿原专用实例 guard，
执行完整 Maven test 生命周期，目标 `-Dtest=IsolatedMysqlTaskSnapshotCapacityIT`，
本地 ByteBuddy javaagent 与原夹具一致。

本地日志 `/tmp/iqc-b-task-snapshot-capacity-green-20261101.log` 及 `.exit=0`；
原 JUnit XML 位于 `target/surefire-reports/TEST-io.github.opensabre.iqc.result.IsolatedMysqlTaskSnapshotCapacityIT.xml`。
新增迁移源码与实际运行 `target/classes` 资源 SHA256 一致：
`5b7c946672aca334793e5f3905940a5cf77c718ff796e9097b6b9e3799f64c92`。

## 其它检查与边界

`git diff --check` 通过。工作区本地旧版 `base-k8s/scripts/validate-flyway-migrations.sh` 全树检查
退出 1：它将原 V1.1.64 触发器定义内 INSERT 判断为 DDL 文件的 DML；该历史文件本次未修改。
正式 PR 的共享迁移 CI 结果单独核验，不把这一工具失败称为通过，也不为此修改历史迁移。

此结果只证明容量缺陷与新装/升级数据兼容已修复。普通 CI 不自动执行 opt-in IT。
完整百万物化、未提交事务回滚、早中尾进程强杀恢复、完整 worker 执行和混合负载仍需独立实测。
真实大表 ALTER 的锁等待、耗时及磁盘开销没有由小规模升级测试测定；本次没有执行共享库迁移或部署。
