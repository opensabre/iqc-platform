# 来源支持报告统计验证

基于后端 PR28 固定 `3bea2f9601338b02fa79623dcdc6a05b7b1f3306`，仅新增测试与本文档，复用 `BusinessFindingDistributionTest` 夹具并调用真实 `BusinessQualityReportService` 聚合。

## 专项范围

- 两个实际结果内按原始 KNOWN LLM 观察计数，同一 raw ID 在不同结果内独立计数；规则与非 KNOWN 观察不进分母。
- SUPPORTED、CONTRADICTED、INSUFFICIENT_SOURCE 分开计数，保留未标注；验证 PARTIAL、UNANNOTATED、COMPLETE、UNMEASURED 和六位覆盖率。
- 缺结果与零声明不产生虚假零幻觉率，零分母覆盖率为 null；待完成新一轮不替代上一轮已完成标注。
- 重复原观察身份、外来目标及重复标注拒绝生成统计；报告 JSON 不泄漏模型原因、人工原因、输入哈希和消息。
- 同跑原分布和当前授权回归，覆盖默认旧协议不额外读取敏感数据、当前三权限在来源 IO 前拒绝等行为。

## 实测

2026-10-31，Java21，离线 Maven，显式 Byte Buddy agent，1.5GiB 测试堆：

`mvn -o -q -Dmaven.gitcommitid.skip=true -Dtest=ClaimSourceSupportReportTest,BusinessFindingDistributionTest,BusinessFindingCurrentAccessTest '-DargLine=-Xmx1536m -javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar' test`

原运行 77977 terminal0；JUnit XML：新增7项、原分布11项、当前授权13项，共31项，0失败/错误/跳过。原日志 `/tmp/iqc-b-claim-report-tests-final-20261031.log` 及 `.exit`。首次运行39358因测试引用不存在的 reason() 访问器编译失败，改为实际 explanation() 后以上原命令运行通过，未改业务源码。

## 边界

来源验证、已完成投影和组织授权为受控 mock；重复/外来标注用刻意异常投影验证报告防线。没有把该测试称为真实 MySQL 与报告组合、真实登录/PDP/远程撤权、Gold 独立审核或完整语义幻觉评测。轮次/冻结分组的全部组合、极端 long 边界及真实 SQL 组合仍需独立验证。普通 CI 待本 PR 自动运行，不能由本机31项或父分支绿色推断。
