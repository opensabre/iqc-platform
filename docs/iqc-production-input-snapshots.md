# 生产流水线输入冻结（I4 事件/API前置）

## 实施前复用与缺口

Task/TaskExecution/TaskItem、Conversation/Message归档、Pipeline执行器已有；TaskItem只保存消息ID，生产执行/重试会重新查询当前正文和完整上下文，不具备输入修订身份。评测DatasetSnapshot/GoldConversation已演示完整消息/上下文不可变摘要，但金标审批和评测隔离不能充当生产输入库。

本批不新增归档、任务或输入主表，扩展原Task.ruleSnapshotJson的productionInputs冻结片段。复用归档查询、角色规范化、精确JSON摘要、原Task/EID/worker/Pipeline evaluator；新增片段只表达生产输入，没有金标事实或审批状态。旧任务无字段时沿用原执行行为。后续事件/API将引用本片段的内容摘要识别输入修订，补录正文应新建明确输入而不是悄悄覆盖已有触发。

## 协议

新创建的正式/试跑/周期Pipeline任务在同一物化事务冻结完整会话输入及元数据；已存在的幂等任务优先返回首次快照，不重新读正文。NO_DATA保存空输入。输入先通过原数据范围检查；可见性继续受Pipeline/Selector/周期与会话来源交集约束。

productionInputs包含schemaVersion、conversations和contentHash。每个会话包括归档ID/来源fingerprint、businessContext、完整有序消息；每条消息冻结ID、sequence、canonical role、content、relativeTime。摘要采用selector-decimal-sha256-v1精确数值规范，区别于Pipeline执行inputHash（执行输入树的SHA256）；两者各校验各自边界。

每任务最多1000会话、100000消息，每会话最多10000消息，片段最多16MiB。超限整体拒绝，不截断输入。队列/重试/恢复/执行校验协议与摘要、任务会话清单和总消息数，冻结检查点必须属于完整输入；生产输出提交再次验证冻结输入及执行inputHash，不采用当前归档正文。评测仍走EVALUATION及原隔离结果表。

本批仅编码与编译，不新增或运行测试、迁移、真实模型/浏览器验收或部署。事件持久接收/恢复、外部内容接入、自动重试/速率/成本预算仍继续。

任务目录与周期实例目录通过既有MyBatis列投影排除ruleSnapshotJson，避免每页重复传输完整冻结正文；有权限的任务详情继续返回快照。前端已按详情接口读取规则/Agent快照，目录不依赖该大字段。

## 完整检查点及继承结果

新冻结生产任务的每轮Execution保存完整冻结消息TaskItem清单，执行前及提交前严格核对ID集合、唯一性、会话/Task/EID归属和合法状态；缺少一条也不能按“此前已完成”计数。重试/恢复复制先前SUCCEEDED/CANCELLED状态和真实原resultId，其余设PENDING；只对含未完成检查点的会话调用模型，仍传完整原会话上下文。执行计数直接聚合完整清单，不再以总数减检查点数推测继承成功。

继承检查点不是新的检测产物。任务级最新canonical和message结果查询沿用跨EID实际结果；按显式EID过滤的报告只呈现该轮真正生成的会话产物，成功继承项仍指原EID。不会伪造该轮重新测得的时延/调用量/成本。旧任务及评测继续原subset协议，不将生产新协议强加到历史数据。

每轮先整体核验快照，Prepared只由本服务私有构造；提交时校验Prepared绑定的Task ID与原快照全文，以及该会话的不可变DTO、上下文和执行摘要。避免每提交一个会话重复解析整批16MiB输入，执行副本即便被改写也不能改变独立的原始DTO证据。
