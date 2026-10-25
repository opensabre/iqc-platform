# Model Gateway 并发与调用事实

## 实施前复用及缺口

复用 SnapshotChatModelRouter、SpringAiLlmQualityProvider/HttpLlmQualityProvider、GovernanceRateLimiter 和现有 UsageCounterRecorder。现有 Task/Execution claim 解决重复运行权，任务 concurrency 与 worker 本实例上限不能保证跨实例模型并发。治理 UsageRecord 只有逻辑事件次数，原 RouteExecutionTrace 只有节点结果，均不能表示一节点多次 retry/fallback 的实际模型调用及 nullable Token 用量。

因此 V1.1.49 增加 IQC 专属模型容量行与调用事实两表。容量行锁串行化跨实例准入；调用事实记录每次显式模型调用独立身份与真实响应用量。没有复制 Task、消息、Trace 或组织主数据，没有新增基础设施或修改 framework。此批不是 token/金额硬预算，未声明实现真实网络请求恰好一次。

## 协议

稳定模型键按 provider/endpoint/modelName 字节长度分帧 SHA256；OpenAI-compatible 供应商别名合并，URL host/默认端口/末尾斜杠规范化，防止相同声明端点分裂额度；不存 endpoint、secret 或提示正文。每次显式 retry/fallback 独立 UUID。短事务 RESERVED 占用容量，网络调用在事务外，响应结束后 CONFIRMED 并释放容量；异常 UNKNOWN 保留占用，进程崩溃后的 RESERVED 也不凭本地时钟自动释放。缺 usage 保持 null，绝不伪造0；非法或部分 usage 单独标识。

配置 iqc.llm.gateway.concurrency-limit 默认0（不限制，但记录占用），同模型各实例配置必须一致。容量行保存首次准入配置，配置变更沿下方管理员管理协议，不能单实例偷偷提高。速率复用同步治理 SLIDING_WINDOW，按每次显式候选/重试准入判定；治理默认 fail-open 的部署配置不能冒充严格限流。准入拒绝是策略结果，不能走 fallback/内部重试绕过。

事实逻辑 recordId/ruleId 是调用者明确传入的观察身份；当前尚不把它推测为 Task/EID。后台 owner 无明确传递时为 null，不能读取异步线程的伪用户。管理员 GET /api/iqc/model-gateway/calls 分页查询，权限 iqc:model-gateway:calls:view 与服务端 IQC_ADMIN/SUPER_ADMIN 身份同时校验；普通全范围读权限不等同管理员。模型配置页面复用 ModelGatewayCalls，Token 以字符串传输保持整数精度。V20261004_19 仅授予 IQC_ADMIN，无新MENU/全局ADMIN。未知调用人工恢复沿下方管理员核对协议；Task/EID稳定归属尚待继续。

受管资产的 SDK maxRetries=0，由路由显式执行原 maxRetries（0–5），每次重试独立准入。Legacy 默认 ChatModel 的 SDK 隐式重试、自动工具多轮与 legacy ChatModel 内部行为需要单独处理；包装 model.call 只能证明显式调用边界，不能冒充传输层逐HTTP计量。后续需接入传输层、模型版本定价/最大输出token、跨Task预算及可查询等待状态。没有新增或执行测试、SQL、真实模型/事务/浏览器或部署。

同模型 identity 的全局容量不等同不同未声明端点的物理服务配额。Legacy 缺少 endpoint/model 声明时使用 deployment-default，真实部署身份后续完善。治理限次已发但并发拒绝可能保守消耗一次速率票据，不会多发模型请求。网络异常保留并发可能降低可用性，需要下方有审计的人工核对恢复；当前不能凭 TTL 猜测远端停止。

## 容量管理及审计恢复（2026-10-07）

复用原容量/调用行及治理Audit，没有新恢复队列或并行调用模型。V1.1.50扩展容量managed/version与调用释放凭据字段。管理员容量配置采用字符串精确版本CAS，降低上限允许占用暂时高于新限额但禁止新准入；启用managed后所有实例沿DB限额，部署默认仅初始化未管理模型，不再造成配置0切换正值时永久拒绝。原配置前后值同治理审计响应记录。

未知或崩溃RESERVED可由管理员明确确认远端已完成/取消或请求未发送，并提供核对凭据编号后恢复一次容量。操作不是远端自动验证，管理员对外部核对事实负责，特别是RESERVED必须确认原发起进程不会恢复后再次发出请求，远端停止本身不足以释放尚可能恢复的发起者；不允许基于TTL推断停止。锁序call→capacity与响应核销一致；恢复、保存稳定操作人/原因/凭据同事务，重复相同凭据幂等，替换拒绝。保留原UNKNOWN/RESERVED及未知Token，不冒充响应成功或费用0。RESERVED后迟到真实响应仍可记录，但不再重复减少已释放占用。

