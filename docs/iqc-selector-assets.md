# Selector 独立资产

## 实现前复用取舍

PRD §7 要求独立 Selector 和保存前预览；§5.2/§17 要求影响结果的配置冻结、历史按快照解释。当前 ConversationSelectionService / SelectorDefinition 已完整集中条件、窗口、抽样和预览，继续复用，不另建筛选器。

检索 owning IQC 的 PipelineAsset、InspectionScheme、EvaluationDataset、模板与配置，以及 framework starters、base-sysadmin/base-organization 和 Wiki：现有持久化对象分别承担引擎、业务方案、金标数据集、组织或字典职责，没有独立Selector修订/发布定义。向Pipeline或Task原地添加资产草稿会混淆生命周期，不能跨流水线复用。新增 iqc_selector_asset / iqc_selector_asset_version 只保存Selector定义，沿用 BasePo/MyBatis、IqcDataScope、Audit 与现有版本模式；不新建任务/队列/调度基础设施，不改 framework。

本PRD未明文列出Selector CRUD/API或在§17单独列出Selector；独立资产与发布协议是落实保存复用和调度快照的设计选择。

## 契约

资产 code 全局唯一不可变，name 1–100字；草稿修订从1开始，更新要求 expectedRevision。发布在资产行锁下按修订幂等，生成不可变连续版本及保留精确小数的规范JSON SHA-256。任务事务内锁定资产并复核精确来源，停用阻止新使用，历史查询仍可用。所有读取/写入核验当前操作者可见范围；无已认证操作者的system上下文不能维护资产。

`/api/iqc/selectors/assets` 提供分页/新建，`/{id}` 提供详情/修订，`/{id}/availability`启停，`/{id}/versions`历史，`/{id}/publish`发布，`/{id}/versions/{versionNo}`读取精确版本，后缀`/preview`复用现有即时预览。每条端点采用独立资源码。

正式Pipeline任务可以提交 selectorAsset:{selectorId,versionNo}；与内联selector和conversationIds互斥。服务端解析精确已发布版本，将资产ID/版本/修订/摘要/完整定义冻结进原选数证据，并加入幂等请求摘要。重复请求复用首个选集。任务可见范围取会话/来源证据与Selector资产可见范围交集，禁止把私有资产定义通过同组任务泄露。内联Selector原协议兼容。

## 实施状态

后端资产/精确版本任务引用、前端管理与选数版本入口及九项独立资源权限已编码，构建与提交证据见交接。没有运行测试、SQL或部署；Cron/周期实例、触发时创建者授权重新核定及全局预算仍未完成。

### 精确摘要与历史兼容

资产快照采用 `selector-decimal-sha256-v1`：对象字段按字典序、数组保留顺序，数字按精确BigDecimal规范化，不经Double或展开巨大指数。资产请求摘要、完整来源及选集Hash使用同一算法，选集内部definitionHash与资产contentHash一致。旧内联Selector继续旧协议，不改历史摘要；幂等重试核验是否属于同一协议、全部资产来源与首次实际IDs，不重新计算相对窗口或选数。

私有或异组资产引用会清空task.ownerGroupId，使任务对创建者私有；不会仅凭会话属于同组就共享私有资产定义。启停只影响新任务创建，已冻结任务按既有执行快照运行，不把停用当作已发远端调用的撤销。

UI沿用图形编辑器；导入时按JSON数字token比较精确十进制语义，无法用JavaScript数字往返的配置切换原JSON编辑/预览，保存通过互斥的definitionJson字符串由服务端精确解析。仅改名称不会静默舍入比例/条件值。创建、更新和即时预览仍兼容definition对象；同时提交definition与definitionJson会拒绝。服务端快照和幂等校验不依赖UI舍入结果。
