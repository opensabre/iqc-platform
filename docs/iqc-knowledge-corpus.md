# Knowledge Base 语料版本与管理（KB1/KB2）

KB1为领域服务和DDL，KB2已增加配套授权/敏感投影的管理HTTP与页面入口（见后文）。Agent精确引用、任务授权正文冻结与运行上下文继续KB2引用/KB3；不表示知识库整体功能或实际运行验收已完成，见ADR 20261024-knowledge-corpus-versions。

## 语料协议

`iqc-knowledge-corpus-v1`包含documents，每份文档为documentId/title/text/sourceReference四个字符串。文档ID须为1–64位字母数字或:_-且唯一；标题1–200字，正文1–16000字，来源引用1–512字符且无控制字符/首尾空白。集合1–100份文档，正文总长度最多200000个Java字符，序列化正文最多2MiB。文档按ID排序，输入列表不被修改；字符串数字不被自动当文档正文，未知字段/重复JSON字段/尾随JSON/空正文及不完整Unicode代理字符拒绝。来源引用只作证据定位，不下载内容或自动视为可信会话证据。

语料示例（管理表单提交时生成该结构）：

```json
{"schemaVersion":"iqc-knowledge-corpus-v1","documents":[{"documentId":"service-hours","title":"服务时段示例","text":"示例服务时段为工作日09:00至18:00。","sourceReference":"manual:service-v1#hours"}]}
```

## 独立版本与组织范围

`iqc_knowledge_base`只维护草稿修订、可用性与最新发布指针；`iqc_knowledge_base_version`保存不可变原版本/源草稿修订/JSON摘要，唯一约束保证同草稿不重复发布。创建默认DISABLED，发布后显式启用；后续发布维持原可用性，精确旧版本不跟随新指针。restore把历史语料和名称写入新草稿修订，必须再次发布，不修改原版本或旧任务。

所有内部读入口复用IqcDataScope owner/group/all；维护操作复用现有资产的接口写权限和相同数据范围，拒绝system，不额外创造创建者专属ACL；版本分别冻结原归属owner与实际publishedBy。分页目录不读取草稿正文；原组改变只能收窄为私有，不能把旧私有历史开放到新组。Prepared返回的ownerGroupId为当前资产与原版本的来源交集，后续任务必须使用该字段收窄来源范围，不能误用历史snapshot.ownerGroupId扩大当前共享。没有tenant字段或客户端组织头。

发布在原资产行锁/事务内检查草稿修订、原正文及版本上限，同修订重放返回原版本；后续插入/指针失败由同事务回滚。readPublishedEvidence纯校验原版本ID/知识库ID/版本/源修订/原归属/实际发布人与内容Hash。viewVersion允许读取停用后的历史，publishedForTask另外要求当前ACTIVE、调用方已有事务并锁原资产，和启停/更新串行化。历史来源校验不重新选取最新语料。history/viewVersion跨父记录和版本查询使用同一只读REPEATABLE_READ事务，防止旧组检查与并发新私有版本错配；新任务引用则在原调用事务锁父记录。

## 接入边界与验收

Agent共享目录只携带精确知识库引用/Hash；私有正文应在有当前组织授权的任务物化阶段冻结到原任务，并与其他来源取组交集。不能把知识库语料复制到全局可读Agent配置、Gold数据集或Prompt指令库来省略权限。运行上下文需有界且保留原引用/文档/Hash，参考知识不能替代原会话引文；这些仍属后续KB2/KB3。

本批V1.1.75仅写入迁移，不执行共享SQL或部署。编译终态见协作记录；真实迁移/Mapper、当前授权操作者/跨组/撤权、CAS并发发布/恢复/停用、Hash及坏原父来源、参数边界和实际任务/页面均需独立验证。编译不能证明SQL/角色/worker或全适用I1–I7已完成。

## KB2 管理入口增量（2026-10-24）

新增/api/iqc/knowledge-bases的目录GET/创建POST、/{id}详情GET/修订PUT、availability POST、versions元数据GET、publish POST、versions/{versionNo}精确GET及restore POST。草稿请求为code/name/corpusJson或expectedRevision/name/corpusJson，corpusJson由原严格语料解析器验证；修订须正整数JSON数字，启停须JSON布尔值，拒绝字符串/小数或隐式取整。恢复为新草稿，不移动当前发布指针。每个URL/方法使用独立iqc:knowledge:*资源，并在查询前复用SensitiveReadAccessService检查当前资源和组织范围，所有回执private,no-store，审计不记录正文。

