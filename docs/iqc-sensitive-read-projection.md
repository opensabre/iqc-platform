# IQC 敏感读取投影实施调查

依据PRD18.3/19，所有输入均为处理好的会话；语音转换/ASR/解码/转写生成不在范围。敏感读取与数据归属是两层约束：任务/会话scope不能替代完整会话、客户PII、Prompt敏感权限。

## 已有能力与复用边界

- Framework已有SensitiveRule/DefaultSensitiveRule，提供姓名/证件/银行卡/邮箱/手机等pattern与替换方法；REST DesensitizationSerialize仅按字段注解序列化，当前源码依赖Jackson3，IQC事实/快照大量使用Jackson2及JSON字符串。不能只贴注解就声称嵌套Finding/审计快照已脱敏。
- 原RegxLogBackDesensitizer是ILoggingEvent日志插件，不能作为REST权限判定器。应用可复用同一规则的pattern/replace处理只读字符串投影，不能复制另一套PII正则或修改不可变原始事实。
- IQC ConversationAudioController已有独立敏感resource+ScheduledActorAccessService当前授权核验+原scope的接法，可供读取权限判定复用；不恢复音频转换范围。
- 当前FindingContext、复核历史JSON、结果Trace与导出可包含原文/事实值/证据和原因。仅保护主会话接口不能覆盖这些副本；必须清点所有读取面及审计response，不能靠前端遮挡保护已发送字段。

## 下一步实施规则

1. 清点结果详情、会话消息、Finding上下文/历史、样本/金标、版本/Agent/模型调用与导出；先区分结构身份与可含PII的内容叶子。
2. 原scope先授权，敏感权限用原当前授权服务检查；未知/过期授权不得输出原文。资源与组织角色沿既有权限仓库注册，不从ADMIN名称或canViewAll推断敏感授权。
3. 脱敏只作用于返回DTO/复制的JSON树/下载输出，不写回raw、Task快照、Finding、hash、人工裁决或金标。身份/hash/枚举/数值结构不盲目替换。
4. 明确返回redacted标记；编辑场景中若事实值被脱敏，应禁止把脱敏默认值当原值提交，或要求独立原文编辑权限，避免误写星号。
5. 普通审计日志禁录敏感请求/响应payload；只保留操作人、来源ID、hash及变更身份。Prompt与模型原文日志单独权限，业务PII权限不自动授权Prompt。
6. 复用框架规则无法覆盖的已记录需求再讨论应用扩展；任何framework源码修改须先说明影响并获得用户明确批准。本调查未修改framework。

本页为实施调查，不代表敏感权限/脱敏已实现。Wiki access-control页为seeded，已继续读取raw/project-docs/base-organization/modules/organization-and-access.md（目录为指向owning docs的符号链接；普通rg --files不会跟随，不能据此判断资料缺失）。以上判断同时参考owning源码、注解与原权限实现。完整I1–I7、真实权限/数据库/API/浏览器验收继续。

## 首批审计正文最小化

已核对原Audit.request默认true、response默认false，AuditAspect将args整体序列化到审计request，且已有key SpEL会在调用前计算目标键。QualityOperationsController的标签/业务/消息复核、反馈与候选创建六个入口，以及GoldSampleController标注/审核，现显式request=false/response=false并用既有path ID作为key；不改变权限、裁决参数或原业务持久记录。

ConversationImportController单条/批量已处理会话API接入显式关闭审计请求/响应正文，txt/ZIP入口原已关闭request。审计事件仍有操作人、时间、路径、方法、模块和结果状态/错误，已有来源ID的操作新增目标key。接入新会话的结果ID在方法执行前尚不存在，当前framework key不支持从result取值，此处不伪造ID，原会话来源/创建记录继续存在。

此批只处理三个已确认入口文件，不代表所有Prompt/模型调用日志、HTTP日志或全部读取副本已覆盖；敏感读取权限/PII投影仍待后续。未改framework；Root只编译，具体注解/审计事件回归待B按其授权验证。

## 配置与发布快照的审计正文

队列最终集成0a6b285离线skipTests test-compile原75481已terminal exit0。继续核查PipelineAssetController发布/例外发布原显式response=true，返回PipelineAssetVersion包含snapshotJson和publicationDecisionJson；create/update请求可含完整节点配置。IqcSkillController create/update请求包含instructions正文，默认request=true。

这六个入口现在显式关闭request/response采集，已有流水线/Skill path ID保留或加入目标key，原资源权限/门禁/管理员校验不改。发布原因、操作者、时间和门禁证据仍由PipelineAssetService写入不可变publicationDecisionJson及publicationDecisionHash，不删除或改写；只是避免在普通审计流重复完整快照/提示正文。创建资产的结果ID在当前Aspect调用前key阶段尚不存在，不伪造新ID；原创建记录和操作审计仍保留。

该批尚待编译与B注解/事件回归。其他模型调用日志、配置详情读取、全部敏感副本仍需按前述范围接入独立权限/只读脱敏，不能将此当完整I7完成。未修改framework、未运行Root测试/SQL/部署。

## 框架规则文本投影内核（尚未接接口）

b7032b4配置审计编译原28984已terminal exit0。已用javap核对当前实际1.1.4 boot依赖包含DefaultSensitiveRule及pattern/category/replace接口，非只凭framework当前分支推断。新增应用privacy.SensitiveTextProjection复用原九类有实际识别pattern的规则（口令、证件、银行卡、手机/电话、邮箱、姓名、车牌、地址），不复制PII正则；CUSTOM/ACCOUNT_NO的占位pattern不用于自由文本识别。

投影返回固定policyVersion、text、redacted及有限类别；不保存原文/hash，不改原字段或事实。替换保留UTF-16长度；原规则替换范围不可用/没有实际遮罩时，仅将该已匹配片段等长全遮罩，不返回该片段明文或改变证据位置。null/空文本保留原语义，replacement按字面写入。

这是规则内核，不是完整敏感读取方案：未接入权限、DTO/JSON副本、编辑防误写、导出和Prompt单独授权；框架规则命中范围不等于所有PII均被识别。需B基于真实安装规则验证中英文标签/嵌入文本/短邮箱/多行/重复匹配和长度边界；规则不足先记录gap，不擅改framework。Root仅编译，无新增运行测试/SQL/部署。

## 当前敏感授权复用内核（尚未接接口）

27128文本投影编译与46219合B损坏阶段保护的UI构建均已terminal exit0。已核对权限分支IqcSchedulerAccessService只筛application=iqc-platform/product=iqc/ACTIVE资源，不局限于Action代码，因此原ScheduledActorAccessService.capture/resolveCurrent可复用为当前读取授权，账号/30秒新鲜度/基础资源/主体核验仍沿原实现。

应用SensitiveReadAccessService每次读取重新调用原当前授权链，不缓存bearer或grants、不新增ACL表；同时拒绝JWT旧all/group范围较当前授权更宽的情况，避免先按旧scope读取。三个独立代码拟定为iqc:sensitive:conversation:read、iqc:sensitive:pii:read、iqc:sensitive:prompt:read，缺失一律false；角色ADMIN/IQC_ADMIN/canViewAll不自动赋予任何一个。Access不能由请求体提供，baseResources只能由拥有读取流程的服务端固定，原数据归属检查仍须执行。

目前只是授权适配内核，未注册这些新敏感资源或接入任何读取端点；不伪造不存在的URL来注册权限。后续须与真实读取路由/组织资源/角色迁移一起落地，明确全文/PII/Prompt各字段投影及编辑防误写，不能仅返回Access就宣称已保护正文。未改framework，Root只编译、未加/跑测试/SQL/部署。

## 首个读取面：会话列表与详情

66218当前授权适配内核编译已terminal exit0。ConversationQueryController复用原ConversationQueryService的归属/scope查询与当前授权链；列表只返回脱敏的PO副本，原详情GET返回PREVIEW（最多3条/每条200 UTF-16字符），新增真实GET `/{id}/full-content`与`/{id}/pii-content`分别标注前述FULL_CONVERSATION/PII代码。FULL始终PII脱敏；PII入口没有全文grant时仍只预览，有两种grant才完整未额外脱敏。基础conversation:view仍必需，权限不足/当前scope变更拒绝，不凭旧JWT扩大范围。

ConversationReadProjection不改原实体：字段已知姓名/账号/联系方式复用框架对应替换，正文先按已知客户/员工/业务身份字面替换再用原自由文本规则，保证替换长度；未知businessFieldsJson/tagsJson不伪称识别完毕而显式withheld。预览及非PII模式不返回rawLine副本，音频身份一律不通过此接口输出，原独立音频权限仍保留。结构ID/源摘要/时间/置信度不改；模式/有限内容/额外脱敏/可用动作/隐藏字段显式返回。规则不保证识别没有标签/元数据的任意PII，完整隐私验收仍待。

两个敏感入口audit request/response=false、目标key=id；成功读取Cache-Control=private,no-store。Controller生产构造强制注入敏感授权适配，旧单参数嵌入构造在敏感读取时fail-closed，不以测试兼容绕过授权。详情抽屉默认预览，展示条数/模式并按当前服务端flags提供全文/PII动作，切换仍丢弃迟到响应；API其他消费者保持可选privacy字段兼容。

组织资源/角色迁移尚待：实际路由已确定，可注册两代码并沿原角色/按钮模型赋权，不造Prompt假URL。建议全文脱敏授权管理员/主管/质检员，PII仅管理员/主管；最终按原组织能力配置且不隐式授予SYSTEM ADMIN。结果页/导出后续接入见本页“结果页读取投影”；复核/Gold/Prompt等其他副本仍待处理，不能声称跨所有入口已保护，也未部署。

## 会话双权限边界补齐

