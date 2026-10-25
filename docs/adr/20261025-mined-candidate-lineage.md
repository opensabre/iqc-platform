# 自动挖掘候选沿用原样本池

状态：来源协议、筛选/持久化及原动作v2已编码；界面和SQL/权限/重放组合验收未完成。

PRD P2要求自动候选挖掘。源码复用检索：QualitySample/GoldSampleService已有候选、标注和独立审核；BusinessReviewSampleService已有冻结segments及精确候选输入v3；EvaluationCandidateActionAdapter/Service目前只支持完成业务复核的v1；ActionAdapterRegistry已有精确type/version选择。因此沿原样本池和动作版本扩展，不新增候选表、样本注册服务或并行审核流程。

现新增iqc-ai-mined-candidate-v1来源，记录15项：schemaVersion、sourceResultId、taskId、executionId、conversationId、owner、ownerGroupId、sourceHash、inputHash、executionInputHash、miningPolicyHash、miningStrategy、miningVersion、reasonCodes、requestFingerprint。ownerGroupId显式null或字符串；其余身份/摘要/策略字段为文本，miningVersion为1–1000整数，reasonCodes为1–20个不同受限代码。严格拒绝重复键/尾随/未知字段、数字转文本、额外expected或审核结论。算法/版本/原因仅是挖掘依据，不是已确认误判分类。

新候选sampleType固定UNCLASSIFIED、messageId为空，Gold读取核对原结果/会话/创建者、组不扩大及输入Hash，且只接受原EXACT_INPUT_SCHEMA v3冻结segments。旧已复核候选仍走原协议/字段列表，不为旧来源新增挖掘上下文。两个来源复用原消息字段/顺序/坐标/置信度解码；新来源不查询当前会话、不重新转写或复算模型，不拷贝AI输出为Gold。GoldDraft仍由用户标注，原独立审核及数据集已审核版本条件保持。

来源协议校验只验证可信存储中的来源协议/输入一致性，不替代原生产结果验证。动作v2/创建服务另须在原任务锁下重新验证原生产输入与结果、当前授权和组织范围、真实策略及筛选原因，首次捕获不可变/幂等恢复；不能允许客户端提交任意此协议就作为真实来源。不新增自动创建API，发现/claim复用原Action服务；目前未开启生产开关或完成自动创建整链验证，不能据此宣称自动挖掘完成。

后续需补原池展示与v2配置，并组合验收原动作筛选/创建、当前授权撤销与跨组、来源变动与恢复、不重复挖掘、真实SQL并发及独立审核；禁止自动批准或预判FALSE_POSITIVE/FALSE_NEGATIVE。无Framework/共享SQL/部署或默认v2变更。

## 筛选与本地持久化阶段

新增FUSED_UNCERTAINTY v1纯筛选，配置显式findingCodes(1–50个不同声明编码)、threshold(0–1精确十进制字符串)，可选name/tags。从原已验证融合结果发现缺失、非KNOWN、缺置信度、低置信度，以及原始KNOWN观测完整类型值不一致；原因代码排序去重。置信度只是挖掘线索，不保证校准，更不等于误判真值；不重新调用模型，不自动分类FP/FN。数值/对象比较复用原FindingEvaluationMetrics.equal。

原ADD_TO_EVALUATION_CANDIDATE动作v2调用本地服务，v1保持复核后候选。创建前沿原SourceValidator锁task→execution→result并验证原来源、当前sample:manage、策略和Action条件；按已提交并经原验证的会话结果触发，允许原RUNNING任务；暂停/取消/缺完整证明仍由原SourceValidator拒绝。使用原validatedExecutionInput的冻结messages/businessContext，复用原候选segments编码，写原QualitySample，UNCLASSIFIED/CANDIDATE/UNLABELLED，不写GoldDraft。原结果/任务/源执行/输入摘要与策略/筛选原因保留，快照超4MiB明确拒绝而不截断。

候选ID沿原动作幂等key确定；首次产物存在即确认原请求，不重新验证当前会话/改写输入。首次创建在原父锁下再锁候选，重复键读获胜记录；确认核原来源身份/请求指纹/策略/元数据，不覆盖。reconcile只读已有持久候选，没有产物返回不可确认，不调用create，不捕获新输入。适配器只有读到实际候选ID才给原成功Receipt，异常沿原UNKNOWN与受控恢复，不以模拟标成功。

发布校验要求v2挖掘findingCodes为流水线已声明事实，原注册表按type/version区分。部署与界面配置、真实自动发现/claim、SQL并发、当前组织权限和独立Gold审核仍须组合验收；本阶段没有新增API/表/线程池/定时服务。

未命中挖掘的结果在原入队处直接跳过，不生成永久等待的动作；命中结果按已提交会话处理，不等待大批任务整体终态；原暂停/取消控制和完整来源验证保持。发现/claim/当前授权与重试仍使用原Action基础设施，受iqc.actions.enabled原开关控制，不自动启用。FUSED_UNCERTAINTY v1固定对应适配v2，未来算法语义升级需新版本，不原地修改已发布策略含义。

示例动作（附加到已有actionPolicy.actions，事实编码必须已声明）：

```json
{"actionId":"MINE_UNCERTAIN","type":"ADD_TO_EVALUATION_CANDIDATE","adapterVersion":2,"condition":{"kind":"ALWAYS","code":null,"value":null,"children":[]},"idempotency":"PRODUCTION_SOURCE_V1","retry":null,"failureHandling":"RECORD_ONLY","configuration":{"findingCodes":["SALE_COMPLIANCE"],"threshold":"0.8","tags":["mined"]}}
```

阈值为示例，不是PRD质量标准。可用原Action条件进一步限制来源；没有全库自动扫描，也不会给所有已发布流水线隐式增加动作。新适配需全部执行实例支持后再配置。界面v2配置与完整运行验收仍待接通。