GET/PUT /api/iqc/model-gateway/capacity/{modelKey} 和 POST /calls/{callId}/capacity-release 分别要求 capacity:view/configure、calls:release及服务端质检管理员角色。容量政策管理与已释放状态在模型调用页面展示；没有框架修改。实际远端核对/事务并发、SQL迁移及浏览器运行尚未验收，完整硬预算及Task/EID归属仍待继续。

## 执行来源关联（2026-10-07）

复用原 Task/TaskExecution、RouteExecutionTrace 与 Pipeline NodeContext。V1.1.51 仅扩展原调用事实 taskId/executionId/nodeId/runIndex/ownerGroupId/conversationId，并建立任务轮次查询索引，无新任务/节点/调用表。

ModelCallContext 在执行器显式创建和逐层传递，普通消息/会话、逐项候选/复核及Pipeline桥接沿真实根Task/EID，不解析逻辑recordId、不借用异步线程用户，也不把为缓存隔离生成的stageTask临时ID当真实根身份。节点未定义的旧规则调用nodeId保持null；runIndex为原检测/节点轮次，适配器retry/fallback各自callId沿同一来源。

reserve 在独立短事务校验Task当前EID、Execution归属、运行状态/暂停取消意图及保存的owner/group一致；陈旧来源停止准入，不冒充新任务。该一致读不等于能撤销已发远端请求；记录只证明捕获的来源，原执行器EID提交fence继续复用。缺少来源的旧调用保留null，旧适配器默认重载继续可用，不从字符串猜历史关联。

GET /api/iqc/tasks/{id}/model-calls 先沿Task.get数据范围授权，可选择已存在且属于本任务的EID，再查询显式字段。全局查询仍管理员独有。任务详情复用原调用记录组件，权限iqc:task:model-calls:view四IQC角色；不开放全局容量管理给普通任务查看者。真实权限/模型/数据库事务运行、迁移尚未验收；部署版本/冻结价格、Token硬上限及传输层工具计量仍继续。

## 模型连接检查接线（2026-10-07）

复用原连接检查API/资源及Router/Gateway，不新增表/API/权限。ModelConnectionTestService不再create(...).call直连，callProfile将一次检查纳入相同声明模型限次、全局容量和真实nullableToken记录；SDK重试仍为0，不隐藏额外检查请求。逻辑身份固定model-connection:profileId:versionNo，捕获请求scope的owner（无可信身份保留null），Task/EID/rule保持null而非虚构质检任务。

治理拒绝/核销未确认用安全检查未完成消息，区别连接参数错误；不返回provider原异常、密钥或正文。没有执行真实连接检查、测试、迁移或部署。模型冻结定价及Token/金额硬预算仍继续。

## 冻结模型价格前置（2026-10-07）

复用原ModelProfile/versionNo、ModelCommand、Agent ModelSnapshot及AssetReferenceValidator，不新建ModelRegistry/价格主表/API/权限。V1.1.52仅增加pricingJson。可选iqc-model-flat-pricing-v1声明ISO币种、inputPerMillion/outputPerMillion非负精确十进制字符串（每百万token）；未知字段、重复键、尾随值、数字强制转换、指数、超长/超精度拒绝，单价最多1000000且12位小数。规范化不经过double/JS数字。

更新未提供pricingJson保留已有配置；空字符串显式清除。Agent主/备模型发布快照保存该版本价格；旧无价格快照省略null字段保持旧JSON摘要，不从当前ModelProfile回填旧历史。这里只配置并冻结声明价格，尚未计算调用费用或宣称实现预算；cache折扣/特殊计费另需明确协议，未知价格和用量不能填成本0。没有新增或执行测试、SQL、真实模型、浏览器或部署。

模型更新/启停在原profile行锁内读取；更新防止版本为空/负值/int耗尽并断言写入一行，避免并发定价更新复用同一versionNo。既有ModelView安全投影增加价格字段，继续不返回secretRef。

## 每调用冻结价格及计算费用（2026-10-07）

V1.1.53仅扩展原调用事实，不新增账单/费用主表。受管资产与连接检查在准入前从原模型快照绑定profileId/version、canonical pricingJson/hash与声明币种；不读取现行ModelProfile补价。实际请求modelName与响应modelId分别记录；配置版本不是远端部署build版本，不混称。重试/fallback每次各自冻结所选模型价格，模型容量key继续沿实际声明端点/名称跨配置版本共享。

只有无自动工具回合、已确认响应、完整且合法input/output/total用量和完整价格证据时，按(input×inputPerMillion+output×outputPerMillion)/1000000使用BigDecimal精确计算，DECIMAL(38,18)及API字符串无double/JS舍入。状态CALCULATED_FROM_DECLARED_PRICE表示按用户声明价格计算，不等同供应商发票费用。缺价/用量、未知响应、工具多回合范围不全或价格摘要无效分别未知，不填0；用户明确单价0及真实0Token才可能计算出0。

