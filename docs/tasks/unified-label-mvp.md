# 统一标签识别 MVP

> 以下记录初次 MVP 范围；用户已确认继续开发。后续 P1 迁移与动作能力见 [P1 增量](unified-label-p1.md)，不能将初始 MVP 的“无新增迁移”用于当前分支发布。

本版本依据工作区 `IQC_统一规则标签任务执行框架_PRD_V1.0.md`，先交付可本地验证的匹配闭环。它不是 P1～P5 全量交付，也不改变旧任务的评分、路线及标签值语义。

## 已实现

- 复用现有规则、规则版本、标签和标签绑定，不增加规则库或标签字典。
- 任务配置 REGEX / DLS / LLM 的唯一、有序阶段；按既有标签绑定解析适用规则。
- 引擎每阶段输出一份多标签结果；每会话独立输入，阶段结束后使用既有结果集存储追加检查点。
- `MATCHED / NOT_MATCHED / ERROR / UNKNOWN / SKIPPED` 与阶段 `SUCCESS / PARTIAL_SUCCESS / ERROR / SKIPPED` 保存在原始阶段 JSON。外层结果集沿用既有状态名称 `SUCCEEDED / PARTIAL_SUCCESS / FAILED / SKIPPED`。
- 正则复用按消息及规则角色匹配，DLS 复用原有完整会话语义；不擅自增加跨消息正则拼接。
- LLM 仅排除上游成功或部分成功阶段中明确 MATCHED 的标签；NOT_MATCHED、ERROR、UNKNOWN、SKIPPED 不用于过滤。每个剩余标签集合一次批量请求，正常成功路径没有逐标签调用。
- 无适用标签或无剩余 LLM 标签时记录 SKIPPED，模型调用为零。
- 请求/响应校验区分缺项、重复、越界 ID 和非法状态；错误不解释为未命中。响应中未请求标签不会成为有效观察。
- 继续复用现有 Spring AI 模型路由、重试、限流和用量设施。此处是 IQC 现有内嵌 Agent 能力，不宣称完成一个尚未提供接口的独立 Agent Platform 服务。
- 统一结果处置输出 `PENDING_POLICY` 并引用全部引擎结果；不自动生成正式标签、评分或冲突结论。
- 重试保留原输入及首个失败阶段之前的成功/跳过前缀，重新执行其后的阶段，避免重用依据旧上游结果过滤的 LLM 输出。

## 使用方式

任务中心 → 创建任务 → 数据筛选 → 任务类型 → 选择“统一标签识别 MVP”。选择已发布标签，排列引擎顺序；使用 LLM 时选择有权限的已发布智能体。结果处理步骤明确显示待处置。创建后沿用任务列表的执行按钮。

任务详情显示冻结的引擎和标签；运行详情默认进入引擎记录，可查看标签状态、证据、过滤前后数量、是否调用模型、耗时及提供方返回的 Token。

接口复用任务控制器、任务权限和执行生命周期：

```http
POST /api/iqc/tasks/unified
Content-Type: application/json
```

```json
{
  "name": "退款标签识别",
  "dataSelection": {"mode": "BATCH", "conversationIds": ["现有会话ID"]},
  "configuration": {
    "engineSequence": ["REGEX", "DLS", "LLM"],
    "tagSelectionMode": "SELECTED",
    "selectedTagIds": ["现有已发布标签ID"],
    "agentId": "现有已发布智能体ID"
  },
  "concurrencyLimit": 1
}
```

- `ALL` 时 selectedTagIds 必须为空；本 MVP 在创建时解析并冻结当前有权限的已发布标签。
- 现有 RANDOM / FILTER 数据选择可复用；预览种子不允许作为创建参数。
- 一个标签同类型存在多条绑定时明确拒绝统一任务，不挑选第一条或最新一条；旧标签绑定需要人工调整。
- 旧关键词、结构化等非 REGEX / DLS / LLM 绑定明确拒绝进入统一模式，不自动转换。
- 请求最多 500 标签，LLM 请求最多 150000 字符，Agent 指令及 Skill 合计最多 8000 字符；超限失败，不拆批、不截断。提供方实际上下文上限仍可能更低。
- 标签规则使用绑定的已发布精确版本；当前标签和 Agent 改动不影响已创建任务快照。

## 明确保留到后续阶段

