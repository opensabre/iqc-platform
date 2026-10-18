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

## 配置审计真实切面回归与规则解析错误（2026-10-17）

集成8f9a89b等价本地提交58cfc6b：原QualityConfigController18个配置动作、IqcModelProfileController4个模型动作关闭请求和响应审计正文，路径ID与Agent回滚版本作为目标键，四个规则集生命周期保留审计。ConfigurationAuditPayloadTest显式列22方法，每方法成功/失败两种共44项，通过真实已安装AuditAspect、实际Controller注解及SpEL参数名生成事件；领域proceed与本地事件sink隔离，验证无配置canary且操作者/目标键/操作/URL/耗时/失败元数据保留。不是HTTP/PDP/领域事务或EDA落库验收，预算接口沿原独立审计行为。

审阅发现原QualityRuleService.validateExpression拼接exception.getMessage，正则解析异常含表达式全文，会通过AuditAspect.errorMessage绕过正文开关。应用服务改为固定错误，正则只附数值位置；结构化条件保留固定字段/操作符/JSON类别，不返回用户原字段或表达式。异常cause保留诊断；此变更只保护该边界外层消息与审计事件，不声明已治理所有堆栈或日志。规则解释/匹配及领域权限不变，无framework修改。

新增3项真实Controller+Service+AuditAspect覆盖REGEX/STRUCTURED/DLS错误，正则cause实际包含canary证明风险输入确实触发底层解析消息；事件和外层异常均无canary，无DAO写入。最终93384原handle exit0，Audit47项+原QualityRuleService9项合计56 run、0 failures/errors/skipped；日志/tmp/iqc-config-audit-validation-compatible.log。11838首轮仅原结构化诊断类别断言失败，补回固定安全类别后原测试保持通过，未恢复解析正文。原26066的44项也exit0。

其它Prompt/配置读取面、任意服务异常、日志保留、真实权限/HTTP/审计落库及完整I1–I7仍待，不据此声明I7完成。

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

### 2026-10-18 当前累计后端默认全套回归

552335d累计源（含反馈、业务分页/汇总和金标精度修复）首轮11754默认全套1139项有5项旧HTTP夹具失败。联合试跑仅提供旧result:view或缺当前grant，标签HTTP夹具无currentActionAccess，组织故障仍期待业务数据fallback。同步两类夹具到固定当前grant和稳定身份，不修改生产授权/数据范围；保留仅business:view可读、admin/本人/同组/外组及零分/null分检查。新用例复用同一有效令牌，在组织当前grant撤销后再次真实HTTP请求，确认IQC-1005无data；组织查询异常明确IQC-1006无data，旧标签队列fallback契约仍保持。成功响应检查no-store/nosniff，helper同时检查HTTP状态与业务成功码。

67297专项59项仅剩旧文件名预期失败（实际无敏感grant正确返回******）；改为脱敏预期并通过H2 SELECT验证原文件名未变。最终35079默认全套进程exit0，按/tmp/iqc-current-full-final.log创建时刻筛选本轮新报告：154份XML、1140 tests、0 failures/errors/skipped，报告时间2026-10-18 07:44:12–07:48:23。包含LabelReviewQueueHttpTest10和JointLabelMaterializationTransactionTest49，非叠加旧MySQL IT报告；日志/tmp/iqc-current-full-final.log。命令沿离线mvn -o -q test并显式传入已安装ByteBuddy agent，真实HTTP随机本机端口、隔离H2，组织及限流依赖仍为测试替身。

本结果不含随后A6c81569金标读取投影，不证明真实组织/网关角色、供应商、MySQL持久化、浏览器或整个I1–I7完成；不能把旧默认全套1067项或本轮1140项外推到尚未集成源。

## 金标HTTP展示与独立功能权限

GoldSampleController的草稿、版本、历史、标注和审核在原service前刷新各自原有固定基础权限；原样本/版本数据范围继续由服务验证。GET审计关闭正文，写接口沿原无正文审计，成功响应no-store/nosniff；旧单依赖嵌入构造缺敏感组件时fail-closed。

金标专用读取只有完整+PII双grant才返回未经投影的原goldDraftJson及版本snapshotJson；其它模式不返回可误当原始输入的opaque字段。独立GoldSampleReadProjection复用既有ConversationReadProjection，从原冻结GoldDraft/GoldConversation构建完整脱敏或前三条/200字符预览；只显示Finding code/type/state/证据ID元数据，值和业务上下文不返回。样本及会话身份必须与快照绑定，损坏/缺省快照仅返回明确状态，不能视作无事实。没有读取当前会话替换旧输入；候选policySource已核实仅公共版本身份，不含节点配置/Prompt。

泛样本sample/samples投影可按原sample:view+双grant展示候选内容，但始终不附带完整goldDraftJson，需独立Gold读取功能权限；没有双grant时源contentSnapshot/expectedJson/tagsJson也withheld。审核历史只给原版本摘要，始终不附snapshotJson，不能绕过独立gold:version:view；无完整权限时审核原因也withheld，防止其复制全文，完整但无PII时沿原文本规则处理原因。原ID、修订、状态、hash、分页及操作者身份保留。