原裸HTTP/legacy部署无冻结资产价格不借Agent主模型推测，旧调用不回填。cache折扣/特殊计费不支持本flat协议，供应商账单核对、传输逐HTTP、真实部署版本与Token/金额硬预算仍继续；费用计算及并发释放同一核销事务，人工占用恢复不制造费用。未新增或执行测试、SQL、真实模型/账单/浏览器或部署。

缓存读/写Token沿Spring AI Usage显式值记录；HTTP只取观察到的prompt_tokens_details.cached_tokens，不猜不存在字段。已知缓存token为正时费用保持UNKNOWN_CACHE_BILLING，不能套flat价格冒充缓存账单。缺缓存字段仍保持null；声明价格计算不是证明缺缓存或供应商实际账单。

## 费用覆盖汇总（2026-10-07）

复用原调用事实和两个GET分页接口、既有管理员/任务数据范围授权，无新表、权限或汇总API。响应原分页字段保留，追加costSummary。SQL在数据库按声明币种聚合全部匹配记录，保留状态/modelKey/Task/EID过滤，不加载所有调用到内存；分页与汇总在同一只读REPEATABLE_READ事务中查询。

仅CALCULATED_FROM_DECLARED_PRICE、金额非空且币种非空的调用纳入已知小计，其他调用全计未知。无已知费用的分组小计为null，空结果计数0；不把未知费用填0。计数及BigDecimal小计作为字符串返回，按币种分别展示，不汇率换算、不宣称完整费用或供应商账单。历史无费用字段的调用仍在未知覆盖中。全局查询聚合扫描成本随历史增加，索引/保留策略及生产容量验收仍须继续。没有运行测试、SQL、模型调用或部署。

## 单次请求输出上限（2026-10-07）

复用原ModelProfile/version、Agent ModelSnapshot、模型配置API/权限和Spring AI OpenAiChatOptions。源码检索framework/Sysadmin与当前IQC未发现现成资产输出上限；现有SDK提供maxTokens及maxCompletionTokens。V1.1.54仅扩展原profile两字段，不新增模型/政策表或基础设施。

maxOutputTokens为严格JSON整数0到1000000；0清除，未提供/null保留原配置。正值必须明确outputTokenParameter为MAX_TOKENS或MAX_COMPLETION_TOKENS，分别发送max_tokens或max_completion_tokens，不会同时发两种。Agent主备发布快照冻结上限与参数，旧null省略保持原摘要；历史快照不借现行配置补上限。连接检查沿原profile使用相同参数。坏快照限制在密钥解析/远端调用前拒绝，禁止fallback绕过坏限制。

上限只表示请求参数，供应商接受/遵守及推理token语义需按实际模型核验；自动工具每回合适用模型默认上限，但累计工具回合不等于这个上限。Legacy默认模型/HTTP部署沿原配置，本资产字段不臆造其请求限制。尚未形成输入token可证明上界或累计Token/金额硬预算；没有运行测试、迁移、真实模型或部署。

## 调用输出限制证据（2026-10-07）

复用原call事实及受管模型选项，不创建平行限制表/API。V1.1.55在reserve时保存当次所选模型的输出上限/参数及单响应范围，与原价格/Task来源同一事务；重试/fallback各自记录所选值，旧记录不回填。无声明NOT_DECLARED，有声明待响应UNKNOWN_RESPONSE；自动工具多回合UNKNOWN_REQUEST_SCOPE，输出用量缺失/无效UNKNOWN_USAGE。

仅可观察单响应且供应商返回合法输出Token时比较上限，标记OBSERVED_WITHIN_LIMIT或OBSERVED_OVER_LIMIT。部分usage中合法output存在可以观察，不能制造缺失input/total。超限保留真实Token和声明费用，不截断事实，也不重发已完成请求；在上限内仅说明该响应观测值，不证明供应商以后遵守限制。max_completion_tokens的完整推理范围仍依赖供应商用量协议。Legacy/HTTP上限未知保持未声明。累计Token/金额硬预算及传输范围仍继续；没有测试/SQL/真实模型/部署证据。

存在冻结输出限制时，Prompt运行时携带输出上限覆盖项会在reserve前拒绝，防止模型默认选项被覆盖后留下错误请求证据。既有调用只构造消息/工具选项，不携带输出覆盖；未修改framework。

## 工具回合实施前复用及缺口（2026-10-07）

