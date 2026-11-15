# IQC 部署与发布门禁

本文档描述 `iqc-platform` 与 `iqc-platform-admin` 的最小可运行部署契约。远程环境执行前必须完成清单核对；本文档不授权直接修改远程数据库、Nacos、网关或认证配置。

## 条件与联合试跑的版本边界（尚未批准上线）

逐项路线任务定义使用 `iqc-scheme-v2-item-routes-v1`，显式优先于旧读取器可能忽略的执行字段。当前本地支持四条有限路线、冻结依赖计划、适用条件、显式 LLM 范围、多轮、允许策略变体，以及与联合标签的组合。条件计划使用 `iqc-item-execution-plan-v2`，把独立条件规则冻结为 `APPLICABILITY` 阶段；条件明确命中才执行该项路线（包括选中的策略变体路线），完整未命中记为不适用并跳过后续阶段，错误或不确定状态不转换为通过。路线与条件现在可经专家试跑、结果复核后正式发布，并由普通批量/定时模板任务执行；普通用户可选择允许变体，所选编码进入请求幂等与任务快照。标签运行独立于条件门控，评分与标签物化沿用同一会话事务。B154 的 H2 端到端场景和隔离 MySQL 8.0.34 正式迁移 schema 上 49 个服务事务／HTTP 场景均通过；这不代表生产数据库、真实模型或线上环境验收。旧 API 可能忽略新字段，升级前不得向旧节点写入新配置或任务；如需回滚，先隔离新格式草稿及路线/条件/标签/范围快照，不能删字段或改协议以强行兼容。部署与共享数据操作仍需另行授权。

路线计划另有冻结投影检查，接入发布、任务创建、排队、实际开跑及恢复边界：核对实际规则数组、完整阶段版本清单、检测扣分为零／不否决、冻结 Agent 与实际 Agent 快照，再仅用冻结参数重算完整计划并核对 release 摘要。计划中的额外字段、不同输入范围、上游引用或业务项映射不能被忽略。此检查不查询当前草稿来替换标准，也不是数据库内容的密码学认证；资产实时停用仍由既有校验负责。B154 打通本地发布及普通任务，不代表外部业务审批和生产执行已验收。

显式 LLM 输入范围试跑新增 `iqc-scheme-v2-input-scope-v1`，优先于下述联合／条件标识；同一任务中的标签和条件仍保留，不能因标识改变而忽略它们。草稿质检项可携带 `inputScope: MESSAGE | CONVERSATION`，仅允许 LLM 检测；缺省保持历史输入语义。普通规则及 DLS 继续自身范围。同一冻结规则暂不支持混用消息与会话上下文，也不支持与条件依赖混用显式范围；配置阶段明确拒绝，不能以 ruleId 相同为由复用错误上下文。

范围随依赖冻结为 `inspectionScope`，完整会话调用在本次会话／运行内共享；无模型引文时不得把每条投影消息伪造为命中证据。专家页面可选择范围，正式发布与普通模板任务均按冻结范围执行；真实浏览器、服务端外部模型与业务样本仍待验收。前端部署前须先升级后端。投递前必须升级所有 API／执行／结果读取节点；回滚需隔离含 `inputScope` 的草稿及新协议任务，不得删除范围字段或改回旧标识。这里不授权部署或共享数据改写。

专家草稿编辑仍使用 `iqc-scheme-v2`。创建含标签的联合草稿试跑时，任务内冻结定义使用 `iqc-scheme-v2-joint-v1`，同时含适用条件也使用该标识；只有条件、没有标签时使用 `iqc-scheme-v2-applicability-v1`。内容摘要基于实际冻结的 ReleaseSnapshot 计算；草稿对象不会被修改。无扩展的历史定义、试跑和发布快照保持原格式，不批量重写任务或历史摘要。新读取器兼容上述三个标识及历史旧标识的联合定义；未知标识仍拒绝，禁止回退旧评分。

联合标识同样要求先升级所有相关读写节点再开放投递。回滚时隔离含标签的草稿与联合任务，不能删除标签引用或改写协议以兼容旧节点。已有旧标识联合任务维持原内容，升级盘点须按标签字段识别，不能只看协议字符串。当前协议回归不能代替实际旧镜像、混合版本或真实数据库验证。

独立标识用于触发严格校验 `iqc-scheme-v2` 的旧读取器拒绝，不能使旧二进制理解条件，也不保证所有历史版本都严格校验。本地隔离 MySQL 8.0.34 已使用正式迁移完成条件与允许变体四场景集成验收；仍未运行旧镜像或完成混合版本、生产数据库、网关和真实业务验收。不要把本地已开放理解为部署批准。