标注/审核写响应仅返回身份、修订、状态等回执，不把写权限当作完整读取权限；原写命令与角色、独立审核、CAS和原始存储不改变。编辑器保存后另走已授权GET重新取得草稿，失败明确提示已保存但加载失败并清除旧展示；受限预览不能回写或被当原始金标审核。QualitySample/Version的privacy为exist=false且null不序列化，不改原领域JSON或冻结hash；预览永不重算原hash。

其它样本列表/创建入口由协作开发者接同一sample/samples/sampleMetadata投影，未接入前仍是已知旁路；候选、数据集、评测和其它副本仍需逐项核验。当前是HTTP读取/回执投影，不声明完整I7、任意无标签PII识别、真实角色/数据库/浏览器验收完成。Root未新增或执行测试、SQL或部署。

### 通用样本读取与创建回执旁路

集成A6c81569等价2b106d3后，QualityOperationsController的GET samples在原领域查询前刷新固定iqc:sample:view，用ResultReadProjection.samples返回副本。即使完整+PII双grant，通用列表也不返回goldDraftJson，完整金标仍须走独立Gold读取授权；候选contentSnapshot/expectedJson/tagsJson沿双grant判定。查询Audit无正文，也不把可空筛选作为目标键。

POST results/{resultId}/samples在进入原消息/业务候选服务前刷新既有iqc:sample:manage，不引入新sample:create权限，保留原参数和来源/复核检查；结果只经sampleMetadata返回标识/修订等元数据，不靠写权限返回冻结正文。两入口复用反馈已有敏感响应helper与no-store/nosniff；缺敏感组件直接拒绝，旧嵌入构造不回退原始内容。原领域对象和数据库模型未新增持久化列。

GoldSamplePrivacyTest使用真实投影及Controller直接调用，覆盖四grant组合、冻结消息预览、通用列表与金标专用读取边界、跨样本/会话和损坏快照拒绝预览、历史及写回执不含snapshot、原对象/Hash/分页保持及固定权限拒绝先于领域读写。服务隔离为mock；本批不代表HTTP/PDP/组织角色/数据库或前端迟到响应验收，运行终态见协作记录。先前1140默认全套仅属df525a4，不外推到新金标源。

84131专项最终exit0，逐XML确认GoldSamplePrivacyTest5、FeedbackPrivacyTest4、BusinessReviewControllerTest10、GoldSampleReviewTest12、EvaluationDatasetVersionTest7，共38 run、0 failures/errors/skipped；日志/tmp/iqc-gold-sample-privacy.log。本轮为直接Controller与投影/领域服务既有测试组合，未重新执行默认完整套件，也未验证本批HTTP/PDP/UI路径。

### 默认MVC金标请求的跨代树绑定

GoldSampleHttpContractTest通过默认MockMvc路由/转换器执行GET样本与Gold的四敏感grant组合、预览条数/200字符、no-store/nosniff、源对象保持，以及PUT标注精确值和POST样本元数据回执。领域与组织授权为mock，不代表网络服务器/PDP/数据库验收。

41055首轮11项中10通过、1 error：默认Jackson3 MVC转换器无法实例化GoldFinding.value的Jackson2 JsonNode，PUT请求在进入service前失败；单独用Jackson2 ObjectMapper读请求的既有测试不能覆盖此问题。已核对应用与framework WebMvc配置，未注册专用Jackson2 HTTP转换器，不修改框架或通过测试强制换转换器绕过。

应用层复用HumanFindingReviewEvaluator原精确解码位置，新增HttpExactValueDeserializer用于金标请求字段：Jackson3局部精确数字树转成原Jackson2精确领域树。仅给GoldFinding.value、GoldDecision.labels内容、GoldSampleService.Annotation.businessContext补Jackson3字段注解，保留原Jackson2解码注解、持久化模型、共享mapper和历史hash。当前结果以协作记录终态为准；其它使用Jackson2树的HTTP请求面仍需逐项核验，不声称已全局迁移。

17259中间回归30项29通过/1失败：字段级readTree默认尾随检查把下一个同层属性误当多余文档。仅局部HTTP字段mapper关闭FAIL_ON_TRAILING_TOKENS，外层MVC保持严格；新增完整请求后追加第二JSON文档返回400且授权/领域均未访问的回归。最终74757 exit0：GoldSampleHttpContractTest7、GoldSamplePrivacyTest5、GoldSampleReviewTest12、EvaluationDatasetVersionTest7，共31项，无失败/错误/跳过，日志/tmp/iqc-gold-sample-http-fragment-final.log。此为默认MockMvc契约及既有领域专项，不冒称真实PDP或完整全套验收。

### 数据集与离线评测写操作审计