本地实际spring-ai-openai/model 2.0.0 javap显示OpenAiChatModel.call→internalCall只返回单响应，没有executeToolCalls或递归续调；ToolCallingChatOptions也没有旧版internalToolExecutionEnabled。当前代码仅把callbacks传入Prompt后调用一次，不能完成模型要求的工具续调。复用SDK ToolCallingManager.executeToolCalls/ToolExecutionResult.conversationHistory，不创建自定义工具协议或平行MCP服务。

受管OpenAiChatModel显式循环，每次响应独立Gateway reserve/settle及同来源工具回合编号；重用快照工具白名单、原会话Session关闭及请求输出限制。最多配置8次工具执行（允许初次响应及8次续调），超限停止而不fallback；工具执行异常或执行过工具后的模型异常停止整轮重试，避免重复外部副作用。旧未知ChatModel行为不能假定单响应，不自动改造成同协议。每次受管回合的价格/输出观察不再因注册工具而一概判UNKNOWN_REQUEST_SCOPE；依然需要实际网络/供应商范围验收，不宣称任意自定义ChatModel/传输已全覆盖。

部署默认模型若为当前OpenAiChatModel并传入工具，也沿显式循环（其隐藏SDK重试/缺价范围仍未知）；其他自定义默认ChatModel带工具拒绝，不能冒充单响应计量。工具次数另限制默认64（配置1–256），轮次配置1–32，工具多候选响应拒绝而非执行不明确分支。完成工具后的业务JSON解析失败也停止原Provider整轮重试，防止重放已经执行过的工具。单次响应最终返回保留该次metadata，不把多响应token伪装成最终响应总用量；全程费用沿每调用事实汇总。

每个工具Callback复用原Task/EID/current/status/暂停取消来源校验，独立只读短事务完成后才执行网络工具；旧无执行来源仍不能猜Task。SDK工具异常处理器明确抛出，不能把策略拒绝变成可继续的工具错误文本。此校验是执行前观察，不能撤销已经发送的远端工具请求；没有持久工具副作用幂等账本，外部跨Task重放仍需要工具方幂等协议。

工具调用ID在本轮需非空/受限且不重复，包括跨续调的重复ID；整批ID先校验再执行工具，防止同一轮内重放。该内存集合不提供跨进程/跨Task幂等。

## 上下文窗口及部署声明实施前复用（2026-10-07）

PRD16.1要求Model Registry具有contextWindow/deployment，现有原ModelProfile/ModelSnapshot仅endpoint/modelName和业务配置version，不提供上下文窗口或部署修订声明。复用原资产/快照/调用事实扩展，避免新Registry/部署表。声明不是供应商实际部署验证；缺失仍未知，不从profile配置version猜远端版本。

窗口声明严格整数0–100000000，0清除，未提供/null保留，正值表示供应商声明的输入与输出总上下文Token；输出上限不得大于窗口，修改后合并原字段再次验证。deploymentRef受限128字符版本/部署参考标识，空字符串清除、未给保留。发布主备快照冻结；每次所选调用保存其声明，旧记录不回填。Token/金额硬预算仍需要可证明输入上界及远端限制，声明不能直接充当硬保证。

## 工具副作用与任务自动重试实施前缺口（2026-10-07）

原Provider/Router禁止工具后本轮重放，AutomaticRetryService仍只按Task失败/次数/退避发现，可能另建Execution重放工具。复用原Gateway call作为持久“可能已开始工具执行”证据，在工具网络前短事务写入，失败则不发工具；不新建工具结果/任务表。自动重试沿Task范围查看该证据，保守停止并显示TOOL_SIDE_EFFECT_REQUIRES_REVIEW。

未建立工具外部幂等或逐失败项关联前，任何该Task的工具开始证据均停止自动失败重试，包括其他已成功项的工具，可能降低可用性；旧未记录事实无法追溯，不推断旧副作用。手动重跑仍是用户显式操作，不提供自动幂等证明。记录表示执行可能开始，非成功/失败/恰好一次；崩溃/工具部分执行也保留。

## 预算事务核心实施前复用（2026-10-07）

现有治理UsageCounter仅事件次数，无Token/金额预留及未知余额协议；复用原capacity行锁与call事实，扩展同两表，不建平行账单/预算服务。预算是模型键生命周期累计额度，不按自然日重置，不合并不同币种。配置/界面另接原容量管理，默认无预算不改变旧调用。

启用预算必须明确核定上下文窗口、部署参考及凭据，所选冻结声明严格一致，单请求输出上限存在且范围明确。预留Token为核定总窗口W；金额上界为(W×输入单价+min(W,输出上限)×max(输出单价-输入单价,0))/百万。该上界依赖核定供应商契约：计费用量受总窗口约束、超长拒绝请求不产生超过窗口的计费用量，以及选定输出参数确实生效。配置声明或一次未超限响应不自动证明契约；不能对任意兼容端点宣传无条件硬保证。缺少依据拒绝预算调用。