- P1：完整 Action 实体/复用定义与绑定管理、数据库每类型唯一约束、存量重复绑定迁移及删除/停用引用完善。
- ID：本 MVP 没有新增持久化实体，复用现有 String/VARCHAR 标识和结果表；Long/BIGINT 存量迁移未执行，不能称为已满足 PRD 的 ID 统一要求。
- P2：周期定义、定时触发与任意选数组合、每次运行独立冻结标签而非创建时冻结、全路径流式工作项加载。当前是既有立即执行任务闭环。
- P4：正式标签落库、Action 执行、扣分、冲突处置。MVP 只保存配置引用与完整匹配证据并明确待处置，不套用现有 REQUIRE_AGREEMENT 政策。
- P5：独立 Agent Platform 联调、真实模型及业务样本验收、MySQL 生产兼容与大数据量测试、10/100/500 标签真实性能对比、灰度及回滚演练。

不新增数据库迁移、不修改框架或共享服务。运行环境必须已具备当前主干的 `V1.1.33` 引擎结果表与其他既有迁移；不代表已核实生产部署状态。新协议任务不能在不认识该协议的旧版本上运行，回退应用前需停止新任务接收和执行，保留结果数据。

## 本地验证

后端使用 Java 21，测试环境禁止动态挂载时显式加载本地 Mockito agent：

```sh
mvn -o -DforkCount=1 -DargLine=-javaagent:/本地仓库/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
  -Dtest=UnifiedTaskLifecycleTest,UnifiedTagEngineAdapterTest,UnifiedLabelPlanServiceTest,SpringAiLlmQualityProviderTest,MultiEngineExecutorTest,EngineExecutionResultSetTest,EngineResultSetStoreTest,TaskEnginePlanServiceTest,ConfiguredTaskResultProcessorTest,TaskWizardResultDetailTest,InspectionTaskServiceTest,InspectionTaskControllerTest test
```

前端：

```sh
pnpm test -- src/components/TaskCreateWizard.spec.ts src/components/TaskWizardResults.test.ts
pnpm build
```

这些测试不能替代真实模型、真实 MySQL 和已部署后端的端到端验收。首次人工评审前不推送、不创建 PR、不部署。

本地验证结果（2026-11-14）：后端针对性回归 86 项通过，补充 H2 检查点与恢复链路测试 1 项通过；前端创建与结果展示 10 项通过。前端类型检查与生产构建通过。后端测试覆盖现有任务、旧结果处理及新批量模式；未进行真实模型、MySQL 或部署环境验收。

## 人工验收清单（待执行）

以下为验收步骤，不表示已在真实环境验证。使用有权限的测试会话、已发布标签和已发布 Agent；先核对环境已应用既有数据库迁移。

| 场景 | 操作与预期 | 当前自动化证据 |
| --- | --- | --- |
| 绑定版本冻结 | 创建选定标签的任务，核对详情中的规则版本和引擎顺序；随后修改配置不应改变该任务快照 | `UnifiedLabelPlanServiceTest` 精确绑定版本及顺序测试；修改配置后的真实运行待验 |
| 正则命中后过滤 | 选择 REGEX → LLM；正则命中的标签应保留原始证据，并从 LLM 待分析集合移除 | `UnifiedTagEngineAdapterTest` 上游命中过滤与批量调用测试 |
| 全部命中 | 所有适用 LLM 标签已被上游命中时，LLM 阶段显示 SKIPPED、模型未调用 | `emptyPendingSetSkipsModel`；H2 链路测试 |
| LLM 优先 | 选择 LLM → REGEX，LLM 应分析全部适用标签；其他会话不继承前一会话的过滤集合 | `llmFirstEvaluatesEveryApplicableTagAndAnotherConversationHasNoUpstream` |
| 异常响应 | 在测试提供方返回重复、缺失或越界标签，观察 ERROR / PARTIAL_SUCCESS；不能显示为 NOT_MATCHED | `malformedDuplicateMissingAndUnexpectedTagsNeverBecomeMisses`；真实提供方待验 |
| 恢复与结果保存 | 中断后恢复同次执行，已完成阶段不重复调用；查看阶段记录及待处置提示，不生成正式标签或分数 | `UnifiedTaskLifecycleTest` 使用 H2 存储验证检查点恢复及待处置结果；真实进程中断待验 |
| 旧任务兼容 | 创建旧模式任务并查看历史结果，确认原有配置、评分和证据展示 | 既有任务与处理器回归、前端旧模式测试；真实环境待验 |

验收确认后才进入 PR 与发布流程。该确认不等同于决定扣分或冲突策略，也不自动批准修改框架或共享服务。后续 P1～P5 仍按上面的未完成范围继续，不能以 MVP 测试通过替代全量 PRD 验收。
