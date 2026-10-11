# 离线音频接入：需求与复用边界

## 当前证据（2026-10-12）

原 PRD 第15节要求统一会话的 ASR/附件信息，Segment 的 speaker/text/startMs/endMs/confidence/sequence，以及音频与转写按时间戳双向定位。第13节要求复核音频时间轴，第19节要求原始音频敏感权限，非功能需求要求音频/会话访问审计。实时语音排除不改变这些离线要求。

现有 `conversation/model/Conversation.java` 已有 asrStatus、归属、业务字段和原始来源指纹；`ConversationMessage.java` 已有顺序、角色、正文和 LocalTime。`ConversationApiIngestService` 复用原导入、原指纹与计次，当前不接收音频引用或毫秒区间。`ProductionInputSnapshotService.Message` 冻结 id/sequence/role/content/relativeTime，尚未冻结音频对齐元数据。LocalTime 不能独立表达大于24小时的相对时长，也不能从单个起点推断真实 endMs。

本次检索 IQC conversation 模块、base-sysadmin 与 framework 文件名未发现已接通的附件存储/受控音频读取能力；这不是全平台不存在该能力的证明。实施存储前还须核对基础设施、其它应用及正式文件配置，不能直接新增对象存储服务或任意URL代理。

## 最小扩展路线

1. 沿原 Conversation/ConversationMessage 扩展离线转写契约，保留原 TXT/API 请求、指纹与历史快照的兼容行为。startMs/endMs 使用非负整数且 endMs>=startMs，置信度为可选受限数值；缺失时保持未知，不由 LocalTime 推造结束时间或置信度。重复序号、越界时间、错误说话人和超量载荷均拒绝。
2. 音频引用归属原会话，冻结内容摘要、媒体类型和真实时长，使用已有存储能力或最小应用层适配。原始音频读取须同时满足独立敏感资源权限与原会话数据范围，审计不含完整转写或签名凭据；播放与Range请求必须受同一授权。
3. 扩展原生产输入冻结/验证与证据协议，明确新协议版本，并保持旧版任务按原摘要验证。后续补录转写不能改写历史已执行输入；音频与Segment定位从同一冻结修订派生。
4. 沿原会话详情、证据与业务复核页面加入受控播放器和双向定位。文本无对齐信息时保留普通文本体验，撤权或切会话废弃播放/旧响应。
5. 验证正式迁移新库/升级/重复无变化、导入幂等与来源冲突、旧快照兼容、敏感权限正反例、Range访问、证据时间戳和浏览器双向定位。没有这些证据不能宣布离线音频完成。

这是实施前的复用与缺口记录，不是离线音频交付。外部ASR自动调用、附件保留策略及存储适配尚待接通；不修改 framework 或 gateway，也不新增平行会话/消息/生产快照模型。
