# I5风险与动作实施

## PRD要求

PRD12要求SAVE_RESULT/UPDATE_LABEL/CREATE_RISK_EVENT/SEND_ALERT/CREATE_REVIEW/CREATE_WORK_ORDER/CREATE_COACHING_TASK/WEBHOOK/ADD_TO_EVALUATION_CANDIDATE，配置触发条件、重试、幂等键和失败处理。动作失败不能默认使已完成AI分析失效，执行记录独立保存。

## 实施前复用

检索已有IQC生产结果、DecisionRevision、标签、BusinessItemReview/LabelReview队列、样本候选，以及base-sysadmin NotificationService/NotificationSendResponse和framework EDA；没有找到可复用的IQC动作执行/Outbox。保存结果/标签/复核/样本仍调用原能力，通知沿公共通知服务，不能另建通知表。通知返回status需要分辨模拟发送和真实确认，不能把模拟成功标为真实副作用完成。工单/辅导外部系统协议未给出，先制定适配契约，不模拟实际成功。

本批是动作执行契约：来源显式绑定生产Task/EID/会话/保存结果/decision参考、冻结政策hash/actionId，引用生成稳定SHA256幂等键，重试次数不影响键，不使用线程作用域或解析临时字符串。复用原AutomaticRetryPolicy的次数/延迟/期限参数；仅确认失败且适配器声明安全重试时规划下一次，UNKNOWN永不自动重发。契约不创建表、API或网络，后续须接冻结政策、持久执行/Outbox、真实适配和权限/UI。

契约来源仍是引用，不证明Task/EID/结果确属生产；后续保存必须读取原Task/Execution/结果/发布政策并核对所有归属，EVALUATION/DEBUG不得创建副作用。Source不能从单纯字符串推断有效关系。owner允许原用户Unicode标识，禁止空白/超长/控制字符，不将中文用户排除；错误消息不返回用户载荷。尚未接入业务流程，不代表动作已执行。