原ResultReadProjection新增知识库DTO投影，source对象不被修改：目录、历史列表和创建/修订/发布/启停/恢复回执一律不返回正文；详情与精确版本只有三项既有FULL/PII/PROMPT敏感阅读齐备才显示正文。版本元数据列表查询直接排除snapshotJson，不批量加载/解析100份大型语料；其contentHash为存储的版本摘要，实际正文/来源校验在精确版本读取中完成。缺少正文时canonicalPayloadVisible=false，不能把回执当作空白可编辑配置。

权限迁移V20261024_01在组织服务注册九项资源和知识库菜单：IQC_ADMIN/IQC_QUALITY_MANAGER具有维护资源，IQC_INSPECTOR/IQC_VIEWER仅目录/详情/历史/精确版本阅读；不会额外授予敏感阅读或全局ADMIN。沿用现有产品菜单父级与资源代码upsert，不重写既有URL/方法/身份。脚本仅交付，未执行共享SQL。

前端/knowledge-bases复用原权限与路由、表格/抽屉/表单，逐文档编辑ID/标题/正文/来源引用并检查边界。草稿修改未保存时不能切其他资产或发布/恢复覆盖；命令回执后清正文并独立重读，失败保留对象身份并要求重新读取。正文/目录/版本/维护权限变化清空状态和请求代次，关闭/切换资产/版本的旧响应不恢复文档。敏感受限详情为元数据视图，不能用空正文覆盖。

本批仅管理与阅读，不声明Agent/Pipeline精确引用、任务授权正文冻结或LLM上下文已完成；这些继续KB2引用/KB3。编译构建终态见协作记录，真实HTTP/PDP/四角色/SQL脚本/浏览器及源对象不变专项待独立验证。
## B 协议与发布来源验证（2026-10-24）

固定同步a095f7a后新增KnowledgeCorpusTest：规范排序且不改变原列表、输出列表只读、
正文/总量/Unicode/重复ID边界、未知字段/重复字段/尾随JSON/字符串类型严格解析，
以及发布版本Hash、身份、修订、发布人核对。原12902终态0，知识库4项与Prompt组合
共12项零失败、错误、跳过；日志`/tmp/iqc-b-knowledge-protocol-final.log`。
摘要复用原JSON规范化协议，格式空白不属于正文篡改；实际正文变化拒绝。
本批未执行DDL/SQL，不替代组织权限、CAS并发、事务锁、管理界面和任务正文冻结验收。

## B 真实SQL生命周期验证（2026-10-24）

原实测发布夹具注册实际Knowledge Mapper及Spring事务代理Service，在专用34307
应用V1.1.75后验证：默认停用、同修订重复发布同ID、无事务的任务引用拒绝、
停用任务引用拒绝、启用后精确历史读取、原审计owner/publishedBy、旧修订CAS拒绝、
后续发布保持ACTIVE、原历史正文不变、恢复只生成新草稿且保留发布指针、停用历史可读。

原10993终态0，IsolatedMysqlMeasuredPublicationIT四项零失败、错误、跳过，
27.455秒，专用MySQL关闭且socket消失；日志`/tmp/iqc-b-knowledge-lifecycle-sql-final.log`。
首次测试编码含小写被正确拒绝，修正为大写后组合通过。组织scope沿原夹具受控，
不外推真实PDP、跨组/并发竞态、任务正文冻结或全新数据库完整迁移验收。

## B 发布故障回滚验证（2026-10-24）

在原生命周期场景注入真实MySQL父表UPDATE失败：版本插入后的父指针更新
被触发器拒绝，事务回滚后版本行数为0、发布指针为null、草稿修订仍为1。
finally移除触发器后，原发布及同修订重放正常，后续生命周期继续通过。
原97051终态0，完整集成四项零失败、错误、跳过，32.155秒；
日志`/tmp/iqc-b-knowledge-rollback-sql.log`，专用34307已关闭且socket消失。
Flyway实际校验76项迁移，当前1.1.75，无新增迁移操作。
此为单实例故障回滚证据，不替代并发发布/跨组授权/任务冻结验收。

## B 同修订并发发布验证（2026-10-24）

