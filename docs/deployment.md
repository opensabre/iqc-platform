# IQC 部署与发布门禁

本文档描述 `iqc-platform` 与 `iqc-platform-admin` 的最小可运行部署契约。远程环境执行前必须完成清单核对；本文档不授权直接修改远程数据库、Nacos、网关或认证配置。

## 1. 服务与依赖

| 组件 | 约定 |
| --- | --- |
| IQC 服务名 | `iqc-platform` |
| 默认端口 | `8040`；`8030` 已被 `base-gateway-admin` 占用 |
| 数据库 | MySQL schema `iqc_platform` |
| 注册中心 | Nacos，服务注册名必须为 `iqc-platform` |
| 组织服务 | `base-organization`，用于当前用户 `groupId` 和数据范围 |
| 治理服务 | `base-sysadmin` / OpenSabre Framework Starter |
| 前端 | 独立站点 `iqc-platform-admin`，Ant Design Vue，默认端口 `3010` |

仓库提供 `base-k8s/docker-compose-iqc-platform.yml` 作为服务运行模板；镜像、数据库密码和治理令牌必须由部署环境显式注入。
该 Compose 文件同时提供 `iqc-platform-admin` 前端容器；前端容器只负责静态资源和 `/api`、`/oauth2` 反向代理，业务请求仍统一进入 OpenSabre Gateway。

后端镜像沿用 OpenSabre Framework 父 POM 的 Jib 配置构建：`mvn -DskipTests -Djib.to.image=<registry>/opensabre/iqc-platform:<tag> jib:build`；不要另行维护重复的后端 Dockerfile。

## 2. 数据库初始化

新环境执行 `src/main/resources/db/iqc-platform-ddl.sql`。已有环境按顺序执行 `db/migration/V1.1.2__...` 至 `V1.1.10__...`，其中 V1.1.10 为分页、数据范围和结果筛选索引。初始化后必须确认核心表、版本表和索引均存在；迁移脚本只允许执行一次。

## 3. 服务环境变量

至少配置 `SERVER_PORT=8040`、Nacos 的 `REGISTER_HOST/REGISTER_PORT`、MySQL 的 `DATASOURCE_*`、Redis 的 `REDIS_*`、`SYSADMIN_SERVICE_ID=base-sysadmin` 和治理传输配置。生产环境必须显式提供数据库密码、治理注册令牌和资源注册令牌，不得使用 `application.yml` 的本地默认值。

启动后 IQC 会通过同一部署级 `GOVERNANCE_REGISTRATION_TOKEN`（可由各能力专用变量覆盖）向 OpenSabre 管理面上报：错误码目录和字典快照进入 `base-sysadmin`，HTTP 资源权限完整快照进入 `base-organization`，`@Audit` 事件进入 `base-sysadmin` 审计日志。`/actuator/opensabreGovernanceRegistration` 中 `error-catalog`、`dictionary`、`resource-permissions` 三项必须均为 `SUCCEEDED` 才允许发布。

一期 TXT 上传默认限制为 20 MiB，后端会独立校验 `.txt` 扩展名和字节大小；可通过 `IQC_CONVERSATION_MAX_FILE_SIZE_BYTES` 调整业务限制，并同步调整 `IQC_CONVERSATION_MAX_FILE_SIZE` / `IQC_CONVERSATION_MAX_REQUEST_SIZE` 的 Multipart 限制。

### 可选 LLM 适配器

IQC 默认不启用模型调用。启用时需配置 OpenAI-compatible chat completions 端点，并确认模型服务已纳入组织的敏感数据边界：

```bash
IQC_LLM_ENABLED=true
IQC_LLM_PROVIDER=spring-ai
IQC_LLM_ENDPOINT=https://model.example.com
IQC_LLM_PATH=/v1/chat/completions
IQC_LLM_API_KEY=通过配置中心/密钥系统注入
IQC_LLM_MODEL=your-model
```

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

本地或部署后可使用 `scripts/iqc-runtime-smoke.sh` 执行只读运行探针。脚本默认要求所有核心查询接口返回 HTTP 200 和 OpenSabre 统一响应结构；通过 `IQC_COOKIE_JAR` 或 `IQC_ACCESS_TOKEN` 提供已认证会话。脚本不会创建会话、任务或结果。

只读探针同时执行单接口响应时间门禁，默认基线为 2000ms，可通过 `IQC_MAX_RESPONSE_TIME_MS` 调整。任务执行日志包含稳定的 `event`、`taskId`、`executionId`、`status`、处理/失败数量、`errorType` 和 `elapsedMs` 字段；Actuator 暴露 health、metrics 和 prometheus 端点供部署环境采集。

专用验收环境可执行 `scripts/iqc-e2e-smoke.sh`，覆盖“创建并发布 Agent/规则—上传 TXT—创建并执行任务—查看结果—读取 Agent 版本效果”。该脚本会写入验收数据，因此必须同时提供认证信息并显式设置 `IQC_E2E_WRITE_ENABLED=true`；禁止直接对生产库执行。

远程部署、数据库初始化和网关发布属于外部状态变更，需在本地门禁完成后单独获得执行授权。
