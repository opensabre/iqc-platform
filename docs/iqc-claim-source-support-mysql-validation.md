# 来源支持标注实际持久化验证（2026-10-31）

基于 PR26 `bd4b013e09718a6c0d7323c116038165dbaff7fc`，测试固定 `61ee555af6c21c49cc42e7b7ffd1ecf2127f1a46`。复用 IsolatedMysqlActionSourceIT 的冻结流水线/模型事实及 IsolatedMysqlBusinessReviewActionIT.Config 的原 BusinessItemReviewService、真实 MyBatis mapper、Spring 事务与 InnoDB，没有修改生产代码或增加存储模型。

原编译34533正常退出0；随后原实测88848正常退出0。JUnit XML实际3项，0失败、错误、跳过，28.675秒：

1. SUPPORTED、CONTRADICTED、INSUFFICIENT_SOURCE分别经过原业务申请/裁决事务。重新通过mapper读取持久JSON，检查标注，再通过findingContext触发已存投影验证和回显。相同裁决重试整行不变，不同标注原因拒绝覆盖，仍只有一轮；原机器结果和任务整行不变。
2. 不同owner及group不能读取上下文或裁决，持久待审行不变；原owner随后可正常完成。
3. 专用测试触发器拒绝UPDATE后，事务保留原待审整行；移除触发器后同一裁决可完成。

环境：Java21、MySQL8.0.34，全新datadir `/private/tmp/iqc-mysql-validation.ULvWGO`，仅127.0.0.1:34307，原端口/datadir身份保护生效。原Flyway实际应用58迁移至1.1.77，schema `iqc_worker_state_validation`。退出trap关闭自有服务，server.log确认04:08:43Z Shutdown complete。

原日志 `/tmp/iqc-b-claim-source-support-mysql-20261031.log` 及exit文件；JUnit XML在本工作树target/surefire-reports。启动器 `/private/tmp/iqc-b-claim-source-support-mysql-run.sh` 复用百万扫描启动器的新目录、loopback、agent及退出关闭机制，仅将测试目标改为IsolatedMysqlClaimSourceSupportIT。执行使用Java21离线test-compile及如下已编译测试入口：

```sh
mvn -o -q -Dmaven.gitcommitid.skip=true -Dtest=IsolatedMysqlClaimSourceSupportIT \
  '-DargLine=-Xmx1536m -javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar' surefire:test
```

复现前必须启动新的专用MySQL实例并设置IQC_MYSQL_VALIDATION_DIR；agent路径按机器缓存调整，不得连接共享库。本IT不在默认单元测试命令内，普通CI不能代替这次隔离实测。

边界：scheduled actor和模型输出均为受控夹具，不含真实登录/PDP、远程撤权或供应商调用；范围测试证明应用数据范围拒绝，不证明真实组织职责授权。HTTP完整会话/PII矩阵仍是已有独立回归。本次不验收UI、Gold/报告、语义幻觉分母或百万实际物化恢复及完整执行。