首批17430 backend/11729 UI原handle均terminal exit0。PII预览没有全文grant时，businessFieldsJson/tagsJson同样withheld，只有完整且PII模式才可返回这些未分类JSON；防止其中夹带不受预览条数限制的全文。两个敏感Controller在调用service.detail前显式require对应flag，复用同一授权判定，不依赖下游投影才拒绝；旧current(required)原本已校验基础资源。

列表说明明确默认脱敏，完整内容/PII在详情按权限查看，避免用户把星号当原始存储值；后续各批接口和测试状态见下面的专门章节。其他响应面、角色迁移和真实权限/数据库/浏览器仍未验收，未部署。

## 结果页读取投影

InspectionResultController当前`/results`、`/results/{id}`、两种`result-detail`、`result-hierarchy`及`/tasks/{id}/label-results`先通过原`ScheduledActorAccessService`当前解析器核验`iqc:result:view`，再查询数据；读取端点关闭审计请求/响应正文并设置`Cache-Control: private, no-store`和`X-Content-Type-Options: nosniff`。输出由`ResultReadProjection`复制实体/JSON，不写回结果、Finding、证据、hash或坐标。结构ID、消息ID、序号、状态和偏移保留；可解析 JSON 的自由文本叶子复用`SensitiveTextProjection`等长遮罩，格式损坏的 opaque JSON 在受限模式下替换成显式 WITHHELD 标记。

结果明细始终保留全部消息行和结果状态。无全文权限时只有前三条消息正文可见且每条限200 UTF-16字符，其余行仍保留消息ID/序号但正文为空；PII权限单独可显示这三条正文，Full单独可显示全部脱敏正文，只有两种权限同时具备才显示全部原文。`privacy`元数据标明正文行数、脱敏与 withheld 字段，避免把预览当作完整会话或无命中。Prompt快照不随质检权限返回；只有独立Prompt grant才投影Agent/规则快照。

质检结果 CSV、业务/复核 ZIP、标签 XLSX 导出当前在服务查询前要求当前`iqc:result:export`、完整会话和PII三个授权；审计正文关闭并设置no-store。单条结果读取原有`LlmTextSanitizer`的变长替换已移到接口投影之外，避免破坏证据偏移；结果投影改为使用长度不变的PII规则。

`ResultReadProjectionTest`六项、`InspectionResultSensitiveControllerTest`一项、`InspectionResultControllerTest`两项、`InspectionResultExportTest`八项，共17项通过，覆盖PII/FULL独立与组合、末条消息命中定位、JSON/Finding/标签证据文本脱敏、ID/序号/坐标/原对象不变、授权拒绝先于查询和导出权限门禁。它们是本地单元/MockMvc契约证据；当前组织资源实际授权、登录HTTP、MySQL、外部导出下载验收尚未完成。

结果级编辑/复核写入回放、业务结果聚合、复核/样本/Gold/评测读取与多种附件/导出仍需单独核验和接入；当前实现不等于I7完成，也不覆盖规则未识别的任意PII。未改framework、未执行共享SQL/部署；输入仍是处理好的会话。

### 集成回归（2026-10-17）

集成安全任务展示元数据与精确 JSON/嵌套载荷修复后，七个测试类共81项通过，无失败、错误或跳过。新增`TaskPresentationProjectionTest`的12组历史快照分别检查两种详情响应：隐藏Prompt后仍返回有限任务类型/labelOnly信息，未知与冲突历史不推断为未命中，原任务快照不变。`ResultPayloadProjectionTest`三项检查嵌套结构字段名不能绕过PII遮罩、长小数与大整数不损失精度且共享ObjectMapper不变，以及文件名与标签业务值的双授权边界。

`JointLabelMaterializationTransactionTest`49项通过，使用本机H2、真实Spring事务与随机端口HTTP；测试身份没有令牌grants捷径，组织服务夹具每次返回新鲜的当前`iqc:result:view`，不赋予全文或PII权限。断言脱敏标志及另一账号即使具备基础结果权限也不能读取他人任务。此证据不替代真实组织服务、MySQL、网关或浏览器验收；默认全量测试另记终态。

随后在`471337a`源码上运行默认全量`mvn -o -q test`（显式传入本机已安装的ByteBuddy agent），进程退出0。149份当前Surefire报告合计1067项，失败/错误/跳过均0；报告修改时间覆盖本轮2026-10-17 14:42:50至14:47:59，包含之前失败的联合事务与标签HTTP测试。结果仅证明默认本地测试覆盖范围，不将其泛化为整个I1–I7或真实环境验收完成。日志`/tmp/iqc-full-integrated-regression.log`。


## 不含Prompt的任务展示元数据（独立修复分支）

隐藏ruleSnapshotJson后，结果页仍需要识别旧规则、Scheme、Pipeline和仅识别标签方案，否则会误用消息平均分或隐藏业务复核/导出入口。复用现有ResultReadProjection，在两种会话result-detail和单结果detail响应顶层增加taskPresentation，不新增表或API。

字段为schemaVersion=iqc-task-presentation-v1、kind=LEGACY/SCHEME/PIPELINE/UNKNOWN、labelOnly=boolean/null。只由原冻结快照解析固定枚举和布尔，不包含Prompt、规则正文、ID、自由文本或任意JSON。无效历史返回UNKNOWN/null，不回退暴露原快照，也不把未知推断成无命中或零分。Scheme有质检项即非labelOnly；仅items为空且labels非空时labelOnly=true，历史省略labels不能使正常计分方案误判未知。

前端优先使用该元数据，旧响应缺字段时保留旧快照兼容；未知元数据隐藏依赖类型的评分摘要及业务操作并提示核对。原结果、坐标、Finding及机器/人工记录不改。编译状态以根协作记录为准；未新增或执行测试，无SQL/部署，完整敏感读取面仍未完成。


## 响应JSON的精度与嵌套边界加固

结果投影采用响应本地的ObjectMapper副本，沿现有Selector精确树读取配置保留BigDecimal数值，不改变全局Jackson或冻结JSON。结构白名单只适用于结构文本及明确的messageIds文本数组；同名对象/异常容器必须继续递归，不能整棵deepCopy。Finding的value、标签valueJson/人工有效值以及变量/输入/业务载荷内部不继承外层ID或Hash字段豁免；Hash豁免限64位十六进制文本。已知证据的结构ID、序号、偏移与数值事实仍按原类型保留，不以字符串重写数字。

结果行、会话摘要及标签结果的sourceFileName复用ConversationReadProjection的原字段级CUSTOM规则，避免只依赖自由文本匹配漏掉文件名中的无标签身份。没有完整+PII授权时遮罩副本，原档案和存储文件名不改。自由文本规则仍不保证识别任意无标签PII；未知JSON和所有响应面尚需逐项验收，不据本批宣称I7完成。Root未新增或执行测试，编译状态见协作记录。


## Agent、规则及模型配置的审计正文边界

源码核对GatewayCall、GatewayCallService.CallView及ModelGateway：当前调用账本仅保存身份、部署版本、用量、费用、状态和安全错误类型，不保存Prompt或原始供应商响应；不为此增加第二套调用日志表。模型调用正文是否可配置落盘及其他读取面仍单列验收，不能将metadata账本当作正文日志。

QualityConfigController的18个配置审计入口（Agent七项、规则/导入六项、规则集五项）与IqcModelProfileController原四个模型变更入口统一request=false、response=false。具有路径ID的变更以固定#id作为目标，Agent回滚包含versionNo；创建及批量导入保留原操作/URL身份，不从配置正文取审计key。规则集版本创建、提交、审批、驳回补原Audit注解，覆盖此前未记录的配置操作。

这样保留原操作者、时间、模块、操作类型、路径目标与结果，避免完整Prompt/表达式/模型配置被复制到未具独立敏感授权的通用审计载荷。原服务、角色权限、审批与不可变版本继续执行；没有关闭原领域配置/冻结输入存储，没有修改framework或发起实际审计发送。预算配置原显式审计及已冻结发布原因不在本批改动，亦不由此推断其它端点安全完成。构建和独立回归状态见协作记录。


## 业务结果分页与批次汇总副本

原/results/business与/tasks/{id}/result-summary也返回归档文件名，不能绕过详情投影。现复用ResultReadProjection复制原BusinessConversationRow/IqcPage和BatchResultSummary/ConversationResultSummary；文件名沿原CUSTOM字段规则，任务名沿原自由文本规则，只有完整+PII双授权返回原展示文本。ID、状态、原BigDecimal分数（含0/null）、分页current/size/total、会话及各类统计保持，不重新计算或修改原对象。

两个入口在原service查询前刷新固定基础授权：业务分页只要求其已有iqc:result:business:view，不额外误要求legacy result:view；批次汇总仍result:view。保留原Task数据范围，不新增资源或路由；成功返回no-store/nosniff。查询Audit只记录目标/元数据，不含请求/响应正文；缺敏感组件的嵌入构造fail-closed。标签聚合等其他副本仍待独立核验，不以本批声明全读取面已保护。Root未新增或执行测试，构建及B验证以协作记录为准。


## 金标精确数值与展示回写前置边界

金标原始值和完整输入不可因脱敏展示或浏览器浮点改变。GoldFinding.value、GoldDecision.labels及Gold草稿/审核/标注的businessContext复用现有人工复核ExactValueDeserializer，仅为这些JsonNode入口启用精确树，不改全局mapper/数据模型/协议。GoldDecision.score原BigDecimal绑定保留。

金标编辑器对已有无法由浏览器无损表示的草稿保持原JSON只读，历史快照不经浮点美化；新NUMBER/业务分数先保留用户输入文本，保存前复用精度检查，OBJECT/业务上下文/高级标签JSON亦检查。不能精确表示的值明确拒绝表单保存，不悄悄舍入；后端原标注接口可接收原始精确JSON。更完整的精确可视化编辑与金标隐私投影/回放防护仍待，不以这个前置保护宣称完成Gold/I7。

## 配置审计真实切面回归与规则解析错误（2026-10-17）

