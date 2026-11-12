# 智能体大模型引擎与测试聊天

开发基线：origin/main `bac0a12`，分支 `feature/agent-engine-chat`。前端对应基线 `bf62916`，同名分支。

## 边界与任务模块对接

本变更不修改 task、方案、规则执行、结果聚合及评分代码。智能体新建、新版本及历史回滚创建新版本仅接受 schema 3.0 纯模型能力；历史配置查询与既有执行读取逻辑保留。旧版规则配置不能直接恢复成新的智能体版本，需在配置页面重新确认模型能力后保存。

任务侧继续负责引擎选择、正则/普通规则、调用顺序和评分。新智能体不提供 mode 或 ruleSetId；任务开发者应从任务配置读取执行策略，不能从新智能体推导规则执行方式。既有已发布版本的资产冻结模型继续复用。

## 测试接口

`POST /api/iqc/config/agents/test-chat`，沿用 `iqc:agent:manage` 权限及治理限流（20 次/分钟）；需要治理注册后的 URL 资源授权。未修改基础服务或其数据。

请求：`configJson` 为 schema 3.0 配置字符串，`messages` 为交替排列的 user/assistant 消息。可选 `agentId` 与 `versionNo` 必须成对传入，用于从服务端读取保存版本。临时配置测试不传此二项。

保存版本存在资产快照时使用服务端冻结配置；草稿和临时配置从启用资产解析，忽略浏览器提供的快照。聊天最多 40 条消息，总文本最多 32000 字符，最后一条必须是用户消息。上下文由前端维护，切换配置或关闭测试会话时清空，不新增持久化表。

回复包含 content、latencyMillis、model、calledTools（实际调用工具名，不含工具参数/结果和密钥）。测试复用 SnapshotChatModelRouter、SnapshotMcpToolProvider 与凭据解析，不调用质检提示词、规则服务或任务服务。MCP 工具可能产生真实副作用；白名单继续采用空列表拒绝全部工具的现有语义。

模型生成采用一次完整响应，暂未提供流式输出或服务端取消。关闭面板会丢弃迟到回复，不承诺中止已经发出的模型或工具调用。

## MCP 管理

`POST /api/iqc/agent-assets/mcps/{id}/discover-tools` 沿用 `iqc:mcp:manage`，使用服务器保存的地址和认证引用，复用运行时客户端完成 initialize/listTools，返回名称及描述并关闭连接，不调用发现的工具。前端选择后需保存才更新工具白名单。

## 管理页面

统一菜单名称/顺序（包括授权菜单下已知智能体子树），保留路由与权限编码。四个页面增加搜索和状态过滤；智能体与 Skills 接入既有版本历史，智能体支持已有版本比较；资产关联视图查询智能体版本并区分冻结配置与当前引用，不新增关系表。关联查看需要智能体查看权限。

## 发布前验证

本地构建及自动测试不替代真实账号和真实模型环境验证。发布前需要验证治理资源注册、角色授权、保存草稿/发布版本、多轮对话、MCP 工具执行及错误反馈；不得以模拟模型测试宣称真实模型已验收。当前未部署、未改远程数据。

## 本地验证结果

- 前端 `npm run build` 通过（含 vue-tsc）；聊天交互 Vitest 2 项通过。
- 后端定向测试 6 类共 25 项通过：聊天、智能体版本服务、配置校验、资产校验、MCP 工具提供器、模型路由。
- 前后端 `git diff --check` 通过。
- 未执行真实模型/真实 MCP 联调，未推送、合并或部署。

## 2026-11-05 DS 连接测试故障

原线上镜像 DS 测试返回 HTTP 500；同一时刻 IQC 日志出现 `Terminating due to java.lang.OutOfMemoryError: Metaspace`，网关记录上游连接提前关闭，容器多次重启。原 JVM 元空间上限 128 MiB，容器总内存 1 GiB。保留原容器及受保护 inspect 快照后，仅将 IQC 运行参数改为 MaxRAMPercentage=45、MaxMetaspaceSize=256m；镜像、数据库、DS 地址及凭据不变。

回退资料：服务器 `/root/backups/iqc-model-memory-20261105-003444/`。修复后认证会话连续两次 POST DS 连接测试均 HTTP 200、success=true，模型为 deepseek-v4-flash，模型耗时 2908ms / 795ms，容器重启次数 0。

IQC POM 单独覆盖 Jib 对应属性，避免后续构建退回框架的 128 MiB 默认值，不修改 Framework 或基础服务。此运行参数修复不代表本分支智能体新接口已部署。

## 2026-11-05 后端部署验收

已部署镜像 `opensabre/iqc-platform:agent-chat-20261105`，镜像 ID 前缀 `8bcebad34859`。应用 jar SHA-256：`9873ac4efd7c4d68732fe6b63734f1b186daed01b591d7b553527a362a28155d`。原容器保留为 `iqc-platform-before-agent-chat-20261105`，备份与部署清单位于服务器 `/root/backups/iqc-agent-chat-20261105/`。

部署前 7 类共 28 项定向测试及 package 通过。本次无数据库迁移，不改任务业务代码或基础服务；保留堆45%/元空间256MiB配置。

补充历史聊天兼容：可信服务端保存的 schema 2.0 版本，如果具有模型能力，可只读投影成纯 LLM 能力进行测试。规则与执行策略不进入测试；历史工具能力保持原有 AGENT_LLM 限制，不启用原本未授权的工具。纯规则版本或无模型版本仍拒绝聊天。新增配置契约仍只接受 schema 3.0，不改写历史版本。

真实模型管理员会话经网关测试客服质检Agent V2：第一轮返回“已记住”（4568ms），第二轮正确回答暗号“蓝鲸”（1531ms）；DS 连接测试成功（949ms）。error-catalog、dictionary、resource-permissions 注册均成功；部署后容器 running、RestartCount=0。前端远程镜像未替换，当前使用本地开发前端查看。
