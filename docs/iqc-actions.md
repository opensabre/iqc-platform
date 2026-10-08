# I5风险与动作实施

## PRD要求

PRD12要求SAVE_RESULT/UPDATE_LABEL/CREATE_RISK_EVENT/SEND_ALERT/CREATE_REVIEW/CREATE_WORK_ORDER/CREATE_COACHING_TASK/WEBHOOK/ADD_TO_EVALUATION_CANDIDATE，配置触发条件、重试、幂等键和失败处理。动作失败不能默认使已完成AI分析失效，执行记录独立保存。

## 实施前复用

检索已有IQC生产结果、DecisionRevision、标签、BusinessItemReview/LabelReview队列、样本候选，以及base-sysadmin NotificationService/NotificationSendResponse和framework EDA；没有找到可复用的IQC动作执行/Outbox。保存结果/标签/复核/样本仍调用原能力，通知沿公共通知服务，不能另建通知表。通知返回status需要分辨模拟发送和真实确认，不能把模拟成功标为真实副作用完成。工单/辅导外部系统协议未给出，先制定适配契约，不模拟实际成功。

本批是动作执行契约：来源显式绑定生产Task/EID/会话/保存结果/decision参考、冻结政策hash/actionId，引用生成稳定SHA256幂等键，重试次数不影响键，不使用线程作用域或解析临时字符串。复用原AutomaticRetryPolicy的次数/延迟/期限参数；仅确认失败且适配器声明安全重试时规划下一次，UNKNOWN永不自动重发。契约不创建表、API或网络，后续须接冻结政策、持久执行/Outbox、真实适配和权限/UI。

契约来源仍是引用，不证明Task/EID/结果确属生产；后续保存必须读取原Task/Execution/结果/发布政策并核对所有归属，EVALUATION/DEBUG不得创建副作用。Source不能从单纯字符串推断有效关系。owner允许原用户Unicode标识，禁止空白/超长/控制字符，不将中文用户排除；错误消息不返回用户载荷。尚未接入业务流程，不代表动作已执行。

## 持久账本实施前缺口
现有TaskItem属于检测进度，NotificationRecord属于公共渠道发送，两者不能保存跨复核/风险/Webhook的独立动作状态；已检索无通用Outbox可用。按PRD20新增一份iqc_action_execution，每行同时承担动作记录和待执行队列，不另建重复Outbox/检测结果表。稳定幂等键唯一，冻结input/policy及其摘要，显式Task/EID/结果/decision归属引用，claimToken和version作为未来并发围栏，nextAttemptAtUtc支持持久重试索引，未知状态待核对而不自动回队。仅迁移/映射，不填模拟成功，不运行网络；写入时实际来源与已发布政策核验、claim/settle事务及worker尚待实现。

## 并发状态 SQL（尚未接入 worker）
Mapper 增加固定最多100条的到期发现、version 条件领取、claimToken+version 条件结算和失联隔离。发现本身不授予执行权；只有领取更新一行才取得本轮权限。领取原子增加attemptCount/version；结算限定RUNNING、结果/下一状态组合与未来重试时间，旧执行者或重复回写返回零行。失联只能进入REQUIRES_RECONCILIATION/UNKNOWN，保留原claim供审计，不能重新发送。重试再次领取产生新token。显式UTC参数和system审计字段不依赖线程用户上下文。

这些是仓储原语，不是可调用业务API；上层仍须核验持久生产来源/冻结政策、生成随机token、校验存储时间和引用范围、以短事务提交领取后再执行网络、按适配器证据调用原plan规划结算。CAS只能保护本地状态，不能证明远端幂等或阻止已发出的远端请求。未接业务入队、调度、适配器或人工核对入口，也未执行SQL/数据库并发验收。
