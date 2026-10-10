# 动作状态历史读取

复用 GET `/api/iqc/conversation-results/{id}/actions` 和原 `iqc:action:history:view`。不新增路径、资源、菜单或角色。默认保持动作摘要列表，新增 `history: null`；不读取日志表。需要某个动作的日志时使用查询参数：

```
actionExecutionId=<原64位动作ID>&includeHistory=true
```

返回相同列表结构，但只包含指定动作，并附 `history`：`events`、`nextBeforeVersion`、`earlierHistoryUnavailable`。每页最多 50 条，按账本版本从大到小排列。下一页把返回的字符串 `nextBeforeVersion` 原样作为 `beforeVersion`；严格读取小于该版本的记录，重复或并发新增不会改变已读取的分页边界。版本和前一版本均为十进制字符串，避免超过 JavaScript 安全整数后失真。

只有通过原结果/任务数据范围授权且动作的 result/task/EID/conversation/owner 全部匹配后才读取日志。没有权查看原结果时不读取动作或事件；不属于结果的动作不会返回另一个结果的历史。游标必须为正、规范十进制且不超过 BIGINT 最大值，启用日志必须指定一个动作。读取不要求当前任务 EID 等于历史结果 EID，原有历史归属规则保持不变。

事件公开前后状态白名单：状态、领取次数、领取/结算/下一检查 UTC 时间、回执分类、有界结果/证据引用、错误码；另含操作人及数据库 UTC 记录时间。SQL 使用显式 JSON_OBJECT/JSON_EXTRACT 重建投影，即使原 JSON 新增字段也不自动公开。claim token、冻结输入、策略和身份快照不进入投影或 HTTP 响应。数据库 JSON null 和字面字符串 "null" 不混淆。

`previousVersion == null` 表示真实初始观察；抵达末页但最早记录仍有前一版本，或整个日志为空时，`earlierHistoryUnavailable=true`。它表示升级前或其他未保存过程不能还原，不生成假历史。日志代表状态提交和领取资格，不证明网络动作已经发送。

管理端复用 ActionHistoryPanel 的“查看历史”弹窗；按变更由新到旧显示实际记录，加载更早记录使用精确游标。加载防重复，关闭/刷新/切换结果/撤销 history 权限/卸载会废弃旧响应。非法动作响应、排序或分页边界只提示读取失败，不构造成功/发送历史。证据与操作人按文本呈现。

## 验证记录（2026-10-11）

定向查询6项+原人工核对8项，74845 exit0。真实 MySQL ActionExecution12项（50129 exit0）和 Source11项（5357 exit0），两组失败/错误/跳过为0：实际白名单SQL不读claim/输入/新增字段、JSON null与字符串"null"不同、51条上界及严格版本分页；task/EID/conversation/owner/result分别伪造均由真实来源/账本查询拒绝。组织权限投影仍为本地边界，非真实登录/网关验收。

完整后端59258 exit0，有效94份非IT报告787项零失败/错误/跳过，全部刷新07:21:40..07:25:08；排除已删除的ActionSourceDiscoveryTest旧报告。日志 /private/tmp/iqc-b-action-history-{unit,mysql,source-mysql,full}.log。

UI定向14项、完整28文件185项及最终14项组件验证均通过；最终76405 exit0（组件验证随后vue-tsc/Vite构建），原Node/pnpm版本及chunk警告保留。日志 /private/tmp/iqc-b-action-history-ui-{targeted,full,types,build,final-targeted,final-build}.log。最后仅手工浏览器夹具按来源区分证据引用，不改变已验证产品代码。

真实Chromium/原Vue/Ant，本地adapter夹具验证50→53条且游标保持9223372036854775757、末页移除继续按钮、展示历史缺口。截图仅本地output/playwright/action-history-pages.png。初轮超时源于把Ant保留隐藏容器误当必须销毁；DOM核对旧条目0且弹窗不可见。后轮对body全局查同证据字符串，误把新结果摘要当旧响应；夹具现已按结果区分。最后修正后完整脚本未执行：自动审批审核因usage limit无法完成，明确非判定不安全。不把前两轮部分验证称作最终浏览器通过；撤权浏览器步骤尚未完成，组件测试覆盖此边界。

代码审查/架构独立agent 01a1281e-b879-7373-b3b5-ad67e70b3153、01a1281e-c2f7-7e41-954e-337e611a64e0 均usage limit失败，independent review unavailable；没有合并批准，不以作者检查替代独立审查。未推送/合入/部署或改A/framework/gateway。原完整PRD及人工原因完整持久审计、阻塞恢复、远端适配/真实组织动作链仍继续。

## 成功人工核对原因（V1.1.65，待真实 SQL 验收）

原事件新增可空 `reconciliationReason`，仅保存成功人工核对的原因，沿原历史权限返回。历史记录不回填；没有确认成功或旧版本观察等没有状态变更的请求，其持久审计仍待实现。原因最长1000字符，保存前去掉首尾空白；UI按文本显示。

只读适配器核对在事务外执行。最终短事务重新对账本版本、claim token、完整冻结来源/输入/策略及领取次数作精确匹配，字符串按二进制比较，避免数据库排序规则放宽身份匹配。CAS成功后在同一事务给该版本事件附加原因；事件不存在、状态不匹配或写入异常会抛出异常。真实回滚及冻结输入竞争的SQL验收用例已准备，尚未执行，单位测试不证明数据库事务行为。

必须先完成V1.1.65迁移再运行新后端，历史查询依赖新列。不可修改V1.1.64或旧baseline/history。合并前仍需真实新安装、支持升级、validate及重复migrate，以及原因写入故障回滚、外层事务回滚隔离和冻结输入竞争验收。

本轮本地验证：9059 exit0，核对原因7项、原核对8项、历史查询6项，零失败/错误/跳过；UI14项及vue-tsc/Vite构建通过。迁移布局通过不代表实际MySQL迁移成功。前述787项全量属于e3c693b之前批次，不是本轮全量验证。独立审查及最终浏览器仍未完成，无合并批准。