原生命周期场景以两个线程各自设置用户上下文，经屏障同时发起原Spring代理
Service.publish，使用独立事务。限时完成后两侧版本ID相同，实际版本表仅一条记录；
后续普通重放仍返回同ID。线程上下文与线程池均清理，原故障回滚及生命周期断言保留。
原94472终态0，集成4项零失败、错误、跳过，103.585秒；日志
`/tmp/iqc-b-knowledge-concurrent-publish.log`，专用34307关闭且socket消失。
本轮不增加用例数量，不代表多进程压力测试或跨组真实权限验收。

## B 实际负责人范围验证（2026-10-24）

在原SQL生命周期中独立使用实际IqcDataScope(null)与实际Mapper服务，验证负责人
可读、其他用户get/history/update被拒绝且page不含记录、ADMIN可读、无认证get/page
被拒绝；用户上下文finally还原。更新调用使用原事务管理器。
原9737终态0，集成4项零失败、错误、跳过，62.889秒；日志
`/tmp/iqc-b-knowledge-owner-scope.log`，专用34307关闭且socket消失。
没有接入组织服务，同组共享、跨组变更竞态和真实PDP/HTTP授权仍待联调。

## B 同组协作与私有收窄修复（2026-10-24）

实际IqcDataScope配受控组织响应验证同组读取、维护、发布；发布证据保留原owner，
实际publishedBy记录协作者。owner在无组上下文维护后，数据库组范围必须清空，
历史Prepared有效组为空，原同组用户历史读取及更新均拒绝。
首轮SQL发现ownerGroupId默认更新策略忽略null，旧组留存；现为该字段显式ALWAYS
更新，保证私有收窄落库，无DDL或框架变更。
原55185 test-compile+surerfire终态0，集成4项零失败、错误、跳过，46.748秒；
日志`/tmp/iqc-b-knowledge-group-fixed.log`，专用34307关闭/socket消失。
此为受控组织响应的领域SQL验证，真实组织网络/PDP与并发组变更仍待联调。
## KB2 管理入口增量（2026-10-24）

新增/api/iqc/knowledge-bases的目录GET/创建POST、/{id}详情GET/修订PUT、availability POST、versions元数据GET、publish POST、versions/{versionNo}精确GET及restore POST。草稿请求为code/name/corpusJson或expectedRevision/name/corpusJson，corpusJson由原严格语料解析器验证；修订须正整数JSON数字，启停须JSON布尔值，拒绝字符串/小数或隐式取整。恢复为新草稿，不移动当前发布指针。每个URL/方法使用独立iqc:knowledge:*资源，并在查询前复用SensitiveReadAccessService检查当前资源和组织范围，所有回执private,no-store，审计不记录正文。

原ResultReadProjection新增知识库DTO投影，source对象不被修改：目录、历史列表和创建/修订/发布/启停/恢复回执一律不返回正文；详情与精确版本只有三项既有FULL/PII/PROMPT敏感阅读齐备才显示正文。版本元数据列表查询直接排除snapshotJson，不批量加载/解析100份大型语料；其contentHash为存储的版本摘要，实际正文/来源校验在精确版本读取中完成。缺少正文时canonicalPayloadVisible=false，不能把回执当作空白可编辑配置。

权限迁移V20261024_01在组织服务注册九项资源和知识库菜单：IQC_ADMIN/IQC_QUALITY_MANAGER具有维护资源，IQC_INSPECTOR/IQC_VIEWER仅目录/详情/历史/精确版本阅读；不会额外授予敏感阅读或全局ADMIN。沿用现有产品菜单父级与资源代码upsert，不重写既有URL/方法/身份。脚本仅交付，未执行共享SQL。

前端/knowledge-bases复用原权限与路由、表格/抽屉/表单，逐文档编辑ID/标题/正文/来源引用并检查边界。草稿修改未保存时不能切其他资产或发布/恢复覆盖；命令回执后清正文并独立重读，失败保留对象身份并要求重新读取。正文/目录/版本/维护权限变化清空状态和请求代次，关闭/切换资产/版本的旧响应不恢复文档。敏感受限详情为元数据视图，不能用空正文覆盖。

本批仅管理与阅读，不声明Agent/Pipeline精确引用、任务授权正文冻结或LLM上下文已完成；这些继续KB2引用/KB3。编译构建终态见协作记录，真实HTTP/PDP/四角色/SQL脚本/浏览器及源对象不变专项待独立验证。

## B KB2敏感正文投影验证（2026-10-24）

