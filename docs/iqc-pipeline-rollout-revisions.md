# 流水线灰度策略不可变修订

## 实施范围与复用

已接入 [显式会话灰度任务入口](iqc-pipeline-rollout-tasks.md)：保存修订会用于后续灰度新请求，原请求重放继续使用原任务证据。

在确定性分配预览之上提供策略配置持久化、当前修订和游标历史，以及实际管理界面。ACTIVE/ROLLED_BACK由显式灰度任务入口用于新请求分配；保存不重写已有任务。

已检查原流水线发布版本、调度配置、公共持久化和权限能力。检测配置版本与灰度比例/回退具有独立生命周期，原 PipelineAssetVersion 不能表达多个比例修订。因此新增一张 iqc_pipeline_rollout_revision，仅保存策略证据；继续复用 PipelineAsset 锁、MyBatis/BasePo、现有精确策略解码和发布/单变量验证。不另建发布模型、当前指针、账户/ACL或幂等请求表，不修改 framework。

## 数据与并发

DDL V1.1.74 为同一 pipeline_id/revision_no 建唯一约束，两个数据库触发器拒绝 UPDATE/DELETE。每次修订保存完整策略 JSON/hash、两侧精确发布 hash、前一修订 contentHash、修订号与变更类型；contentHash 覆盖整个证据信封。原 BasePo 保存操作者和时间；HTTP DTO 不返回操作者原文或内部 PO。

当前策略为最高修订，不维护额外可变指针。追加修订必须在 Spring 本地事务中取得原资产 FOR UPDATE 锁，与发布、停用串行；再用锁定读取取得最新修订，即使外层事务已有 REPEATABLE_READ 快照也不使用过期快照进行 CAS。expectedRevision 初始为0，必须等于最新修订，版本上限防溢出。写入失败整笔事务回滚；重复相同策略拒绝，不通过原地更新覆盖历史。

首次仅允许 ACTIVE；同一版本/盐/变量下比例增加或降低记录 EXPAND/REDUCE，修改版本/盐/变量记录 REPLACE。由已回退配置恢复 ACTIVE 记录 RESUME。ROLLED_BACK 必须从当前 ACTIVE 转换并完整保留两侧版本、比例、盐及变量声明，记录 ROLLBACK，防止以回退名义替换基线。保存重新验证原发布证据及单变量语义；回退候选跳过当前执行依赖可用性，基线仍要求可执行。

历史读取只验证当前资产范围、原始证据信封hash/schema/流水线/修订和策略/发布摘要格式，不重新要求旧供应商可用，也不重新运行选数或生成任务。previousContentHash 是追加时验证的前一记录链接；当前未实现整条历史的离线审计工具。

## API 与授权

- GET /api/iqc/pipelines/{id}/rollout：iqc:pipeline:rollout:view，返回 revisionNo 与 revision；未配置时为0/null。
- GET /api/iqc/pipelines/{id}/rollout/revisions?beforeRevision=N&limit=20：iqc:pipeline:rollout:history:view，修订号倒序，limit 1–100，不截断全部历史。
- POST /api/iqc/pipelines/{id}/rollout/revisions：iqc:pipeline:rollout:manage，接收 {expectedRevision, policy}。expectedRevision 及策略整数使用双代局部精确树解码，拒绝小数/字符串/溢出。

各入口刷新固定资源的当前授权后才进入 service，继续由原资产检查数据范围。响应 no-store/nosniff，审计不记录请求和响应正文，保存声明10次/60秒限流；配置不含Prompt/会话/身份原文，不额外要求敏感正文grant。组织权限 V20261019_02 登记3个实际URL/方法及按钮900266–900268，仅IQC_ADMIN/QUALITY_MANAGER。迁移尚未执行，不代表实际授权生效。

## 界面与故障行为

复用现有预览表单，只有最近一次有效预览的表单才能用于保存；输入改变清除该资格。先读当前修订，再以该修订CAS保存。写成功与重读分开处理，重读失败明确已保存，不诱导重复提交；保存结果不明或CAS失败必须先重新读取核对。历史独立游标翻页，不能把第一页当全部历史。切资产/卸载通过generation丢弃旧响应。

## 后续与验证边界

显式会话灰度任务的版本选择、跨修订原请求重放、任务内修订/分桶/来源冻结已编码，见灰度任务协议。仍须完成Selector/周期事件自动灰度、AB指标归因及真实启用/回退和完整 I7 验收。

Root仅编译和构建，不新增/执行测试、不执行SQL、推送或部署。编译结果与独立B后续行为/HTTP/隔离数据库证据以协作记录为准；当前没有真实角色/切面/浏览器或数据库迁移验收结论。
