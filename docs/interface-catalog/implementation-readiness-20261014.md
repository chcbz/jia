# 一次性统一改造：实施前核实与澄清

核对日期：2026-10-14；任务JYT-SKILL-SE2-20261013；Owner本聊天主Agent。
本轮为源码核实及详设补漏，不修改应用代码、不构建、不切换线上。公共规范现为1.2。

## 1. 已复核源码，不以文档代替事实

- API HEAD `7e2d07a05b9e9b18461e97abcff44642504320ff`；Web HEAD `a900ea4cff8158decc9b28888392c091fd0c62fe`；Runtime工作树HEAD `b5126ea2a81a5c374f77c8f9c025b8796dd751ee`，与既有详设一致。未查询远端最新或当前线上版本。
- API候选`75a050c7d39b15dad22a9822256dcb9da6f3a7fe`以以上API HEAD为祖先，差异14文件、423插入/33删除；包括安装生命周期与测试修复，不可只基于旧develop改造后漏带修复。实施先在干净集成工作树保留该候选增量，不操作其他工作树。
- 库存596条显式路由对应99个文件，逐文件SHA全部匹配；只是静态范围，不等于596条完整语义已核验。
- 声明匹配：51条含JsonResult、178条含ResponseEntity、364条直接返回Object、27条@RequestBody String、222条含HttpServletRequest、15条含MultipartFile。分类交叉，不能相加，也不能把Object都判作裸响应；需要看实际body。
- Web src的JS/TS/Vue静态模式检索：22个文件命中useHttp/createApi调用、35个文件定义unwrap命名函数、5个文件命中直接fetch。不是调用次数或完整消费者证明；非UTF8的game/config.js未纳入文本匹配，不修改其编码。

## 2. 关键技术疑点及已定处理

| 源码核实 | 处理决定 |
| --- | --- |
| JsonResult无协议元数据，有status/location；JsonResultPage独立继承Result | 按既定详设修改共享类型，分页移入业务data；不只补Advice |
| useHttp返回本地resultObj，业务body仍在data；解析失败退回text | 公共入口严格解包一次，调用点同步迁移；不保留猜测兜底 |
| PersonalWorkspace两次unwrap且依赖headers；createApi不暴露response ref | 补详设§6.1：沿用onSuccess(dto, metadata)的第二参数，以每请求局部闭包保留ETag等；业务await仍返回DTO。禁止用共享最后响应串单 |
| PersonalWorkspace、Workspace本地异常与裸DTO；Workspace byte[]实际application/json | 显式迁移成功及失败出口；JSON字节重新封装与计算长度，真实文件原样传输 |
| versions使用Map.of(...nextCursor,null)；PROCESSING为202 | 修复null构造；202明确处理中和原操作恢复，不当完成 |
| 全局异常部分HTTP200，AccessDenied返回401，断流生成204 | 明确真实HTTP状态；局部Handler和安全Filter也同步，不依赖全局Advice全覆盖 |
| Runtime客户端只取json.data，命令expiresAt使用Date.parse；AgentWS仍兼有type/payload分支 | HTTP/WS/UDS与消费者同步改；领域/磁盘对象显式映射，不批量改历史字节 |
| 一次性技能凭据重复返回409；effect/resume/commit有持久恢复语义 | 保留秘密不可重放、幂等与恢复；禁止用公共响应缓存替代 |
| Web直接fetch混合OAuth、自有用户接口、语音及静态资源 | OAuth保持其标准；user/my及revoke-all走自有协议；语音按实际媒体类型；静态资源不封装。RUM另核其采集入口，不能统一视作外部豁免 |
| 工作流GET delegate/claim/assign调用写服务 | 改POST并同步消费者，不以接口统一名义保留GET写入 |

前端元数据补漏的最早定向回归：两个并发文件请求逆序返回，确认各自ETag独立；身份切换后旧响应不得回写；下载Content-Disposition及二进制错误类型保持正确。

## 3. 未限定method的20条入口

