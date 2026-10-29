# B：UNKNOWN 人工持久产物核对

基线 d5cb4a3，仅 B 独立工作树。新增原账本人工核对入口，复用 SAVE_RESULT、业务复核及评测候选的实际持久产物与原请求指纹，不新增数据库表或迁移。

## 行为与边界

- POST `/api/iqc/conversation-results/{id}/actions/{actionExecutionId}/reconcile`，独立资源 `iqc:action:reconcile`，原 Audit/RateLimit。请求仅 version/reason；历史查询增加 version。
- 当前稳定身份与冻结 actionOwnerBound 必须匹配原负责人，结果/task/EID/conversation 归属必须匹配；查看全局/组结果不能代替创建者核对。
- 只读适配边界不调用 execute/request/create。原业务服务计算原稳定幂等ID和指纹，缺产物继续 UNKNOWN；历史 EID 和归档后来变化不重建产物。
- 成功仅以真实产物引用、观察 version 和原 claimToken CAS UNKNOWN 到 COMPLETE；保留尝试和 claim，更新当前操作人，绝不返回发送队列。缺确认/观察失效分别返回 UNCONFIRMED/STALE_OBSERVATION。
- 动作开关关闭不妨碍确认历史产物。没有自动授权角色、原发送 grant 复用或模拟成功；后台/框架/网关/A 工作树未改。

## 验证

- 编译会话 10183 exit0；定向核对服务会话 16881 exit0，8 项通过，验证越权、稳定用户ID变化、外来回执、无产物、UNKNOWN、失效观察和不调用 execute。
- 初轮隔离 MySQL 会话 98040 exit0：SAVE_RESULT 6、实际业务复核 7、实际候选 8、原结算 7，共28项零失败/错误/跳过。产物全行对比不变；EID或归档变化后可确认第一份真实产物；无产物不补写；迟到worker与重复CAS拒绝。
- 最终并发结算会话 8097 exit0，结算8项通过（包含上述7项及新增双线程CAS一个赢家）。四类SQL独立用例共29项；非同一运行批次。
- 完整默认回归会话 33579 exit0：93份有效非IT报告781项，失败/错误/跳过均0，全部刷新2026-10-10 19:27:02..19:30:56。排除已删除 ActionSourceDiscoveryTest 的旧报告。
- 日志 `/private/tmp/iqc-b-reconcile-compile.log`、`iqc-b-reconcile-unit.log`、`iqc-b-reconcile-mysql.log`、`iqc-b-reconcile-full.log`、`iqc-b-reconcile-concurrent-mysql.log`。
- code-review 独立代码审查读取18文件，0项发现，COMMENT（缺LSP独立诊断）。架构子代理因额度限制失败，independent review unavailable；本批不提供可合并批准，也未合入A/推送/部署。

## 仍未证明或完成

真实HTTP登录/组织授权链、角色受控种子迁移和前端操作入口尚未验收。审计注解不等于完整尝试历史；阻塞恢复、远端适配恢复、其他动作、Risk Policy、标签持久化、二审与完整非实时语音PRD仍未完成。现有模型/发布查询继续使用隔离fixture，未连接共享组织或模型网络；只读产物与SQL CAS分别实测，不宣称完整生产调度链已验收。
