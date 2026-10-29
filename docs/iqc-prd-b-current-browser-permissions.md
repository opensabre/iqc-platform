# 当前组合的权限与浏览器检查

后端基线b71dbee，前端af67247。处理后会话输入，不含语音转换。

## 签名HTTP与组织范围SQL

原18398 terminal0，`/tmp/iqc-b-current-signed-role-sql.log`，LabelReviewQueueHttpTest当前11项零失败错误跳过，54.826s。真实本机随机端口、框架签名令牌校验与H2/MyBatis查询；涵盖本人/同组/外组、分页前范围、申请人不是权限依据、当前授权撤销、异常组织/身份、方案历史与恢复入口。组织授权服务、限流为受控mock，不是真实PDP/网关/审计collector。

## 实际浏览器

已构建前端在127.0.0.1:43179预览，缓存Playwright CLI专用会话iqc-b-current-review：

- 无可用身份服务时访问quality-operations，安全跳转login并保留redirect。预览代理无后端有500日志，该检查仅证明认证加载失败时关闭入口，不代表真实登录成功。
- 注入测试用户和空菜单，访问相同路径进入403，显示暂无访问权限。
- 注入仅iqc:review:view菜单，可进入原质量运营页；点击实际标签复核页签显示没有查看标签复核的权限，不以通用权限替代独立标签权限。

浏览器网络响应由Playwright隔离注入，不访问共享服务、不提交业务数据，不将此与上述签名SQL测试拼接声称真实角色端到端通过。CLI wrapper离线缺@playwright/cli缓存，改用已缓存playwright-core自带同名CLI；最初run-code回调参数错误后修正，未改应用。

UI工作树output/playwright保留review-anonymous-login.yml、review-label-denied.yml、review-permissions-network.txt。浏览器已关闭，本机preview原40090收到Ctrl-C退出1（主动关闭）。完整四角色登录、真实PDP/组织撤权、有效复核裁决/审计与浏览器联调仍待，I1–I7目标不变。
