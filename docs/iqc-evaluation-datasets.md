# I3 金标与冻结数据集

状态：后端本地实施；未运行数据库迁移、角色或浏览器验收，不代表评测中心完成。

## 复用与版本

候选主库复用 `iqc_quality_sample`，金标字段由 V1.1.38 追加；每个金标修订的审核决定保存在不可变 `iqc_quality_sample_version`。V1.1.37 属于开发者 B 的整会话候选扩容，尚待交付集成。V1.1.39 保存 Dataset 草稿与冻结版本；不增加第二套 Task/Execution。

旧样本 ENABLED 和 B 的 CANDIDATE 均可显式标注，均不代表金标批准。旧单消息候选第一次标注按当前授权冻结完整会话，要求原消息内容与候选快照相同、会话消息数完整；B 的 `iqc-conversation-candidate-input-v1` 从已保存快照读取，校验对应候选 inputHash，不再次读取实时会话。不会自动把业务项人工 PASS/FAIL 转换为 Finding 布尔真值。

金标草稿 `iqc-gold-draft-v1`，批准快照 `iqc-gold-conversation-v1`：完整有序消息（id/role/content/sequence/relativeTime）、业务上下文、期望 Finding、可选业务结论、来源、标签、难度、服务器标注人/审核人。Finding 支持 BOOLEAN/STRING/NUMBER/ENUM/OBJECT、KNOWN/UNKNOWN/CONFLICT/ERROR/NOT_EVALUATED；非 KNOWN 不携带规范值，证据消息 ID 必须在冻结输入中存在。ENUM 语义允许值与目标目录的对齐在评测执行准入阶段仍需实现。

可选 decision：`score`、`scoreStatus`（FINAL/PENDING/NOT_APPLICABLE）、`labels`（稳定标签码到期望 JSON 值）、`risk`（NONE/LOW/MEDIUM/HIGH/CRITICAL/UNKNOWN）。FINAL 必须有0–100分，其余不得有数值；维度缺省表示未标注，不表示零分、空标签或无风险。现有标签版本/结构化值契约与金标的冻结对应仍需补齐。难度 BASIC/INTERMEDIATE/HARD。标注可传对象 businessContext，来源业务元数据在 conversation 下，显式标注补充在 annotation 下。

## 金标 API

基址 `/api/iqc/quality-operations/samples/{sampleId}/gold`：

| 方法/后缀 | 权限 | 请求/结果 |
|---|---|---|
| GET | iqc:gold:view | 现有 QualitySample，含 goldDraftJson/goldDraftRevision/goldStatus/goldReviewVersion |
| PUT | iqc:gold:annotate | `{expectedRevision, findings, businessContext?, tags?, difficulty, decision?}` |
| POST /reviews | iqc:gold:review | `{expectedRevision, approve, reason}`，返回审核版本 |
| GET /versions | iqc:gold:versions:view | current/size；IqcPage 元数据，不返回 snapshotJson |
| GET /versions/{versionNo} | iqc:gold:version:view | 精确版本及校验后的 snapshotJson |

初始 goldDraftRevision=0；标注事务锁定样本并递增为1，状态 PENDING_REVIEW。标注人来自登录上下文，不接受客户端身份。审核要求不同登录人及1–1000字原因；每个修订只能形成一个决定。完全相同的审核人/决定/原因重试返回既有版本，不重复写入；不同决定拒绝，退回后必须重新标注。审核版本号包含 APPROVED/REJECTED 两类，Dataset 只接受 APPROVED。历史查询不要求样本当前启用；加入新数据集需样本可用。单金标快照最多8MiB/10000条消息/1000个Finding。

## 数据集 API

基址 `/api/iqc/evaluation/datasets`：

| 方法/后缀 | 权限 |
|---|---|
| GET | iqc:dataset:view |
| GET /{id} | iqc:dataset:detail:view |
| POST | iqc:dataset:manage |
| PUT /{id} | iqc:dataset:update |
| POST /{id}/freeze | iqc:dataset:freeze |
| POST /{id}/availability | iqc:dataset:availability |
| GET /{id}/versions | iqc:dataset:versions:view |
| GET /{id}/versions/{versionNo} | iqc:dataset:version:view |

创建/更新 `{name, expectedRevision?, draft}`，draft 为 `{schemaVersion:"iqc-dataset-draft-v1",type,description?,members:[{sampleId,versionNo}]}`。类型 BASIC/BOUNDARY/RISK/ADVERSARIAL/REGRESSION/CUSTOM，与候选正负例分类独立；1–1000个唯一 sampleId，引用必须已批准且在访问范围内。更新/冻结须指定当前 expectedRevision。冻结请求 `{expectedRevision}`，同一草稿修订幂等；可用性 `{enabled}`。目录和版本列表均分页 current/size（1–100）。

冻结 `iqc-dataset-snapshot-v1` 包含 datasetId/name/type/description、按草稿顺序的每个成员 sampleId/versionNo/draftRevision/contentHash/**完整已批准 gold**。重复会话在冻结时拒绝，避免同一输入的冲突标注和重复权重。每个成员重新检查数据权限及当前可用性，按稳定样本ID顺序锁定。版本最多64MiB，保留成员顺序，摘要复用既有规范JSON SHA-256。精确版本读取检查外层摘要、身份、样本数与每个内嵌金标摘要，不依赖可变 sampleId 或实时 Conversation 内容。

Dataset 的新冻结可被 DISABLED 阻止；历史版本仍可查。评测创建时的数据集可用性校验、冻结任务来源、共享生产运行时 EVALUATION 接入、评测结果隔离及逐层指标、同数据集对照、发布门禁、强制发布审计仍待实施。当前 Pipeline 草稿试跑门禁保持原语义，不能作为金标评测完成的证明。

## Finding 指标计算基础

`FindingEvaluationMetrics` 是纯计算器，尚未接入持久化评测执行。输入为冻结金标及通过运行时/来源校验的 Fusion 输出；不重跑引擎，不写生产结果。提供整体/按Finding码计数、Precision/Recall/F1、KNOWN覆盖率、值准确率、状态一致率及可选证据准确率。

BOOLEAN true 为阳性，KNOWN false 为阴性；未知/冲突/错误/未执行不是 false，阴性金标上的不确定输出不记 TN，阳性金标上的无阳性输出计 FN。其它强类型值按精确 `(code,value)` 分类：错值计 FP+FN，未决计 FN。NUMBER 以十进制数值比较，OBJECT 字段次序无关、内嵌数值同样比较，ARRAY 保留次序。未标注的输出只单独计数，不假定人工否定，不记 FP。非 KNOWN 金标不参与规范值 Precision/Recall/F1，只参与状态一致率。

任何分母为0的指标返回 null；空数据集拒绝计算。KNOWN值准确率以全部已知金标为分母，未决输出不能靠缩小分母提高准确率。覆盖率与类型不匹配、缺失、错误、冲突数量同时保留，未来门禁不能只看 F1。证据只比较选中候选的 CONVERSATION 消息集合，保留但被拒绝的候选证据不用于计算命中；仅标注了证据的样本进入证据分母。

业务评分偏差、标签准确率、高风险召回、模型校准/幻觉、ASR WER、时延/成本指标仍待实现；不把该计算器称为完整评测报表。
