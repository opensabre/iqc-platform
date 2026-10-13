# I6 Session Engine 实施约定

需求来源：PRD 8.2 Session Engine，会话级流程完整性与行为次数。实施前检查原RuleMatcher、rule/dls/DlsEngine、DlsRuleDocument、FrozenDetectorEngine、原Finding/资产冻结及共享框架/Wiki。原DLS已经有角色匹配、子规则命中坐标、布尔组合、按消息序列的流程步骤 `%` 和消息窗口计算；不得再造正则库、流程表达式语言、会话表或聚合结果表。

## 复用与缺口

原DLS最终输出Evaluation(boolean hit,evidence,reason)，已经能够判断已声明步骤是否按序满足，但没有面向流水线的独立SESSION版本与数字聚合契约。原Evidence只包含最终命中分支的证据，不能把它的条数当成某行为的全会话出现次数，未命中最终组合时也不能据空Evidence称次数为零。

拟在原DlsEngine内最小提取已有命中扫描，保留旧evaluate入口与原布尔语义；为新SESSION提供同一完整扫描的指定子规则计数及流程判断。第一批操作定义为DLS_ENTRY_MATCH（原声明流程布尔判断）及MATCHED_MESSAGE_COUNT（指定RULE子规则命中的不同消息数，同消息重复命中只计一次）。该消息数不是实际业务行为发生次数，字面匹配不能推断否定、转述或真实行为，业务解释由原事实定义与融合政策负责；真正行为次数和其他上下文计算仍按PRD继续。

## 输入、版本与证据边界

SESSION必须接受完整可信冻结消息，映射不得截取或替换manifest。重复ID、混合会话、缺失内容或序号、重复/无效序号必须拒绝；空输入保持UNKNOWN，不伪造false或0。沿原已发布DLS规则版本及Finding目录冻结，不在config嵌入未发布新表达式。BOOL流程定义与NUMBER计数定义分别校验，固定引擎版本、来源EID、实际规则版本和完整输入摘要均进入事实证据；计数0也必须证明完整输入和实际扫描，不靠模型提供计数。

扫描及证据应有显式上限，超限报错，不截断后发布完整结论。重用原引擎超时/取消及失败状态，但运行时线程超时不等于Java正则可安全中止，不能据此宣称恶意正则资源隔离已完成。不得顺带改变现有DLS协议或历史trace。生产/评测沿同一适配器，无业务副作用。

## 当前阶段

本节保留实施前缺口与取舍记录；当时SESSION尚未编码或注册，当前进展见下节。未运行测试、SQL或部署。A拥有Session桥接、DlsEngine最小扩展、原资产服务与流水线配置界面；B按用户最新处理后会话范围，继续指标/Session与实际执行验收，不开展语音转换。实际发布/生产/评测/回放及资源容量验收仍待。

## Session编码进展（2026-10-13）

原DlsEngine扫描提取为共用内部函数；旧evaluate继续原布尔流程/窗口语义，新evaluateSession有10000消息/10000总命中/100万命中文字及100万规则消息对上限，超限拒绝，不截断。新SessionAggregationEngine固定dls-session-v1，完整可信manifest/EID/发布规则引用匹配；空会话或声明规则参与者缺失UNKNOWN，坏身份/重复序号/混合会话拒绝，完整扫描得false/0亦有RULE_MATCH来源证明。字面正则的命中消息数不证明真实行为次数。

已接入原RuntimeConfiguration与原PipelineAssetService：引用原已发布DLS规则及BOOLEAN/NUMBER事实定义，服务器覆盖冻结体并沿原版本/哈希/试跑与评测门禁；执行检查原规则/目录可用性，保留frozen body。尚待最终编译、配置界面及真实运行验收；未新增/运行测试或SQL，尚未部署。

发布校验补齐：原PipelineDecisionEvaluator当前只支持BOOLEAN评分及适用条件；发布端复用原冻结事实类型表提前拒绝把NUMBER/其他类型当成命中布尔，避免试跑到执行层才失败。同编码的冻结类型不一致也提前拒绝。此处不新增数值评分语言；数值业务阈值政策仍是后续完整决策需求，不把命中消息数直接扣分。

## 本批终态构建

后端79968首轮及7741评分类型最终离线skipTests test-compile均退出0；UI80808 vue-tsc/Vite退出0，e86df64配置界面已提交。当前已接通原Runtime/发布冻结与编辑器；这些编译结果不证明真实发布、Task/评测、并发/资源边界或浏览器验收。未新增运行测试、SQL或部署，整体PRD继续。


## B验证进展（2026-10-13）

合并后DlsSessionAggregationTest三项与原DlsEngine/指标回归41207退出0：重复字面命中按不同消息去重、角色过滤、完整扫描零值、无效定义与空输入拒绝。Session前端合并构建37288退出0。
SessionAggregationEngineTest新增真实Runtime数值及完整来源证据、映射裁剪/重复身份拒绝和冻结规则版本不一致拒绝，55532已退出0，节点3项、底层聚合3项与原DLS测试全部通过；覆盖实际Runtime而非完整数据库任务链。
PipelineAssetService发布时取得原规则PUBLISHED版本并覆盖冻结体；执行时检查当前可用性但保持原冻结表达式。此源码核对不代替实际数据库发布、任务物化、评测与历史读取验收。