reserve与并发/调用事实同事务累加committed（实际核销+仍持有预留），新预留不能超过限额。响应完整合法且可定价才替换相应预留；缺/部分/非法usage或价格不确定保留相应预留。UNKNOWN/崩溃RESERVED及人工恢复并发不退Token/金额。观测超核定窗口/输出/费用预留时保存实际核销和隔离标记，停止该模型后续准入，不把超过预留的实际费用截断。尚待管理配置/可查余额/等待及运行验收；本批不宣布预算已可用。

金额依据还需核定统一flat价格的计费范围；响应已观察到缓存差异计费或价格证据损坏时，保留费用预留并隔离模型，不能继续将未知差异当作已核定上界。核定凭据编号与政策版本在call冻结。后续管理配置应保持同模型账本币种稳定，不通过改币种、启停或自然日重置累计承诺；未知额度人工对账需另有明确协议。

核定绑定具体输出参数；金额预算另外匹配核定的冻结定价hash，价格变更不能继续沿旧核定凭据准入。账本覆盖参与预算准入的调用，不把启用前旧费用或未配置预算的调用回填成0；后续界面必须明确覆盖范围。

## 预算管理API（2026-10-07）

复用原GET/PUT capacity接口、管理员角色/资源权限及前后策略Audit。PUT可选budget全字段配置，未给/null不修改预算；至少一种限额，0明确阻止新消耗，null仅清除单项限额，不能清空全部以停止已开始账本。Token与金额均精确字符串，窗口严格整数，必须明确contractVerified及部署/输出参数/依据，金额另需ISO币种/冻结pricingHash；未知字段/错误类型拒绝。

原policyVersion CAS覆盖并发及预算配置，降低限额允许低于已承诺量但新准入受阻，不撤销历史/重置committed，不清隔离。模型账本已设币种不能改，不能靠改币种重置余额。GET返回committed与剩余（耗尽显示0）、核定声明及隔离，原call分页追加每调用预留/核销状态。实际供应商契约仍须管理员外部核定，API布尔确认不是自动验证；旧未预算记录保留未知。管理界面及运行验收继续。

原容量弹窗展示限额/累计承诺/剩余/隔离，可选择同时修改预算；只改并发不重新确认预算。管理员填写核定参考及凭据并确认外部契约，金额绑定已有核定价格或所选调用冻结版本，不要求输入内部JSON/hash。Token及金额额度保持字符串，不经JS数字转换。金额承诺仅覆盖启用金额额度的调用，清除金额单项限额时后续费用不进入该金额账本，完整已知费用沿原调用汇总查询；不把未参与历史算作已知0。界面不是供应商自动验证；Task等待、未知额度对账/隔离恢复、真实运行验收仍需继续。

## 预算等待实施前复用与恢复（2026-10-07）

原PAUSED状态、Task/Execution/Item持久ledger及用户resume API可复用；普通resume生成新EID/attempt，预算等待需特例沿同EID重新QUEUED，不增加总轮次数。V1.1.60仅原Task增加安全预算原因/模型键，不新增等待任务表/API/权限。

Gateway将预算准入结果连同真实模型key显式传递，Provider及Pipeline future不把预算拒绝折成LLM错误、节点retry/fallback或质检失败。所有会话worker结束后，Task→Execution锁序记录PAUSED/原因，仅RUNNING且无用户暂停取消意图、无该Task工具曝光时生效；RUNNING工作项退回PENDING，实际成功/失败清单不修改。预算暂停不触发FAILED自动重试。

用户调整策略后沿原resume授权/冻结依赖与输入清单校验恢复同EID，保留成功产物，不重置预算和轮次；实际再次准入仍检查额度，恢复不是额度已够的保证。不自动轮询重新发送。工具已可能执行的Task保留失败/核对路径，不能通过预算等待自动重放工具。并发worker完成前及取消/用户暂停优先，仍须真实事务/任务/模型验收。

## 人工预算对账实施前复用
现有GatewayCall/Capacity、管理员Actor和容量恢复已可复用；未找到公共模型账单核销能力。扩展原call保存独立人工事实，不改原usage/cost，不新增账本表。已确认响应或已提供远端停止凭据才可核对；调用锁→容量锁，同一凭据冻结且幂等，不自动解除预算隔离。迟到响应保留原始事实但不再重复预算核销。此批仅事务核心，管理API/UI尚待接入。

### 管理入口复用与权限缺口
原容量释放接口仅授权并发恢复，不能兼任修改预算金额。沿原ModelGatewayController/CallPage/ModelGatewayCalls接入对账，新增独立IQC_ADMIN资源及按钮；复用Audit记录容量前后和人工事实，不创建新页面或通用审计模型。金额输入与冻结预算币种强匹配，Token和金额通过字符串保持精度。

