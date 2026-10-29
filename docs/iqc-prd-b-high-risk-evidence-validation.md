# 高风险证据固定源独立验证

2026-10-28，B62472b5后仅合固定A935cea7至dc1dcb3，生产门禁4f73190；不取A未提交源码。新增RiskPolicyEvidenceTest六项：

- 无条件HIGH/default HIGH不能用于新配置，旧策略对象仍可evaluate为HIGH，不原地改写历史协议。
- ANY每条可匹配路径均须保证证据；ALL允许证据门与其它业务条件组合。
- UNKNOWN和NOT(KNOWN)不能冒充已知证据；直接否定要求证据的FINDING_EQUALS仍沿原三态条件契约。
- 嵌套ANY不能借另一分支的证据绕过。
- 真实FindingFusion按优先级保留被拒绝候选证据，但该证据不能支撑选中机器结论；人工可定位证据门与机器选中证据门明确区分，低风险不附加此门。
- 选中来源的实际证据可支撑CRITICAL检查，原raw/selected/rejected保留。

## 执行证据

原88888 test-compile surefire:test终态1，`/tmp/iqc-b-high-risk-integrated-20261028.log.exit=1`。总40项中，新增历史兼容测试夹具错误地传入null scoreStatus，违反原Context必填约束，进入策略判断前NPE；不是生产缺陷。仅终态后修夹具为合法PENDING，未修改生产源码。

同次实际生产4f73190的MeasuredPublication主方法1项115.6秒、BusinessItemReviewService23项2.888秒、BusinessItemReviewEvaluator10项0.058秒均零失败/错误/跳过，共34项。主链覆盖实际发布、任务冻结、批次物化回滚、跨JVM领取互斥/崩溃恢复、满载停止传播、优先级、暂停恢复取消清理、终态六组、手工与配置子策略自动重试，以及原Finding/MVC/复核链。专用MySQL34307、已验证新schema，无共享数据操作。

原14262 compiler:testCompile surefire:test仅重跑修正的RiskPolicyEvidenceTest：终态0、`/tmp/iqc-b-high-risk-evidence-final-20261028.log.exit=0`，fresh6项0.500秒零失败/错误/跳过。合计40项各有通过证据，不能说首轮整批退出0。diff检查通过，专用socket不存在。

## 边界

静态门六项与原SQL正常路径通过，不能替代所有首次发布/物化拒绝路径、同code多生产者证据一致性、实际人工新增高风险/重算/风险Action和历史幂等的独立场景验证。原SQL scope/组织与供应商受控，不能说真实PDP、真实浏览器、模型中断、外部通知或百万容量已完成。原日志预期注入SQL异常、Action receipt pending保留。仅输入处理后的会话，不含语音转换；本轮除合固定生产外只新增测试/docs，无framework/push/部署，完整适用I1–I7继续。
