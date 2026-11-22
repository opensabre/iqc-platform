# 智能体后端迁移至 IAC

智能体及版本、模型配置、MCP、Skill、聊天与会话历史由独立 `iac-platform` 服务管理。
IQC 保留规则、方案、任务、执行快照、结果及效果统计，既有前端仍为 `iqc-platform-admin`。

IQC 不再包含上述资产 CRUD 或 Mapper；读目录、版本、资产校验和通用模型/MCP 执行通过签名内部 RPC 完成。
旧数据库迁移保持不可变，不在本次自动删除旧表。模型错误不会回退到旧数据库或默认模型。

默认消费服务 `iac-platform`，可配置 `iqc.iac.service-id`。
Feign 客户端 `iqcIacConsumer` 的读取超时默认 600000ms，支持 `IAC_RPC_READ_TIMEOUT_MILLIS`；连接超时默认 5000ms，支持 `IAC_RPC_CONNECT_TIMEOUT_MILLIS`。
历史部署默认模型只服务于原有非资产配置，保留每任务温度/超时行为。

发布必须配合 IAC 七表数据迁移、共享配置审批和所有 IQC 实例更新。IAC 项目 `docs/deployment.md` 为切换与回退说明。