人工对账入口：`POST /api/iqc/model-gateway/calls/{callId}/budget-reconciliation`，独立资源`iqc:model-gateway:calls:reconcile`仅IQC_ADMIN授予。body：modelKey、tokens精确Long字符串、cost精确非负金额字符串或null、currency原预算币种或null、evidence受限128字符、verified true。未参与金额预算不得填金额和币种。返回before/after容量和reconciliation（callId/modelKey/人工用量金额/Actor/UTC/凭据）；Audit关闭请求正文、记录返回值，不采集账单正文。原调用分页及Task调用分页增加可空reconciliation；原声明价汇总仍只计算响应事实，人工预算核对不混入。
界面复用原调用表，管理员在已确认响应或已恢复并发占用且参与预算的记录上操作。输入保持字符串精度，确认每次打开重置，保存时阻止重复提交，关闭/切换后旧响应不覆盖新弹窗。运行事务、权限和浏览器尚未验证。

## 预算隔离恢复实施前复用
复用原capacity版本/预算声明、call核销事实和管理员Audit。原策略修改不能解除隔离；新增明确恢复动作，要求全模型并发占用为0，所有参与预算调用已SETTLED或RECONCILED，原累计承诺保留且不超过重新核定限额。提交完整新核定政策及不同凭据、按原version CAS，禁止把未知预留当已完成。容量锁内查询核销状态，不反向锁call，避免与call→capacity核销锁序冲突；恢复不能证明供应商契约，仅记录管理员外部核定。

恢复API：`POST /api/iqc/model-gateway/capacity/{modelKey}/budget-recovery`，body为expectedVersion精确Long字符串和budget完整原九字段政策；独立资源`iqc:model-gateway:budget:recover`，仅IQC_ADMIN。返回CapacityChange记录前后状态供原Audit存储。无自动恢复，不重置committed、不改原call人工/响应事实；若仍有HELD/TOKEN_SETTLED_COST_HELD/QUARANTINED或状态缺失参与调用，需先逐call最终对账。该批管理界面尚待接入，SQL和事务运行尚未验证。

恢复事务采用READ_COMMITTED：管理员身份读取可能早于capacity锁取得，恢复条件必须读取取得容量锁后的当前调用事实，避免复用先前RR快照漏掉刚完成的未知调用；持有容量锁期间禁止新准入及预算核销提交，不反向锁call。

隔离恢复UI已接入原容量弹窗。仅具备独立恢复权限且当前已隔离时显示入口，占用非0时禁用；重新核定复用原完整预算表单，清空旧凭据及确认，冻结并发上限。提交调用独立恢复API，成功保留累计承诺并更新政策版本，服务仍核验全部账本和版本。界面按钮不替代后端校验，供应商实际契约/运行事务/浏览器尚未验证。

## 首次调用前预算配置实施前复用
复用ModelProfile版本、ModelIdentity物理键、GatewayCapacityMapper.ensure及原configureCapacity版本CAS，不建立新模型注册表。缺口是容量仅首个reserve创建，原管理入口依赖已有call。先扩展事务核心：只初始化原capacity行，不发模型请求；完整政策必须与选定模型版本声明一致，原账本已存在时仍按expectedVersion CAS而非覆盖重置。profile选择/权限/API及界面后续接入，不可让用户任意声明端点绕开模型资产。

### 首次配置管理接口缺口
复用原模型管理Controller/Mapper和预算事务核心；新增模型id下的明确预算配置动作，独立管理员资源。服务锁定原profile并核对versionNo，再构建与调用相同的ModelIdentity，调用原账本初始化；不读取密钥或发送网络，不接受客户端provider/endpoint/modelName。返回profileId/version和容量前后值供原Audit。并发profile修改按同一行锁串行，原模型status禁用时拒绝。

API：`POST /api/iqc/agent-assets/models/{id}/budget`，独立资源`iqc:model:budget:configure`仅IQC_ADMIN。body：modelVersion精确JSON整数、capacityVersion精确Long字符串、concurrencyLimit精确JSON整数、budget原完整政策。未有capacity使用version0，已有账本需当前版本；冲突拒绝且事务回滚，不发试探模型请求。返回modelProfileId/modelVersion/capacity.before/after，Audit requestfalse/responsetrue。UI入口和首次配置前读取已有账本版本仍需接入。

### 模型预算读取复用缺口
现有capacity GET对不存在返回404，不能把所有错误解释成初始版本0。新增原模型budget GET，复用ModelIdentity及CapacityView，以capacity:null明确未创建；读取错误仍失败。返回保存模型version、预算绑定所需价格摘要/币种和窗口部署输出声明，无密钥或远端调用；只读一致快照，不创建capacity。

