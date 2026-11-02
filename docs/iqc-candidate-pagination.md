# 候选池分页浏览

PRD14.6 的候选池和数据集成员选择复用原样本库。此前 GET /quality-operations/samples 一次加载全部授权样本；本次扩展同一接口分页能力，不新增表、端点、权限或框架组件。

## 兼容协议

- 不提供 current 和 size：保持原 JSON 数组及 Java samples(type,status) 签名，旧调用方无需迁移。
- 同时提供 current、size：返回原 IqcPage 协议 records/current/size/total。current 为 1–2147483647，size 为 1–100；只提供一个或超范围时拒绝。
- type/status 继续沿原精确筛选和大小写归一化。排序为 created_time DESC、id ASC，同时间记录顺序稳定。翻页时新增数据可能改变偏移位置；这里不承诺冻结游标快照。
- 两种读取复用同一原查询：owner/group/all 权限约束、排除 goldDraftJson；分页后仍调用原 ResultReadProjection.samples，不绕过原敏感读取前置、私有响应缓存策略及审计。total 只计授权筛选结果。

示例：GET /quality-operations/samples?current=2&size=20&type=FALSE_NEGATIVE。

## 前端衔接

样本库增加页码、类型和状态筛选；数据集候选目录按页加载，金标审核版本继续使用原独立分页与精确版本引用。旧 listSamples API 导出保持。新版分页响应必须提供正确 records/current/size/total，旧后端数组不伪装成分页、不退回全量读取；部署需先后端再前端。

## 验证边界

Root 原72852离线 Maven compile 退出0，未本地编写或运行应用测试。diff检查通过；独立服务、HTTP新旧协议、实际SQL分页/范围、UI筛选翻页/撤权迟到等回归由B负责，自动PR CI另行记录。此次不运行百万专项，不声明真实角色/PDP或完整业务场景已验收。

## 跨页查找（2026-11-02）

分页模式追加可选 keyword（最多128字，去首尾空白）：按名称模糊匹配或样本ID精确匹配，与原type/status及owner/group/all条件取交集，仍使用绑定参数。total与records应用同一过滤；空白关键词等同不筛选。显式提供keyword时也必须同时提供current、size，不退回全量搜索。原服务及Controller两参、四参Java入口保持。

数据集选择器此前show-search过滤全量目录，分页后不能仅过滤当前20条。本增量通过同一路径提供跨页搜索，前端显式提交关键词后回到第一页；批准版本仍独立选择，已有数据集成员不因搜索而变化。

Root原26595离线Maven compile退出0，未本地编写或运行应用测试；B需补关键词范围/计数、跨组名称与ID不泄露、旧协议及超长关键词拒绝、前端跨页/请求代次回归。现有EnvelopeIT准备阶段WAITING_BACKOFF失败保留，由B独立诊断；本功能不改退避政策或测试断言。百万专项继续暂缓。
