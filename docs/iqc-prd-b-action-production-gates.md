# 动作生产链缺口与接通验收门槛

后续状态：B新增ActionSourceDiscovery接通后台enqueue调用与循环keyset扫描，见[发现说明](iqc-prd-b-action-source-discovery.md)。下文断点1描述6f453bc核对时基线；权限注册/人工核对/外部适配门槛仍未完成，发现代码不代表真实生产链已验收。

核对基线：B6f453bc，A已提交9397dc9；A未提交ActionQueryService/ActionExecutionController只作为协作所有权记录，未集成、未作为已完成证据。

## 当前断点（源码证据）

1. `ActionEnqueueService.enqueue`在src/main/java只有定义，没有调用者。`ActionDispatcher.tick`只发现已有ActionExecution行，因此仅启用iqc.actions.enabled不会从生产结果产生动作。内部服务/SQL测试通过不证明这一入口接通。
2. `iqc:action:enqueue`只出现在ActionEnqueueService.REQUIRED_GRANTS，资源注册监听复用MappingInfoHandler.getMappingInfo汇总实际ResourcePermission端点。当前没有该资源声明，不能假定组织已存在或默认授予任何用户。A草稿查询声明的是history:view，不能代替enqueue。
3. 现有结算覆盖每动作当前状态；完整每次尝试历史、回执恢复、人工核对、blocked恢复与失败升级尚无已提交入口。不能将UNKNOWN重置PENDING当恢复，也不能用SETTLED当成功。
4. 只存在SavedResultActionAdapter，其他八个枚举不代表有实际服务适配。独立Risk Policy与真实风险事件也不能由IqcRiskLevel枚举替代。

## 接通生产发现的实现边界

复用现有任务冻结actionOwnerBound、ProductionInputSnapshotService、ActionProductionSourceValidator、ScheduledActorAccessService和ActionEnqueueService；不能新增第二份检测结果、第二套身份或并行动作账本。

发现已提交的PIPELINE会话结果，验证任务当前execution和稳定负责人；试运行/评测不入账。来源发现与组织解析、动作适配执行均不能持有分析事务锁。只将最小来源引用交给独立enqueue短事务；入队失败保留已提交检测结果并有可观察状态。

必须确定发现进度策略。固定LIMIT扫描无动作/UNKNOWN/授权阻塞来源可能永久占据前100条，不能靠重复从头扫描声称恢复可靠。优先复用现有任务/工作项完成记录的稳定进度与可重放方式；如确需持久游标或独立记录，先记录现有能力不足、丢失窗口/多实例竞争权衡，再实现。不能凭推测先建表。

资源注册应关联真实受保护的业务入口或明确的既有治理能力，不创建无业务行为的假API；不得修改framework，除非用户明确批准。注册不等于授予，启动和发送仍默认关闭直到实际授权与验收完成。

## 必须实际证明的门槛

- 从新生产任务完成结果到动作账本创建，使用真实任务/结果及当前授权，不能手工预插ActionExecution冒充发现成功。
- 分析事务未提交/回滚不产生可发送动作；重放、重启和多实例产生同一唯一动作来源，完成动作不重发。
- 评测/试运行、旧轮结果、篡改冻结事实、身份替换、授权撤回或过期均拒绝；拒绝不改变检测结果。
- 超过100条来源且前批无匹配/阻塞时，后续合法来源仍可发现，不能饥饿。
- 组织暂时不可用和入账失败有明确可见状态/恢复路径；UNKNOWN有真实凭证核对而不是自动再次发送。
- 实际资源快照包含固定enqueue权限；未授权HTTP入口拒绝；UI显示动作实际状态和证据，不返回冻结消息或秘密配置。
- 至少实际SAVE_RESULT链覆盖无事务发送/真实产物确认；其他八类须分别证明真实协议、幂等、超时和回执，不能用模拟成功通过最终PRD。

## 现有证据与范围

当前默认后端761项通过是6f453bc完整回归证据；独立MySQL分别有结算6、工作流5、保存确认5、来源5项。它们的模拟/代理边界见各B验证文档，不能将四组拼成未经运行的完整生产链。无真实组织/模型/浏览器/外部动作发送，也未部署。

下一实现批次应先接通发现和授权，再补人工核对/完整尝试审计与真实适配；A查询草稿保留其所有权。完整IQC PRD继续，包括离线音频，排除实时语音；本文件不缩小最终验收范围。
