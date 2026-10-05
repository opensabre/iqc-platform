# Selector 会话元数据扩展

## 复用与缺口

复用现有 iqc_conversation、ConversationMetadata.normalized()/fingerprintPart() 和 ConversationImportService.persist()；TXT、ZIP 与 API 已共用这一持久化入口。employeeGroupId 继续引用组织部门/团队，不复制组织表。现有输入缺少 ASR 状态、客户等级、投诉标记及自定义业务字段，I4 Selector 无法判断这些条件，因此增加四个可选元数据字段和现有会话表列。

## 合同

asrStatus 是上游真实状态，空值表示未知，不把文本导入当成 ASR 成功。customerLevel 为上游等级；complaint 为可空布尔值，未知和 false 不混同。businessFields 为最多 64 项标量 JSON 对象，键匹配字母开头的字母/数字/下划线且最长 64 字符；字符串最长 4096 字符，数字仅限有限数，整体 UTF-8 JSON 最多 65536 字节。禁止数组、嵌套对象和内部节点类型，按键排序并复制节点。

旧 12 参数构造器保留。新增四项全部缺省时，fingerprintPart 保持逐字不变；有新元数据时追加带版本的规范 JSON，避免不同新元数据被内容去重吞掉。API externalId 仍是上游幂等标识，重放不得静默丢弃冲突的新元数据。读取复用现有 Conversation 返回对象，businessFieldsJson 是已规范化的 JSON。

迁移 V1.1.42 仅扩展现有表，历史值均 NULL。尚未运行迁移、测试或环境验收。
