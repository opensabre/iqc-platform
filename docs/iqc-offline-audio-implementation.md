# 离线音频接入：需求与复用边界

## 当前证据（2026-10-12）

原 PRD 第15节要求统一会话的 ASR/附件信息，Segment 的 speaker/text/startMs/endMs/confidence/sequence，以及音频与转写按时间戳双向定位。第13节要求复核音频时间轴，第19节要求原始音频敏感权限，非功能需求要求音频/会话访问审计。实时语音排除不改变这些离线要求。

现有 `conversation/model/Conversation.java` 已有 asrStatus、归属、业务字段和原始来源指纹；`ConversationMessage.java` 已有顺序、角色、正文和 LocalTime。`ConversationApiIngestService` 复用原导入、原指纹与计次，已接收毫秒区间和置信度，尚未接收音频引用。`ProductionInputSnapshotService.Message` 冻结 id/sequence/role/content/relativeTime，现已用可选字段及v2协议冻结转写对齐元数据，尚未冻结音频附件身份。LocalTime 不能独立表达大于24小时的相对时长，也不能从单个起点推断真实 endMs。

本次检索 IQC conversation 模块、base-sysadmin 与 framework 文件名未发现已接通的附件存储/受控音频读取能力；这不是全平台不存在该能力的证明。实施存储前还须核对基础设施、其它应用及正式文件配置，不能直接新增对象存储服务或任意URL代理。

## 最小扩展路线

1. 沿原 Conversation/ConversationMessage 扩展离线转写契约，保留原 TXT/API 请求、指纹与历史快照的兼容行为。startMs/endMs 使用非负整数且 endMs>=startMs，置信度为可选受限数值；缺失时保持未知，不由 LocalTime 推造结束时间或置信度。重复序号、越界时间、错误说话人和超量载荷均拒绝。
2. 音频引用归属原会话，冻结内容摘要、媒体类型和真实时长，使用已有存储能力或最小应用层适配。原始音频读取须同时满足独立敏感资源权限与原会话数据范围，审计不含完整转写或签名凭据；播放与Range请求必须受同一授权。
3. 扩展原生产输入冻结/验证与证据协议，明确新协议版本，并保持旧版任务按原摘要验证。后续补录转写不能改写历史已执行输入；音频与Segment定位从同一冻结修订派生。
4. 沿原会话详情、证据与业务复核页面加入受控播放器和双向定位。文本无对齐信息时保留普通文本体验，撤权或切会话废弃播放/旧响应。
5. 验证正式迁移新库/升级/重复无变化、导入幂等与来源冲突、旧快照兼容、敏感权限正反例、Range访问、证据时间戳和浏览器双向定位。没有这些证据不能宣布离线音频完成。

这是实施前的复用与缺口记录，不是离线音频交付。外部ASR自动调用、附件保留策略及存储适配尚待接通；不修改 framework 或 gateway，也不新增平行会话/消息/生产快照模型。

## 首批离线转写对齐进度

V1.1.71 在原消息表增加可选 start_ms/end_ms/confidence，不推断缺失值。原 API/批量/事件共享输入可接入；TXT与旧三字段请求保留原构造、指纹及省略新null字段的v1冻结表示。有对齐数据使用iqc-production-inputs-v2，历史v1不得携带对齐字段。端点为JSON整数且0..9007199254740991，成对有序；置信度0..1、最多6位小数。旧来源重放逐字段核对，修改对齐必须使用明确新输入身份。

实际MySQL专项已通过：正式baseline消息表先写旧记录，再应用完整V71，旧字段保持NULL；超过24小时坐标与六位置信度精确读回；部分端点、倒序、负数、超精确范围、超范围置信度均被CHECK拒绝。日志/private/tmp/iqc-b-offline-alignment-ddl.log。该专项不是完整Flyway新库/升级路径或真实服务落库/生产冻结验收。首轮17项应用兼容测试通过，加入严格JSON解析后的回归尚待终态；代码尚未提交，不宣称离线音频完成。

严格整数解析回归54839已退出0：OfflineTranscriptIngress4、ImportService3、Controller6、TXT5共18项一次零失败/错误/跳过。覆盖新指纹/区间/置信度边界、两输入DTO小数/文本/越界拒绝、旧冻结JSON构造兼容。日志/private/tmp/iqc-b-offline-transcript-exact-tests.log；真实服务Mapper和完整v1/v2生产冻结往返仍需独立验证。

## 冻结往返修复与回归

实际capture/prepare回归暴露Java LongNode与持久JSON IntNode的节点类型差异导致合法v2被规范检查拒绝。typed输入经同一exact parser JSON往返再比较，保留字段形状、精确数值及摘要校验，不使用double。修复后OfflineProductionSnapshot3/OfflineTranscriptIngress4/原ReviewSampleInputValidator10共17项一次通过、零失败/错误/跳过；v2坐标与confidence往返、恢复不重读归档、v1原五字段兼容、时间戳篡改及v1携带对齐拒绝通过。归档Mapper/范围仍替身；日志/private/tmp/iqc-b-offline-production-snapshot-fixed.log，不替代真实Mapper落库与完整Flyway验收。

