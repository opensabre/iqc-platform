# 分片扫描真实 SQL 验证及时间精度限制

以下首轮711f875/760c4de记录为历史证据；当前v2结论见末节，不再将同秒纳入作为现行行为。

基线711f875（包含A固定1323a73/8360685）。只增加IsolatedMysqlSelectorShardIT，真实Spring只读事务代理/MyBatis/本机独立MySQL随机新库，数据源为处理好的会话。当前owner/group/all及未使用的结果、标签mapper受控，不代表真实PDP或标签来源SQL。

## 结果

原61748终态0，`/tmp/iqc-b-selector-shard-sql-precision.log.exit=0`；fresh Surefire XML 5项、49.410秒、零失败错误跳过。脚本退出后专用socket不存在。运行`compiler:testCompile surefire:test`，生产源码沿用既有编译基线未改动。

- 20137条授权源连续覆盖21页；前20000条零匹配仍按最后扫描ID前进，末137条匹配无重复，下一秒未来源及其他权限源排除。原普通入口20000候选保护仍拒绝超池；尾后空页终止，targeted结果一致。
- owner OR group、空组、显式all保持；锚点返回毫秒截断。授权NULL创建时间拒绝，非授权NULL不阻塞。
- 六种非ALL采样、非法大小/空锚点/游标拒绝。
- 独立information_schema断言会话created_time精度为0；锚点后1ms的同秒源仍被纳入，明确记录当前围栏的限制，不将通过解释为严格毫秒快照。

## 失败证据与未完成项

原26893终态1为新seed遗漏必填source_fingerprint，补指纹后原50483终态1（4项2失败0错误）：未来1ms未被排除，扫描20138而非20137，scope额外出现d。日志`/tmp/iqc-b-selector-shard-sql.log`及`/tmp/iqc-b-selector-shard-sql-final.log`保留。没有通过改变生产约束/期望掩盖同秒边界；最新用下一秒验证可表达边界，同时新增专门用例固定同秒限制。

现行datetime(0)无法证明“所有锚点之后导入数据均排除”；原ADR中的毫秒锚点不等于存储毫秒精度。协调器仍需确定严格水位或明确纳入语义，已通知根协作记录。没有修改正式schema或生产文件。此测试不是百万容量/全池快照/父子持久协调/暂停取消恢复证明；SQL执行计划和大数据SLO仍未验收，不以LIMIT推断底层扫描成本。

## v2严格存储截止复验（2026-10-27）

B合并A固定4b1fc73（含ef54158），合并基线b160cd6。原17461终态0，`/tmp/iqc-b-selector-v2-sql.log.exit=0`；fresh XML 5项62.274秒，零失败错误跳过；独立MySQL已停止，socket不存在。执行test-compile surefire:test，不消费A后续未提交代码。

当前ScanPage v2的createdBeforeExclusive始终为锚点整秒起点，SQL严格小于此值。真实DATETIME(0)下：上一秒纳入；开放秒内早于毫秒anchor、晚于anchor及第一页后新增的三类源均排除；后续锚点批次可完整纳入这些源。每页断言v2协议与相同截止，20137授权源跨21页、20页零匹配/137末页匹配、原20k保护、权限/NULL/非法输入/空尾页和target一致性继续通过。

原scope与NULL夹具调整为截止前一秒，保证该测试继续验证权限/NULL而非误混开放秒语义。旧失败证据保留；不是放宽未来排除期望。该方案通过留待后续批次处理整个开放秒解决精度问题，仍不宣称百万可变语料跨页全局快照。父子事务、输入冻结、双worker恢复和容量需后续独立验证，本轮仅变更测试与文档。