获准部署后，应先关闭专家条件写入／试跑入口并停止相关任务投递，升级全部 API、执行、结果、复核及报表读取节点，确认其支持新标识，再在隔离环境验证条件任务和历史回放；只升级生产者或前端不满足条件。这里的关闭入口与停止投递是运维前置条件，不是已实现的能力开关。旧节点不得接收新条件草稿（草稿仍为旧编辑协议）或消费新条件任务。

若需回滚到不支持条件的版本，先隔离含条件的草稿及所有新标识任务，停止其创建、执行、恢复和结果读取，再评估旧版本覆盖范围。不得通过删除 `appliesWhen` 或把版本标识改回旧值强行兼容，否则会改变已冻结业务语义并可能错误计分。新协议任务保持原始内容，待兼容节点恢复后处理。该说明不授权部署、停服或修改共享数据。

## 1. 服务与依赖

专家发布历史新增只读 `GET /api/iqc/schemes/{id}/versions?beforeVersion=<正版本号>`，沿用 `iqc:scheme:manage` 资源码；省略游标读取最近 20 个版本，响应 `versions` 与 `nextBeforeVersion`，后者为空表示没有更早页。不新增表或授权码，仍需部署后验证端点治理注册、网关鉴权与团队范围；前端升级前应先保证后端提供此接口。历史读取不要求方案启用，也不改变现有发布／执行门禁。

| 组件 | 约定 |
| --- | --- |
| IQC 服务名 | `iqc-platform` |
| 默认端口 | 业务端口 `8040`；管理端口 `18080`；`8030` 已被 `base-gateway-admin` 占用 |
| 数据库 | MySQL schema `iqc_platform` |
| 注册中心 | Nacos，服务注册名必须为 `iqc-platform` |
| 组织服务 | `base-organization`，用于当前用户 `groupId` 和数据范围 |
| 治理服务 | `base-sysadmin` / OpenSabre Framework Starter |
| 前端 | 独立站点 `iqc-platform-admin`，Ant Design Vue，默认端口 `3010` |

仓库提供 `base-k8s/docker-compose-iqc-platform.yml` 作为服务运行模板；镜像、数据库密码和治理令牌必须由部署环境显式注入。
该 Compose 文件同时提供 `iqc-platform-admin` 前端容器；前端容器只负责静态资源和 `/api`、`/oauth2` 反向代理，业务请求仍统一进入 OpenSabre Gateway。

后端镜像沿用 OpenSabre Framework 父 POM 的 Jib 配置构建：`mvn -DskipTests -Djib.to.image=<registry>/opensabre/iqc-platform:<tag> jib:build`；不要另行维护重复的后端 Dockerfile。

## 2. 数据库初始化

新环境由 `base-k8s` 创建 `iqc_platform` schema 和迁移账号，然后在启动应用前运行独立 Flyway 迁移；Flyway 从 `src/main/resources/db/migration/mysql/baseline/` 建立初始状态并执行后续版本迁移。已有环境按发布矩阵确认基线后执行尚未应用的迁移。执行后确认核心表、版本表和索引存在，并用 `flyway validate` 核对历史；重复执行 `migrate` 应为零变更。详见 `base-k8s/docs/database-migrations.md`。

IQC V2 派生方案与版本归档依赖 `V1.1.31__ddl_add_scheme_derivation_source.sql` 和 `V1.1.32__ddl_add_scheme_version_archive.sql`：前者在 `iqc_inspection_scheme` 添加 nullable 来源列，后者在 `iqc_inspection_scheme_version` 添加默认 `FALSE` 的归档标记。新版 IQC 服务访问这些字段前必须先按顺序应用迁移；迁移保留既有方案/版本内容，旧版本忽略附加列。此说明只定义依赖顺序，不授权连接或修改任何共享数据库。

## 3. 服务环境变量

至少配置 `SERVER_PORT=8040`、`MANAGEMENT_SERVER_PORT=18080`、Nacos 的 `REGISTER_HOST/REGISTER_PORT`、MySQL 的 `DATASOURCE_*`、Redis 的 `REDIS_*`、`SYSADMIN_SERVICE_ID=base-sysadmin` 和治理传输配置。生产环境必须显式提供数据库密码、治理注册令牌和资源注册令牌，不得使用 `application.yml` 的本地默认值。管理端点地址为 `http://<iqc-platform实例地址>:18080/actuator/health`，该端口不承载业务 API。

