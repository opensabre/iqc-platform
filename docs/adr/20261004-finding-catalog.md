# 跨引擎 Finding 目录的存储边界

需求：PRD 9.3 规定稳定事实编码、类型、分类、可选值、严重度、证据要求和不可变历史版本；多个不同规则/模型可输出同一事实。

已检索：QualityRule/QualityRuleVersion 标识检测配置，LabelDefinition/LabelValue 标识画像字段，InspectionScoring.ItemResult 标识方案业务裁决。平台字典能维护通用枚举，无法表达事实类型/证据约束及被执行快照引用的版本语义；不得将规则、标签和 Finding 目录混为同一对象。

决定：新增 IQC 领域 FindingCatalog 与不可变 CatalogVersion；沿用现有主记录+草稿修订+发布版本指针和组织数据范围模式。原始节点观察先在现有会话轨迹中保存，不同时新增一套原始规则结果和证据主库。

取舍：增加两张领域表用于跨引擎语义标准，后续 Pipeline 发布冻结其定义，不在每个模型/规则旁复制目录。Legacy规则使用 LEGACY_RULE 命名空间，仅作为兼容输出，不自动写入新目录。字段类型一经发布不改变，同一语义的破坏性修改应新建 code。