集成8f9a89b等价本地提交58cfc6b：原QualityConfigController18个配置动作、IqcModelProfileController4个模型动作关闭请求和响应审计正文，路径ID与Agent回滚版本作为目标键，四个规则集生命周期保留审计。ConfigurationAuditPayloadTest显式列22方法，每方法成功/失败两种共44项，通过真实已安装AuditAspect、实际Controller注解及SpEL参数名生成事件；领域proceed与本地事件sink隔离，验证无配置canary且操作者/目标键/操作/URL/耗时/失败元数据保留。不是HTTP/PDP/领域事务或EDA落库验收，预算接口沿原独立审计行为。

审阅发现原QualityRuleService.validateExpression拼接exception.getMessage，正则解析异常含表达式全文，会通过AuditAspect.errorMessage绕过正文开关。应用服务改为固定错误，正则只附数值位置；结构化条件保留固定字段/操作符/JSON类别，不返回用户原字段或表达式。异常cause保留诊断；此变更只保护该边界外层消息与审计事件，不声明已治理所有堆栈或日志。规则解释/匹配及领域权限不变，无framework修改。

新增3项真实Controller+Service+AuditAspect覆盖REGEX/STRUCTURED/DLS错误，正则cause实际包含canary证明风险输入确实触发底层解析消息；事件和外层异常均无canary，无DAO写入。最终93384原handle exit0，Audit47项+原QualityRuleService9项合计56 run、0 failures/errors/skipped；日志/tmp/iqc-config-audit-validation-compatible.log。11838首轮仅原结构化诊断类别断言失败，补回固定安全类别后原测试保持通过，未恢复解析正文。原26066的44项也exit0。

其它Prompt/配置读取面、任意服务异常、日志保留、真实权限/HTTP/审计落库及完整I1–I7仍待，不据此声明I7完成。


## 金标HTTP展示与独立功能权限

GoldSampleController的草稿、版本、历史、标注和审核在原service前刷新各自原有固定基础权限；原样本/版本数据范围继续由服务验证。GET审计关闭正文，写接口沿原无正文审计，成功响应no-store/nosniff；旧单依赖嵌入构造缺敏感组件时fail-closed。

金标专用读取只有完整+PII双grant才返回未经投影的原goldDraftJson及版本snapshotJson；其它模式不返回可误当原始输入的opaque字段。独立GoldSampleReadProjection复用既有ConversationReadProjection，从原冻结GoldDraft/GoldConversation构建完整脱敏或前三条/200字符预览；只显示Finding code/type/state/证据ID元数据，值和业务上下文不返回。样本及会话身份必须与快照绑定，损坏/缺省快照仅返回明确状态，不能视作无事实。没有读取当前会话替换旧输入；候选policySource已核实仅公共版本身份，不含节点配置/Prompt。

泛样本sample/samples投影可按原sample:view+双grant展示候选内容，但始终不附带完整goldDraftJson，需独立Gold读取功能权限；没有双grant时源contentSnapshot/expectedJson/tagsJson也withheld。审核历史只给原版本摘要，始终不附snapshotJson，不能绕过独立gold:version:view；无完整权限时审核原因也withheld，防止其复制全文，完整但无PII时沿原文本规则处理原因。原ID、修订、状态、hash、分页及操作者身份保留。

标注/审核写响应仅返回身份、修订、状态等回执，不把写权限当作完整读取权限；原写命令与角色、独立审核、CAS和原始存储不改变。编辑器保存后另走已授权GET重新取得草稿，失败明确提示已保存但加载失败并清除旧展示；受限预览不能回写或被当原始金标审核。QualitySample/Version的privacy为exist=false且null不序列化，不改原领域JSON或冻结hash；预览永不重算原hash。

其它样本列表/创建入口由协作开发者接同一sample/samples/sampleMetadata投影，未接入前仍是已知旁路；候选、数据集、评测和其它副本仍需逐项核验。当前是HTTP读取/回执投影，不声明完整I7、任意无标签PII识别、真实角色/数据库/浏览器验收完成。Root未新增或执行测试、SQL或部署。
### 反馈响应副本保护

反馈列表与提交返回值复用SensitiveReadAccessService和ResultReadProjection；分别刷新既有result:view、result:feedback基础权限后才调用领域服务，缺少接线直接拒绝。原结果归属和数据范围校验保留。两个响应均private,no-store与nosniff，查询审计不记录正文。

反馈comment和evidenceJson仅完整会话与PII双授权可返回原文；其它组合按原自由文本规则遮罩。证据全部视为用户payload，id等结构形似键不豁免；非法JSON返回既有withheld标识。所有操作在响应副本进行，原始反馈保存值及精确数字不改。本批不覆盖复核/样本/Gold等其它入口，也不保证识别任意无标签PII。验证结果以协作记录的终态为准。

## 业务结果分页与批次汇总副本

原/results/business与/tasks/{id}/result-summary也返回归档文件名，不能绕过详情投影。现复用ResultReadProjection复制原BusinessConversationRow/IqcPage和BatchResultSummary/ConversationResultSummary；文件名沿原CUSTOM字段规则，任务名沿原自由文本规则，只有完整+PII双授权返回原展示文本。ID、状态、原BigDecimal分数（含0/null）、分页current/size/total、会话及各类统计保持，不重新计算或修改原对象。

两个入口在原service查询前刷新固定基础授权：业务分页只要求其已有iqc:result:business:view，不额外误要求legacy result:view；批次汇总仍result:view。保留原Task数据范围，不新增资源或路由；成功返回no-store/nosniff。查询Audit只记录目标/元数据，不含请求/响应正文；缺敏感组件的嵌入构造fail-closed。标签聚合等其他副本仍待独立核验，不以本批声明全读取面已保护。Root未新增或执行测试，构建及B验证以协作记录为准。

业务分页/汇总已与反馈投影合并保留。业务分页taskId可省略，审计不再把可空筛选值作为目标键，保留原查询元数据及正文关闭。新增BusinessSummaryPrivacyTest覆盖四种敏感grant组合、原对象不变、零分/null分、分页与全部会话统计不重算、授权撤销先于第二次查询及批次固定result:view；原InspectionResultControllerTest改用完整接线和仅business:view的敏感授权模拟，MockMvc验证no-store/nosniff与业务分页零分及总数。13724终态exit0，连同反馈4项和结果投影6项，本轮共14项通过；此处MockMvc不含真实网关/PDP，直接Controller撤销模拟也不代替组织服务真实授权验收。

补跑原查询服务测试发现单类运行依赖其它测试预热InspectionTask的MyBatis lambda元数据，66105首轮15项中1error。夹具按已有TableInfoHelper模式显式初始化实体后，74081最终exit0，BatchResultQueryServiceTest15+BusinessSummaryPrivacyTest2+InspectionResultControllerTest2共19项零失败/错误/跳过，涵盖原任务无权/无可见任务边界。未修改生产查询服务或降低断言；日志/tmp/iqc-business-summary-scope-final.log。此次合并仍非当前完整默认套件验收。

## 金标精确数值与展示回写前置边界

金标原始值和完整输入不可因脱敏展示或浏览器浮点改变。GoldFinding.value、GoldDecision.labels及Gold草稿/审核/标注的businessContext复用现有人工复核ExactValueDeserializer，仅为这些JsonNode入口启用精确树，不改全局mapper/数据模型/协议。GoldDecision.score原BigDecimal绑定保留。

金标编辑器对已有无法由浏览器无损表示的草稿保持原JSON只读，历史快照不经浮点美化；新NUMBER/业务分数先保留用户输入文本，保存前复用精度检查，OBJECT/业务上下文/高级标签JSON亦检查。不能精确表示的值明确拒绝表单保存，不悄悄舍入；后端原标注接口可接收原始精确JSON。更完整的精确可视化编辑与金标隐私投影/回放防护仍待，不以这个前置保护宣称完成Gold/I7。

### 金标精确值生命周期回归

集成86609ac等价8aa3b99字段级解码，不修改全局Jackson。GoldSampleReviewTest新增一条真实GoldSampleService标注、独立审核和EvaluationDatasetService冻结的贯穿用例：默认ObjectMapper解码Annotation的高精度NUMBER、嵌套OBJECT、大整数、补充context和labels，断言精确数值；读取审批GoldConversation和冻结DatasetSnapshot后再次断言精确值，逐字序列化保持，成员金标hash及数据集外层hash一致，标注草稿未因审核被改写。DAO为mock，不代表事务/数据库或HTTP与UI验收。原78610终态exit0，日志/tmp/iqc-gold-exact-lifecycle.log；原金标审核和数据集历史/防篡改回归同轮通过，数量见协作记录。样本/金标读取隐私与前端精确编辑仍需独立交付。

### 冻结候选元数据的精度丢失修复

在原金标字段级精确解码之外，GoldSampleService.reviewedCandidate还会把contentSnapshot读为普通JsonNode再嵌入金标context。新增frozenCandidateMetadataKeepsItsExactNumbersDuringAnnotation先复现：冻结候选原值0.12345678901234567890123456789在draft变成0.12345678901234568（74878终态exit1，单例精度断言失败）。

应用服务仅在read目标为JsonNode时使用局部mapper副本启用BigDecimal和精确节点工厂，保留现有大小/空值检查、模块和其它typed读取；不改共享Jackson，不重新捕获当前会话，也不写回原候选内容。回归同时检查原冻结contentSnapshot未变及大整数保持；最终测试状态见协作记录。

PipelineAssetService.hash仍沿历史普通树规范化，不能据hash保持断言证明它区分所有高精度差异；更换摘要协议和历史兼容需要独立处理，本批没有改全局算法。真实HTTP转换器、数据库持久化、UI与其它候选生产解析边界不在这条局部修复的证明范围。


### 通用样本入口接线