固定同步59aa155后，KnowledgeReadProjectionTest遍历FULL/PII/PROMPT八种组合：
目录/命令元数据与历史均无正文，详情与精确版本仅三项权限齐备时返回正文，
canonicalPayloadVisible与实际正文一致，原草稿/历史/Prepared对象不被修改。
原15682 test-compile+surerfire终态0；新增1项、语料4项、原ResultReadProjection9项，
共14项零失败、错误、跳过，日志`/tmp/iqc-b-knowledge-read-matrix.log`。
此为原投影器验证，非HTTP当前授权、四角色迁移或浏览器交互证据。

## B HTTP参数与回执验证（2026-10-24）

默认MVC转换器下9种非法修订值和5种非法启停值均返回参数错误且不调用领域服务；
当前详情grant被拒绝时领域读取不发生。合法boolean启停检查对应固定资源，
即使具备全部敏感阅读权限，命令回执仍无语料正文且Cache-Control为private, no-store。
原44435终态0，HTTP16项、投影1项、语料4项共21项零失败、错误、跳过，
日志`/tmp/iqc-b-knowledge-http-contract.log`。领域与授权服务受控，
不代表真实PDP、角色迁移、SQL或浏览器端到端验收。

## Agent 精确版本引用（KB3 前置）

部署采用单租户，继续复用 IqcDataScope 的 owner/group/all 与当前组织资源授权，不引入租户模型或另一套 ACL。schema3 Agent 可引用最多四个不同知识库的已发布版本；版本号必须为正整数，服务端核验当前启用状态、组织可见性、版本行及 SHA-256，并补全 versionId/contentHash。共享 Agent/Pipeline 配置仅携带 knowledgeBaseId/versionNo/versionId/contentHash，不复制知识库正文、标题或组织信息。无引用的历史配置保持原序列化行为。

引用解析复用知识库领域服务，在独立只读 REPEATABLE_READ 事务内校验当前范围和不可变版本，返回不含正文的 Binding；不会跨多个知识库保留父行写锁。此检查不能代替任务物化时的授权。后续 KB3 必须在任务事务中冻结合法正文并收窄来源组织交集，处理所有任务入口以及有界上下文和来源追踪。

当前尚未提供任务正文上下文，两种真实模型适配器对非空 knowledgeAssets 显式返回 KNOWLEDGE_CONTEXT_NOT_FROZEN，防止静默忽略引用。此提交仅为引用管理前置，不作为知识库推理完成或上线验收。后端离线 test-compile 与前端生产构建均退出0；本轮未新增或执行测试，未执行共享迁移或部署。

### B Agent精确引用专项验证

固定fad71e0已合入5ff95b9。KnowledgeAssetReferenceTest三项验证精确版本元数据
绑定、调用者不变与JSON往返，当前授权撤销先于来源读取，错误来源/版本/摘要拒绝，
旧配置不调用知识库依赖。联跑原PromptAssetVersionTest四项，原21830终态0，
/tmp/iqc-b-knowledge-reference-tests-agent.log.exit=0，七项零失败错误跳过。
原59754编译终态0；原7358因Mockito自动附加失败终态1，未执行业务断言，
使用既有显式ByteBuddy agent后通过。来源服务及授权受控，非SQL/PDP/任务正文冻结证明。
### KB3 批量任务读取前置

publishedForTask(List<Binding>) 必须加入调用方事务，先检查全部精确引用和重复冲突，再按知识库ID、版本排序，每个父库只锁一次。相同精确版本去重；不同版本保留各自不可变语料和有效来源组；任一库停用、不可见或版本行/摘要不匹配则拒绝整批。上限400条用于限制多节点提交开销，不替代每个Agent最多四库的规则。返回不可变列表，空引用不访问数据库。调用方仍须负责当前资源授权、任务组交集和有界正文冻结；本批未接入任务调用链。

### B 批量任务读取前置验证

固定b5c46d1合入37b1aa3。原85688 test-compile+surerfire终态0，
/tmp/iqc-b-knowledge-batch.log.exit=0；Batch3+Corpus4+Reference3十项零失败错误跳过。
全批无效参数、相同版本冲突及超量在任何来源IO前拒绝；倒序输入先查询字典序首个
父资源并带FOR UPDATE；空批无IO且结果不可变。Mapper受控并在首资源不存在时中止，
不据此宣称完整成功批次、多事务无死锁或任务正文冻结通过。
### KB3 任务保存边界接入（模型上下文仍待完成）

