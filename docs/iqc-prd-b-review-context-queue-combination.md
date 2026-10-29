# 复核上下文与队列组合验证

## 变更

在B组合基线3e3c475合并A96a8661（含455e108）。复用既有responseAccess，修正自动合并后两处readAccess旧名称。FindingReviewContextRead.messages改用响应专用List<Map>：默认Jackson 3 MVC原将Jackson 2 JsonNode序列化成节点bean属性，前端收不到数组；内部FindingContext、冻结消息、数值字符串及hash不变。无框架修改。

普通业务队列和标签队列保留原分页及组织范围查询；敏感正文需要完整会话和PII同时具备，Finding上下文不额外要求Prompt权限。新HTTP测试覆盖8敏感grant组合、精确事实值字符串、源对象序列化不变、四种阶段、分页总量、标签结果新旧状态及当前授权拒绝先于业务查询。

前端验证分支a2b90f3合并A4455aa2，标签queue/history/create/decide使用原独立code。旧测试夹具对齐既有权限契约，新增通用复核grant不可替代标签grant、queue/history独立、点击前撤权及不确定提交重试前撤权检查。

## 验证

- UI原40301 terminal0：2文件14项通过；/tmp/iqc-b-label-grant-ui-tests.log。
- UI原15596 terminal0：vue-tsc及Vite通过，Vite51.49s；/tmp/iqc-b-label-grant-ui-build.log。保留既有chunk提示及Node/pnpm声明版本差异，不称声明环境验证。
- 后端原74107 terminal1：完整main/test编译成功，42项9失败5错误；8项暴露消息序列化问题，6项旧controller夹具未注入授权依赖。未放宽生产fail-closed。
- 修正后原36503 terminal1：完整main/test编译，8套件42项全部通过；另1套件因沙箱禁止Tomcat端口启动失败。/tmp/iqc-b-review-combined-http-final.log。
- 原11017允许本地端口后terminal1：签名H2套件11项2失败，旧fixture缺scheme:manage且旧组织查询失败仍允许owner读取预期过时。只修fixture/预期，不改生产授权。
- 最终原13172 terminal0：compiler:testCompile/surefire，LabelReviewQueueHttpTest 11项全部通过；/tmp/iqc-b-review-signed-sql-terminal.log。生产源与36503完整编译一致。
- 两批9套件53项零失败、错误、跳过：ReviewContextQueueHttpPrivacy13、ResultReadProjection6、BusinessReviewController10、ReviewWorkflow4、StoredPipelineReviewReplay1、ExactFindingLabelRead2、ActionSourceContext3、ExactPipelineExecutionInput3、LabelReviewQueueHttp11。

## 证据边界

签名套件使用真实入站身份、当前授权服务、HTTP和H2组织范围SQL，组织服务为受控mock；不代表真实网关/PDP或四角色浏览器。其最小H2 schema有后台model-call/audit相关表缺失日志，本批不据此宣称后台持久化或审计收集通过。无共享数据库、迁移、push、部署或默认v2切换。

复核历史/命令回执、评分原因、实际审计收集仍由后续组合验收覆盖；完整实际发布→物化→worker→复核重算→动作链路未完成。适用I1–I7保持active，单租户，输入为已处理会话，排除语音转换。
