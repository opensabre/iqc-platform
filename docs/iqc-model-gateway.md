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
