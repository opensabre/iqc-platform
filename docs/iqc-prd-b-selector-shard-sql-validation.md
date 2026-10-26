# 分片扫描真实 SQL 验证及时间精度限制

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
