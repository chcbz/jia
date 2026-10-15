# 接口目录、逐接口契约与复查结果

最新续查：2026-10-15（源码基线日期仍为2026-10-14）；任务`JYT-SKILL-SE2-20261013`；Owner：本聊天主Agent。**仅文档/只读源码分析，无业务代码、配置、数据库或服务改动。**

## 当前实现收口（2026-10-16）

下方统计、开发前核对、审计JSON及分包清单是2026-10-14/15原始设计证据，不是当前待办或线上接口全集。公共规范1.2及原始契约文件未重写。

本轮已发布API1.16.5 `6116a10403e0df250850e6ebb73f8123f5ac9ded`、Web1.16.1 `421f628c245b83466cafb70510ff69188180bdee`、Runtime1.2.7 `6a9d8d3c1219450c19b3edfbef317e615940ab5d`。API正式362 tests通过；Runtime985+221 tests通过；Web使用Flow4403172/213。规范实现的稳定说明回写根仓库`docs/knowledge-base/06-juyiting-end-to-end.md#image-skill-contract`；实际验收见`specs/juyiting-skill-execution/acceptance.md`。

已确认最后修复：取消请求版本使用规范十进制字符串；认证Receipt显式投影后统一runtimeInstallationId/sessionGeneration；Runtime将技能语义版本与Long状态版本区分。第三方回调协议不变。本次图片技能已真实调用/归档/下载，但OCR两次误读；不将部署或抽样成功扩写成所有596条接口的逐项线上验收通过。

## 1. 从哪里看

- [开发前源码核对](predevelopment-review-20261014.md)：本轮实际返回/调用/重放入口、已确定处理、前置验收及未闭合项；[机器核对表](predevelopment-audit.json)明确开工状态，不以扫描代替对齐。

- [统一公共规范1.2](../interface-standard.md)：所有接口共享，不复制各模块方言。
- [代码字段级详设](../interface-standard-detailed-design.md)：具体类/方法/字段迁移。
- [关键链逐接口契约](critical-contracts.md)：Runtime、个人资料、资料关联、技能商城、议事/结果、执行/副作用、退役写入口及UDS/WS/SSE。
- [一次性改造核实与澄清](implementation-readiness-20261014.md)：本轮源码复核、响应头遗漏修订、外部消费者与切换问题。
- [≤30分钟工作包](work-breakdown-30m.md)：关键链、527条存量接口及消息/收口逐包拆分；仅规范详设，不含代码迁移上线。
- [遗漏与风险评估](risk-review-20261014.md)：证据、风险、修订动作与尚未完成的事项。
- [机器可读源码库存](http-source-inventory.json)：每条method/path、handler、完整Java声明、mapping原文、源文件行/文件SHA、覆盖状态。**不是可执行请求schema或上线端点注册表。**

## 2. 覆盖统计（不混同已定义、已验证、已上线）

固定API commit：`7e2d07a05b9e9b18461e97abcff44642504320ff`；tree沿用影响分析`0c28979ec1363f27b9d90b3b11b37456002250a5`。Web/Runtime沿用详设的固定源码；本轮未查询线上或重新宣称远端最新。

- 扫描`src/main`源码中的直接Spring mapping注解：684处，含90处类级前缀；99个文件产生**596条显式method/path声明**。GET+HEAD各算一条；ANY表示源码没有限定method，不虚构成GET。
- **69条HTTP入口已有关键链设计条目**（含退役写接口），另定义4个UDS操作及3个WS入口的迁移边界。这些设计条目中的多态/Map/Object/状态enum仍有明确待闭合项，不能表述为69条完整schema已验收。
- 其余527条有逐接口源码声明、输入Java类型/位置/默认值、返回Java类型及目标封装规则，**未逐条完成人工业务语义核定**。未核定项仍然是缺口，不是第二套获准规范。
- 全部接口代码迁移、正式协议测试及本轮上线验收：**均未执行**。

| 模块 | 显式method/path条目 | 每接口声明 |
| --- | ---: | --- |
| agent | 179 | [http-agent.md](http-agent.md) |
| base | 7 | [http-base.md](http-base.md) |
| chat | 70 | [http-chat.md](http-chat.md) |
| dwz | 7 | [http-dwz.md](http-dwz.md) |
| economy | 4 | [http-economy.md](http-economy.md) |
| isp | 61 | [http-isp.md](http-isp.md) |
| kefu | 11 | [http-kefu.md](http-kefu.md) |
| material | 39 | [http-material.md](http-material.md) |
| oauth | 13 | [http-oauth.md](http-oauth.md) |
| point | 14 | [http-point.md](http-point.md) |
| sms | 17 | [http-sms.md](http-sms.md) |
| task | 12 | [http-task.md](http-task.md) |
| user | 80 | [http-user.md](http-user.md) |
| workflow | 25 | [http-workflow.md](http-workflow.md) |
| wx | 57 | [http-wx.md](http-wx.md) |

类别标记仅为静态定位线索：

- `BYTE_RESPONSE_REQUIRES_MEDIA_CHECK`：19条。
- `DYNAMIC_RETURN`：383条。
- `METHOD_UNRESTRICTED`：20条。
- `MULTIPART`：15条。
- `RAW_OR_DYNAMIC_BODY`：27条。
- `RETIRED_410`：5条。
- `STREAM`：5条。

