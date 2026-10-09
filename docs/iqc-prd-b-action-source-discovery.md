# 生产动作来源发现入口

B6aae9b2后实现ActionSourceDiscovery与ActionSourceDiscoveryMapper，复用稳定身份解析、scheduled actor scope及原enqueue短事务，不新增表、账本或检测保存路径。

与现有dispatcher共用iqc.actions.enabled（默认关闭）；source-scan-delay-ms默认5000。每tick最多100个轻量来源引用，查询仅current execution的PIPELINE结果，排除暂停/取消/试运行；任务和执行状态限定现有可核验状态。随后逐条读取任务快照的actionOwnerBound，在事务外resolve当前固定grant，并在actor scope内调用enqueue；源码现在有真实入队调用者。最终来源/范围/条件/幂等仍由原服务重新核验。

按结果主键keyset循环扫描，所有条目包括无bound/解析错误/授权不可用都推进；末尾下一轮从头复查。游标不是处理完成记录，也不授权发送：进程重启丢弃游标后安全重放，由现有唯一账本与requireSame排重，多实例各自扫描也依赖原事务/幂等。新插入较小key在下一轮被发现。当前没有持久游标或逐来源失败表，失败记录为仅resultId日志；人工可见失败/恢复与持续增长下容量仍未验收。不是exactly-once发现承诺。

三项服务测试：首100无身份不阻塞第101；单条失败继续下一来源；末尾重放。实际MySQL两项新SQL覆盖exclusive keyset、current execution、owner映射与暂停/试运行排除，复用此前五项真实来源回归。定向3项Maven exit0，独立MySQL来源/发现7项exit0，均0 failures/errors/skipped；日志 `/private/tmp/iqc-b-action-discovery-tests.log`、`/private/tmp/iqc-b-action-discovery-mysql.log`。完整默认回归exit0、92报告/764项0 failures/errors/skipped，所有默认报告本次22:06:37..22:09:59刷新，日志 `/private/tmp/iqc-b-action-discovery-full.log`。diffcheck通过，临时MySQL正常shutdown且服务exit0，无推送/合入A/部署。

后续注册快照代码已声明后台能力（见[资源声明](iqc-prd-b-action-resource-registration.md)），真实组织发布/授权未执行；组织未授予iqc:action:enqueue时真实解析拒绝；未开启动作开关、未真实组织调用或发送。不得因新增发现代码称整条生产链完成。A查询/controller草稿仍由A处理，framework不改。完整PRD继续。