EvaluationDatasetController创建、更新、冻结、可用性四个写操作及PipelineEvaluationController创建评测均显式关闭审计请求与响应正文。已核对框架Audit默认request=true、response=false：本次实际关闭数据集草稿、名称等请求正文，显式保留响应关闭；冻结响应含完整金标snapshotJson、评测创建响应含冻结任务配置，继续禁止其进入审计正文；更新/冻结/可用性保留路径id目标，创建不使用未生成ID作审计键。沿用既有Audit事件及权限和领域调用，不新增日志表，不改变冻结内容或hash。此处仅减少审计正文；原HTTP数据集和评测返回值仍须另行实施读取投影与实际权限验收。

### 冻结数据集版本展示边界

版本GET、历史GET及freeze POST分别在领域调用前刷新原dataset:version:view、dataset:versions:view、dataset:freeze；缺敏感组件拒绝，原服务数据范围与完整性/hash校验保持，成功no-store/nosniff。两个查询Audit仅目标元数据。版本专用读取仅FULL+PII双grant返回原snapshotJson；限制模式不返回原opaque正文，解析原冻结DatasetSnapshot并验证datasetId，逐成员复用GoldSampleReadProjection获得完整脱敏或3条/200字符会话预览和无值Finding元数据，保留样本/版本/hash引用。损坏、来源不匹配或超限明确INVALID_DATASET，不回退原文、不查询当前会话。

历史与冻结写回执无论敏感grant均只给版本摘要，不含snapshotJson或预览，避免用写/历史权限绕过专用读取。新增privacy仅exist=false且null不序列化，不改持久化和冻结hash。UI未知策略不显示正文，受限预览明确非原始金标；每次版本读取先清旧内容并用现有generation拒绝迟到响应，完整快照原文本展示，不经JSON.parse/数字舍入。数据集草稿的名称/说明、评测结果actualJson及其它副本仍待，不以本批声明I7完成。仅构建检查，行为、浏览器、真实组织与数据库验收仍待协作验证。

### 离线评测机器产物读取

PipelineEvaluationController分页与详情在原service前分别刷新固定evaluation:results:view/result:view，保留原任务范围、轮次、产物hash及来源校验。成功no-store/nosniff，查询Audit无正文。分页始终仅元数据，防止绕过独立详情授权；详情只有FULL、PII、Prompt三grant同时具备才返回原actualJson，因为任意机器输出、变量或错误可能复现输入及Prompt。其余模式只展示原运行枚举状态、轨迹/事实数量及明确的POLICY_SATISFIED标记；不展示变量、值、错误或轨迹正文，不把受限正文视作无命中。解析失败/超限返回INVALID_OUTPUT，不回退原文。

仅复制原EvaluationSampleResult，privacy字段exist=false/NON_NULL；原status/durationMs、dataset/gold/actual哈希与执行/样本身份不改、不重算。UI读取前清旧详情并拒绝迟到覆盖，未知policy不显示正文；受限摘要明确标记，完整产物按原字符串展示避免数值舍入。现有Prompt敏感资源的实际登记/授予仍待，不借系统ADMIN或JWT绕过；当前缺grant仅限制正文，不阻断原基础权限下元数据查看。评测create回执、report和通用task读取及数据集草稿仍需单独处理；当前仅构建检查，不代表真实组织/浏览器/数据库验收。

### 冻结数据集及评测输出的默认MVC验收

在215b034基础集成A审计ccfdfc6、数据集a8e24e7与评测a0b47d0（本树86ed3f9/319008a/2ee0897），保留反馈、通用样本及精确字段桥。DatasetEvaluationHttpPrivacyTest使用默认MockMvc、真实ResultReadProjection，领域/PDP依赖隔离mock；四种数据集FULL/PII组合与八种评测FULL/PII/Prompt组合验证独立详情读取、列表/历史/冻结回执永不含正文、冻结消息预览的3条/200字符限制、零时长和原对象不变、跨数据集/损坏快照及非法评测输出明确状态、当前授权失败先于领域访问。

原59362 exit0，/tmp/iqc-dataset-evaluation-http-privacy.log；本轮逐XML为DatasetEvaluationHttpPrivacyTest14、GoldSampleHttpContractTest7、GoldSamplePrivacyTest5、EvaluationDatasetVersionTest7，共33项，0 failures/errors/skipped。本测试不证明真实组织/PDP/数据库/浏览器，不包含A后续评测创建回执和报告增量，不外推整个I7或默认完整回归。
### 评测创建任务回执及报告当前授权

评测create在领域写入前刷新原evaluation:task:create，返回ResultReadProjection.taskReceipt副本：保留任务ID、状态及运行元数据，移除agent/rule/label快照、Prompt、触发请求、重试策略与错误、筛选JSON、会话/规则集合JSON。写grant不能充当读冻结输入grant，即使全敏感grant也不在创建回执附带这些内容。任务名称沿现有文本保护，领域存储不变；UI创建仅消费task.id，当前流程兼容。普通任务及Benchmark入口尚待独立接线。

报告刷新原evaluation:report:view先于service，沿用原Task/Execution范围与完整金标分母、数值指标及原样本来源，不重算、不投影成空报告。查询Audit仅taskId且无正文，成功no-store/nosniff；缺接线拒绝。构建检查不能替代实际组织/HTTP/浏览器行为验收。
