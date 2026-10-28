# 全 Controller 资源注册与 91 项权限契约独立验证

2026-10-29，生产基线 c6cd2a2，B 合并提交 d20d632。输入为已处理会话，不含语音转换或转写开发。

## 结果

原进程 57942 terminal 0，`/tmp/iqc-b-all-controller-permissions-20261029.log.exit` 为 0。离线 `mvn test-compile surefire:test`，选择 EndpointPermissionSplitTest、QualityConfigControllerPermissionTest，使用本机 ByteBuddy javaagent。报告分别为 52 项 / 22.357s 和 1 项 / 0.505s，失败、错误、跳过均为零。

- 独立固定清单 91 项，16 个真实 Controller 经 Spring MVC 与框架 MappingInfoHandler 注册，逐项检查权限码唯一、HTTP 方法、URL 与显式声明。原 label:approve 仍唯一对应 POST /api/iqc/labels/{id}/publish。
- 扫描 io.github.opensabre.iqc 下全部 RestController，实例化实际类并注册真实 MVC 映射。实际发现 42 个 Controller、242 条资源映射，每个资源码仅对应一条映射；覆盖常量声明，避免只靠源码文本搜索。
- 50 项敏感入口拒绝用例逐项验证当前授权必须使用准确操作码，撤权时不得进入业务服务；同轮保留 QualityConfig 原权限专项。

## 边界

Controller 构造依赖使用受控 mock。全包注册不等同应用条件 Bean 激活，不运行真实登录、网关/PDP、业务成功写入或数据库资源注册 upsert；50 项拒绝检查不代表其余入口已经获得同等行为验证。组织七迁移/91 契约数据库证据另见组织仓库报告，不能拼接宣称端到端完成。

真实五职责、跨群组、跨服务当前授权、任务页/菜单、业务 A–E、外部供应商、监控采集与百万容量等适用 I1–I7 继续推进。