通用samples GET在查询前刷新原sample:view，复用samples投影，即使双敏感grant也不返回goldDraftJson；创建POST在写入前刷新原sample:manage，只返回sampleMetadata。原业务候选/消息样本领域校验及数据范围保持，不新增写权限，不因写授权返回冻结正文。共享反馈授权接线改名readAccess，仍仅接受控制器内部固定资源码；缺少组件拒绝，成功no-store/nosniff。样本列表审计不记录正文。此接线收口此前通用样本已知旁路，其他读取面及行为验收仍待。


### 数据集与离线评测写操作审计

EvaluationDatasetController创建、更新、冻结、可用性四个写操作及PipelineEvaluationController创建评测均显式关闭审计请求与响应正文。已核对框架Audit默认request=true、response=false：本次实际关闭数据集草稿、名称等请求正文，显式保留响应关闭；冻结响应含完整金标snapshotJson、评测创建响应含冻结任务配置，继续禁止其进入审计正文；更新/冻结/可用性保留路径id目标，创建不使用未生成ID作审计键。沿用既有Audit事件及权限和领域调用，不新增日志表，不改变冻结内容或hash。此处仅减少审计正文；原HTTP数据集和评测返回值仍须另行实施读取投影与实际权限验收。

### 冻结数据集版本展示边界

版本GET、历史GET及freeze POST分别在领域调用前刷新原dataset:version:view、dataset:versions:view、dataset:freeze；缺敏感组件拒绝，原服务数据范围与完整性/hash校验保持，成功no-store/nosniff。两个查询Audit仅目标元数据。版本专用读取仅FULL+PII双grant返回原snapshotJson；限制模式不返回原opaque正文，解析原冻结DatasetSnapshot并验证datasetId，逐成员复用GoldSampleReadProjection获得完整脱敏或3条/200字符会话预览和无值Finding元数据，保留样本/版本/hash引用。损坏、来源不匹配或超限明确INVALID_DATASET，不回退原文、不查询当前会话。

历史与冻结写回执无论敏感grant均只给版本摘要，不含snapshotJson或预览，避免用写/历史权限绕过专用读取。新增privacy仅exist=false且null不序列化，不改持久化和冻结hash。UI未知策略不显示正文，受限预览明确非原始金标；每次版本读取先清旧内容并用现有generation拒绝迟到响应，完整快照原文本展示，不经JSON.parse/数字舍入。数据集草稿的名称/说明、评测结果actualJson及其它副本仍待，不以本批声明I7完成。仅构建检查，行为、浏览器、真实组织与数据库验收仍待协作验证。

### 离线评测机器产物读取

PipelineEvaluationController分页与详情在原service前分别刷新固定evaluation:results:view/result:view，保留原任务范围、轮次、产物hash及来源校验。成功no-store/nosniff，查询Audit无正文。分页始终仅元数据，防止绕过独立详情授权；详情只有FULL、PII、Prompt三grant同时具备才返回原actualJson，因为任意机器输出、变量或错误可能复现输入及Prompt。其余模式只展示原运行枚举状态、轨迹/事实数量及明确的POLICY_SATISFIED标记；不展示变量、值、错误或轨迹正文，不把受限正文视作无命中。解析失败/超限返回INVALID_OUTPUT，不回退原文。

仅复制原EvaluationSampleResult，privacy字段exist=false/NON_NULL；原status/durationMs、dataset/gold/actual哈希与执行/样本身份不改、不重算。UI读取前清旧详情并拒绝迟到覆盖，未知policy不显示正文；受限摘要明确标记，完整产物按原字符串展示避免数值舍入。现有Prompt敏感资源的实际登记/授予仍待，不借系统ADMIN或JWT绕过；当前缺grant仅限制正文，不阻断原基础权限下元数据查看。评测create回执、report和通用task读取及数据集草稿仍需单独处理；当前仅构建检查，不代表真实组织/浏览器/数据库验收。

### 评测创建任务回执及报告当前授权

评测create在领域写入前刷新原evaluation:task:create，返回ResultReadProjection.taskReceipt副本：保留任务ID、状态及运行元数据，移除agent/rule/label快照、Prompt、触发请求、重试策略与错误、筛选JSON、会话/规则集合JSON。写grant不能充当读冻结输入grant，即使全敏感grant也不在创建回执附带这些内容。任务名称沿现有文本保护，领域存储不变；UI创建仅消费task.id，当前流程兼容。普通任务及Benchmark入口尚待独立接线。

报告刷新原evaluation:report:view先于service，沿用原Task/Execution范围与完整金标分母、数值指标及原样本来源，不重算、不投影成空报告。查询Audit仅taskId且无正文，成功no-store/nosniff；缺接线拒绝。构建检查不能替代实际组织/HTTP/浏览器行为验收。
84131专项最终exit0，逐XML确认GoldSamplePrivacyTest5、FeedbackPrivacyTest4、BusinessReviewControllerTest10、GoldSampleReviewTest12、EvaluationDatasetVersionTest7，共38 run、0 failures/errors/skipped；日志/tmp/iqc-gold-sample-privacy.log。本轮为直接Controller与投影/领域服务既有测试组合，未重新执行默认完整套件，也未验证本批HTTP/PDP/UI路径。

### 默认MVC金标请求的跨代树绑定

GoldSampleHttpContractTest通过默认MockMvc路由/转换器执行GET样本与Gold的四敏感grant组合、预览条数/200字符、no-store/nosniff、源对象保持，以及PUT标注精确值和POST样本元数据回执。领域与组织授权为mock，不代表网络服务器/PDP/数据库验收。

41055首轮11项中10通过、1 error：默认Jackson3 MVC转换器无法实例化GoldFinding.value的Jackson2 JsonNode，PUT请求在进入service前失败；单独用Jackson2 ObjectMapper读请求的既有测试不能覆盖此问题。已核对应用与framework WebMvc配置，未注册专用Jackson2 HTTP转换器，不修改框架或通过测试强制换转换器绕过。

应用层复用HumanFindingReviewEvaluator原精确解码位置，新增HttpExactValueDeserializer用于金标请求字段：Jackson3局部精确数字树转成原Jackson2精确领域树。仅给GoldFinding.value、GoldDecision.labels内容、GoldSampleService.Annotation.businessContext补Jackson3字段注解，保留原Jackson2解码注解、持久化模型、共享mapper和历史hash。当前结果以协作记录终态为准；其它使用Jackson2树的HTTP请求面仍需逐项核验，不声称已全局迁移。

17259中间回归30项29通过/1失败：字段级readTree默认尾随检查把下一个同层属性误当多余文档。仅局部HTTP字段mapper关闭FAIL_ON_TRAILING_TOKENS，外层MVC保持严格；新增完整请求后追加第二JSON文档返回400且授权/领域均未访问的回归。最终74757 exit0：GoldSampleHttpContractTest7、GoldSamplePrivacyTest5、GoldSampleReviewTest12、EvaluationDatasetVersionTest7，共31项，无失败/错误/跳过，日志/tmp/iqc-gold-sample-http-fragment-final.log。此为默认MockMvc契约及既有领域专项，不冒称真实PDP或完整全套验收。

### Benchmark聚合响应中的任务快照

BenchmarkController的create/pair/report分别在领域调用前刷新既有benchmark:create/view/report:view，缺接线拒绝，成功no-store/nosniff。创建审计关闭请求/响应正文，查询审计仅baselineTaskId。ResultReadProjection复制Pair及Report，双侧InspectionTask复用taskReceipt，所有grant组合均不在实验摘要/报告附带冻结任务配置或Prompt；实验名称沿已有文本规则。保留实验身份、数据集版本/hash、selection、comparison、双侧报告、complete及原BigDecimal差值。已核对Comparison仅selection、允许路径和各类hash，不含原变量或Prompt正文。原领域双向绑定、单变量比较、任务范围和原始产物均不改。UI创建后仅使用pair.baseline.id保持兼容，原始任务配置读取需走后续独立受控入口；通用任务接口和数据集草稿尚待。仅编译检查，不代表HTTP、真实角色、浏览器或完整I1–I7验收。

### 数据集草稿与写回执

数据集page/get/create/update/availability在领域调用前分别刷新原dataset:view/detail:view/manage/update/availability，沿现有敏感接线no-store/nosniff，两个查询补无正文Audit。ResultReadProjection.dataset只复制PO；专用详情且FULL+PII双grant才返回原draftConfigJson，限制模式隐藏整个原草稿，仅在校验成功时提供精确sampleId/versionNo引用；不返回description。损坏或超限明确INVALID_DRAFT，不回退原文。名称沿既有文本规则，不保证识别任意无标签PII。

目录与所有写回执始终不附原草稿或成员预览，不能借列表/写权限绕过详情读取。privacy仅exist=false/NON_NULL，原版本、修订、成员、状态及存储不改。UI未知/受限展示只读，不能将遮罩名称/缺省说明重写为原数据；保存先消费元数据回执、清旧历史/快照并独立GET，读取失败明确“已保存但重新读取失败”，保留受限只读状态，不建议重复提交。原直接写命令不新增FULL/PII要求，服务范围和CAS保持。当前仅构建检查，角色/浏览器/HTTP验收及完整I1–I7仍待。

### 通用任务写回执

InspectionTaskController.create/cancel/pause/priority复用taskReceipt，始终不返回任务冻结快照、Prompt、筛选/集合JSON、触发/重试请求或错误正文；保留原任务ID、状态、计数等回执元数据。五个写入口（含无返回值delete）在领域操作前刷新原task:create/cancel/execute/delete固定grant，缺敏感组件拒绝，不新增写命令的FULL/PII要求；成功no-store/nosniff。写Audit显式关闭请求/响应正文，路径操作保留id目标。原服务状态转换、任务范围、创建用量记录保持，响应仅副本，未改存储。已核对任务UI四写返回均未消费冻结配置，操作后走原refresh。list/get/executions以及执行/恢复等其它控制器仍待独立保护，不以此声明全部任务读取已收口；当前仅编译检查，不代表行为/角色/浏览器验收。

