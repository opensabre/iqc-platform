# IQC Pipeline runtime v1

本模块负责冻结 Pipeline 定义执行，不读取当前资产版本，不计算业务评分，不发布业务动作。生产引擎适配器复用现有规则、模型路由和资产快照；旧 ItemRouteRunner 的四条路线保留其引用候选片段、重复轮次和裁决语义，不应直接转换成通用节点然后宣称完全等价。

## 公共契约

- `PipelineDefinition`: schemaVersion 必须为 iqc-pipeline-v1，最多 200 个节点。
- `Node`: 版本、依赖、输入映射、冻结配置、条件、超时、重试、错误政策、备用节点、输出 schema 和 enabled。
- `EngineAdapter`: `type()`, `sideEffecting()`, `execute(NodeContext)`；适配器必须遵守中断并设置实际网络连接/响应超时。所有检测节点禁止业务副作用，生产和 EVALUATION 均检查该声明。声明检查不能代替适配器代码审查。
- `PipelineRuntime(Collection<EngineAdapter>, maxWorkers, maxQueued)`：应用级共享实例，生命周期终止调用 close。
- `execute(definition, input, initialVariables, Mode)`：返回 Run，包含完整尝试轨迹、原始事实、变量、结果状态、复核标记。执行开始先验证定义。

## 顺序、条件与级联

依赖只能引用声明中前面的节点，依赖未成功时本节点记录 SKIPPED，并标记需复核。条件支持 JSON pointer 的 EXISTS、EQUALS、NOT_EQUALS、GREATER_OR_EQUAL、LESS_OR_EQUAL；缺失值不会被 NOT_EQUALS 当成业务事实。路径统一使用 `/input`、`/variables`、`/upstream`。InputMapping 是目标顶层字段到上述 JSON pointer 的映射，缺失输入失败；空映射使用原始输入。

FALLBACK 引用同一冻结定义中 disabled 节点，此节点只能在失败时调用，禁止递归 fallback，依赖必须是源节点已有依赖的子集。备用节点输出进入源节点逻辑结果，trace 保留备用节点真实身份；来源 Finding 必须使用真实适配器节点身份。备用失败会标记需复核。CONTINUE 保留失败轨迹继续独立节点；MARK_REVIEW 还标记需复核；FAIL_PIPELINE 立即终止。重试最多 3 次，节点超时最多 300 秒。

## 输出 schema 范围

schema 对 `NodeResult.variables` 整体校验，Finding 自身遵守 finding-v1 强类型契约。支持 type（object/array/string/number/integer/boolean/null）、properties、required、enum、items、布尔 additionalProperties，嵌套最多 16 层。所有未知关键字直接拒绝，当前不是完整 JSON Schema，不支持 $ref、组合、pattern、数值范围等。失败输出不得携带变量或事实。

## 有界执行与取消

所有节点使用共享固定上限线程池及有界队列，队列满返回节点 ERROR；不会逐节点创建线程池。超时取消 Future 并中断任务，超时不立即重试，避免不响应中断的底层请求被并发重复调用。调用线程中断会取消当前节点并返回 CANCELLED。

JDK 无法强制杀死忽略中断的适配器。此类节点会占用现有有界池资源，线程数量仍受上限约束；必须在 HTTP/模型客户端落实请求超时，否则无法声称释放资源。多实例全局模型预算、数据库租约/执行恢复、节点持久化和 API 鉴权由生产执行服务承担，不能由内存 runtime 代替。

## 本次范围

已提供顺序/条件/级联的通用运行时与集成契约；并行 DAG、跨进程取消和复原属于后续迭代。未新增或执行测试，由集成负责人统一编译与验收。