读取API：`GET /api/iqc/agent-assets/models/{id}/budget`，独立资源`iqc:model:budget:view`仅IQC_ADMIN。返回当前保存模型的预算声明、价格摘要/币种及capacity（不存在明确null）。RR只读事务内profile/capacity为同一快照，提交仍重新锁profile和capacity按双版本复核；读取不初始化，不使用读取失败作为未创建。
模型列表“预算”复用原ModelGatewayCalls容量表单和权限，初次账本采用version0和可编辑并发默认1，明确未创建及未预算历史不补计。预算声明及价格绑定当前保存模型版本，保存调用profile budget接口；成功后替换读取状态，不再显示未创建。声明不足的模型配置需先保存窗口/部署/输出限制。未新增页面、表或预算组件，真实数据库/供应商/浏览器尚未验证。

## 系统层模型性能监控实施前复用
PRD14系统层TPS/P50/P95/P99/失败率及18.2监控复用现有Actuator/Micrometer/Prometheus，不新增采集服务或持久化模型。当前Gateway有每调用账本但无运行时模型耗时直方图。计时仅Supplier模型调用边界，每显式重试/工具续调独立样本；排除预算准入和账本核销时间，响应返回不代表质检成功，异常只说明结果未知，不猜远端失败。仅固定outcome标签，不加Task/用户/模型键高基数或端点秘密。查询控制面、GPU和实例抓取仍待接入。

### 准入、核销及Token运行指标复用
继续复用ModelGateway短事务返回和MeterRegistry，不另建实时Token账本。固定阶段/结果标签统计准入和核销；仅已确认核销且合法的nullable供应商Token加入运行计数，缺失不补0、无效不计。Micrometer浮点计数仅运行趋势，不能替代原精确Long/BigDecimal账本及预算。


## 工具启动与预算暂停的共同状态边界

带Task/EID来源的 `markToolsStarted` 按Task→Execution→Call取得短事务行锁，再沿用当前轮次、运行状态及来源校验。预算暂停先获得Task锁时，工具标记等待其提交并拒绝PAUSED来源；工具启动标记先提交时，原预算等待查询看到已有工具证据并拒绝自动暂停。模型响应的Call行锁本身不能替代此共同Task锁。

标记事务提交返回后才调用实际工具，锁不包围工具I/O；既有无执行来源调用协议保留。这保证数据库准入顺序，不代表能够撤销已经发送的工具请求或自动重放未知副作用。真实并发复现和验收见 `iqc-prd-b-tool-budget-race.md`。

## 2026-10-23 单会话费用查询

扩展原`GET /api/iqc/tasks/{id}/model-calls`可选`conversationId`（非空合法会话ID），不增加端点或权限。未传保持原任务分页/费用统计，旧Java五参数控制器与服务入口保留。传入时，原任务get组织scope及EID属于该任务的检查先完成；记录和全筛选费用汇总均按taskId/executionId/status/conversationId一致过滤，同REPEATABLE_READ事务。敏感读取授权、no-store、审计不记录请求/响应及原gatewayCalls响应副本投影继续复用。

复用iqc_model_gateway_call及原费用聚合SQL，新增参数用绑定变量；为保留旧mapper调用契约，旧/会话两方法引用同一COST_GROUP_QUERY和结果映射，不复制计费算法。没有新增费用表、重新计价、网关结算或模型调用。

UI原任务模型调用组件提供会话ID筛选，显示实际生效会话/执行范围。切换任务清筛选，查询沿用原generation及清理流程；状态/会话变化清旧结果，迟到响应不回填。无executionId表示全部轮次，包括重试，不是最后一次成功结果；可观察工具续调/降级各条真实物理调用费用。

已知费用小计只包含CONFIRMED且CALCULATED_FROM_DECLARED_PRICE并有金额/币种的账本调用；其它为未知。按币种分组，不能加不同币种。单会话已记录费用只覆盖明确关联该任务/会话的账本，不推测旧未关联记录；无记录不意味着零成本，存在未知费用时不能当完整成本。这是冻结声明价计算，不是供应商结算账单。原成本摘要计数/金额继续字符串精确传输。

当前本批仅编译/构建，无新增或运行测试。B待验证实际MyBatis旧四参数缺conversationId绑定/新五参数SQL与共享ResultMap、跨任务同会话ID隔离、EID归属、不同币种/未知价格、分页全量汇总、零记录、输入拒绝、当前授权/撤权、UI筛选变更及迟到响应，真实供应商账单仍独立。整体系统层成本/吞吐/GPU及I1–I7未因此全部完成。

### B 2026-10-23 单会话费用实际 SQL 行为

