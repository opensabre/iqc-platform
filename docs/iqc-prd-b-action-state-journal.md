# 动作事务状态日志

V1.1.64 在原 iqc_action_execution 旁增加从属 iqc_action_execution_event，主键是动作 ID + 账本版本。它不参与发现或发送，不改变来源结果、冻结输入和重试策略。

复用调查：原 ActionExecutionMapper 的全部状态变更均通过递增版本的 CAS；ActionClaimService、ActionSettlementService、ActionDispatchStateService 持有本地事务，而人工核对的单条 CAS 采用数据库自动提交。框架 DefaultAuditEventHandler 通过异步治理事件转发且捕获客户端异常，不能保证状态和审计同时提交。因此选用原 InnoDB 事务内的 AFTER INSERT/UPDATE 触发器，避免依赖人工入口注解或遗漏某条后台路径。不修改框架。

每条日志保存前后版本、状态、尝试次数、claim token、领取/结算/下次检查时间、回执分类、有界产物/证据引用、错误码、操作人和数据库 UTC 记录时间。没有原文、输入快照、策略配置或身份凭据。初次插入是实际状态的初始观察，不代表已发送；重试领取仍只是取得尝试资格。日志中的 claim token 仅用于数据库追溯，后续面向用户的读取不得暴露它。

日志写入和账本写入共享事务；日志失败则领取失败，不能把该次未提交的 claim 交给发送器。状态元数据变动必须递增一个版本，重复/败者 CAS 不新增事件，回滚同时撤销事件。输入快照异常仍由原来源/冻结校验拒绝，单独改动快照不会被误记成发送尝试。

升级不回填运行数据，已有行第一次变更记录当时可观察的 OLD/NEW 状态，不能还原升级前已覆盖的回执。日志不是通用不可篡改审计：拥有直接数据库写权限者仍能修改或删除日志；应用没有日志写入/删除接口。没有外键级联删除，以免清理账本时自动销毁历史；生产账本删除入口尚不存在，未来清理需明确保留策略和幂等 ID 不复用规则。隔离测试清理日志仅为固定夹具 ID 重用。

迁移身份需要 CREATE TRIGGER，并在部署后持续具备触发器定义者对日志表的 INSERT 权限；本地 root 验证不证明生产账号已具备权限。人工核对原因继续通过原 @Audit 记录，本日志不复制原因；业务状态日志与人工入口审计分别保持真实含义。此批没有新增历史查询 API 或 UI，也没有验证真实组织/HTTP/远程发送链。

## 本地验证证据

隔离服务器 MySQL 8.0.34，127.0.0.1:34307，数据目录 /private/tmp/iqc-mysql-validation.FVGdkd；每条 IT 在写入前核验实例身份，不使用应用或集群凭据。

- 首轮 Maven 57978 exit 0：Migration 3、ActionExecution 10、Workflow 5、Settlement 8，共 26 项，失败/错误/跳过均为 0。覆盖新安装、1.1.31/1.1.44 升级、validate/repeat、两个并发 claim 一个日志赢家、回滚和日志故障反向回滚、原定结算与人工核对并发边界。
- 单独 1.1.63 有运行行的升级：57999 exit 0，1 项；新迁移恰好 1 份，升级后的日志为空，首次新变更才保存旧回执和新 claim，validate/repeat 不新增迁移。临时升级 schema 在 finally 删除。
- 最终新增延后/阻塞用例后 ActionExecution 报告 11 项全部通过，2026-10-11 00:35:07 刷新；16891 后续查询返回句柄不存在，因此以实际 XML 结论为据，不额外声称本轮 Maven 最终退出码。三轮共 28 个不同 SQL 用例，并非一次 28 项运行。日志 /private/tmp/iqc-b-action-journal-{mysql,upgrade,final}.log。
- 共用迁移布局检查在独立 base-k8s B 树中扩展显式 DELIMITER / CREATE TRIGGER / END 格式识别，触发器定义不改变顶层 DML 禁令；4 组 unittest、bash -n 和此后端全 migration 目录检查通过。该扩展是本迁移布局检查的配套依赖，旧检查器仍误报触发器正文。

本批没有 Java 生产逻辑变更，仅增加 DDL 和实际 SQL 验收，因此不重复已有 781 项默认回归。新 DDL 使用 MySQL 独立 vendor 目录，baseline/history 未变，无运行数据回填。触发器权限仅在本地 root 环境验收，真实部署角色未验。

code-review 技能要求代码和架构两条独立审查：本次 agent 01a126a8-4d2c-72c0-a968-d1e755f71d12 / 01a126a8-566a-7be1-9c4f-e9d3300b73fd 均因 usage limit errored，independent review unavailable。没有审查通过或可合并批准；本地提交不代替合并门禁。整体非实时语音 PRD 继续，日志查询/UI、人工原因完整持久记录、阻塞恢复和真实生产动作链仍待后续。
