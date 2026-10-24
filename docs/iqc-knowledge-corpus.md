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

## B 协议与发布来源验证（2026-10-24）

固定同步a095f7a后新增KnowledgeCorpusTest：规范排序且不改变原列表、输出列表只读、
正文/总量/Unicode/重复ID边界、未知字段/重复字段/尾随JSON/字符串类型严格解析，
以及发布版本Hash、身份、修订、发布人核对。原12902终态0，知识库4项与Prompt组合
共12项零失败、错误、跳过；日志`/tmp/iqc-b-knowledge-protocol-final.log`。
摘要复用原JSON规范化协议，格式空白不属于正文篡改；实际正文变化拒绝。
本批未执行DDL/SQL，不替代组织权限、CAS并发、事务锁、管理界面和任务正文冻结验收。

## B 真实SQL生命周期验证（2026-10-24）

原实测发布夹具注册实际Knowledge Mapper及Spring事务代理Service，在专用34307
应用V1.1.75后验证：默认停用、同修订重复发布同ID、无事务的任务引用拒绝、
停用任务引用拒绝、启用后精确历史读取、原审计owner/publishedBy、旧修订CAS拒绝、
后续发布保持ACTIVE、原历史正文不变、恢复只生成新草稿且保留发布指针、停用历史可读。

原10993终态0，IsolatedMysqlMeasuredPublicationIT四项零失败、错误、跳过，
27.455秒，专用MySQL关闭且socket消失；日志`/tmp/iqc-b-knowledge-lifecycle-sql-final.log`。
首次测试编码含小写被正确拒绝，修正为大写后组合通过。组织scope沿原夹具受控，
不外推真实PDP、跨组/并发竞态、任务正文冻结或全新数据库完整迁移验收。

## B 发布故障回滚验证（2026-10-24）

在原生命周期场景注入真实MySQL父表UPDATE失败：版本插入后的父指针更新
被触发器拒绝，事务回滚后版本行数为0、发布指针为null、草稿修订仍为1。
finally移除触发器后，原发布及同修订重放正常，后续生命周期继续通过。
原97051终态0，完整集成四项零失败、错误、跳过，32.155秒；
日志`/tmp/iqc-b-knowledge-rollback-sql.log`，专用34307已关闭且socket消失。
Flyway实际校验76项迁移，当前1.1.75，无新增迁移操作。
此为单实例故障回滚证据，不替代并发发布/跨组授权/任务冻结验收。

## B 同修订并发发布验证（2026-10-24）

原生命周期场景以两个线程各自设置用户上下文，经屏障同时发起原Spring代理
Service.publish，使用独立事务。限时完成后两侧版本ID相同，实际版本表仅一条记录；
后续普通重放仍返回同ID。线程上下文与线程池均清理，原故障回滚及生命周期断言保留。
原94472终态0，集成4项零失败、错误、跳过，103.585秒；日志
`/tmp/iqc-b-knowledge-concurrent-publish.log`，专用34307关闭且socket消失。
本轮不增加用例数量，不代表多进程压力测试或跨组真实权限验收。

## B 实际负责人范围验证（2026-10-24）

在原SQL生命周期中独立使用实际IqcDataScope(null)与实际Mapper服务，验证负责人
可读、其他用户get/history/update被拒绝且page不含记录、ADMIN可读、无认证get/page
被拒绝；用户上下文finally还原。更新调用使用原事务管理器。
原9737终态0，集成4项零失败、错误、跳过，62.889秒；日志
`/tmp/iqc-b-knowledge-owner-scope.log`，专用34307关闭且socket消失。
没有接入组织服务，同组共享、跨组变更竞态和真实PDP/HTTP授权仍待联调。
