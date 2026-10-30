# 运行环境与候选版本只读基线（2026-10-31）

本记录来自 `ssh root@opensabre` 的容器/镜像、实际Jib classpath、网络namespace与MySQL元数据只读观察。没有执行迁移、DML、应用登录/令牌签发、账号授权、配置修改、业务请求、重启或部署；没有读取个人账号或输出运行凭据。它用于界定验收前置条件，不是新版本功能验收。

## 当前运行镜像

| 服务 | 运行镜像ID | 仓库标签 / revision证据 | 容器启动UTC |
| --- | --- | --- | --- |
| iqc-platform | `sha256:4f664ad1f6d4f1e45a2c721552bd98b3d6e896cccec87bb992fcaff3e8b250a5` | `sha-bac0a12-iqc-v2`；无OCI revision label，不凭标签还原完整commit | 2026-10-29 03:09:10 |
| iqc-platform-admin | `sha256:013f994713f75a563aa87866faf2075a7ea18be142e5ef8c6e377c9d5e8a56b3` | `sha-bf62916`，OCI revision=`bf62916ee762f8ae91505fb2ca5f1b7fc1c9c1f7` | 2026-10-04 08:40:16 |
| base-organization | `sha256:79e7e84bb346f0be4eaa510cc41b7553cc0b875107a1ccd60cdd7e88b2b59d3a` | `sha-5780d1e-iqc-v2`；无OCI revision label | 2026-10-04 08:37:28 |

容器启动时间不是源码版本证明，镜像标签也不是不可变源码签名。前端存在明确revision证据，与候选PR56固定 `a7fba1b9644a4dbf700e81342083c582f7faa692` 不同。

后端实际启动classpath为 `@/app/jib-classpath-file`。只读解析该文件确认包含 `/app/classes`、`/app/resources` 和256个jar；`IqcPlatformApplication.class`在classes目录存在，而候选源码中的 `DurableInspectionWorker`、`BulkTaskCoordinator`、`PipelineConversationEvaluator`、`GoldSampleController` 对应class文件均不存在。未穷尽检查所有依赖jar，不能仅此推断所有运行能力；也不能把该旧实例当作候选源码的完整PDP/业务场景验收环境。

## 数据源关联与限制

MySQL PROCESSLIST会话元数据仅显示统一宿主源地址，不能用容器IP直接归属连接。宿主无conntrack工具且无可读proc conntrack表，本轮未安装工具或加载模块。

改以各实际容器network namespace中已建立的目的端口3306连接源端口，与同轮PROCESSLIST HOST端口关联；只输出schema与计数，不读取账号名、SQL正文或逐用户角色。五个服务之间没有重复匹配源端口：

| 服务 | 已建立MySQL源端口数 | 唯一端口关联schema / 会话数 | 歧义匹配 |
| --- | ---: | --- | ---: |
| base-organization | 20 | os_base_organization / 20 | 0 |
| iqc-platform | 10 | iqc_platform / 10 | 0 |
| base-authorization | 20 | os_base_auth / 20 | 0 |
| base-gateway-admin | 20 | os_base_gateway_admin / 20 | 0 |
| base-sysadmin | 20 | os_base_sysadmin / 20 | 0 |

这是同时观察到的源端口关联，增强了既有默认库快照的关联证据；不是NAT表、最终配置解析、任意未来连接或实际登录/PDP证明。不能从连接数推导自然人、岗位、角色或授权成功。

## 当前迁移记录与候选差距

在明确的只读事务中查询两个schema的flyway_schema_history，按installed_rank取得最后一条记录：

| schema | 最新安装记录 | success | 候选隔离CI目标 |
| --- | --- | --- | --- |
| iqc_platform | 1.1.32 / ddl add scheme version archive | 1 | 后端PR19固定f712c5f，[41069629856](https://github.com/opensabre/iqc-platform/actions/runs/41069629856)迁移到1.1.77并重复执行通过 |
| os_base_organization | 20260928.01 / dml add iqc business result resource | 1 | 组织PR59固定ec3d40f，[41138532072](https://github.com/opensabre/base-organization/actions/runs/41138532072)对应候选验证记录见该PR |

这里只取安装末条，未完成全部历史checksum、out-of-order或每项资源状态对账，不据版本间隔直接推算应执行迁移数量。本轮未执行候选SQL。当前库记录/运行镜像与候选前端、后端、组织改动尚未形成同一部署快照。

## 实际授权/业务验收的前置条件

1. 原小PR审阅后固定前端、后端、组织候选组合及不可变镜像/制品来源；同头CI只证明对应源码/测试组合，不证明已部署。
2. 按原发布流程先核对真实目标schema与完整迁移历史，准备明确迁移/兼容/回退方案；涉及写入和部署的动作单独执行，不把本只读记录当作已完成。
3. 使用原组织模型核对五类职责、ACTIVE资源和显式关系，保留原ID/状态与owner/group/all边界；不能全量激活或泛授ADMIN/all来通过页面。
4. 在实际候选运行快照上证明网关/PDP、跨组、敏感副本、成功写入与撤权，再执行外部契约、采集、容量及A–E；当前未部署能力不能以旧实例401/404或静态页面200验收。

后续实现和验收仍按[当前缺口清单](iqc-prd-current-gaps.md)，全部适用I1–I7、单租户组织范围、已处理会话输入及语音处理排除保持。本记录没有切默认v2/动作开关、修改framework、共享数据库或线上服务。