在原IsolatedMysqlMeasuredPublicationIT模型场景结束追加8条合成费用账本，复用原GatewayCallService/任务读取与真实Mapper；补配置数据库分页插件，账本读取走原事务代理。筛选task+EID+conversation的4条调用拆两页，记录不重不漏且两页共享全量4条汇总；USD已知小计2、EUR2.5，UNKNOWN计数1且单独过滤时knownSubtotal=null。无EID包含prior共5条，同会话其它task与其它会话/旧null关联不会混入；空结果无币种，旧四参数costGroups缺conversationId键仍正常，新五参数与原ResultMap覆盖一致。跨任务EID、无效会话ID及scope撤权拒绝。

原37831 terminal1新增Bean与导入原GatewayCallService重复，删除新增Bean复用原服务；原46397 terminal1遭本机时钟回拨约1秒，ID生成器在费用断言前拒绝，未改生产ID或校验规则。用已编译产物重跑原6505 terminal0，`/tmp/iqc-b-conversation-cost-sql-retry.log`，fresh XML SQL2+一致性计算5+报告23=30项无失败/错误/跳过；前轮testCompile完成，末轮surefire:test、git diff --check通过。专用34307脚本自动关闭。

费用为隔离SQL合成账本而非供应商计费，数据范围服务/组织权限lookup仍受控；本轮没有验证新会话参数HTTP投影、实际PDP或浏览器费用筛选。既有模型/报告链断言保留，不将30项数量扩称全量PRD验收。

### B 2026-10-23 单会话费用 HTTP 投影

扩展原ModelCallReadHttpPrivacyTest：conversationId精确绑定六参数service，在最小敏感授权/全部敏感授权下HTTP data与原ResultReadProjection结果一致，费用长小数字符串与账本总数保留、预算证据按权限隐藏，源对象不变；未传EID保持null（全部重试范围），空汇总callCount为字符串0；撤销当前model-calls:view拒绝且不查询账本。修正原审计测试反射目标到实际HTTP六参数方法（原五参数Java兼容入口已不承载映射），继续断言审计不记录正文。

原42445 terminal0，`/tmp/iqc-b-conversation-cost-http.log`，fresh XML17项无失败/错误/跳过；testCompile+surerfire与diff检查通过。真实MVC/响应包装/敏感投影，grant和领域服务mock；单会话真实SQL隔离证据沿上一条，不叠加宣称实际PDP/浏览器/供应商费用整链通过。

## 业务报告精确执行集合汇总（2026-10-23）

原GatewayCallMapper/Service增加内部taskRunUsage精确任务/EID/会话集合，调用方为原业务统计显式includeModelUsage选项。costGroups原SQL与费用汇总器共用；新集合参数不能成为全表查询，空集合返回无记录汇总。追加同集合CONFIRMED合法接收/结算平均耗时及未测量计数，字符串保留数值精度，任务与执行来源复用原get/mapper核对。HTTP当前report:view+task:model-calls:view检查及默认不查询原则、指标边界见iqc-pipeline-rollout-tasks.md。无新增持久化/API/权限；本批编译构建，实际SQL与授权专项待独立验证。

### HTTP回退Provider部署调用证据

HTTP Provider可选配置iqc.llm.deployment-ref（环境变量IQC_LLM_DEPLOYMENT_REF），采用原DeploymentDeclaration受限128字符格式。它是运维维护的模型版本/部署参考，不是凭据，不会作为额外字段或Header发送给供应商；不设置则保持部署未测量。必须在实际模型权重、版本或路由语义变化时更换引用并重新拟合，不得把可变别名当不可变版本保证。

成功请求保留原ModelGateway.invokeTracked的Handle，用Handle.modelKey与此次规范化请求身份匹配后生成原InvocationFingerprint，输入摘要覆盖HTTP请求正文、path、模型key和部署引用；不包含Authorization。原Gateway调用记录保存部署声明，usage/重试/可选诊断仍沿同一入口。无Gateway、无Handle或身份不匹配不伪造指纹；无部署声明的三字段指纹只能提供部分诊断，不能满足校准执行的完整身份要求。网关旧modelKey和容量/预算桶不变。

这使HTTP路径具备校准来源所需的身份测量入口，不等于网络/供应商/SQL/发布验证完成；服务端仍须原拟合身份一致、留出集隔离和发布测量门禁。运营方需保证deployment-ref实际对应版本，系统不能从一个自报标签证明供应商权重不可变。

HTTP客户端记录的端点与构造该RestClient时的baseUrl一致；每次尝试在调用前捕获model/path/deploymentRef，正文、准入身份与成功后的指纹复用同一份值，不在网络返回后重读可变配置。重试仍按各次实际尝试记录，不将先前失败尝试身份冒充最终成功身份。修改端点需重建客户端，不能仅改属性对象就声称HTTP目标已经切换。