源码回读确认：19条wx/mp是本系统调用微信SDK的代理/管理入口，另1条ISP更新DNS；不能因路径含wx/oauth2直接当第三方回调豁免。
保持路径，目标方法如下（请求/响应使用自有规范；参数位置按逐操作契约同步客户端）：

| 前缀 | 后缀 | 目标method |
| --- | --- | --- |
| /isp | /dns/update_record | POST |
| /wx/mp | /menu/create | POST |
| /wx/mp | /menu/get | GET |
| /wx/mp | /menu/delete | DELETE |
| /wx/mp | /user/get | GET |
| /wx/mp | /user/info/batchget | POST |
| /wx/mp | /user/sync | POST |
| /wx/mp | /oauth2/authorize_url | GET |
| /wx/mp | /oauth2/access_token | POST |
| /wx/mp | /oauth2/userinfo | POST |
| /wx/mp | /oauth2/refresh_token | POST |
| /wx/mp | /oauth2/auth | POST |
| /wx/mp | /jsapi/signature | POST |
| /wx/mp | /customservice/getkflist | GET |
| /wx/mp | /customservice/kfsession/getwaitcase | GET |
| /wx/mp | /message/custom/send | POST |
| /wx/mp | /message/template/send | POST |
| /wx/mp | /material/batchget | POST |
| /wx/mp | /material/add_news | POST |
| /wx/mp | /material/add_material | POST multipart |

含access/refresh token的代理读取使用POST body避免把秘密继续置于URL；不记录秘密，不重放一次性code。实际微信回调和框架OAuth端点仍保持原外部协议。DNS外部脚本调用方未闭合，改方法前必须纳入同批更新。

## 4. 范围不再反复询问

- 全系统自有接口统一修改，前端/API/Runtime及受影响脚本同批准备；不是只改图片链、不分模块上线。
- 按既定规范同步信封、分页、ID/时间/摘要映射及对应消费者，不悄悄缩为仅加一层data。
- 不改业务/DB内部模型，不清历史，不新增费用准入/外单/服务；认证、资源隔离、事务及幂等保留。
- 全量低成本检查加按协议类别抽样；关键安全/状态回归和图片上传→识别→修改→下载必须覆盖。

## 5. 用户已澄清的事项（本轮确认）

1. 当前仅Web、API、Runtime和仓内脚本调用；无额外外部客户端，不再重复询问或以此阻塞。
2. 统一发布允许停止服务，不要求零停机，不开发双协议。
3. 旧在途可忽略：不等待完成、不迁移续跑、不兼容旧wire；旧数据保留但退出新执行自动重放路径。不解释为删除资料/订单/账本，亦不解释为已发生外部副作用被撤销。
4. 改造前必须把实现契约核清，不把设计疑问拖到最终验收。实际缺陷仍需测试发现和修复，不能承诺未经测试必然通过。

## 6. 工程核验顺序与真实状态

- 将已索引入口关联到实际生产者/消费者和媒体类型；逐处查局部错误、原始body、泛型/Map、分页、数字与时间、摘要和直接fetch。优先核请求与响应边界，不另起全量文档工程。
- 旧输入隔离必须定位API派发、Runtime本地恢复与安装回执重试入口，确定精确操作后才停机；不增加旧任务兼容层。若同一存储混有身份/资料，禁止整目录删除。
- 开发早期用实际序列化/解析双向fixture检查JSON、WS、UDS和特殊媒体；首轮定向测试同时定位已知Java UDS失败。新任务状态、安全、幂等仍覆盖。
- 最终以新图片任务验收；不以旧任务续跑和历史四笔安装完成作为前置。前端Flow/后端本地及版本制品规则不变。
- 此次用户侧决策已全部收口，工程侧全量映射与早期测试尚未完成。原r18的363项/1失败及图片NOT_RUN为历史事实，不冒称本轮已修复。
- 原285文档包不作当前实施计划；2小时是集中实施目标，不以缩减必需认证/资料隔离或伪报测试来满足。停止服务授权不等于本轮已停止服务。
