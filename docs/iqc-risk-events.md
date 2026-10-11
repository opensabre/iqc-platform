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

CREATE_RISK_EVENT v1 实际 RiskEventActionAdapter 已注册原精确版本 registry，requiredResources固定iqc:risk:create；配置复用RiskEventConfiguration，ready只接受实际LOW/MEDIUM/HIGH/CRITICAL，不把UNKNOWN/NONE伪造成已确认风险。execute调用原服务代理提交再返回真实事件ID；任何无法确认异常返回UNKNOWN，reconcile只读持久产物。权限未迁移前操作仍被原授权围栏拒绝；DDL69和风险中心尚未真实验收。动作默认开关不启用。

结果风险列表复用原 ConversationInspectionResult/Task ownerGroup 数据范围，不开放第二套授权范围；新增只读原结果风险接口用于详情/中心组合。返回事件元数据前须验证实际历史生产来源、原风险动作完整冻结输入/政策/领取、配置与已保存事件一致，不直接信任一条孤立事件行。输出不包含claim token或完整冻结正文。

风险创建权限复用已有 ResourceRegistrationListener 的 BACKGROUND 声明模式，固定代码risk:create、内部能力引用/internal/iqc/risk-events/create、实际RiskEventActionService.create handler；该引用不是HTTP入口。原注册快照保留资源ACTIVE，角色授权另由组织迁移控制，默认不自动授角色。

处置审计能力缺口：Framework AuditAspect发布治理事件，DefaultAuditEventHandler调用sysadmin.saveAuditLog且失败只warn，不能与IQC状态事务原子提交。保留原Audit，A领取V1.1.70新增风险专属版本事件，仅保存状态前后/原因/操作人/实际UTC，不复制来源结果或治理审计模型。事件写入失败须撤销处置CAS，版本唯一，不可改删；真实SQL/服务事务验收待。框架源代码不修改。

RiskHandlingService 已编码独立事务处置：原历史查询证明/数据范围先于事件锁，观察版本字符串精确解析、MAX拒绝；OPEN可进入处置/解决/排除，处置中可解决/排除，终态仅显式重开OPEN。CAS成功同事务append原因/实际操作人/UTC，零行或异常回滚，竞争败者无日志。不改机器结果/冻结事件元数据。公开权限与入口尚未接入，真实SQL并发回滚验收待。

风险处置POST /api/iqc/conversation-results/{id}/risk-events/{riskEventId}/handle接入原risk:handle资源/治理Audit/独立限流，请求version字符串/status/reason，响应HANDLED或STALE_OBSERVATION，不返回或更新机器结果。旧观察不自动重试；状态成功及原因由真实服务代理事务统一提交，权限迁移及实际HTTP/SQL验收待。

风险处置历史GET /api/iqc/conversation-results/{id}/risk-events/{riskEventId}/history使用risk:history:view，复用实际事件查询生产证明与原结果scope；单页50+1/version字符串游标，REPEATABLE_READ。核对当前版本/状态与首事件、倒序连续版本/前后状态链，缺失或损坏不伪造空历史。只返回真实状态/原因/actor/UTC；权限/历史页面与实际SQL验收待。

历史分页额外验证首条版本与游标实际边界一致、末页必须连续回到版本1/初始OPEN，空页不能掩盖未到起点的缺失记录；超出实际当前版本的游标拒绝。该批首次编译通过后加强，最终编译/实际SQL验收仍待。