启动后 IQC 会使用 Nacos 公共配置 `opensabre.governance.registration-token` 向 OpenSabre 管理面上报。该值可保存为 Jasypt `ENC(...)` 密文，并由各应用共享的 `JASYPT_ENCRYPTOR_PASSWORD` 解密。错误码目录和字典快照进入 `base-sysadmin`，HTTP 资源权限完整快照进入 `base-organization`，`@Audit` 事件进入 `base-sysadmin` 审计日志。`/actuator/opensabreGovernanceRegistration` 中 `error-catalog`、`dictionary`、`resource-permissions` 三项必须均为 `SUCCEEDED` 才允许发布。

一期 TXT 上传默认限制为 20 MiB，后端会独立校验 `.txt` 扩展名和字节大小；可通过 `IQC_CONVERSATION_MAX_FILE_SIZE_BYTES` 调整业务限制，并同步调整 `IQC_CONVERSATION_MAX_FILE_SIZE` / `IQC_CONVERSATION_MAX_REQUEST_SIZE` 的 Multipart 限制。

### LLM 适配器

Agent 2.0 任务是否调用模型由任务绑定的已发布 Agent 快照决定。模型端点、密钥和模型名称在 Agent 模型配置中维护，并应确认模型服务已纳入组织的敏感数据边界。以下全局配置仅作为历史 Agent 的兼容默认值：

```bash
IQC_LLM_PROVIDER=spring-ai
IQC_LLM_ENDPOINT=https://model.example.com
IQC_LLM_PATH=/v1/chat/completions
IQC_LLM_API_KEY=通过配置中心/密钥系统注入
IQC_LLM_MODEL=your-model
```

Agent 2.0 不需要全局启用开关。设置 `IQC_LLM_PROVIDER=disabled` 可用于紧急停止全部模型调用。

`spring-ai` 是默认运行时，基于 Spring AI 2.0，兼容当前 Spring Boot 4.1。DashScope/百炼可将
`IQC_LLM_ENDPOINT` 配置为其 OpenAI-compatible 地址。紧急回退到原始协议适配器时设置
`IQC_LLM_PROVIDER=http`。Spring AI Alibaba 1.1.x 仍基于 Spring Boot 3.5 / Spring AI 1.1，
因此本版本不直接引入其 Graph 依赖；待其支持 Spring AI 2.0 后通过现有 `LlmQualityProvider` 边界替换。

适配器只接受 `choices[0].message.content` 中的 JSON，且必须包含布尔 `hit` 和非空 `reason`；调用前会对常见手机号、邮箱和证件号做最小脱敏。调用限次和计次分别复用 OpenSabre `GovernanceRateLimiter`、`UsageCounterRecorder`，未通过限次或响应校验失败的调用不会被当成未命中。

## 4. 网关路由

IQC Controller 的真实路径已经包含 `/api/iqc/**`，网关应用路由应保持同路径转发：

```text
service_id: iqc-platform
target_uri: lb://iqc-platform
external_path: /api/iqc/**
upstream_path: /api/iqc/**
auth_mode: AUTHENTICATED
```

路由必须通过 `base-gateway-admin` 的应用路由草稿、校验和发布流程生效，禁止直接改 Nacos 运行配置绕过控制面。

## 5. OAuth2 与独立前端

`iqc-platform-admin/.env.development` 使用端口 `3010` 和独立 registration `iqc-platform-local`，回调为 `http://localhost:3010/login/oauth2/code/iqc-platform-local`。授权服务执行 `base-authorization` 的 IQC 客户端迁移，网关加载同名 registration 后，IQC 与 `opensabre-admin` 的 3000 回调互不覆盖。

不能仅修改前端注册名或端口而不同步网关和授权服务配置。

前端镜像使用 `iqc-platform-admin/Dockerfile` 构建，运行时由 `deploy/nginx.conf` 提供 SPA 路由回退、健康检查和网关代理。生产环境应将 `iqc-platform-admin` 与 `iqc-platform` 放入同一 `opensabre` 网络，并通过镜像变量覆盖默认镜像地址。

## 6. 发布门禁

### V2 模板停用兼容边界

模板停用/恢复复用既有方案状态列和发布权限，不新增数据库迁移。须先发布支持
`/schemes/{id}/publish` 的 `DISABLE/ENABLE` 动作与方案启动检查的后端，再发布专家管理入口。
使用停用能力后，不能回退到缺少方案检查的旧执行器并继续消费队列；应保留兼容检查或停止任务消费。