### 执行与恢复任务回执

InspectionResultController.run/resume复用已有resultReadAccess固定task:execute，在queue/resume及异步提交之前刷新当前授权，成功沿no-store/nosniff。两响应仅taskReceipt副本，任何敏感grant组合均不附冻结配置/Prompt/筛选/触发/重试正文；缺接线拒绝。原异步执行、worker启停时背压处理、当前executionId、RUN用量记录及领域状态转换不变。审计仅id目标并关闭请求/响应正文。任务UI原run/resume均只等待成功再refresh，不依赖正文；读取接口和其它创建入口仍需继续处理。此处仅编译检查，不等同实际执行链路、权限和浏览器验收。

### 流水线任务创建返回

PipelineAssetController.trial/task在访问冻结草稿、发布版本或Selector资产前刷新原pipeline:execute/task:create；返回统一taskReceipt，覆盖显式会话IDs、内联Selector与发布Selector资产分支。原互斥参数检查、冻结来源、请求幂等、数据范围及任务构造保持，任何敏感grant组合均不通过写响应附带快照/Prompt/筛选正文。成功no-store/nosniff，缺接线拒绝；两个写Audit仅id且关闭正文。已核对流水线页面创建后仅消费任务ID/名称/状态，无快照依赖。方案试跑页面仍从快照读取修订/标签类型，须先补公共展示元数据再收口，当前未误删该功能；其它流水线配置/历史读取与完整I1–I7仍待。仅编译检查，不代表实际角色/HTTP/浏览器验收。

### 方案试跑公共元数据与任务回执

InspectionTask新增taskPresentation仅exist=false/NON_NULL，由taskReceipt在HTTP副本上生成，复用已有iqc-task-presentation-v1任务类型/labelOnly判断，扩展hasLabels、正整数schemeDraftRevision及格式受限selectedVariantCode。无名称、Prompt、规则/变量或任意快照JSON。未知/损坏不伪造修订或标签类型，原存储/冻结序列化和hash不变；执行/恢复等原回执自动保留这些展示信息。

InspectionSchemeController trial/trials/task分别在领域调用前刷新原scheme:trial:create/trial:view/use，缺接线拒绝，成功no-store/nosniff，无正文Audit及id目标。试跑创建/列表及正式模板任务的BATCH/SCHEDULED、默认/显式variant分支统一taskReceipt，不附原冻结正文。原请求幂等、修订、数据范围、任务参数互斥及发布证据验证保留。UI优先公共元数据，旧响应无元数据仍兼容原展示；新响应未知/不完整元数据明确提示并阻止确认发布，不回退私密快照。variant只显示受限代码，避免自由名称扩散。方案配置/模板读取、通用任务列表/详情等尚待；当前仅构建检查，不代替真实角色、HTTP、浏览器验收。
### Benchmark及数据集草稿MVC验证与跨代输出修复

从3cccf3b接A0fd6b7c/ea80b68为a1b17af/a52561d，保留既有投影/精确绑定。BenchmarkDraftHttpPrivacyTest八种敏感grant覆盖create/pair/report双侧任务无冻结正文、选择与比较树完整相等、数据集身份/零值/高精度差值/原对象保持；四数据集grant覆盖独立详情正文、受限成员引用、目录和三写回执无草稿、损坏状态与固定基础grant拒绝先于领域调用。默认MockMvc、真实投影，领域/PDP mock。

首轮68072为46项38通过/8失败：默认Jackson3把Jackson2 ObjectNode当普通POJO，仅返回节点属性而丢失selection/comparison内容。基于测试实际classpath独立复现/tmp/IqcTreeSerialization.java确认。仅BenchmarkService.Pair两个HTTP JSON字段新增HttpTreeSerializer，以局部精确Jackson3树writeTree输出；保留Jackson2领域类型、原比较算法/存储/历史hash/全局converter，不改framework。此补丁不代表所有跨代HTTP树均已验证。

最终68868 exit0，/tmp/iqc-benchmark-draft-http-privacy-final.log；逐XML BenchmarkDraftHttpPrivacyTest14、EvaluationCommandHttpPrivacyTest11、DatasetEvaluationHttpPrivacyTest14、GoldSampleHttpContractTest7，共46项，0 failures/errors/skipped。无SQL/浏览器/真实组织验收，未重跑默认完整套件，整体I1–I7仍未完成。

### 通用任务读取及跨页面公共导航

InspectionTaskController list/get/executions分别在原服务前刷新固定task:view，缺接线拒绝，成功no-store/nosniff；查询审计关闭正文，详情/轮次仅id目标。list通过taskRead副本始终不附快照；get仅FULL+PII+Prompt三grant才附原agent/rule/label快照与autoExpandPrompt，因为冻结评测任务可嵌全部金标。其它筛选/集合JSON、触发请求、重试策略/错误始终withheld；不修改原存储、范围、状态、计数/hash。executions原记录仅id/轮次/状态/数量/时间/current，无自由正文，刷新权限后原样返回。

InspectionTask.privacy为派生exist=false/NON_NULL；公共taskPresentation继续复用Scheme展示事实，并增加校验过的conversationCount（损坏/缺失为null，不视作0）。仅冻结任务确认为PIPELINE_EVALUATION、benchmark schema/side及双方ID格式有效且不相等时提供benchmarkBaselineTaskId，用于页面导航；实际Benchmark查询继续原双向绑定/范围/冻结证据验证，此元数据不授予权限。评测页优先该导航元数据，不为恢复入口读取整份快照；旧响应无元数据仍兼容。

任务详情页受限/未知policy隐藏配置区并明确提示，不把隐藏正文解释成历史没有规则/Agent；会话数量未知明确展示未知，元数据不回写配置。周期计划/事件订阅实例列表、其它配置/复核副本仍待；仅构建检查，不等同真实组织/HTTP/浏览器和全部I1–I7验收。

### 周期计划与事件订阅任务实例

PeriodicScheduleController/EventSubscriptionController.instances分别在原query前刷新schedule:instances:view/subscription:instances:view，不误要求额外task:view；原服务仍检查负责人/组范围。复用taskRead(false)，所有敏感grant组合均仅列表元数据，不附冻结配置、Prompt、筛选、触发请求或错误正文。原分页current/size/total、顺序、任务状态、executionId、计划时刻/事件ID和公共计数保持，响应副本不改原任务。缺接线拒绝，成功no-store/nosniff，查询Audit仅id且关闭正文。

两个Controller的创建/修订/发布/可用性共8个写Audit均显式关闭请求/响应正文，路径操作保留id；原发布response=true会复制冻结配置，现仅保留审计事件元数据，不改领域发布/触发行为。配置/历史HTTP响应仍需独立读取保护。事件实例表格及任务详情对withheld triggerError明确显示原因未展示，不把缺省当作等待接入会话或空原因；其余UI原任务状态/链接依赖保持。当前仅构建检查，真实角色/HTTP/浏览器及完整I1–I7仍待。

### Prompt独立敏感资源的实际读取入口

新增GET /api/iqc/tasks/{id}/configuration，ResourcePermission使用原SensitiveReadAccessService.PROMPT码iqc:sensitive:prompt:read。复用原任务Controller/授权服务/taskRead，不新增存储或ACL模型；先刷新固定task:view+Prompt基础资源，再要求FULL+PII+Prompt当前均有效，最后调用原taskService.get执行数据范围检查。仅原冻结快照，无实时配置替换；成功no-store/nosniff，审计无正文/id目标，限流沿原RateLimit。原通用task GET继续同三grant控制正文，不因新路由放宽。该实际功能入口解决此前只有常量、没有可登记Prompt资源的问题。

组织权限树新增迁移V20261018_01，资源/按钮900263挂任务900103，仅IQC_ADMIN/IQC_QUALITY_MANAGER，沿原SHA2关联与自然键去重，不授系统ADMIN/查看者/质检员，不隐含FULL/PII授权。该迁移仅本地草案，未执行或部署，真实角色和MySQL新库/升级/重复执行验收仍待。

任务页面提供独立敏感读取按钮，读取开始清旧快照，失败保持受限内容，切换任务/关闭详情/卸载均阻断迟到响应；菜单可见性不替代后端实时授权。当前仅编译/构建检查，不能宣称Prompt权限已在环境生效、或全I1–I7完成。

### 模型容量与预算HTTP字段边界

ModelGatewayController的ConfigureCapacity.concurrencyLimit/budget、ReconcileBudget.tokens/cost、RecoverBudget.budget五个JsonNode record字段复用已存在HumanFindingReviewEvaluator的ExactValueDeserializer和HttpExactValueDeserializer，分别支持Jackson2领域/历史调用和默认Jackson3 MVC字段绑定。局部转换保留整数、极长小数及字符串，不改变全局mapper/HTTP转换器、持久化类型或预算服务；原并发canConvertToInt、版本精确字符串、Token/金额字符串及证据/确认/状态校验保持，不放宽数值。

该接线来自对已验证Gold跨代问题的同类输入面排查；本批仅test-compile，未在这些预算入口复现或执行HTTP测试，不宣称行为验收已通过。PRD§18.3和安全条款要求模型调用数据可配置落盘，现GatewayCall账本只有用量/费用/容量元数据，不含模型请求/响应正文；该日志配置需求仍待设计实施，不因字段绑定修复声明完成，也不新增重复日志表。

### 模型调用账本列表自由证据

ModelGatewayController.calls和TaskModelCallController.page在query前刷新各自原model-gateway:calls:view/task:model-calls:view，不追加错误的task:view要求，原全局管理员/任务数据范围检查保留；成功no-store/nosniff，无正文Audit，任务查询仅id目标。ResultReadProjection.gatewayCalls复制原CallView，人工capacityReleaseEvidence、budgetEvidenceRef、reconciliation.evidence仅FULL+PII+Prompt三grant齐全才原文，否则null；请求模型名/响应模型名/部署引用沿既有文本规则。操作者身份、状态、日期、全部Token及精确金额字符串、hash、版本、预算/容量事实不变，costSummary直接保留原对象，不重算统计。

