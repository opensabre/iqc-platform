# I4 自动失败重试

## 实施前复用与缺口

既有 InspectionExecutionService.queue、TaskExecution/TaskItem 已经保存执行轮次和生产完整消息清单，新轮沿原冻结输入，仅重试未完成单元并保留既有成功产物。自动失败重试应复用这些能力，不能复制任务/执行/消息持久模型。

当前手动排队只在最后按旧状态 CAS，未绑定旧 EID/attempt；后台重试与手动请求同时发生时，新一轮快速失败可能再次呈现相同 FAILED 状态，使旧请求误用前轮检查点。这批先将共享 queue 在 Task 行锁内解析来源/清单和生成新轮，并提供严格按旧 EID/attempt 比较的内部生产重试入口。该入口是自动策略调度的前置，不代表自动失败重试配置/后台发现已完成。


## 执行轮次前置边界

queueRetrySystem(taskId,expectedExecutionId,expectedAttemptCount) 仅用于已有完整 productionInputs 的生产 PIPELINE，调用方须先完成 fresh 负责人授权及冻结策略检查。缺失/取消/成功/暂停、状态或EID/count不匹配返回null，不产生新轮次；它不是公开HTTP入口，也不自行调度或查询授权。普通queue/queueSystem仍沿原语义。

共享queue和resume均在Task当前行锁内读取前轮清单与依赖，再按状态/EID/count CAS，检查Execution/Item插入和Task绑定行数。次数负值或int上限拒绝。完整生产清单继承成功/取消及真实resultId，只把未完成工作项重新置PENDING；恢复仍生成新执行轮次，原检查点不覆盖。锁序Task先于Execution/Item，与既有提交/取消路径一致，依赖验证仅普通读，没有新增Task→asset行锁。

上述执行轮次前置批次未增加表/页面/权限或框架变更。下方记录随后完成的策略接入；全局预算继续待开发。既有mock测试可能需要适配新的selectOne锁读，未宣称运行回归通过。

## 策略实施约束

后续配置和后台调度需同时满足以下条件，不能以本批入口存在代替策略交付：

1. 策略明确总执行轮次上限（包含初次及手动轮次）、初始/指数退避上限及有效期；缺省不自动重试。策略和负责人范围冻结并校验摘要，运行中启停不改写历史策略或次数。
2. Cron/事件订阅/API 接收策略沿发布/Accepted 版本冻结，新增可选字段在缺省null时省略以保持旧幂等摘要；手动任务配置仅允许首次排队前，重试不能偷偷更换输入或Pipeline。
3. 后台发现只处理 FAILED/PARTIAL_FAILED 生产冻结任务，读取当前EID/count及真实失败轮次时间。fresh 当前账号、稳定身份和服务器固定执行/策略权限通过后，范围取原上限与当前交集，复查所有原输入来源的当前可见性。
4. Task 锁内重验当前EID/count、策略摘要、有效期、次数、退避及暂停/取消；调用queueRetrySystem。过时观察直接结束，不新增Execution，不绕过检查点验证。
5. 自动排队及不可变尝试完成事实同一事务；次数/有效期耗尽可查询，授权或依赖失败退避但不重置总轮次。人工重试沿既有权限，不让手动操作重置自动预算。
6. 前端提供配置/启停、等待原因和下一次时刻，清楚区分“重试原输入”和“按新版本重跑”。全局并发/速率/成本控制继续复用治理限流、已有worker和usage能力，在记录具体缺口后再补原子跨实例预算。

## 策略接入（2026-10-07）

新增 AutomaticRetryPolicy（iqc-automatic-retry-v1）与原 Task 上的 V1.1.48 字段，不新增任务、执行或预算表。maximumAttempts 是包含首次及人工轮次的总次数（1–10）；initialDelaySeconds 1–86400，整数 multiplier 1–4，maximumDelaySeconds 不小于初始且最多604800。effectiveUntil 为可选 UTC Instant，精确到毫秒。首次失败后等待初始间隔，后续按 multiplier 指数增长并封顶；截止期前无可用时刻则停止。

策略、原稳定负责人身份/范围、服务器固定权限集合一起冻结并校验 SHA256。手动生产任务仅在 CREATED 且首次排队前配置；周期发布、订阅发布和 API 接收可携带可选 retryPolicy，缺省 null 从 JSON 省略，保留旧协议幂等摘要。原输入及发布版本始终沿首次冻结值。启停只修改开关，不重置次数或替换策略；任务详情公开策略由专用接口读取，不返回负责人绑定 JSON。

接口：GET /api/iqc/tasks/{id}/retry-policy、PUT 同路径、POST 同路径/availability。分别要求 iqc:task:retry-policy:view / configure / availability；写入额外要求原稳定身份负责人。Cron/订阅/API 配置策略需 configure；后台每次 fresh 重验原触发权限和 create/execute/configure，原上限与当前范围取交集，复查所有冻结输入的当前归属可见性。

后台默认关闭：iqc.retry.enabled=true 才启用，iqc.retry.scan-delay-ms 默认5000。每轮至多25个 FAILED/PARTIAL_FAILED 任务；基于真实失败 Execution 的更新时间、当前EID/count、策略摘要和退避时刻产生观察。Task锁内重新验证，过时观察不会生成新轮；成功产物沿完整清单继承。删除终态也持有 Task 行锁，避免自动排队与删除交错。暂停/取消标记及终态不参与发现；失败任务可在策略面板停用重试，也可取消任务；取消保留历史失败 Execution 状态。发布源中的绝对重试期限到期后，后续新任务仍正常首次执行，只将自动重试冻结为停用。

STARTED 使用原追加尝试账本独立提交，排队及 FINISHED 同事务；失败回滚后记录 FAILED，源上下文严格匹配失败 EID/策略摘要/计划时刻。nextRetryAtUtc/error 可查询等待、次数/期限耗尽或依赖/授权不可用原因；依赖失败至少延后30秒，不重置预算。未配策略的任务维持原行为。

UI 提供共享策略编辑器、周期/订阅表单与任务详情启停/等待状态，组织权限迁移 V20261004_18。全局并发/速率/token/成本预算、真实外部 MQ 和离线音频仍待继续，I4 与完整 PRD 未完成。本批仅编译与前端构建，没有新增/运行测试、SQL、真实事务/模型/浏览器验证或部署；交接记录保存提交及构建证据。

应用现有 ObjectMapper 补注册 JavaTimeModule，并对 Instant/LocalDateTime 写 ISO 文本；保留原 java.util.Date 时间戳及其他序列化选项，避免变更旧非 Java-time 证据摘要。原裸 Mapper 无法读写周期/接收协议中的 Java-time，属于本应用配置缺口；未修改 framework。
