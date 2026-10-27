# 批次子任务结果写入失败与显式重试

2026-10-27，生产b163836、B基线b6174f4；只扩原MeasuredPublication隔离SQL主链，无生产源码修改。

通过实际bulk.create/queue/claim/materializeNext创建单个冻结会话分片。专用MySQL触发器只拒绝该子任务的`iqc_inspection_conversation_result` INSERT，真实InspectionExecutionService.executePersisted执行到结果落库失败；检查子任务及其执行FAILED、零会话结果，实际父tick汇总FAILED。finally移除触发器。

随后通过原生命周期queue创建父新轮次，显式调用既有queueSystem重试同一子任务，再实际executePersisted和父claim/tick。验证子及父成功、原父/子失败执行仍FAILED、子产生第二执行但不产生第二任务、原冻结输入全文与父扫描进度不变。

该流程明确需要子任务显式重试：父queue本身不自动重跑终态子。不能据此声称批次自动失败重试策略或真实模型中断已验证。

证据：原进程18319终态0，`/tmp/iqc-b-bulk-child-write-retry.log.exit=0`；fresh Surefire主方法1项136.1秒，零失败/错误/跳过。日志22:07:00.827目标执行failureRecorded=true，含注入SQL异常；22:07:01.507同一taskId的新EID成功。原父终态六组、跨JVM claim/崩溃恢复、满载停止传播、优先级、暂停恢复取消清理和发布/Finding/MVC同次回归通过。专用socket已退出，diff检查通过。

边界：这是实际执行服务处理SQL结果落库故障的证据，检测器为原受控Metric链，组织scope仍受控，非真实PDP/供应商故障/运行中模型中断/双完整scheduler或百万容量。原受控Action receipt pending警告保留。仅使用已处理会话，不含语音转换，无共享数据库/framework/push/部署；完整适用I1–I7尚未完成。
