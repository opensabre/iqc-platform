# Knowledge Base 语料版本基础（KB1）

本批为内部领域服务和DDL；没有尚未配套授权/敏感投影的HTTP接口，不表示知识库整体功能已完成。后续管理/引用与运行上下文分KB2/KB3推进，见ADR 20261024-knowledge-corpus-versions。

## 语料协议

`iqc-knowledge-corpus-v1`包含documents，每份文档为documentId/title/text/sourceReference四个字符串。文档ID须为1–64位字母数字或:_-且唯一；标题1–200字，正文1–16000字，来源引用1–512字符且无控制字符/首尾空白。集合1–100份文档，正文总长度最多200000个Java字符，序列化正文最多2MiB。文档按ID排序，输入列表不被修改；字符串数字不被自动当文档正文，未知字段/重复JSON字段/尾随JSON/空正文及不完整Unicode代理字符拒绝。来源引用只作证据定位，不下载内容或自动视为可信会话证据。

语料示例（仅协议样例，当前没有HTTP入口）：

```json
{"schemaVersion":"iqc-knowledge-corpus-v1","documents":[{"documentId":"service-hours","title":"服务时段示例","text":"示例服务时段为工作日09:00至18:00。","sourceReference":"manual:service-v1#hours"}]}
```

## 独立版本与组织范围

`iqc_knowledge_base`只维护草稿修订、可用性与最新发布指针；`iqc_knowledge_base_version`保存不可变原版本/源草稿修订/JSON摘要，唯一约束保证同草稿不重复发布。创建默认DISABLED，发布后显式启用；后续发布维持原可用性，精确旧版本不跟随新指针。restore把历史语料和名称写入新草稿修订，必须再次发布，不修改原版本或旧任务。

所有内部读入口复用IqcDataScope owner/group/all；维护操作复用现有资产的接口写权限和相同数据范围，拒绝system，不额外创造创建者专属ACL；版本分别冻结原归属owner与实际publishedBy。分页目录不读取草稿正文；原组改变只能收窄为私有，不能把旧私有历史开放到新组。Prepared返回的ownerGroupId为当前资产与原版本的来源交集，后续任务必须使用该字段收窄来源范围，不能误用历史snapshot.ownerGroupId扩大当前共享。没有tenant字段或客户端组织头。

发布在原资产行锁/事务内检查草稿修订、原正文及版本上限，同修订重放返回原版本；后续插入/指针失败由同事务回滚。readPublishedEvidence纯校验原版本ID/知识库ID/版本/源修订/原归属/实际发布人与内容Hash。viewVersion允许读取停用后的历史，publishedForTask另外要求当前ACTIVE、调用方已有事务并锁原资产，和启停/更新串行化。历史来源校验不重新选取最新语料。history/viewVersion跨父记录和版本查询使用同一只读REPEATABLE_READ事务，防止旧组检查与并发新私有版本错配；新任务引用则在原调用事务锁父记录。

## 接入边界与验收

Agent共享目录只携带精确知识库引用/Hash；私有正文应在有当前组织授权的任务物化阶段冻结到原任务，并与其他来源取组交集。不能把知识库语料复制到全局可读Agent配置、Gold数据集或Prompt指令库来省略权限。运行上下文需有界且保留原引用/文档/Hash，参考知识不能替代原会话引文；这些仍属后续KB2/KB3。

本批V1.1.75仅写入迁移，不执行共享SQL或部署。编译终态见协作记录；真实迁移/Mapper、当前授权操作者/跨组/撤权、CAS并发发布/恢复/停用、Hash及坏原父来源、参数边界和实际任务/页面均需独立验证。编译不能证明SQL/角色/worker或全适用I1–I7已完成。
