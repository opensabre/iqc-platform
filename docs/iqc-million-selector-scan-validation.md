# 百万会话元数据扫描实测（2026-10-31）

固定源码/测试：`907b94897ded12498324944aa5199ce94e3833c0`，基于容量协议 PR21；本次仅验证协议切片1中的受控扫描场景。原进程19121正常退出0，JUnit 1项、0失败/错误/跳过，804.972秒；自有MySQL随后正常关闭。源码/测试自启动至终态保持冻结，随后才加入本报告。

## 数据和环境

- 全新目录 `/private/tmp/iqc-mysql-validation.gxEKxf`，仅 `127.0.0.1:34307`，随机schema `iqc_selector_shard_707b88cc1908450b91c597b005ba3634`；使用原安全身份校验、Flyway和生产MyBatis/ConversationSelectionService。
- Java21，MySQL8.0.34，JVM可用CPU4，最大堆1.5GiB；InnoDB buffer pool 256MiB、redo capacity 128MiB。单机本地运行，没有并发业务负载；并非生产环境容量。
- 确定性整数0..999999生成1000000条IMPORTED会话元数据，message_count=1、owner/group固定；最后137条channel=TAIL，前999863条SKIP。另加owner、group、跨组、锚点开放秒、未来和FAILED状态6条对照。没有生成或持久化百万条消息正文，不能证明消息输入冻结或实际物化。
- 导入229125ms，独立于下表扫描耗时。每轮先用独立数据库ORDER BY id建立期望顺序，因此缓存受预读影响；四轮顺序运行，不能当作独立冷缓存样本或生产性能基线。

## 实际结果

| 受控范围/条件 | 页数 | 扫描条数 | 选中条数 | 扫描及断言耗时 |
| --- | ---: | ---: | ---: | ---: |
| owner，无group | 1001 | 1000001 | 1000001 | 147958ms |
| group，owner不匹配 | 1001 | 1000001 | 1000001 | 159998ms |
| 显式all | 1001 | 1000003 | 1000003 | 110447ms |
| owner OR group，TAIL过滤 | 1001 | 1000002 | 139 | 114399ms |

每页实际调用生产scanAllPageAt，逐页验证候选数最多1000、游标等于数据库实际排序的候选尾、选中ID完整有序且不重复、hasMore及最终空页。开放秒/未来/失败状态不进入扫描；owner/group/all各自包含正确的范围对照。TAIL轮前999页零匹配仍推进，第1000页命中137条，最后页命中2条授权对照。各轮完整结果SHA256见摘要文件；不是只扫描百万然后仅抽验几条。

## 查询计划与成本

原MyBatis Executor采集全部实际查询的次数和耗时；每轮第1/501/1000页分别记录NULL时间探测、主扫描的原SQL、实际Java参数类型及带相同绑定的EXPLAIN。原始采样包含owner/group/all与授权OR，未仅记录LIMIT主查询。

| 模式 | NULL探测调用/P95 | 主扫描调用/P95 |
| --- | ---: | ---: |
| owner | 1002 / 33ms | 1002 / 24ms |
| group | 1002 / 21ms | 1002 / 16ms |
| all | 1002 / 2ms | 1002 / 6ms |
| TAIL | 1002 / 1ms | 1002 / 6ms |

NULL探测使用idx_iqc_conversation_created；主扫描使用PRIMARY，首段index、带游标中尾段range。EXPLAIN的rows为优化器估计，**不是实际扫描行数**；主扫描中段仍估计413245行，不能用LIMIT 1001或本次密集授权数据证明最坏扫描成本有界。Count、payload字节数和候选完整读取也各有1001次真实查询及耗时记录，不能把上表两个查询的P95当作整页/任务P95。

阶段只读观测：导入事务修改行数545026→925177，无行锁等待；InnoDB累计写入2279556608→4548322816字节（包括初始化/导入，不是扫描净IO）。阶段RSS样本：MySQL453288KiB，测试JVM572216KiB；不是峰值。未连续采集CPU/IO/锁、堆/RSS峰值，未测排队/worker吞吐/费用；缺失项不能填零或外推SLO。扫描耗时包含Java转换、断言及采样EXPLAIN，不能等同单一业务SQL耗时。

## 可追溯产物和复现

- [摘要、结果计数与SHA256](validation/iqc-million-selector-summary-20261031.json)
- [实际查询、参数类型、索引、计划、进度与每类查询统计](validation/iqc-million-selector-20261031.log)
- [本次schema实际Flyway版本/checksum](validation/iqc-million-selector-migrations-20261031.tsv)

原完整日志 `/tmp/iqc-b-million-selector-run.log` 及exit文件、JUnit XML位于本轮工作树target/surefire-reports；摘要保留原日志SHA256。仓库采样仅保留MILLION前缀的合成数据观测，不含生产个人数据或凭据。

复现须启动一个全新专用MySQL8.0实例，datadir位于 `/private/tmp/iqc-mysql-validation.*`，空密码测试root，仅127.0.0.1:34307；原工具校验端口和datadir，不得连接共享库。先按项目Java21离线编译test-compile，再设置实际IQC_MYSQL_VALIDATION_DIR，执行 `mvn -o -q -Dmaven.gitcommitid.skip=true -Dtest=IsolatedMysqlMillionSelectorIT surefire:test`，为测试JVM配置1.5GiB堆及已有Mockito ByteBuddy agent。本轮准确启动器留在 `/private/tmp/iqc-b-million-selector-run.sh`，包含实际agent路径和进程退出关闭逻辑。IT不在默认单元测试命令内，普通CI不等同本专项运行。

同固定头普通CI run41141169444：1918测试0失败/错误/跳过，Flyway验证78迁移、应用58到1.1.77后第二次up-to-date；镜像push-only skipped。这是另一个证据范围。

## 剩余验收

本轮证明这些合成数据上的百万扫描、范围/锚点/游标与零匹配推进行为。真实PDP和撤权、稀疏授权/其它数据分布、百万消息正文和实际子任务物化、早中后进程强杀恢复、两个完整worker竞争、普通任务混合负载、完整百万执行以及供应商/外部成本和SLO仍需独立实测。不能用本轮扫描或普通CI宣布百万容量或全部PRD完成。
