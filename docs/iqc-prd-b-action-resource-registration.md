# 后台动作入队能力资源声明

在891ed55生产发现基础上，IqcResourceRegistrationListener复用原完整资源快照/治理协调器。若已有HTTP映射声明iqc:action:enqueue，则保留原声明；若缺少，则补充真实ActionEnqueueService.enqueue后台能力：code=iqc:action:enqueue，method=BACKGROUND，url=/internal/iqc/actions/enqueue，handler为实际服务方法，declaredPermission=true。

BACKGROUND不是HTTP方法，该地址没有新增Controller映射。现有base-organization ResourceRegistrationService只要求method/url非空，按code识别资源；后台ScheduledActorAccessService以resourceCodes判断当前授权。无需修改framework、组织或网关；不是假API，也不是另建注册系统。已有HTTP映射快照原样保留，使用同code时不追加BACKGROUND资源，避免重复注册和覆盖真实入口。此兼容边界针对A d0a5ee8新增的POST /api/iqc/conversation-results/{id}/actions/enqueue；未合入A其他源码，测试模拟其资源快照。

仅注册能力，不调用角色/用户绑定接口，不自动授予账号权限。仍需管理员通过已有组织授权流程明确授予负责人固定的三个grant；后台每次解析当前组织证据，并与冻结稳定userId/范围核对，撤权仍拒绝。开关保持默认关闭，未实际向共享组织发布资源、未开启动作、未部署。

验证：旧基线完整快照含原HTTP资源+BACKGROUND能力，新HTTP声明原对象保留且无重复，缺注册token在组织调用前拒绝；组织client模拟，不证明真实组织落库或负责人获得权限。兼容修正后完整默认Maven回归exit0，92份非IT报告合计766项，failures/errors/skipped均为0，报告全部刷新于2026-10-10本次运行；资源注册三项覆盖旧快照补声明、现有HTTP声明复用及缺token拒绝。日志：/private/tmp/iqc-b-action-resource-compatible-full.log。未在本批复跑独立MySQL IT。

与发现/SQL测试一起仍不能替代实际组织授权→生产发现→真实来源→执行的完整运行验收，人工核对/尝试历史/其他真实适配继续。完整非实时语音PRD未完成。

集成约束：A f9c344a已增加持久化检查点发现器ActionProductionDiscovery，B891ed55包含内存游标ActionSourceDiscovery。后续合并应选择单一发现器并验证A的检查点迁移，不能同时启用两套调度。本批仅修复注册兼容性，未将A的新入口、适配器或发现器合入B。
