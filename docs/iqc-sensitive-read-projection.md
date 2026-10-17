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
