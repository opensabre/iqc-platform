# 任务冻结字段容量回归（2026-11-01）

状态：已复现缺陷，等待应用迁移修复；不是绿色验收报告。输入为已处理会话，不涉及转写。

## 问题与现有契约

`InspectionTaskService` 允许最多 1,000 个会话，并允许最多 8 MiB UTF-8 Selector 冻结证据。
原 `iqc_inspection_task.selection_filter_json` 与 `conversation_ids_json` 却仍是 MySQL `TEXT`。
这些合法业务边界超过其 65,535 字节容量。复用原表和 Flyway；不新增持久化模型，也不改历史 baseline。

百万实际输入原运行首先暴露该问题：百万会话及百万消息插入断言均通过，导入 417,567 ms；
第一个 1,000 会话片沿 `BulkTaskMaterializationService.materializeNext` →
`InspectionTaskService.createBulkShardUsingPipelineSnapshot` → `saveSnapshotTask` 的实际 INSERT，
报 `Data too long for column 'selection_filter_json' at row 1`。
尚未到事务强杀检查点，不能称百万物化、回滚或恢复通过。

## 独立快速 SQL 回归

[IsolatedMysqlTaskSnapshotCapacityIT](../src/test/java/io/github/opensabre/iqc/result/IsolatedMysqlTaskSnapshotCapacityIT.java)
复用原隔离服务 guard、随机专用 schema、生产 Flyway 迁移与 JDBC：

| 场景 | 验证目标 | 当前固定基线实测 |
| --- | --- | --- |
| 新库 8 MiB 合法 JSON | 完整读回、UTF-8 字节长度和 JSON 有效性 | selection_filter_json 截断错误 |
| 新库 1,000 个唯一 64 字符 ID | 67,001 字节 ID JSON 保序、无截断读回 | conversation_ids_json 截断错误 |
| 原 1.1.77 升级至候选 | 历史 null/中文 JSON 原值保持、重复迁移无变化、多字节大证据保存 | 历史断言通过，较大证据仍截断 |

原运行 `60036` 终态退出 1，JUnit **3 项、0 failures、3 errors、0 skipped，17.473 秒**。
这三个 error 是被复现的生产持久化容量缺陷，不是跳过或预期异常断言包装后的“通过”。
编译原 `95570` 退出 0。没有手工 ALTER 测试表来制造绿色结果。

生产源码基线 `bbec6e016ff23a3381dcaa5f0870905bb7ff9298`；本测试 SHA256：
`28ba6ecdeec0942bf8c6ff16b96a09d587f28b021e600926ae693158417088a4`。
MySQL 8.0.34、Java 21，临时实例 `127.0.0.1:34307`，datadir
`/private/tmp/iqc-mysql-validation.tgJCSb`；原 launcher 退出时停库。

本地原证据：

- `/tmp/iqc-b-task-snapshot-capacity-red-20261101.log` 及 `.exit`。
- `target/surefire-reports/TEST-io.github.opensabre.iqc.result.IsolatedMysqlTaskSnapshotCapacityIT.xml`。
- 百万首片失败 `/tmp/iqc-b-million-materialization-20261101.log` 及 `.exit`，JUnit 1 项、1 error、611.6 秒。
- 百万失败完整日志、XML、停库日志已归档 `/private/tmp/iqc-b-completed-mysql-evidence-20261031/s1XBGI-*`。

## 合并与验证边界

此 PR 交付可复现的回归，须与修复容量契约的新版本应用迁移配套。
普通 Maven `test` 不自动执行 `*IT`；普通 CI 绿色不代表本专项修复。
在受原 `verifyIsolatedServer` 保护的新临时实例启动后，显式执行：

```sh
mvn -o -Dmaven.gitcommitid.skip=true -Dtest=IsolatedMysqlTaskSnapshotCapacityIT test
```

仅允许原隔离实例/环境 guard；不得指向共享库或读取应用凭证。
修复候选须重新证明上述三项通过、升级与重复迁移正常，再沿原生产路径重跑百万物化与恢复。
本专项直接验证真实 SQL 字段容量，不替代生产发布、物化事务、完整 worker 执行、混合负载、真实组织/PDP 或外部供应商验收。