模板运行约束：发布包含 `definition.runLimits` 的版本前，所有服务端实例须支持该字段及创建限制。
旧版本可能拒绝或忽略新增字段，不允许新旧实例混合承接这些模板的创建、试跑和结果处理。
无该字段的历史快照保持原始序列化和运行默认值；新增字段不要求数据库迁移。
停用不是在途调用的即时中断，启动检查之后仍存在并发窗口；历史快照、结果和复核不会被删除或改写。

### CI 自动化

后端仓库的 `.github/workflows/docker_publish.yml` 在 Pull Request 和 `main`/`v*` 推送时执行
Maven Verify，并上传测试报告；`main` 或版本标签通过测试后，使用父 POM 的 Jib 配置构建并推送
`ccr.ccs.tencentyun.com/opensabre/iqc-platform` 镜像。前端仓库的
`.github/workflows/docker-build.yml` 执行类型检查、单元测试、生产构建和构建产物归档，推送事件
通过后构建并推送多架构 `iqc-platform-admin` Docker 镜像。两个仓库都要求配置
`DOCKER_CLOUD_SECRET_ID` 和 `DOCKER_CLOUD_SECRET_KEY`，Pull Request 不执行镜像推送。

- `iqc-platform`: `mvn test`、`mvn -DskipTests package` 通过。
- `iqc-platform-admin`: `pnpm type-check`、`pnpm build` 通过。
- IQC 服务已注册到 Nacos，健康检查返回 UP，网关发布后可访问 `/api/iqc/bootstrap`。
- 数据库迁移版本与目标环境一致。
- 真实认证用户验证数据范围、审计、限次拒绝、计次、错误码和异步任务执行。
- OAuth2 回调与独立站点域名/端口完全一致。

本地或部署后可使用 `scripts/iqc-runtime-smoke.sh` 执行只读运行探针。脚本默认要求所有核心查询接口返回 HTTP 200 和 OpenSabre 统一响应结构；通过 `IQC_COOKIE_JAR` 或 `IQC_ACCESS_TOKEN` 提供已认证会话。治理注册 Actuator 检查另需 `IQC_ACTUATOR_CLIENT_ID`、`IQC_ACTUATOR_CLIENT_SECRET`（具有 `actuator.read` scope 的 OAuth2 client-credentials 客户端），必要时设置 `IQC_ACTUATOR_TOKEN_URL`。不要复用浏览器 Token 或把客户端密钥提交到仓库。脚本不会创建会话、任务或结果。

只读探针同时执行单接口响应时间门禁，默认基线为 2000ms，可通过 `IQC_MAX_RESPONSE_TIME_MS` 调整。任务执行日志包含稳定的 `event`、`taskId`、`executionId`、`status`、处理/失败数量、`errorType` 和 `elapsedMs` 字段；Actuator 暴露 health、metrics 和 prometheus 端点供部署环境采集。

专用验收环境可执行 `scripts/iqc-e2e-smoke.sh`，覆盖“创建并发布 Agent/规则—上传 TXT—创建并执行任务—查看结果—读取 Agent 版本效果”。该脚本会写入验收数据，因此必须同时提供认证信息并显式设置 `IQC_E2E_WRITE_ENABLED=true`；禁止直接对生产库执行。

远程部署、数据库初始化和网关发布属于外部状态变更，需在本地门禁完成后单独获得执行授权。

## 统一规则标签任务：候选发布与恢复（尚未部署）

本节对应 `IQC_统一规则标签任务执行框架_PRD_V1.0.md`，按用户确认不建设旧任务兼容方案。发布状态、测试证据及未决事项见 [统一框架验收记录](tasks/unified-label-acceptance.md)。以下为待执行步骤，不表示环境已经验收，不授权推送或部署。

### 发布顺序

1. 确定联调环境、业务样本及已发布 Agent；记录后端与管理端提交、不可变镜像 tag/digest、当前数据库版本和备份恢复点。先在隔离环境演练恢复备份，不能以“已有备份文件”代替恢复证据。
2. 停止统一任务的新建和手动投递；逐个停用周期定义的后续触发（`PUT /api/iqc/tasks/{id}/enabled`，`{"enabled":false}`）。这不会中断在途会话，也不是全局写入开关。部署协调期间须控制其他投递入口并等待当前执行结束或经授权暂停。
3. 按既有独立 Flyway 流程应用所有尚未执行的迁移并 validate；与本功能直接相关的是 V1.1.33 检查点、V1.1.35～37 绑定类型及唯一约束、V1.1.38 动作、V1.1.39 执行快照和 V1.1.40 触发计划。必须遵循全仓库版本顺序，不能仅抽取本列表执行。记录版本及校验和，重复 migrate 应无变更。
4. 先升级所有后端 API、执行和读取节点，再升级管理端，随后开放受控测试任务。不要让不理解统一快照的旧节点接收或执行新任务。
5. 使用真实认证身份验证越范围拒绝及网关执行权限，再运行立即、定时、周期和零会话任务。对照执行快照检查规则版本、阶段顺序、每阶段标签状态、LLM 过滤和结果处置状态。普通失败、未命中和未评估必须分开；`PENDING_POLICY` 不能作为质检得分或正式冲突结论。
6. 经业务验收后逐个恢复周期定义及投递入口。重新启用会更新该定义保存的授权范围；后续每轮在排队前重新校验当前账户及资源权限，失权或查询失败拒绝执行。