沿用原CallPage增加可空privacy并保留旧5参构造，不新建日志表/副作用，不修改GatewayCall存储或预算对账证据。UI提示凭据受限，隐藏不等于没有凭据；请求开始清旧列表/隐私状态并沿原generation防迟到。容量详情与写回执等其它读取面仍待，现有日志账本不含模型请求/响应正文，模型调用数据可配置落盘依然未实施；不能以本次列表投影声明完成该PRD要求或全I7。当前仅构建检查，默认MVC、真实账号/SQL/浏览器验收待协作补齐。

### 周期与订阅配置/历史读取

PeriodicScheduleController及EventSubscriptionController配置目录、详情、创建、更新、发布、启停、历史分别刷新各自原固定资源，先于服务调用；preview也刷新其原preview资源。原负责人/组范围、CAS、不可变发布、调度与事件触发不改，不新增写命令的FULL/PII/Prompt要求。查询审计无正文，路径操作仅id，成功no-store/nosniff；旧缺接线构造拒绝。

ResultReadProjection复用四个原PO响应副本：目录与所有写回执永不附draftConfigJson/snapshotJson；详情与历史正文只有FULL+PII+Prompt三grant才原文。受限lastError为空，名称沿原文本规则；版本号/修订/活动指针/状态/计划时间/原hash/分页及身份保留，不重算、不修改原存储。新增privacy仅exist=false/NON_NULL，不改原冻结序列化或新增表。历史原本是唯一版本正文入口，沿原versions:view独立授权，不增加虚假版本路由。

两页UI未知/受限policy只展示元数据，隐藏配置表单，不以默认Cron/事件配置填入真实草稿；不能从副本编辑、预览或确认发布。最近错误明确未展示，不当作没有错误。保存/发布/启停成功先清旧历史/配置，再独立GET；失败提示操作已完成但重新读取失败，不建议重复提交。当前编辑存在未保存内容时先阻止同资产启停，避免静默丢弃。重新读取先清旧正文并用generation拒绝迟到；关闭/卸载清配置和历史。版本正文按原字符串展示，不做浮点美化。

仅构建检查，真实角色/HTTP/浏览器与调度SQL仍待验收；其它Selector/Pipeline/Scheme配置/人工复核副本及全部I1–I7仍未完成。

### Selector选数预览的PII副本

ConversationSelectorController.preview和SelectorAssetController.preview分别在解析/查询前刷新原selector:preview、selector:asset:version:preview，保留原选数scope、定义解析/精确JSON、发布版本和限流。成功no-store/nosniff，查询Audit不含输入条件或输出正文；发布版仅id:version目标，缺接线拒绝。没有增加Prompt要求，因为预览不返回模型配置或Prompt。

ResultReadProjection.selectorPreview仅复制原Preview：FULL+PII双grant才显示原employeeId和员工维度map；否则员工标识和两个employees map为null并明确withheld，不生成掩码员工键而造成碰撞/错误合计。文件名复用原字段mask，渠道沿原文本规则。候选/命中/抽样/最终计数、limited、时间窗口、会话ID、团队/部门结构ID及其原分布、时间/风险和definitionHash均保持，不重算、不修改原选集或定义。组织结构ID用于原分布关联，不视作员工标识。自由文本规则不保证识别任意无标签PII。

原Preview增加可空privacy且保留旧12参构造，null不序列化；领域内部旧预览保持原结构，不改Selection持久化/冻结hash或新建表。UI明确员工信息未展示不是没有员工，未知policy隐藏样本/分布明细；旧无privacy响应仅兼容历史部署。当前仅编译/构建检查，HTTP四grant、精确数值/source/分布不变及真实角色/browser仍待。Selector资产草稿/历史/Prepared定义读取尚未接本投影，不能声称整个Selector域已收口。

### Selector资产正文与精确版本使用

资产page/get/create/update/availability/history/publish/version各在原服务前刷新其原固定资源，复用原敏感接线/no-store/nosniff；配置写Audit显式关闭正文，路径操作仅id，查询Audit无正文。原scope/负责人/CAS/定义校验/发布幂等不改，不新增FULL/PII写命令要求。正文不含模型配置，故未额外要求Prompt。

SelectorAsset副本只有专用详情且FULL+PII双grant才返回draftConfigJson；目录及写回执永不附原草稿。历史及发布回执只版本/修订/hash摘要，不附snapshotJson，完整定义走原独立version:view。Prepared仅增加可空非存储definitionJson/privacy/availableForNewTasks，保留旧6参构造，内部正常published/publishedForTask的新增字段全部null且不序列化，不改变原冻结协议/hash。受限精确版本仍返回ID/version/revision/hash和当前可用性，definition/definitionJson为空，能据引用选择可用版本，不误要求敏感正文授权才使用。

version GET改用同服务viewVersion，只读历史允许停用资产，仍校验原scope和版本/hash；返回availableForNewTasks。新任务的publishedForTask保持活动检查/锁定，选择UI亦检查可用性，不因允许历史查看重新启用资产。definitionJson是原精确领域定义的展示序列化，保留数值，不承诺等于存储snapshotJson每个字节；原contentHash由原服务验证，不按展示文本重算。

SelectorDefinition.Condition.value复用已有双代字段精确解码与HTTP JsonNode serializer，不改全局mapper，不新增重复codec，防默认MVC丢失条件树内容或精度。UI只读版本用definitionJson字符串，不将JSON.parse的近似数字回写；草稿原精确JSON编辑保护保留。

资产UI未知/受限policy隐藏配置编辑器，不把空默认条件当原稿；保存/发布/启停成功先清旧正文/历史，再独立GET，重读失败明确操作已完成。未保存草稿先阻止同资产启停或选用，关闭需确认丢弃；重新读取/关闭/卸载清正文及请求generation，版本读取校验来源/版本/hash。历史摘要单独读取精确版本，不通过历史权限绕过定义权限。

当前仅编译/构建，默认MVC精确输入/输出、inactive历史不允许创建、各grant/source/hash/SQL/浏览器仍待验收。其他Pipeline/Scheme配置/复核副本与完整I1–I7仍未完成。

## 流水线配置与发布历史（2026-10-20）

复用SensitiveReadAccessService.current、ResultReadProjection及既有组织scope；不创建ACL、tenant表或第二套配置模型。PipelineAsset与PipelineAssetVersion新增的privacy仅响应使用（TableField exist=false），不落库，不参与release/contentHash计算。

| 端点 | 固定基础资源 | 正文规则 |
| --- | --- | --- |
| GET /pipelines | iqc:pipeline:view | 目录总是不带draftConfigJson |
| GET /pipelines/{id} | iqc:pipeline:detail:view | 完整会话+PII+Prompt三项grant齐备才回原草稿字符串 |
| GET /pipelines/{id}/versions | iqc:pipeline:versions:view | 三项齐备才回原snapshotJson与publicationDecisionJson |
| POST /pipelines、PUT /pipelines/{id}、POST availability | 对应manage/update/availability | 回执不带草稿正文 |
| POST publish、POST force-publish | 对应publish/force:publish | 回执不带版本/发布审计正文 |

各路径先取当前授权再调用原业务服务；原服务继续组织数据权限检查。草稿/历史整段扣留，不做会损坏精确数值或发布hash的局部JSON改写；原字符串在权限齐备时原样返回。版本号/修订/摘要/发布指针仍可作为任务选择元数据；name复用现有PII遮蔽。无原始模型对象或数据库内容变更。专门读取审计只记录操作与业务ID，不收正文；回执路径也禁审计请求/响应正文。响应带private,no-store及nosniff。

privacy使用iqc-pipeline-read-v1，含canonicalPayloadVisible、canReadFull/canReadPii/canReadPrompt、withheldFields。目录/命令回执即使有敏感权限也声明正文不可见。前端只在专门详情允许且正文有效时挂载编辑器；受限时清空旧正文与历史，禁保存/草稿试跑/发布界面，保留原发布版本和灰度任务操作。命令回执三项grant仍齐备时保留用户自己正在编辑的原输入，不从空回执生成草稿；若grant不足则清空编辑器。历史重新加载先清旧数据，unknown协议按受限处理。评测页面仅使用可见的草稿/版本正文解析节点，元数据仍可用于服务端冻结。

后端本批skipTests test-compile原66329 terminal exit0。前端初轮40642因评测页旧非空类型假设exit2，已修复并另起最终构建；最终结果见协作台账。未运行Root测试/共享SQL/部署。B仍需独立覆盖8种敏感grant组合、撤权早拒绝/组织scope、命令回执不漏正文、原对象不变及历史hash、真实MVC/页面和迟到请求。其它Scheme/Agent/Rule/Skill/Model与复核副本仍未宣称全部完成。

## Skill配置与历史（2026-10-20）

复用原IqcSkillController/list/versions和SensitiveReadAccessService，不增加平行详情API、资产模型或ACL。当前Skill为原全局可复用资产，由原skill:view/manage授权；没有新建ownerGroupId或冒称已具备组织归属。配置读取与命令都先刷新固定原grant；原服务、不可变版本、回滚及Agent/流水线依赖选择不变。

- GET agent-assets/skills是当前唯一配置读取：FULL+PII+PROMPT齐备时才回原description/instructions/inputSchemaJson/outputSchemaJson；否则四字段为null，保留原ID/code/version/status和沿原文本策略遮蔽的name，供原依赖选择。
- GET skills/{id}/versions按同三grant返回原历史四字段或整段扣留，不改变存储的原指令、Schema或历史。
- create/update/enable/disable/rollback回执总是扣留四正文，即使有敏感grant；写操作本身仍按原业务权限，不增加敏感读取作为写权限。
- IqcSkill/Version只加exist=false且null省略的响应privacy；iqc-skill-read-v1复用流水线已采用的私有configurationPrivacy元数据构造，不新增通用starter或全局mapper。专门查询Audit与命令Audit均不取正文，响应no-store/nosniff。

