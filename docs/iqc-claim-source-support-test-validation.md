# 来源支持标注专项验证（2026-10-31）

基于 PR23 固定 `2e284d180b33ed61787d4b544e14bbe1ca75c3e7`，只新增独立测试，不修改生产实现。

`ClaimSourceSupportReviewTest` 的八项测试覆盖：

- 历史 JSON 字面量在字段缺省、null、空数组时输出逐字节一致，序列化列表摘要保持一致。
- SUPPORTED、CONTRADICTED、INSUFFICIENT_SOURCE 的序列化、反序列化及 evaluator 回放一致；保留机器原始观察，人工原因规范化。
- 标注结论影响序列化摘要；缺省标注、支持与矛盾不会得到同一摘要。
- 同一冻结会话消息可引用；其它原始观察证据、外会话消息和未知观察 ID 拒绝。
- RULE、UNKNOWN、CONFLICT、ERROR、NOT_EVALUATED 和重复原始观察身份拒绝。
- 其它结果 ID 和过期事实 hash 拒绝。
- 支持/矛盾必须有来源；原因、引用数、ID 长度、重复 ID 和标注数量上限；来源不足允许空引用。
- 输入集合复制及只读访问，调用方修改集合不改变已建立的标注。

本机 Java21，原进程55179正常退出0。JUnit XML实际结果：新增8、原HumanFindingReviewEvaluatorTest7、BusinessItemReviewServiceTest23、ReviewContextQueueHttpPrivacyTest13，共51项，0失败、错误、跳过。后两类是已有服务/HTTP隐私门禁回归，并非新字段真实数据库保存或实际PDP撤权测试。

首次原进程41195退出1：新增及原evaluator共15通过，36项因本机Mockito动态agent初始化失败报错，未改断言。第二次只在命令行显式加载已有ByteBuddy agent，使用已编译的相同源码运行：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-21.jdk/Contents/Home \
mvn -o -q -Dmaven.gitcommitid.skip=true \
  -Dtest=ClaimSourceSupportReviewTest,HumanFindingReviewEvaluatorTest,BusinessItemReviewServiceTest,ReviewContextQueueHttpPrivacyTest \
  '-DargLine=-Xmx1536m -javaagent:/Users/zhoutaoo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar' \
  surefire:test
```

本机日志 `/tmp/iqc-b-claim-support-tests-20261031.log`（首次失败）及 `/tmp/iqc-b-claim-support-tests-agent-20261031.log`（成功），各有exit文件；JUnit XML位于本工作树target/surefire-reports。agent路径须按复现机器已有缓存调整，不修改POM或框架配置。

此小项不声称完成标注的数据库保存/读回/业务幂等重放、真实身份授权与撤权、报告/UI/Gold独立审核链、语义幻觉覆盖率或百万实际物化恢复。上述范围仍需后续独立实现及验证。