### 进程退出后的恢复边界

- `ScheduledInspectionDispatcher` 每次轮询调用 `InspectionTaskService.recoverStaleExecutions`。`iqc.task.scheduler-enabled=false` 时没有该调度组件；默认轮询间隔 `iqc.task.scheduler-delay-ms=5000`，超时阈值 `iqc.task.execution-timeout-minutes=30`（最小 1 分钟）。它按任务 updated_time 识别 QUEUED/RUNNING，批次最多 100 个，将任务及当前执行标记 FAILED，不自动继续原进程。
- 确认原执行进程已停止、任务已变为 FAILED 且定义仍为 enabled 后，授权用户通过 `POST /api/iqc/tasks/{id}/run` 重试。新 attempt 继承原 run_id、冻结配置和选择快照；同轮次已持久化的引擎结果供恢复读取。`POST /api/iqc/tasks/{id}/resume` 仅接受 PAUSED，不用于接管 RUNNING。
- 周期定义下次到期会创建新的 run_id，并按新轮次冻结配置、读取输入；这不是对失败轮次的恢复。停用后手动 run 也会被拒绝。周期定义重新启用会刷新下一到期时间，需在明确的执行窗口内重试并核对 run_id，不能把重新启用本身当成恢复；一次性定义已有执行后不允许重新启用，因此不要以停用定义作为一次性失败任务的重试准备步骤。需隔离调度器进行演练时使用环境级 scheduler-enabled 配置并控制所有实例。
- 同一次执行的已提交检查点不会重跑；新重试 attempt 只沿用统一阶段中连续的 SUCCEEDED/SKIPPED 前缀，失败阶段及其后阶段会重跑。外部模型已返回但检查点未提交时仍可能重发请求。当前不保证外部调用恰好一次，不自行增加动作扣分的幂等或补偿策略。恢复后核对执行历史、输入快照、阶段记录及未完成会话数，不直接改库清除状态或检查点。
- 当前已有执行器跨 JVM 的 H2 文件数据库验证；整服务停止/启动、调度超时标记、真实 MySQL 及授权用户重试串联的部署演练仍待执行。

### 失败退出与回滚

验收失败时停止新的投递和周期触发，保留失败执行、检查点及日志，记录受影响任务和镜像。先确定失败发生于迁移、启动、权限、匹配还是结果处置，再选择修复候选版或回滚。

若回到不能读取统一任务快照的版本，须先停止相关任务的执行、恢复和读取，并隔离该版本不能处理的数据；本期不提供降级翻译器。保留数据库新增列、表和历史，不能通过删除快照字段、修改 schemaVersion、清理 Flyway history 或直接执行反向 DROP 来让旧代码通过。只有已经在隔离环境证明可恢复且业务接受数据时间点时，才可另行执行获准的数据库备份恢复；恢复会影响备份之后的数据，必须明确目标范围。回滚后再次验证健康、身份、数据范围和受影响任务，不能仅凭容器启动判定恢复成功。

### 真实性能记录

10/100/500 标签每组记录模型及提供方报告版本、会话样本标识、输入规模、引擎顺序、并发、上游命中比例、统计窗口、调用次数、过滤前后标签数、输入/输出 Token、耗时分布、会话吞吐及错误率。对比运行使用同一会话、模型和提示词，避免将模型差异当作过滤收益。未返回的 Token 记录未知，不能用字符数代替；失败重试消耗未报告时说明统计不完整。测试环境、现有 Agent 和会话样本已确认，聊天接口真实模型连通性通过（见统一标签验收记录）；候选统一任务尚未部署，目前不填入统一任务性能数值，也不宣称已完成灰度或回滚演练。

实现依据：`ScheduledInspectionDispatcher`、`InspectionTaskService.recoverStaleExecutions`、`InspectionExecutionService.queue/freezeExecution/resume`、`UnifiedTaskScheduleService` 与 `TaskWizardRuntime.initial`。上述恢复操作复用现有能力，不引入新的调度平台。
