# IQC Platform

IQC 质检业务服务，基于 `opensabre-framework` Starter 组合开发。

## 本地验证

```bash
mvn test
mvn -DskipTests package
mvn -DskipTests -Djib.to.image=iqc-platform:local jib:dockerBuild
```

业务表结构见 `src/main/resources/db/iqc-platform-ddl.sql`，部署到 MySQL 前先创建 `iqc_platform` schema 并执行该脚本；已有环境按顺序执行 `db/migration/` 中尚未应用的脚本（当前至 `V1.1.16__extend_iqc_conversation_import.sql`）。平台审计、限次、计次、字典、错误码和资源注册由 OpenSabre Framework/base-sysadmin 提供，IQC 只声明业务使用场景。TXT 会话上传默认限制 20 MiB，并由后端强制校验 `.txt` 扩展名和大小。

独立前端的 OAuth2 registration 使用 `iqc-platform-local`，授权服务需执行 `base-authorization/src/main/resources/db/migrations/V20260822_01__add_iqc_platform_local_oauth2_client.sql`；网关使用同名 registration 并将 `/api/iqc/**` 转发到 `lb://iqc-platform`。

## 运行配置

- `SERVER_PORT`：默认 `8040`（避免与 OpenSabre `base-gateway-admin` 的 8030 冲突）
- `DATASOURCE_*`：IQC 数据库连接
- `REGISTER_HOST/REGISTER_PORT`：Nacos 注册中心
- `GOVERNANCE_USAGE_TRANSPORT`：使用量计次传输方式
- `GOVERNANCE_REGISTRATION_TOKEN`：字典/错误码/资源注册令牌

TXT 导入接口会保存会话与消息，并以文件 SHA-256 指纹保证重复提交幂等；质检任务执行时保存 Agent/规则快照，结果按 TaskItem 和执行 attempt 追踪。数据范围通过 `opensabre-starter-rpc` 调用 `base-organization` 获取当前用户 `groupId`，IQC 只保存业务归属快照。

Agent 支持 `RULE_ONLY`、`RULE_THEN_LLM` 和 `AGENT_LLM` 三种质检模式。规则+LLM 模式先运行本地规则，仅对命中候选调用模型，并把本地候选结果传给 LLM 复核；未配置模式的历史 Agent 保持兼容执行。

会话中心支持 `POST /api/iqc/conversations/batch-import` 多文件批量导入并生成批次号，也支持 `POST /api/iqc/conversations/ingest` 以 JSON 接入单个会话。JSON 消息格式为 `{"externalId":"上游幂等号","batchNo":"可选批次号","title":"会话标题","messages":[{"role":"agent","time":"00:00:01","content":"您好"}]}`。

模板目录、系统设置和操作日志入口已纳入独立前端；操作日志查询复用网关后的 `base-sysadmin` 审计 API，模型设置接口只返回脱敏状态信息。

会话、任务、结果列表使用统一分页契约 `{ records, current, size, total }`，后端单页最多返回 100 条。
