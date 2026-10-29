# I3 冻结输入评测执行

状态：实施中，尚未运行验收。

现有 InspectionTask/TaskExecution/TaskItem 已覆盖排队、租约、暂停、恢复、失败重试及并发会话。PipelineRuntime 与 FrozenDetectorEngine 已共用生产规则/DLS/LLM 实现。复用这些能力，不建平行评测任务主表或另一套检测引擎。

缺口：创建和排队都查询实时 Conversation/Message；processConversation 再读取全会话，并将产物写入生产层级结果。评测必须读取 DatasetVersion 冻结金标输入，不能把运行后实时修改的数据用于回归比较，不能混入生产统计。

决定：扩展 TaskService 的同一快照任务初始化边界，新增 PIPELINE_EVALUATION 任务类型。任务只记录数据集精确版本/hash及Pipeline冻结配置，不复制第二份金标主库。队列和会话运行按显式评测标记读取冻结消息，原TaskItem仍作为检查点；节点执行统一使用 Mode.EVALUATION，保留真实Task租约心跳。业务上下文加入运行输入，生产默认调用保持原输入协议。

V1.1.40 评测逐样本结果职责是保存具体执行轮次对固定金标的机器输出及摘要，生产结果表没有此职责。评测提交必须锁真实Task、验证EID/租约/快照和冻结输入，再更新原TaskItem，不写 InspectionResult、ConversationInspectionResult 或 LabelResult。报告按整个冻结数据集计算，重试继承成功项时仍保留其原始执行来源；没有完整产物不能声明完整评测或发布达标。

实际评测验收、对照、各层完整指标和发布门禁仍待实现；当前新增分支不是完成证明。框架无需修改。
