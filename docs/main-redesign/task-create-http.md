# 任务创建 HTTP JSON 契约

真实浏览器提交四步向导时发现，MVC 使用的 Jackson 版本不能直接解析领域配置中的 `com.fasterxml.jackson.databind.JsonNode`，在进入任务校验前返回 500。原有直接调用服务层的验收未覆盖请求反序列化。

IQC 创建控制器现在接收原始 JSON，并用应用已有 ObjectMapper 解析为同一 CreateTaskRequest。引擎参数、标签映射中的 false/0/null 仍由既有领域模型和服务校验；请求字段与 OpenAPI 请求结构保持不变。解析错误和空请求返回应用参数错误。

组合任务集成验收改为通过 POST /api/iqc/tasks 创建，再执行质检与打标、验证失败后继续及冻结证据重试，并查询真实 HTTP 结果详情和列表。

浏览器已验证真实后端保存的 90 分、三类页签及布尔标签 false。本次发现并修复创建解析问题后，完整浏览器创建到执行仍需重跑；不将组件结果展示验收视为全流程通过。
