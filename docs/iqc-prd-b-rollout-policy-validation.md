# 灰度分配领域与精确解码验证

## 基线与范围

B 验证工作树在 `910ea67` 上仅集成 A 已提交的预览 `5148311`（本分支 `22772ee`）和修订持久化 `99dcd2e`（本分支 `a8927d9`）。未取另一开发者正在进行的任务物化源码，不修改生产策略或迁移。

新增 `PipelineRolloutPolicyBehaviorTest` 直接运行真实 Policy、SHA256、BenchmarkVariableComparator 和 Jackson 两代局部解码，不使用策略/comparator mock。合成 RULE 发布内容仅改变重试次数，选择 PIPELINE 维度；Prepared 的发布摘要是格式正确的合成值，此测试不证明服务层原发布 hash/准入证据验证。

## 覆盖内容

- 0/10000 比例和回退明确选择两侧精确版本与摘要，分桶范围0–9999，比例/回退改变不重排同身份请求的桶。
- 100个固定请求验证重复确定性及扩量不会移除已有候选，bucket<比例为严格边界。
- 账号、请求键、盐变化产生不同 cohortHash；响应不含这三项原文。
- 独立 Python hashlib 固定向量：对 `iqc-pipeline-rollout-cohort-v1`, `pipe-1`, `actor-1`, `request-1`, `salt_0001` 按每项UTF-8字节长度和冒号前缀分帧；SHA256 为 `3187e36ad8996976f61e9b88b8f5d0a0f4fc080465fd32988fa344d406af0ea0`，模10000为9568。测试也核对原 Prepared/Release 序列化保持不变。
- 拒绝不同资产/错误版本/草稿/坏摘要格式；版本与摘要不同但无实际检测语义变化仍拒绝。
- 双代 codec 可往返，三项整数拒绝小数（含1.0）、字符串、int溢出、null及布尔；策略和变量的未知字段拒绝。

## 实际执行与边界

首轮 `/tmp/iqc-b-rollout-policy.log` 原15588终态0，7项零失败/错误/跳过。取得终态后才加独立向量，使用有效主编译产物、`compiler:testCompile surefire:test` 验证最终新增8项及既有SmallModel/Skill比较回归；最终结论以本轮报告和协作记录为准。

本轮不是五维全部语义、授权/MVC/切面、修订CAS或并发、真实数据库/触发器、真实供应商、浏览器或实际任务灰度证据。全 I1–I7 仍进行中。输入为已处理会话，不新增语音转换。

最终原31117终态0，`/tmp/iqc-b-rollout-policy-final.log`，三份本轮XML：Policy8、SmallModel5、Skill4，共17项，0 failures/errors/skipped。没有重新运行当前组合全默认后端套件；此处17项不能替代此前其他组合的全套或真实MySQL证据。