KnowledgeTaskSnapshotService 复用原 ruleSnapshotJson：普通批次/抽样/方案、Pipeline及评测共用保存边界，另覆盖两处定时创建。收集原 Agent 与 Pipeline 节点 Agent 的精确引用，使用当前 iqc:knowledge:version:view 资源校验，再加入原事务批量读取；版本正文独立冻结为 knowledgeInputs，不改共享 Pipeline 发布体及摘要。信封包含原负责人、来源 Prepared、来源有效组交集及 selector-decimal-sha256-v1 摘要，序列化不得超过8MiB；无引用任务不新增正文信封或资源查询。禁止调用者传入已有 knowledgeInputs。旧直接构造路径缺少服务时，只允许无引用任务。

任务 ownerGroupId 只能保持全部来源一致的原组；任一来源私有或不一致则清空，后续 Pipeline/Selector/事件范围可继续收窄。历史详情继续复用现有三项敏感权限投影。模型侧仍拒绝非空引用，待消费该信封、核验任务归属与精确引用覆盖后才能解除；当前不是知识库推理整链验收。任务冻结的实际SQL回滚、当前权限变化、运行与浏览器验收仍需独立证据。

批量领域读取在逐版本加入结果前累计序列化正文大小，达到8MiB立即拒绝，避免先聚集最大400份大语料后才在信封层拒绝；最终信封仍独立检查实际UTF-8字节（包括元数据及摘要开销）。此上限为任务持久化边界，不是模型Token预算；模型接入仍需原网关Token/成本限制与上下文窗口校验。

### KB3 原任务信封执行校验

verifiedSources 从原任务 ruleSnapshotJson 读取信封；严格拒绝重复JSON键、尾随载荷、非协议字段、超限正文及摘要不符。信封负责人必须等于原任务 createdBy；任务当前组可以比信封更窄，不能从私有恢复为组共享。每份来源的有效组必须为null或原发布组；信封共享组必须被所有来源共同允许。

原任务 Agent 与全部 Pipeline 节点引用组成精确引用集合，正文来源必须一一覆盖该集合，不接受多余、缺少、重复或行ID/版本/摘要替换。每个来源重建原 KnowledgeBaseVersion 元数据，复用 readPublishedEvidence 验证发布协议、语料约束、发布人和内容摘要。校验加入原执行器的入队、worker启动和恢复依赖边界，不读取可变知识库草稿，也不重新授权替换历史正文。无引用旧任务不增加知识库来源查询。真实模型有界上下文与使用来源追踪仍需继续，当前未解除模型拒绝。

### B 任务知识库信封专项

固定01ce566合入9d15d5d，原54315 test-compile+surerfire终态0，
/tmp/iqc-b-knowledge-task-envelope.log.exit=0；Snapshot3+Batch3六项零失败错误跳过。
受控来源冻结后，静态验证读取原正文且不查询当前来源，返回不可变；任务范围只允许
收窄，私有来源使任务组为null；即使重算外层Hash，缺失引用覆盖仍拒绝，错误负责人
拒绝；无引用旧任务兼容，注入信封/重复键/尾随JSON拒绝。
以上不证明真实事务/SQL/PDP或模型已消费参考正文。
### KB3 模型参考上下文消费（当前实现）

前述阶段性全量拒绝已由类型化上下文校验替代。原任务信封核验后，由私有构造器创建 ExecutionContext，沿原 ModelCallContext 传递；七参数旧构造器保留，新增上下文通过 JsonIgnore 排除在调用元数据JSON之外。没有知识库标记的历史任务不在每次模型调用前解码完整生产输入；入队/worker/恢复仍检查原任务。

当前节点只能读取其精确引用对应的原任务语料；参考JSON保留taskKnowledgeHash、库ID、版本号、版本行ID、摘要以及每份文档ID/标题/正文/sourceReference，不包含资料负责人或组织属性。单节点参考JSON最多256KiB，超过上限明确拒绝，不静默截断或丢文档。该字节限制不替代模型Token上限：完整消息继续由原Model Gateway执行窗口、Token与成本控制。

Spring AI与HTTP适配器把参考资料作为单独用户消息加入，系统提示明确其为数据、不得执行资料指令或把资料当成会话证据；原会话引用及Finding校验保持。未提供类型化上下文、当前调用任务/负责人/组不匹配、引用不属于原任务、或有引用但缺少Gateway路径时，在模型I/O之前拒绝。会话重复运行复制ModelCallContext时保留同一已核验上下文。

