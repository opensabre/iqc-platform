# RabbitMQ业务事件触发接入

## 复用与范围

I4补已有RabbitMQ的接收传输，输入仍是处理后的会话目标/文本消息，不执行ASR或转写。base-k8s已有RabbitMQ服务；Framework EDA提供本地异步分发和远程发布SPI，没有Rabbit接收器。IQC仅增加spring-rabbit（沿用BOM版本）和应用内监听适配，不改Framework，不增基础设施服务、Inbox/Task表、HTTP API或授权资源。直接库加条件配置避免AMQP Boot starter默认注册全局连接/健康检查。

默认`iqc.trigger.rabbit.enabled=false`；关闭时连接、RabbitAdmin、队列声明、监听器均不注册，不依赖broker健康。启用示例见examples/iqc-rabbit-trigger.yml，需同时开启原iqc.trigger.enabled才能由原触发工作器物化和入队。仅接收提交不等于检测已执行/成功。

## 固定身份与组织权限

部署方配置source、负责人稳定userId/username、可选groupId、明确pipelineIds与eventTypes允许集合。默认owner范围；显式all=true仅与负责人当前已有all授权相交，不授予新权限。负责人是现有组织账号；每次投递用ScheduledActorAccessService.resolve重新读取当前账号/资源授权，组织撤权或组变动不能借用旧权限。消息不接受身份、角色、Bearer或权限声明。专用MessageConverter只返回原始字节，禁止Java对象反序列化；唯一解析路径是后续严格UTF-8及原JSON协议。允许的内容由严格TaskTriggerRequest协议决定，未知字段/重复键拒绝，保留现有精确数值解析。

每条消息必须匹配配置source、eventType和pipelineId；pipeline发布版本仍走原资产授权。默认allow-content=false，仅已有会话/外部身份+inputRevision；开启后才允许现有ApiConversationInput，并额外要求原conversation:upload授权。retryPolicy同样要求原配置权限。内部archive-subscription来源不得使用。

## 拓扑与发布契约

- 主交换机`{queue}.exchange`，Direct且durable；routingKey为固定source。
- 主队列`{queue}`，durable，不auto-delete；拒绝路由至`{queue}.dead.exchange`，routingKey=rejected。
- 死信交换机与`{queue}.dead`队列均durable。只由专用iqcTriggerRabbitAdmin声明，不借用其它应用RabbitAdmin。若同名存量队列参数不同，先按部署流程迁移，不绕过broker参数一致性检查。
- 发布body是原`iqc-task-trigger-v1` JSON，UTF-8，最多16MiB；推荐contentType=application/json、persistent delivery与publisher confirms，并确认路由成功。AMQP messageId不作为幂等身份，重投必须保留body source/eventId及全部载荷。
- broker凭据使用部署secret的AMQP URI；AMQPS使用JDK默认受信任CA及主机名验证，可用JDK信任库配置私有CA。URI、凭据和消息正文不写应用日志；关闭Rabbit客户端/监听器的正文调试日志。

## 提交、ACK与失败

消费者固定1个、prefetch=1，MANUAL确认，同一投递channel操作。严格解析/配置允许集合/当前授权检查后，在收窄scope中调用原TaskTriggerService.accept接收路径。原REQUIRES_NEW事务提交并返回持久化taskId后才basicAck。

幂等继续使用现有稳定负责人ID+source+eventId和payloadHash，同身份同载荷返回原任务，不重复创建；不同载荷拒绝。DB死锁/唯一竞争沿用最多3次事务尝试，每次事务开始重验授权证据时效。业务检测由原TaskTriggerDispatcher及队列工作器处理，不在监听线程执行、不新增第二套自动重试。

解析、授权、允许集合或接收失败，basicReject(requeue=false)进入死信；没有热循环重投。应用依赖暂不可用也进入死信，修复后管理员经broker受控流程重投到原交换机/路由，保留原事件身份和载荷；不要更换eventId来掩盖冲突。死信payload可能包含内容，访问和保留沿用部署的受控broker策略。过期的新事件仍被原时效校验拒绝，已接收事件重复投递复用原任务。

DB已提交但ACK/连接丢失，broker可能再次投递，由原幂等处理；不是跨DB/broker的分布式原子事务，不承诺exactly-once。持久队列不等于所有broker故障下零丢失，生产需匹配既有broker复制/死信策略及publisher确认。不会在应用异常日志打印失败body；低基数iqc.trigger.rabbit.deliveries计数仅区分accepted/rejected投递，不是唯一任务数，也不证明检测成功。

## 验证边界

本批只编译，没有新增/运行测试、连接实际broker、迁移SQL或部署。仍需B独立验证：

1. 关闭时无传输bean/网络连接/健康依赖，开启时配置缺失早拒绝；secret不进入toString/异常。
2. 真实broker声明/绑定、MANUAL ACK晚于DB提交、提交失败死信、ACK丢失重投及同身份不同body拒绝。
3. 固定身份/当前组织授权/撤权与分组收窄，source/pipeline/type/content/retryPolicy边界。
4. 严格UTF-8/重复字段/未知字段/16MiB、SQL竞争、原WAITING_INPUT→原worker→任务产物，死信受控重投及过期边界。
5. 与HTTP接收幂等/历史hash及内部archive-subscription兼容，其他RabbitAdmin存在时声明隔离。

官方传输参考：[Spring AMQP手动确认](https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/async-returns.html)、[RabbitMQ消息确认](https://www.rabbitmq.com/tutorials/tutorial-two-spring-amqp)。正式验收以本提交实际broker和SQL行为为准，不能由参考文档或编译推断。
