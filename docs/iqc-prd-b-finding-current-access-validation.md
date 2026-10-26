# Finding报告当前授权组合验收

2026-10-26新增BusinessFindingCurrentAccessTest，真实IqcDataScope、ScheduledActorAccessService.resolveCurrent、SensitiveReadAccessService与BusinessQualityReportService组合。组织Feign返回证据/业务mapper受控，不mock授权方法。

- 当前report/full conversation/PII任一grant撤销均在业务任务读取前拒绝，即使ADMIN也不绕过。
- 同组且grant齐备可读；不同组拒绝。较宽当前角色不能扩大原上下文，原all撤销或当前组变动使范围不一致时拒绝。
- 证据过期/未来、账号停用、稳定身份或用户名不符在业务IO前拒绝。每次读取重新查询当前grant；恢复须使用新证据，不缓存旧允许。
- 当前用户路径使用currentActionAccess而非后台schedulerAccess，原用户上下文保留。

原25725终态1：新增夹具嵌套Mockito设桩造成13初始化错误，业务断言尚未运行；拆开返回对象创建与设桩，不改生产。原1210重跑terminal0，/tmp/iqc-b-finding-current-access-final.log.exit=0；fresh XML新组合13+分布11+Actor9+Scope4，共37项零失败错误跳过。

组织返回仍受控，不等于实际远端PDP、签名传输、实时撤权或五类岗位整链通过。现有seed的IQC_ADMIN/IQC_QUALITY_MANAGER/IQC_INSPECTOR/IQC_VIEWER四角色与PRD五岗位职责须独立核对，不凭名称新增角色或宣称映射完成。未修改生产/framework/共享SQL或部署。完整适用I1–I7继续。