### 候选、金标和评估对齐传递

有实际区间或置信度的复核候选输入使用 `iqc-conversation-candidate-input-v2`，其摘要覆盖新增字段；无对齐继续使用原 v1。金标冻结直接读取候选冻结输入，不回读已变化的会话；v1 候选携带对齐字段会被拒绝。原金标 Message 增加可选 startMs/endMs/confidence，空值省略以保留旧冻结 JSON。区间沿用配对、非负、顺序和安全整数约束，置信度为0到1且最多六位有效小数。评估重建原 ConversationMessage 时保留这些字段。

本轮39项应用回归通过；真实SQL和端到端验收仍需单独验证。音频引用、受控读取与双向播放定位仍未接通。

金标及来源联合回归33665已终态exit0：GoldReview10、BusinessCandidate7、EvaluationAdmission4、DatasetVersion7、ReviewSampleInputValidator11共39项零失败/错误/跳过。涵盖冻结端点/置信度变更或删除拒绝、等价小数scale接受、旧金标JSON与旧候选协议兼容，以及实际评估prepare保留区间。日志/private/tmp/iqc-b-offline-gold-lineage-fixed-tests.log。Mapper与身份仍为替身，不替代真实数据库、音频存储或播放验收。

复用补充：`opensabre-admin/src/api/file.ts` 定义 `/v1/files` 上传/删除，但当前 base 服务及 framework 的 Java 源码检索未找到对应拥有实现，Compose/基础设施文档也未发现可接通的对象存储配置。该客户端定义不能作为已存在文件服务的证据。存储实施仍需明确应用层持久卷或已有外部存储适配，复用原会话归属及鉴权/审计，不新增平行会话模型。

### 真实 MySQL 导入与生产冻结验证

`IsolatedMysqlOfflineTranscriptIT` 已通过（37872 exit0，1项集成场景）。专用34307服务器身份核对后，仅创建随机数据库，完整Flyway迁移至71、validate及再次migrate零变更；结束清理本测试数据库。原Mapper和Spring事务服务真实执行API导入，>24h坐标和DECIMAL置信度读回、重复输入返回同会话且只有一条消息、不同区间拒绝、修改归档后prepare恢复原冻结端点、实际行改变后原请求重放拒绝、无范围授权时capture拒绝。范围与计次替身，不覆盖完整HTTP登录鉴权或音频附件。日志/private/tmp/iqc-b-offline-mysql-transcript-tests.log。

### 附件存储实施选择

IQC Compose仅提供日志卷，当前源码/基础设施检索未查到共享文件服务的拥有实现。先增加应用层本地持久卷适配，多副本必须共享受控卷，不新增基础设施服务或平行会话模型。文件仅以实际SHA-256摘要寻址，流式限长，临时文件后原子不覆盖发布；同摘要复用前核验字节，不信任上传文件名或外部URL。后续在原会话关联并冻结附件身份，读取须数据范围及独立敏感权限、原治理审计。当前仅存储适配，不代表播放器/完整上传交付。

存储专项10359终态exit0：ConversationAudioStorageTest五项真实文件系统测试通过，含CyclicBarrier双线程发布同字节、最终只有一份文件且完整读回。日志/private/tmp/iqc-b-audio-storage-concurrency-tests.log。该验证仅证明本地存储适配，不包含媒体探测、应用启动配置、HTTP敏感权限或播放。

V72完整正式DDL在任务专用MySQL随机库专项通过40722 exit0：原baseline会话表旧记录迁移后音频字段仍NULL，新完整身份精确读回，部分字段/负长度/零时长/错误媒体类型/大写摘要/超安全整数六类CHECK拒绝，finally清理本测试库。日志/private/tmp/iqc-b-audio-identity-ddl.log；不是完整Flyway新库/升级或服务附件绑定验收。

PCM WAV校验与字节存储联合28621已终态exit0，WAV3+Storage5共8项通过；PCM时长由实际frame计算，格式参数/边界/截断和伪造拒绝，不使用外部ffprobe。日志/private/tmp/iqc-b-audio-wav-metadata-fixed-tests.log。仅PCM WAV子集，其他编码/上传关联/权限/播放仍待。

### 音频冻结协议接入约束

原ProductionInputSnapshotService.Input扩展可选独立audio身份，不把存储摘要/原音频投递给模型的businessContext。仅存在音频的capture使用新v3协议；旧v1/v2的可选audio保持省略，禁止在旧协议塞入新身份字段。身份包含摘要、长度、媒体类型、真实时长，全部校验；有区间时endMs不得超过真实durationMs。prepare只恢复已冻结身份，不回读归档，不用后补附件改变历史输入。读取仍需当前独立敏感权限及会话数据范围，公开一般会话JSON不暴露存储身份。此段是待实施约束，当前Java快照尚未接入audio。

57849实际MySQL回归exit0：完整Flyway新库至72、73项validate、repeat零变更；原Mapper音频绑定wrong revision零更新、首次更新1、不同身份覆盖0、读回完整身份与一般JSON隐藏通过。日志/private/tmp/iqc-b-audio-binding-mysql-tests.log。附件字节和绑定授权仍未形成HTTP闭环。
