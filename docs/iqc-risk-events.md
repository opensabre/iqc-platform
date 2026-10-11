# 风险事件动作与风险中心实施边界

## 需求与现有能力
PRD 第12节要求 CREATE_RISK_EVENT，第24节要求风险中心；它们仍属于完整实施范围。现有 ConversationInspectionResult.riskLevel、InspectionScoring 和生产 Trace 已记录分析风险结论；风险事件是分析完成后的独立业务产物，不再运行风险检测。当前 IQC、Framework、base-sysadmin 和 Wiki 搜索没有风险事件持久模型/处理流程；已有通知是 SEND_ALERT 的候选能力，不能当风险事件代替。

## 复用与必要扩展
复用原 ActionPolicy 条件、实际生产冻结来源、动作领取/结果回执、IqcDataScope 及原结果详情。新增风险事件模型的理由仅为独立风险处理状态/审计和风险中心查询，不复制分析结果、Finding、评分或完整任务快照。事件保存 sourceResultId/actionExecutionId/inputHash 和冻结风险引用，详情从经授权的原生产结果读取证据。关联结果不可授权或损坏时拒绝，不从当前引擎重建历史。

首次风险事件对实际 action stable key 使用唯一键，返回实际持久 ID；发送服务独立事务，按既有来源锁序验证 claim、ownerBound、冻结条件和当前权限。UNKNOWN 只读查找原产物，缺失不表示可重发；同 key 不同实际内容拒绝。事件的处理状态与动作执行状态分开：完成动作仅代表真实事件已建立，不能声称风险已处置。

## 待实现与验收
风险事件实际服务/适配器、原结果入口及风险中心/处理审计尚未编码；表名及迁移版本在双方领取确认后确定。配置须明确风险类别/描述及实际生产风险来源，不能从人工自由文本伪造证据。新接口和操作权限沿组织资源注册，读取须有原结果范围，处理权限独立声明。实际迁移、幂等并发、事务回滚、完整来源/UNKNOWN 核对、真实角色和浏览器验收均待完成。

本节记录新增模型的能力缺口和取舍，不代表功能已交付，不缩减 PRD 的其余八类动作、离线音频或其它迭代。

A 已领取 V1.1.69 并编码 iqc_risk_event/原 BasePo+BaseMapper 模型：来源动作唯一键，原结果与输入摘要引用，独立处理状态及版本。LOW/MEDIUM/HIGH/CRITICAL 沿生产风险语义，NONE 不造事件。模型没有复制 Finding/任务正文，也没有替代 ActionExecution。服务/实际适配器/页面及处理审计仍待，不执行迁移、不注册虚假可执行适配器。

RiskEventConfiguration 已定义实际 v1 配置为 category/title/description 三个文本字段，分别限制稳定编码64、标题128、描述1000；不接受 riskLevel、Finding 值、证据、owner 或来源ID等自填字段。文本保留普通换行/制表，拒绝其它控制字符，必填非空；此配置校验尚未注册可执行适配器，不表示风险事件已创建。后续实际生产来源 UNKNOWN/NONE 不伪造确定风险。

RiskEventActionService 已编码实际原表写入与历史只读产物核对。创建REQUIRES_NEW/READ_COMMITTED按原生产来源锁序再Action，检查当前固定risk:create及三项生产grant/ownerBound/claim/完整冻结输入政策/条件；写前写后期限与授权再验，失败回滚。原动作唯一事件逐字段幂等且不重置人工处置状态。lookup REPEATABLE_READ无发送grant/锁/重写，历史生产证明与实际领取不可缺失。适配器注册、资源授权及风险中心尚待，不启用动作开关。
