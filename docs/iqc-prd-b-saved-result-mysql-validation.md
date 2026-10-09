# B：真实 SAVE_RESULT 适配器持久化验收

基于75e0a13，新增IsolatedMysqlSavedResultAdapterIT，复用隔离MySQL身份检查和迁移；实际SavedResultActionAdapter与ConversationInspectionResultMapper，无适配器/结果仓储mock。

五项范围：真实持久化PIPELINE结果重复两次确认成功，receipt证据和输出为既有resultId，所有数据库列调用前后相同且仅一行；缺失结果UNKNOWN；冻结轨迹变化UNKNOWN；保存的canonical finalScore与scoring JSON不一致UNKNOWN；归属外部execution UNKNOWN。所有UNKNOWN均无确认凭证/输出引用，不伪装远端失败。

此测试固定结果/冻结快照以单独验证保存确认适配器，不运行完整生产Pipeline/Fusion/SourceValidator，也不证明组织权限或其他八类外部适配。成功只证明现存保存产物确认，不能泛化为完整生产动作调用链成功。

实例127.0.0.1:34307、/private/tmp/iqc-mysql-validation.FVGdkd，与共享业务库隔离。仅新增IT，生产/A/framework不改，首次五errors在fixture insert必填reason遗漏，补齐合法结果数据，未改SQL约束或适配校验；首轮日志 `/private/tmp/iqc-b-saved-result-mysql.log`。最终复跑Maven exit0、5项0 failures/errors/skipped，本次独立Surefire报告核对通过；日志 `/private/tmp/iqc-b-saved-result-mysql-verified.log`。临时MySQL正常shutdown且进程exit0；diffcheck通过，未推送/合入A/部署；仅新增独立IT不重复默认761套件。完整非实时语音PRD与真实生产来源验收继续。
