# A最新动作实现的B集成验证

B8bdb26d合入A e84b120（包含8b75da1、03a9afb、f9c344a、d0a5ee8、83f5331、788a1f4）。合并范围包含只读状态查询、授权检查请求、持久化发现、回执结算独立性、实际业务复核及评测候选适配、Finding标签投影契约。A工作树保持独立，UI和组织权限仓库未在本批合并或验证。

删除B ActionSourceDiscovery/Mapper，保留A ActionProductionDiscovery作为唯一生产发现调度器。进度使用既有结果表V1.1.63的发现完成/延期UTC字段；入队与检查点同事务提交。空策略路径补充稳定负责人核对和发现完成标记，防止零动作结果永久占据扫描批次。仍由iqc.actions.enabled默认关闭。

B原Source.idempotencyKey直接SHA256及已存评分的强类型比较保留。权限资源快照复用实际HTTP iqc:action:enqueue声明，无同code时才补后台声明；不自动赋权。

定向发现测试覆盖成功时仅由入队事务完成、单条失败延期不妨碍后续、组织撤权不入队不完成。入队测试增加空策略完成/身份替换拒绝/操作授权不足入账BLOCKED_AUTHORIZATION且无尝试或回执，原幂等断言保留。

验证：完整默认Maven回归会话63707 exit0，92份有效非IT报告769项，failures/errors/skipped均0；报告刷新2026-10-10 07:51:44..07:53:43，排除已删除ActionSourceDiscoveryTest的旧报告。日志/private/tmp/iqc-b-action-a-integration-full.log。

后续仅修改独立MySQL IT夹具/断言，最终会话56456 exit0：来源/检查点10项、工作流5项、结算6项、迁移3项合计24项全部通过。实际SQL覆盖当前EID/暂停与试运行过滤、发现完成移出候选、延期UTC边界、首批100完成后可见后续结果、完成事务回滚仍可发现。迁移验证全新安装及1.1.31/1.1.44历史升级到1.1.63，校验新检查点字段及重复migrate无变化。日志/private/tmp/iqc-b-action-a-integration-mysql.log。来源发布资产查询/模型输出以及工作流来源/adapter仍有mock，不能拼成实际业务复核或评测候选完整运行证据。

生产组织/模型网络、真实复核及候选落库、UNKNOWN人工恢复、标签落库等不因源码合并而证明完成；仍需后续实际验收。此文档不宣称完整PRD完成。