这是静态有界语料上下文，不包含向量检索或自动采样。当前知识库任务仍会随调用上下文重新核验信封，容量优化与真实worker/供应商/网关预算验收待完成；编译不能替代这些证据。

### B 模型参考上下文专项

固定4b17567合入95bf7bb。原12020 test-compile+surerfire终态0，
/tmp/iqc-b-knowledge-model-context.log.exit=0；Snapshot5+Batch3八项零失败错误跳过。
已核验任务信封生成的上下文保留原参考正文及版本来源，任务ID/owner/group不匹配、
节点版本行不属于原任务、缺少已核验上下文均拒绝。ModelCallContext JSON不含
knowledgeContext或参考正文，旧无引用调用无需该上下文。
来源受控，本轮未覆盖真实provider请求构造、网关预算/窗口或SQL/PDP。

### B Provider参考消息边界专项

原31474 compiler:testCompile+surerfire终态0，
/tmp/iqc-b-knowledge-provider-boundary-final.log.exit=0；新边界3+原SpringAI4+HTTP2
共九项零失败错误跳过。实际SpringAI provider经受控router捕获三条消息：system声明
资料不能替代会话证据，独立user参考消息保留正文，末条user只含原会话；不走direct模型。
缺核验上下文返回supported=false/KNOWLEDGE_CONTEXT_NOT_FROZEN且router零调用；
HTTP缺gateway返回supported=false/KNOWLEDGE_REQUIRES_MODEL_GATEWAY且限流入口零调用。
原56367两项失败是测试误期望异常外抛，按现有失败结果契约修正后通过，未修改生产实现。
ExecutionContext/router受控；未覆盖HTTP成功请求、真实远端、网关预算或SQL/PDP。

### B HTTP成功请求构造补验

原1615 compiler:testCompile+surerfire终态0，
/tmp/iqc-b-knowledge-http-request-configured.log.exit=0，ProviderBoundary四项通过。
实际HTTP provider的requestData回调捕获三消息：system声明证据边界，第二条user仅为
参考正文，第三条user含原会话且无参考正文；网关接收同一ModelCallContext，调用一次，
成功响应解析通过，不走旧限流入口。网关返回受控响应且不执行网络supplier，故此项证明
成功请求构造及上下文传递，不证明HTTP传输/远端服务/实际预算。
前两轮分别因测试访问器名称错误和漏填必填模型配置失败，修正测试后通过，未改生产。

### B UTF-8参考上下文大小验证

原46797 compiler:testCompile+surerfire终态0，
/tmp/iqc-b-knowledge-context-size.log.exit=0；Snapshot6+ProviderBoundary4十项通过。
同为6份各16000字符文档，ASCII语料通过原发布/任务信封校验并生成低于256KiB上下文；
中文语料同样通过原证据校验，但UTF-8参考上下文超过256KiB，material拒绝并返回
KNOWLEDGE_CONTEXT_TOO_LARGE。此项证明字节限制；非token窗口、网关预算或真实远端证据。

### B 真实SQL来源的任务冻结补验

原70165专用34307 test-compile+surerfire终态0，
/tmp/iqc-b-knowledge-task-source-sql.log.exit=0，原MeasuredPublication四项集成测试通过；
专用实例关闭/socket消失。实际Spring代理KnowledgeService批量接口验证MANDATORY，
原事务真实Mapper读取精确版本并冻结任务信封，核验当前敏感授权入口。
后续来源修订/发布/恢复/停用，原信封保持不变，历史校验和模型参考保留原版本正文/Hash，
新批量引用已停用来源拒绝。任务信封对象仍在夹具内，未写任务表；敏感授权受控，
不据此宣称TaskService保存/worker全链、真实PDP或远端模型通过。

### B 任务信封SQL持久化补验

原53070专用34307 test-compile+surerfire终态0，
/tmp/iqc-b-knowledge-task-persistence.log.exit=0，原MeasuredPublication四项集成测试通过。
沿原InspectionTaskMapper和事务写入已冻结Agent/规则信封并重读，正文及版本证据一致。
来源修订/停用后再次从任务表读取，原信封不变，仍可校验原版本/Hash并生成历史参考。
唯一任务ID支持重复执行；实例关闭/socket消失。此项补齐上节任务未落表缺口，
仍未经过TaskService创建/worker知识上下文全链，敏感授权受控，非真实PDP或远端证据。