19条byte[]返回不能直接判为文件，AgentTaskWorkspaceController已确认其中有JSON字节；5条STREAM标记仅来自显式返回/produces，不能当全部流语义库存。383条动态返回不等于383条有缺陷，只表示需跟进body/序列化路径。

## 3. 什么才算“每个接口规范定义完成”

每条operation必须具备以下内容，不能用一张路由表代替：
1. 精确method/path或messageType、启用条件及实际入口；path/query/header/body位置、重复参数处理。
2. 完整请求和响应schema，包括嵌套对象、精确联合、required/nullable、范围、枚举及媒体类型；data为可扩展字典必须显式声明。
3. 来源可信的认证主体和资源授权，租户/任务/资料/安装的分别校验；凭据响应的特别安全规则。
4. 成功/失败真实HTTP状态与稳定code、重试行为、202/ACK/ACTIVE/结果归档的各自语义。
5. 副作用、事务边界、幂等键/输入摘要/冲突、在途状态/恢复与签名范围。
6. producer/consumer的实际文件和版本、完整serializer→parser fixture、并发/失败/重放测试。

本次已固定公共决策并补大量字段，但库存中的SOURCE_INDEXED与DESIGN_DOCUMENTED状态不等于这些六项全部完成；不存在隐含“已验证”标记。

## 4. 静态库存的边界及复核办法

- 从固定源码读取注解，保留源文件SHA与原始mapping/声明；不是启动Spring、访问生产actuator或枚举真实部署路由。
- 不包含框架动态注册的OAuth/Actuator/error端点、隐式HEAD/OPTIONS、服务context-path/代理前缀、父类/接口继承映射、自定义组合注解等潜在入口；99个文件是扫描到的直接声明，不是全系统理论端点总量。
- 额外定向搜索WebSocket注册、@ServerEndpoint、SSE及原始JSON出口；在风险文档记明确认事实与未知条件。未发现映射不等于线上一定不存在。
- 当前源码中有已撤mapping的旧public方法；不因public签名将其恢复成接口。条件装配未启用的声明不等于活动接口。
- 检查JSON可解析、每条source SHA/行/声明与固定文件相符、method/path唯一、人工契约全部能匹配库存、Markdown链接存在；这些是文档检查而非应用接口测试。
- 临时静态提取脚本及摘要记录在库存元信息；它是本任务分析证据，不安装到运行环境，不新增构建/发布runner。源码变更后需在新固定基线重做差异核定，不把旧库存当自动同步文档。

## 5. 仍需补齐的明确项

2026-10-14续查：已批准测试数据清理完成，不再作为第三类阻塞；剩余仅“完整字段/错误”和“实际消费者/脚本绑定”两类。最新实物见开发前核对§13、[存量字段续查](ordinary-contracts-review5.md)、关键契约§10与manual-consumer-bindings.json（249条关系/215个operation；另有退役Runtime关系单列，不计当前消费者）。596条路由复提取修正1条拼接截断；这些进展不表示下面全量缺口已消除。

1. 其余527条业务字段/权限/错误/实际消费者（该数字随上表人工范围变更，以库存统计为准）。
2. 关键链中的TypedOutcome投影设计已补§11，但结构schema及真实跨端验证仍未补齐；FileDetail的relations/derivation、Usage的activeExecutions见开发前核对§8，执行Command/manifest补核见关键契约§10，已核实内容不重复列为完全未知；不能用Object/Map当完成的schema。
3. 钱包、托管租赁、任务创建/终止/成果归档等跨事务接口的完整错误/幂等/金额映射；已索引，不把原状态机推倒重建。
4. 框架提供端点、特殊响应及活动客户端/脚本清单；区分外部协议与自有代理接口，确认缓存页面/服务Worker等实际存在性而不猜测。
5. 同源schema/完整fixture与真实跨端测试、生产在途状态和协调切换窗口。

以上不是让用户重新拍板公共方案，也不授权全局自动改代码；它们是从“规范与静态目录”到“每个接口可实施、可验收”仍然缺失的实物。优先把当前图片执行闭包做实，再按实际消费者批次收敛其余模块。

2026-10-15续核：关键契约§11—14与target-finance-contracts.json/target-finance-fixtures.json新增16条结构合同、40组合成报文（20正20负）；仅结构检查通过。finalization三入口字段及RESULT_COMMITTED来源证明已定义，尚未实现。全系统字段闭合及图片链验收状态不变。

第三方协议边界（2026-10-15用户明确）：见[回调保护清单](external-callback-contracts.md)及`external-callback-contracts.json`，10条直接声明回调保持提供方wire，另列框架回调候选。不能只按6条EXTERNAL_CALLBACK筛选，否则遗漏GitHub等OAuth重定向；自有信封和GET迁移规则不覆盖这些外部入口。

2026-10-15 review8：135条原未解析调用已分类，新增7条源码关系后共256/221；关键契约§15补资料关联适配及只读恢复。该数字替代此前249/215当前值，历史记录不重写。开发前设计就绪不要求尚未实施的目标已通过应用测试，实际字段/消费者缺口仍未全量闭合。