Skill页面未知/受限协议或缺失instructions时只显示元数据，不挂编辑表单或保存空配置。写回执不替换原草稿；成功后关闭并清空表单，再独立刷新列表，失败明确重读未获结果。重读先清旧列表及打开的既有资产正文，关闭/卸载清表单，迟到读取/写回执不更新卸载组件或产生提示。创建者仍能按原manage权限输入新配置。Schema保持原字符串，页面解析仅用于JSON对象校验，不将解析结果近似回写。

本批后端原77870与前端原99858均terminal exit0（skipTests test-compile / vue-tsc+Vite，1m7s，既有体积提示）；未运行Root测试/SQL/部署。八grant组合、原对象/历史不变、资源撤权及真实HTTP/browser/每路由注册仍待B独立验收，不能用原共享skill:manage资源声明本身证明网关每条URL已可达。Scheme/Agent/Rule/Model/复核配置副本继续按原完整计划收口。

## Agent配置、版本与组织范围效果（2026-10-21）

沿用原QualityConfigController的Agent路由、QualityAgentService工作流、SensitiveReadAccessService和ResultReadProjection，不新增Agent表/主数据/详情API或租户字段。QualityAgent/Version只增加exist=false且null省略的privacy/agentPresentation响应字段，比较DTO增加可空privacy并保留原4参构造；内部原模型未设响应字段时旧JSON保持。Rules/RuleSet路由本批未接投影，不能把整Config Controller视为已收口。

### 正文与回执

- Agent list是原唯一当前配置读取；历史versions同样只有FULL+PII+PROMPT三grant齐备才返回原description/configJson字符串，否则扣留，不局部改写成可编辑假配置。复制响应实体，原配置/历史/模型字段不写回，不解密凭据引用。
- create/submit/approve/reject/disable/createVersion/rollback在原服务前刷新固定原业务grant；回执总是扣留两正文，敏感读取不代替写权限。
- compare保留原from/to版本和changedFields结构字段，详细changedConfigPaths仅三grant齐备显示；受限时空列表配withheldFields明确扣留，不代表没有配置变化。
- 查询Audit不收正文，版本/比较/效果目标只用ID及版本；命令沿原request/response=false，返回no-store/nosniff。既有共享view资源码每URL网关注册及真实角色仍须独立验收，不用注解或编译代替。

### 独立能力元数据保留使用

agentPresentation用iqc-agent-presentation-v1，声明AVAILABLE/UNAVAILABLE、真实根schemaVersion/已声明mode及能力可用状态；不把缺失/未知配置当默认模式。仅提取原冻结assetSnapshots的id/code/name/versionNo及MCP白名单中的工具名字符串，模型主/备用顺序保留；不包含systemPrompt、Skill指令/Schema、模型或MCP endpoint/secretRef/定价等正文。name沿原PII遮蔽，结构ID/版本/code/工具标识不改，防止错误引用。无伪造/截断能力计数；无可识别原配置或元数据无效则明确UNAVAILABLE。

SkillApiConfigurationEditor优先用该元数据选择冻结Skill/MCP/模型，schemes Agent版本筛选用真实schema元数据，普通任务继续原Agent ID选择。未知privacy/元数据协议不回退解析正文；仅旧无privacy部署兼容原读取。选择器不因缺Prompt权限自动丢失正常已发布能力，后端原版本/可用性/只读工具校验仍权威。

Agent页面受限或配置无效不创建默认编辑器、不能空存；刷新/关闭/卸载清旧列表/正文和资产，异步响应按代次隔离。写回执只作确认，成功后关闭再独立读取，失败不伪造已完成。管理配置用原2/3协议向导；1.0内嵌配置用原始JSON新版本编辑，不把内嵌旧配置悄悄替换为新默认值或近似数字。前端提交/停用/版本保存按钮和处理函数对齐原submit/disable/version:manage业务资源。

### 效果统计组织范围

源码确认AgentEffectService原先按Agent ID读取全部任务。本批复用BatchResultQueryService的IqcDataScope owner/group/all查询模式，在查询时限制可见任务，再按原Agent快照版本及其持久InspectionResult聚合。空/空白组不组成组范围，缺当前身份或scope拒绝；旧4参构造仅保持源码可编译，不提供无范围读取。版本须为正整数。分数/比率用BigDecimal计算并按原1位HALF_UP口径输出，无可评分样本/零分母返回null，避免把未测量当0。

该统计仍为原task.agentId直连的持久结果口径，不是金标准确率、整个Pipeline多Agent节点使用分析或完整I7运营指标。原记录/分数/证据不改，无模型调用。Agent资产本身继续原全局资源模型，不伪造不存在的ownerGroupId；效果业务数据使用原组织可见任务范围。

本批首轮后端97948/前端39271及最终39734/54477均terminal exit0（skipTests test-compile / vue-tsc+Vite，最终1m15s，既有体积提示）。编译覆盖已提交bf6d92e测试源码集成，Root未新增/运行测试、SQL、browser或部署；中断旧编译未知exit不算通过。B需补八grant/原对象原字节、能力元数据不漏正文但仍能选用、unknown协议、受限不空存/旧1.0、关闭/卸载和实际Controller路径；效果旧AgentEffectServiceTest须注入真实scope fixture，验证本人/同组/跨组/all/空组/缺身份/零分母及实际SQL，不能保留旧无scope构造绕过授权。其余Scheme/Rule/Model/复核副本、完整PDP/网关/角色及I1–I7继续。

## Rule表达式、测试回执与DLS导入诊断（2026-10-21）

复用QualityConfigController原Rule路由、SensitiveReadAccessService及ResultReadProjection，不新增表/端点。QualityRule/Version仅加exist=false且null省略的privacy；RuleTestResult/ImportResult加可空privacy并保留旧5参/8参构造。内部原快照字段缺省不变，原表达式/评分/证据与DLS执行未修改。RuleSet路由未接本投影，不能把整个Config Controller视为已收口。

- list/versions：非LLM表达式和描述在FULL+PII齐备时返回原字符串；LLM另需PROMPT。LLM识别对齐既有校验的首尾空白/大小写语义，避免保存的` LLM `绕过Prompt读取。名称沿原PII规则；ID/code/type/version/status、扣分/风险/veto等结构元数据保留，原任务/方案/流水线继续按引用使用。
- create/submit/approve/reject/createVersion：先刷新对应固定原业务grant，回执总扣留expression/description；不把敏感读取当写权限。原审批/版本服务和存储对象不改。
- test：当前端点明确不执行LLM（NOT_SUPPORTED固定公共解释）。FULL+PII齐备回原matchedText/reason/evidence，否则保留matched/resultStatus，隐藏命中片段和证据及动态原因；NOT_SUPPORTED保留原固定提示。ERROR仍是ERROR，不把错误当未命中。测试Audit只业务ID、不取输入或回执正文。
- import-dls：先刷新原import grant。FULL+PII显示原warnings/message，否则扣留诊断、沿原策略遮蔽文件/表/规则名称；预检/创建状态、ID/code、所有计数/顺序保持。没有改导入、匹配、审批、评分或新建请求模型。

privacy使用iqc-rule-read-v1及现有canReadFull/canReadPii/canReadPrompt/canonicalPayloadVisible/withheldFields。查询/测试及原写命令Audit不取正文，响应no-store/nosniff。现有同code多URL实际注册、真实角色及AOP仍需验收；旧无敏感组件的Controller构造不能绕过读取保护。

UI从受限/未知协议响应清理正文，不把null构造为可保存空版本；版本列表保留摘要，表格明确正文未展示，编辑/保存入口受限。测试/导入诊断受限时不显示原片段/证据/消息，状态和计数仍可用；DLS表格按响应序号设key避免遮蔽后的名称碰撞。读取/测试/导入用generation隔离；关闭/卸载清正文、测试输入、历史及File引用，迟到响应不写旧组件。写操作固定发起目标与原始输入，失败提示先核对已有草稿/状态；导航不会撤销已发出的服务器写入，未发起的后续提交会停止。原规则选用无需获得表达式正文。

首轮后端81613/前端93991及最终36835/95124均terminal exit0（skipTests test-compile / vue-tsc+Vite，最终51.16s，既有体积提示）。Root未新增/运行测试、SQL/browser/部署，不能凭编译判权限或业务正确。B待验证非LLM四种FULL/PII组合与LLM八grant组合（含大小写/空白）、撤权早拒绝服务、原实体/原字符串/证据/计数不变，命令回执不漏，ERROR/NOT_SUPPORTED、诊断扣留、受限不空存、关闭/卸载和实际网关角色。Scheme/RuleSet/Model及复核等副本、全部适用I1–I7继续。


## RuleSet 读取边界（2026-10-21）

复用既有规则业务授权及当前敏感授权，RuleSet list/history仅FULL+PII齐备返回原description；所有命令回执扣留description。响应副本使用iqc-rule-set-read-v1，非持久privacy为null时省略；ruleIdsJson原顺序、aggregationMode、ID/code/version/status保留，不改变检测或内部快照。页面仅保存成员选择所需元数据，关闭清理新建表单，刷新代次/卸载阻断迟到响应，创建提交复制输入，禁止提交期间关闭。页面本来没有历史编辑入口，未添加空描述覆盖路径。

当前批次仅编译/前端构建；实际grant组合、源对象不变、内部JSON兼容、网关逐URL注册及浏览器生命周期仍待独立验证。单租户组织范围不变，完整I1–I7未完成。


## Scheme审计正文边界（2026-10-21）

