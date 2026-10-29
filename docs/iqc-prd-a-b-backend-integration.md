# IQC PRD A/B 后端组合（2026-10-22）

## 固定来源

A `b8cc86e` 合并 B `ee4e3dc`，共同祖先 `96a8661`。只合并已提交版本，不修改B工作树或运行进程，不推动main或部署。

## 保留内容

- B显式PipelineRelease v2与冻结任务/轨迹/执行输入、Gold/Dataset、Benchmark、Action策略与来源/标签投影的精确读写；v1原语义保留。
- B真实测量发布链路发现的可选outputSchema JSON null往返规范化，以及TranscriptMetricEngine事实身份与既有Task/Execution/会话/节点/attempt规则对齐。
- B实际发布、输入固化、worker、动作失联对账、复核候选、Gold独立审核和回归门禁的隔离SQL场景源码及各自证据说明。
- A CUSTOM/WEIGHTED/finalConfidence、配置和复核字段投影、已修默认MVC消息响应、置信度诊断和业务人工修改率。

自动合并无文本冲突。复核发现QualityOperationsController旧helper命名差异，统一调用既有responseAccess；原固定资源/敏感当前授权和缺组件拒绝不放宽，不新增授权机制。

## 协议与证据边界

默认PipelineRelease.SCHEMA仍v1，EXACT_SCHEMA=v2仅显式版本；没有自动升级存量任务或切换UI创建默认。v2激活仍须当前组合的兼容、发布和运行验收，不把显式v2能力描述为默认v1精度缺陷已全部消失。

本次Root仅离线skipTests test-compile，包含合并的测试源码；没有运行测试/SQL/服务/browser/外部渠道。B先前的真实MySQL闭环仍有受控身份/scope等边界，其结果归具体B提交；不外推为当前A/B头、真实PDP/组织/权限注册/浏览器、外部收件方或容量SLO通过。

单租户部署，沿用现有组织数据范围。输入为已处理会话；实时语音、ASR、音频解码和转写生成排除。完整适用I1–I7保持进行中。
