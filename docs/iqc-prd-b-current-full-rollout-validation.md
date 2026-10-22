# 当前灰度组合默认全套验证

2026-10-20，在后端提交 `3625fcc`（前置 `6663425`、`6f7b725`、`f782ba5`、`edc2819`、`82c2ab7`）运行离线 Maven 默认 `test`，使用既有 ByteBuddy javaagent。原进程2982 terminal exit0；日志 `/tmp/iqc-b-current-full-rollout.log`。测试时间为07:30:51至约07:36（Asia/Shanghai）。

按本次日志创建时间筛选 Surefire XML，179份fresh报告共1414项，failures/errors/skipped均0。4份旧报告为IsolatedMysqlCallDataIT、IsolatedMysqlMigrationIT、IsolatedMysqlProductionRetryIT及IsolatedMysqlRolloutRevisionIT，均未计入。默认Surefire不包含这些独立IT，本次不证明MySQL事务或锁行为。

没有本轮新dump/dumpstream，没有Surefire强制退出或30秒停机警告；日志记录Tomcat graceful shutdown complete。测试期间未修改源码或重新启动Maven。已有测试关闭注册重试等待的配置在当前默认全套下未出现先前停机问题。

本轮包括灰度策略/修订/任务重放/原始版本证据/缺守卫接线及执行入口的新回归，也复验当前默认套件的原功能。真实runner、AssetService旧构造、StateService串联专项已单独通过48项，见iqc-prd-b-rollout-task-validation.md；这些专项与全套有重叠，不相加作为独立测试数。

空测试库定时任务缺表及故障注入路径的异常日志不作为成功证据；最终结论依据Maven退出码和本轮fresh XML。仅本机临时HTTP监听获准，不访问共享数据库、推送或部署。

本证据限B当前提交组合，不覆盖A未集成的WEIGHTED工作。真实DB并发/CAS/锁/RR、完整worker与异步代理、真实角色/供应商、浏览器联调、容量及原PRD所有I1–I7要求仍需逐项验收。输入为已处理会话，不包含语音转换、ASR或转写生成。