现有Audit.request默认true（framework治理注解只读确认），Scheme创建/修订此前会将DraftRequest完整definition记录到审计。三个原有创建/修订/发布注解显式request=false,response=false；修订和发布保留目标id，操作类型/module不变。不改framework、业务请求或持久快照。

Scheme整体读取边界尚未完成：published模板及专家history的ReleaseSnapshot携带dependencies和variantDependencies，包含规则expression及Agent configJson；不能只清理dependencies而遗漏变体副本。现有UI依赖snapshot.definition的executionVariants/runLimits及历史diff/派生，需专门安全选用元数据及受限编辑/历史展示联动，不能简单置空snapshot。ReleaseSnapshot构造校验要求变体集合与选中dependencies一致，投影不能制造假发布Release或参与hash。内部Release记录不应为显示投影改写。


## 已发布Scheme普通读取投影（2026-10-21）

GET /schemes/published目录及schemeId游标历史先检查当前scheme:view并设置no-store，查询审计不收正文。独立PublishedSchemeRead响应封装，不修改ReleaseSnapshot/内部快照/摘要。FULL+PII+PROMPT全部具备可回原发布正文；其它组合只回业务definition（item/评分/上限/variant/引用），自由名称沿原PII mask、描述扣留，dependencies仅executionMode和规则id/version/name，agent/label/route plan/variantDependencies不复制；policyVersion=iqc-scheme-template-read-v1。contentHash始终原发布物身份，不能用展示投影重算校验。

普通前端API即使有全部权限，也只保留任务选择所需依赖元数据，不保存检测器正文；描述需已知协议全部grant方保留。原TemplateWizard仍按原definition/推荐变体/上限/执行模式及版本创建任务。专家draft/history/preview和发布回执仍待独立收口，不把普通模板路径完成当成整个Scheme域完成。实际权限组合/源快照不变/普通任务与历史/浏览器/网关注册待独立验证，本批仅编译构建。


## Scheme专家读取与命令回执（2026-10-21）

草稿list在原scheme:manage当前授权后复制实体，正文description/draftConfigJson仅FULL+PII+PROMPT齐备读取；命令回执始终扣留正文和presentation。非持久null省略的privacy和schemePresentation不改变原内部快照。专门草稿读取另给iqc-scheme-presentation-v1 typed业务definition（检测器ID/版本引用、评分、执行变体、上限），自由名称/说明走既有PII mask，非法定义显示UNAVAILABLE而不补默认。该展示数据供原试跑选用，不能作原草稿编辑。

专家历史复用独立发布投影，保留来源修订/试跑/归档元数据；原Release/hash不改。preview仍执行原完整依赖校验，但仅返回schemeId/draftRevision/validated，不返回冻结检测器。发布/版本归档返回SchemeVersionReceipt元数据，无snapshotJson。创建/修订/停启用返回无正文草稿回执。各入口在调用业务前检查原资源码，no-store，查询审计无正文。

页面受限/未知协议草稿禁止编辑/保存，合法选用presentation供试跑；派生按原服务端冻结引用创建，回执不直接当可编辑草稿，重读后有权限才打开。历史完整正文不足时禁差异比较并显示正文未展示，查看和派生/归档仍沿原业务权限。刷新/编辑代次及卸载清理用于阻止迟到回填；关闭编辑清配置。权限组合、内部旧JSON、真实Audit/网关/撤权/浏览器/试跑发布仍待独立验收，本批仅编译构建。


## Model/MCP现有边界与审计修复（2026-10-21）

IqcModelProfileController已用ModelView：不返回secretRef，仅secretConfigured；保留选择/管理需要的provider/model/endpoint/限额/价格等字段。ModelConnectionTestService返回固定安全诊断，不返回供应商异常原文。MCP现有secretRef为平台密钥引用，管理页面用于编辑，不能误称实际密钥或直接删除导致更新失效。未凭这些字段新增Prompt授权或另一套配置模型。

IqcMcpServerController四个现有命令原Audit.request默认true，会复制name/description/endpoint/secretRef/allowedToolsJson请求。现均显式request=false,response=false；update/enable/disable保留目标#id，create保留原操作和模块。仅关闭额外审计正文，不改资产持久化/版本/Agent冻结引用/运行配置。模型与MCP接口当前业务授权/组织适用边界、响应中的自由描述及MCP实际请求/凭据规则仍须独立完整审查；不能把审计修复称为整个域隐私验收。


## Finding人工复核上下文（2026-10-21）

实际BusinessItemReviewService.findingContext返回冻结messages.content和Finding值/允许值/证据引用，原reviews?view=FINDING_CONTEXT直接返回。现reviews入口先刷新固定iqc:review:view当前授权/no-store/无正文QUERY审计，仍由原业务服务按task组织范围与来源hash完整校验；仅该上下文响应走独立FindingReviewContextRead。FULL+PII同时具备保留原精确值/证据/消息；否则available=false、固定权限提示、findings/messages为空，并给iqc-finding-review-context-read-v1元数据。保留sourceResultId/sourceHash，源对象和复核存储不改，不把未展示解释为无事实。无需Prompt授权。

现有HumanFindingReviewEditor已在available=false时仅显示message，表单不渲染且save handler拒绝，未新增另一套前端流程。当前只能由编译证明契约可编译，仍待四种grant/撤权前置拒绝/原精确值与消息不变/浏览器真实验证。其它reviews HISTORY/QUEUE/SCORE_CONTEXT仍未完成字段投影：ResultReview的reviewedResultJson/评论，以及scoreContext.manualScore.reason、队列自由文本需后续连同安全工作流stage和UI处理，不能直接扣留JSON后将缺正文当损坏或INITIAL。


## 业务/标签复核待办投影（2026-10-21）

原IqcPage服务查询不变，当前业务review:view、标签review:label:queue分别在业务前刷新授权/no-store，查询审计不收正文。独立ReviewQueuePage响应保留records/current/size/total并加iqc-review-queue-read-v1隐私元数据。FULL+PII齐备回原requestComment；其它组合扣留评论，对taskName/createdBy及标签labelName/valueDescription沿原PII mask。业务record重建、标签DTO复制，原对象/SQL范围/排序分页/ID/状态/currentResult不改，业务stage直接保留原已校验枚举，不重新解析已清理workflowMetadata为INITIAL。

页面已知受限或未知privacy清除评论并提示未展示；卸载使队列和标签历史代次失效/清行和selected，关闭历史清引用。旧无privacy API兼容保留。ResultReview历史与命令返回的reviewedResultJson/评论、人工改分原因仍待配套安全工作流/UI投影；此批不覆盖那些接口。当前仅编译构建，实际四grant/源不变/组织SQL/分页stage/当前授权和浏览器待验。


## 人工改分上下文原因投影（2026-10-22）

SCORE_CONTEXT沿现有reviews固定review:view当前授权/no-store及原service组织/来源/计算hash校验，独立ScoreReviewContextRead只控制前轮manualScore.adjustment.reason：FULL+PII齐备回原原因，否则该字段null，iqc-score-review-context-read-v1声明扣留。computedScore/hash/status、APPLIED/STALE、effectiveScore、原finalScore字符串和source/expectedScoringHash完全保留；没有manualScore仍null，不把旧原因扣留变成未改分或零分。不需要Prompt授权，不修改原ManualScoreProjection/ScoreAdjustment/评估及存储。

ManualScoreReviewEditor原本不以旧原因填表，新一轮原因始终重新必填；现提示前轮原因未展示，仍可用原评分基准编辑。存在但未知/受限privacy剔除旧原因；保留旧无privacy兼容。卸载清评分上下文/输入并失效请求代次。当前仅编译构建，四grant/精确分数及状态/hash不变/源字节不变、实际授权/浏览器仍待独立验收。ResultReview历史与命令正文仍待。


## 标签复核历史与回执（2026-10-22）

标签history/create/decide先检查原固定label:history/create/decide当前授权/no-store，历史QUERY审计不收正文。ResultReview响应copy新增非持久null省略privacy，内部存储/旧快照不设字段。专门history仅FULL+PII齐备回原requestComment/reviewComment/reviewedResultJson；其它组合扣留三字段，创建人/复核人沿原mask，ID/轮次/状态/分数/hash等保留。所有标签命令回执无论敏感grant均扣留正文，前端沿原刷新历史取最终详情，不改复核幂等/锁/来源/组织范围。无需Prompt。

LabelValueReviewPanel区分未展示和损坏：存在但未知/受限privacy清正文评论、显式提示、停止用不完整历史裁决；合法完整旧body原验证保留，无privacy旧接口兼容。LabelReviewQueue历史结论显示复核正文未展示而不猜值。组件卸载清历史/消息/命令草稿并失效请求；已发出的写入不取消，迟到响应不触发刷新。实际grant/源字节/旧内部JSON/命令回执/当前授权及组织/browser待独立验收，当前仅编译构建。业务/消息ResultReview历史与命令仍未投影，工作流stage须另行配套。


## 消息复核历史与回执（2026-10-22）

复用既有标签review响应copy字段处理为私有reviewRead，消息路径使用iqc-message-review-read-v1，不新增持久模型。消息history FULL+PII才回原评论/JSON，命令receipt始终扣留，原original/final数值、状态、风险、ID/hash保留，创建人/复核人沿原mask。request/decide入口在原业务分支前检查固定review:create/decide当前授权；history已有review:view/no-store/QUERY不收正文。原service范围与裁决不变；业务review JSON/工作流本批未扣留，仍待完整配套。

旧质量运营页按当前tab及其原grant分别查询review/feedback/sample，report只在report:view具备时查询，不再无条件四接口联读。消息评论受限显示未展示，表单不填历史评论；提交固定目标/复制payload，handler检查decide并防重复，提交中禁止关闭/改动，关闭清表单/selected，卸载清列表/输入/代次，迟到响应不回填。字段保护和页面运行未独立验收，当前仅编译构建；原组织/role/PDP/browser与I1–I7保持待验。
