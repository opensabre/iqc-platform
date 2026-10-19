# 当前权限投影组合的默认全量回归

2026-10-19，生产基线39b9411，加本批JointLabelMaterializationTransactionTest夹具修正。未集成另一工作树未提交的Selector资产正文增量；当前整体PRD仍在实施中。

## 修正内容

默认全套实际HTTP测试需要本机临时端口。原51583在受限沙箱退出1：1344项、0失败、67错误，已有SocketException绑定失败以及Spring上下文连带错误。原58243获本机端口权限复跑，退出1：1353项、4失败、0错误。差异来自实际HTTP/动态测试的执行，不能把两轮项数相加。

四失败均旧HTTP流程夹具缺少当前原任务/方案基础权限，并期待任务命令回执返回冻结正文。本批仅修测试：组织mock返回流程实际需要的七个固定基础资源，不授FULL/PII/Prompt；保留跨组、匿名及脱敏断言。任务写回执检查ruleSnapshot隐藏；冻结定义、内容hash、标签依赖和策略变体完整性从真实H2持久化任务读取，业务流程和幂等断言不删。没有改变生产授权/响应/任务语义或框架。

原79532单类复验退出0，49项零失败/错误/跳过，日志`/tmp/iqc-joint-http-current-privacy.log`。主源及编译产物有效，夹具迭代使用compiler:testCompile surefire:test。

## 默认全量证据

原42828完整默认`mvn -o -q ... test`退出0，日志`/tmp/iqc-current-full-privacy-fixture-final.log`。按日志创建时间过滤Surefire XML：172份本轮报告，共1353项，failures/errors/skipped均0；无旧的非IT报告，未计入此前隔离MySQL的IT报告。覆盖本地应用、签名HTTP、H2事务与原默认测试，不代表真实组织角色/PDP或供应商环境。

退出清理仍有未解决告警：System.exit(0)后30秒Surefire清理fork JVM，生成`2026-10-19T12-51-04_848-jvmRun1.dump`及dumpstream。dump显示main等待ApplicationShutdownHooks，SpringApplicationShutdownHook及多个容器/线程仍在；尚未定位具体阻塞组件，不能宣称测试资源完全正常释放。未通过放大退出超时/删除测试掩盖此问题，也未未经批准修改框架。

## 剩余范围

真实PDP/角色、浏览器迟到响应和权限变化交互、供应商环境以及完整PRD逐项验收仍待；Selector资产草稿/历史/Prepared后续增量待提交后集成验证。迁移检查器对V1.1.64触发器INSERT的误报另待修正。输入为已处理会话，无语音转换工作；无push、部署或共享数据库变更。
