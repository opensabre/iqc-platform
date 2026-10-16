# IQC 敏感读取投影实施调查

依据PRD18.3/19，所有输入均为处理好的会话；语音转换/ASR/解码/转写生成不在范围。敏感读取与数据归属是两层约束：任务/会话scope不能替代完整会话、客户PII、Prompt敏感权限。

## 已有能力与复用边界

- Framework已有SensitiveRule/DefaultSensitiveRule，提供姓名/证件/银行卡/邮箱/手机等pattern与替换方法；REST DesensitizationSerialize仅按字段注解序列化，当前源码依赖Jackson3，IQC事实/快照大量使用Jackson2及JSON字符串。不能只贴注解就声称嵌套Finding/审计快照已脱敏。
- 原RegxLogBackDesensitizer是ILoggingEvent日志插件，不能作为REST权限判定器。应用可复用同一规则的pattern/replace处理只读字符串投影，不能复制另一套PII正则或修改不可变原始事实。
- IQC ConversationAudioController已有独立敏感resource+ScheduledActorAccessService当前授权核验+原scope的接法，可供读取权限判定复用；不恢复音频转换范围。
- 当前FindingContext、复核历史JSON、结果Trace与导出可包含原文/事实值/证据和原因。仅保护主会话接口不能覆盖这些副本；必须清点所有读取面及审计response，不能靠前端遮挡保护已发送字段。

## 下一步实施规则

1. 清点结果详情、会话消息、Finding上下文/历史、样本/金标、版本/Agent/模型调用与导出；先区分结构身份与可含PII的内容叶子。
2. 原scope先授权，敏感权限用原当前授权服务检查；未知/过期授权不得输出原文。资源与组织角色沿既有权限仓库注册，不从ADMIN名称或canViewAll推断敏感授权。
3. 脱敏只作用于返回DTO/复制的JSON树/下载输出，不写回raw、Task快照、Finding、hash、人工裁决或金标。身份/hash/枚举/数值结构不盲目替换。
4. 明确返回redacted标记；编辑场景中若事实值被脱敏，应禁止把脱敏默认值当原值提交，或要求独立原文编辑权限，避免误写星号。
5. 普通审计日志禁录敏感请求/响应payload；只保留操作人、来源ID、hash及变更身份。Prompt与模型原文日志单独权限，业务PII权限不自动授权Prompt。
6. 复用框架规则无法覆盖的已记录需求再讨论应用扩展；任何framework源码修改须先说明影响并获得用户明确批准。本调查未修改framework。

本页为实施调查，不代表敏感权限/脱敏已实现。Wiki access-control页为seeded，已继续读取raw/project-docs/base-organization/modules/organization-and-access.md（目录为指向owning docs的符号链接；普通rg --files不会跟随，不能据此判断资料缺失）。以上判断同时参考owning源码、注解与原权限实现。完整I1–I7、真实权限/数据库/API/浏览器验收继续。

## 首批审计正文最小化

已核对原Audit.request默认true、response默认false，AuditAspect将args整体序列化到审计request，且已有key SpEL会在调用前计算目标键。QualityOperationsController的标签/业务/消息复核、反馈与候选创建六个入口，以及GoldSampleController标注/审核，现显式request=false/response=false并用既有path ID作为key；不改变权限、裁决参数或原业务持久记录。

ConversationImportController单条/批量已处理会话API接入显式关闭审计请求/响应正文，txt/ZIP入口原已关闭request。审计事件仍有操作人、时间、路径、方法、模块和结果状态/错误，已有来源ID的操作新增目标key。接入新会话的结果ID在方法执行前尚不存在，当前framework key不支持从result取值，此处不伪造ID，原会话来源/创建记录继续存在。

此批只处理三个已确认入口文件，不代表所有Prompt/模型调用日志、HTTP日志或全部读取副本已覆盖；敏感读取权限/PII投影仍待后续。未改framework；Root只编译，具体注解/审计事件回归待B按其授权验证。

## 配置与发布快照的审计正文

队列最终集成0a6b285离线skipTests test-compile原75481已terminal exit0。继续核查PipelineAssetController发布/例外发布原显式response=true，返回PipelineAssetVersion包含snapshotJson和publicationDecisionJson；create/update请求可含完整节点配置。IqcSkillController create/update请求包含instructions正文，默认request=true。

这六个入口现在显式关闭request/response采集，已有流水线/Skill path ID保留或加入目标key，原资源权限/门禁/管理员校验不改。发布原因、操作者、时间和门禁证据仍由PipelineAssetService写入不可变publicationDecisionJson及publicationDecisionHash，不删除或改写；只是避免在普通审计流重复完整快照/提示正文。创建资产的结果ID在当前Aspect调用前key阶段尚不存在，不伪造新ID；原创建记录和操作审计仍保留。

该批尚待编译与B注解/事件回归。其他模型调用日志、配置详情读取、全部敏感副本仍需按前述范围接入独立权限/只读脱敏，不能将此当完整I7完成。未修改framework、未运行Root测试/SQL/部署。
