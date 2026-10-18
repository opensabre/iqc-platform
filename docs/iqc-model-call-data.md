# 可选模型调用诊断正文归档

## 复用与范围

PRD V1.0 §23要求模型调用数据可配置是否落盘。已有GatewayCall保存调用身份、预算、容量、用量和费用，不含正文；本实现扩展同一账本，不另建调用日志表。复用IqcModelProfileService已使用的StringEncryptor，不新增加密算法或framework修改。

正文是独立诊断副本，不参与评分、Finding、冻结输入、执行恢复、请求幂等或账单。关闭归档不会停止必要的用量/预算/并发账本，也不删除业务冻结证据。

## 配置

```yaml
iqc:
  llm:
    call-data:
      enabled: false
      max-bytes-per-body: 65536
      retention-hours: 24
      purge-delay-millis: 60000
```

默认关闭；关闭时不调用诊断序列化器。大小上限为每个正文的UTF-8字节数，允许1至1048576；保留时间允许1至720小时。配置非法、加密器或数据库不可用时，归档失败仅记录callId/异常类型，不输出原文、不自动重发模型调用、不改原结算结果。归档仍是同步附加事务，可能增加延迟，容量和延迟需实际测量。

## 记录与清理

原GatewayCall新增加密请求/响应、状态、UTC记录时间与UTC过期时间，DDL V1.1.73。两个正文均在应用内加密后写库，实体字段JsonIgnore且不进入CallView。仅按原handle的callId/modelKey且未记录过时写入一次，不覆盖既有归档。

状态CAPTURED表示双侧内容，REQUEST_ONLY/RESPONSE_ONLY明确缺失侧；SIZE_LIMIT不保存任何一侧正文，不静默截断。调用失败仅可保存请求，不保存任意异常消息。模型响应成功后归档，失败后亦尝试请求归档；归档事务独立于原结算，异常被隔离，不为归档重发模型请求。

既有Spring scheduler每次最多清除200条过期正文并置EXPIRED，保留调用及用量事实。即使之后关闭归档也继续清理旧归档；清理有调度间隔和积压延迟，不承诺精确到点物理删除。恢复备份与密钥轮换遵循原加密器/运维策略，尚未新增自动密钥版本管理。

## 接入内容

- HTTP provider：发送的JSON body和收到的JSON response；不含Authorization、API key或其它HTTP headers。
- Spring AI router：FRAMEWORK_MESSAGES（角色、文本、工具调用/工具响应）和FRAMEWORK_GENERATIONS（文本及工具调用）；包括显式工具续调各次模型请求。不宣称完整SDK传输报文，模型选项、headers、媒体流和框架隐藏内部调用不在该副本中。
- gateway缺省旧构造/直接兼容调用不具备归档服务；生产DI使用新增服务，旧接口/构造保留，未提供正文序列化器的调用仅保留原元数据账本。

已有独立受控诊断读取接口及任务调用列表入口，见下节；普通调用日志列表仍仅投影元数据/人工凭据，不返回密文或正文。

## 当前证据与待验收

仅离线skipTests test-compile通过，未新增/运行测试、SQL、真实供应商或加密数据库验证。新DDL未执行，部署前需按原迁移流程集成。待验证默认off无序列化/无正文写、开关on密文及精确正文、UTF-8限额、追加一次/失败隔离、工具续调来源、TTL关闭后清理、并发和结算不变。此实现不代表完整I7或PRD已验收。


## 独立受控读取

GET /api/iqc/tasks/{id}/model-calls/{callId}/diagnostics使用独立资源iqc:task:model-call-data:view，先刷新调用日志查看、诊断读取、Prompt当前基础授权，要求FULL/PII/Prompt齐全，原Task.get验证范围，再按taskId/callId查原调用并解密。旧缺组件构造拒绝，成功no-store/nosniff，审计只有任务/调用ID且无正文，限流30/60。配置关闭后仍可在有效期内读取之前的归档。

加密内容为iqc-model-call-data-v1信封，绑定callId/modelKey/REQUEST或RESPONSE及原body字符串。解密后严格核对身份/方向/文本类型/大小，不回退裸正文；不匹配、密钥不可用、非法状态或损坏返回UNREADABLE且两侧正文均空。当前归档代码尚未部署，未增加旧裸密文兼容；该绑定不是新增加密算法或独立的防篡改签名协议。

读取前及解密完成后均检查UTC过期，不依赖物理清理已完成。NOT_RECORDED/EXPIRED/SIZE_LIMIT/UNREADABLE与REQUEST_ONLY/RESPONSE_ONLY/CAPTURED明确区分，不把缺失正文视作模型空输出。普通调用事实、用量和评分不因读取状态改变。

UI仅在任务调用列表显示独立授权按钮；切换任务、重新加载、关闭或卸载会清除正文并失效旧请求，未知协议或不匹配来源不显示正文。JSON按原字符串显示，避免数值舍入，不回写为业务产物。

组织迁移V20261018_02仅本地草案，资源/按钮900264挂任务900103，默认仅IQC_ADMIN/IQC_QUALITY_MANAGER；不隐含其它敏感grant。SQL尚未执行。当前仅编译/构建，权限、真实加密/DB、跨调用与请求响应调换、到期竞争和浏览器交互验收仍待。
