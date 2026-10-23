# 补查遗漏与风险评估

> 2026-10-14后续用户决策：当前规范1.2允许停机整体切换，旧在途不等待、不迁移兼容。本文涉及先完成旧在途的原建议已被取代；历史字节保留、旧输入不自动重放、新任务安全及幂等仍有效。实施以详设§8.3为准。

2026-10-14；基线同[目录](README.md)。这是**源码事实+迁移风险**，未作生产渗透/故障复现，不把潜在问题描述为线上已经发生。P0/P1为实施优先级，不是新建审批或资源门禁。

## 1. 本次发现与处置设计

| 优先级 | 具体遗漏/证据 | 风险 | 已确定的设计处置 |
| --- | --- | --- | --- |
| P0 | 上版只重点列JsonResult；PersonalWorkspaceController、TaskFileLink/ConversationFileLink、RuntimeFile/ConversationRuntime、EconomyWallet都有裸DTO/ErrorBody | 公共Advice只看JsonResult会漏掉上传/资料/账本；前端新decoder整片失败 | 新增逐接口契约和全量清单；这些Controller显式改R<T>，不靠全局任意包装 |
| P0 | AgentTaskWorkspaceController返回ResponseEntity<byte[]>但Content-Type是application/json | 误列“二进制例外”会留下自有JSON方言，或重复编码Content-Length | 按实际媒体类型分类；将snapshot封装后重新序列化/计算长度，保留原快照授权与限制 |
| P0 | PersonalWorkspaceConversationRuntimeController的effect start/result、resume、result-commits未在上版逐项列出 | 图像修改可能重复调用Provider、漏提交数据结果或丢失幂等 | 新增CR01—CR13、fence/snapshot/effect/providerBinding及inputReceipts字段；与Runtime技能执行器同批验证 |
| P0 | 统一幂等原则与SkillManagedCredentials原`SKILL_CREDENTIAL_SECRET_NOT_REPLAYABLE`冲突 | 将一次性apiKey存入响应缓存、日志或重复返回；自动轮换还会中断已有Runtime | 明确凭据不可秘密重放例外；保持409与原安全恢复，不用“同key同响应”绕过 |
| P0 | 只取消同Owner资格容易被误用到文件scope/管理接口 | 跨租户/跨资料访问、非管理者轮换凭据 | 同Owner接单条件和资源授权分别定义；各接口仍有可信身份、任务授权、固定文件版本与安装管理权限 |
| P0 | 旧command/result raw、manifest、fence、expiresAt与新字段映射耦合 | 改wire后历史消息无法验证、重复effect、丢回执、单端回滚损坏状态 | 旧在途按原闭包完成；新wire单一切换；不重算历史hash；新消息写入后禁止单组件回退 |
| P1 | SkillMarketplace priceMicro、EconomyConstants.MICRO_SILVER_PER_SILVER=1,000,000 | 误按CNY分转换导致数量级错误；价格登记被扩成费用授权 | SILVER最小单位明确micro，原整数原样映射amountMinor；运行费用仅登记；数据库账本不改 |
| P1 | costInfo.amount为十进制单价、updatedAt现行String；SkillManifestCodec显式排除costInfo于执行内容摘要 | 把单价按整数金额截断，或改费用描述导致技能执行摘要失效 | 单价用明确rate+十进制string；时间改毫秒；公开DTO映射与原包manifest/可执行摘要隔离，不为费用补执行权限 |
| P1 | 5个旧技能写处理器返回410；若按旧需求文档补“规范” | 复活被退役的图片/同意/inspection写入口，重启旧费用准入 | 明确410契约，不生成可执行请求；无mapping的旧public方法不登记为端点；历史GET只读 |
| P1 | PersonalWorkspaceController.PROCESSING返回HTTP202+ErrorBody | 新decoder把非E0当协议错，或误将202当文件已就绪 | 202目标R<Operation>且E0仅“已受理”；没有可恢复operation引用不能伪造成功；UI持续按状态查询 |
| P1 | PersonalWorkspaceController.versions：`Map.of("items",...,"nextCursor",null)` | JDK Map.of不接受null，构造即异常；格式迁移不能掩盖现有缺陷 | 实施改类型化CursorPageData，nextCursor=null、hasMore=false；加实际GET回归。本轮仅发现，未修代码/未在线复现 |
| P1 | HTTP状态原规范未显式列410/412/415/416/428；文件存在ETag/If-Match/Range/HEAD | 把并发冲突当认证问题、HEAD追加body、Range下载损坏 | 补状态与缓存/预条件条款；If-Match不变，过期412、缺失428；不盲目给所有文件加Range |
| P1 | SSE当前Last-Event-ID有序号语义；统一messageId若随机构造 | 重连从错误位置开始、重复展示或漏事件 | 明确可逆scope内游标映射、单流排序与id一致性；持久事件重放messageId稳定；补真实断线回放 |
| P1 | WebSocketConfig另有/ws/chat；sms有/websocket | 只改AgentWS不能称所有自有消息统一；裸文本[EOM]、URL token仍在 | 列3条WS入口，按原消费者迁移；实际是否启用/安全filter待核，不据源码宣称线上匿名暴露 |
| P1 | 工作流delegate/claim/assign为GET且调用写服务；素材delete等也有GET | 预取/缓存/重复请求可能触发副作用，语义与统一规范冲突 | 必须改明确POST/DELETE并配幂等/权限；消费者同步，不能靠修改文档把GET标成安全查询 |
| P1 | 20条@RequestMapping未限制HTTP method；wx/ISP较多 | 自有接口可能接受非预期方法；以为“微信模块”就全部外部豁免 | 每条核是自有管理代理还是第三方回调；前者明确method与统一信封，后者按真实外部契约；静态ANY不能伪写GET |
| P1 | `resultDigest→resultSha256`上版主要列HTTP SkillResult | selector、actionProgress、typed final、effect结果仍按旧字段/带前缀验证 | 补从请求选择到回执/结果/重放的完整摘要命名链；新schema必须成套，旧持久签名保持；未闭合前不切换 |
| P1 | com.fasterxml.jackson与tools.jackson两套使用痕迹；严格parser不等于全局ObjectMapper | 只配置一种mapper，另一种仍强转/忽略未知字段；注解ALWAYS或Advice不生效 | 每个真实序列化入口实测；不为规范统一顺带升级所有依赖；测试需覆盖两种真实mapper路径 |
| P1 | Global ExceptionHandler存在返回Result且HTTP200、AccessDenied按401、client-abort按204 | 新decoder报协议错；权限失败触发错误重登录；断流被当成功 | 显式真实状态；声明Result但实际JsonResult也要补元数据；已断开的流不制造204成功回执，保留取消/未知结果 |
| P2 | 自动清单看不到框架OAuth端点、隐式HEAD/OPTIONS、配置挂载和外部调用者 | 596条被误认为全部上线接口或全部消费者 | 同时列非注解/条件装配边界；发布前固定实际Spring mapping/安全filter/客户端版本，不临时加宽松解析 |

## 2. 上一版设计必须修订的具体逻辑

1. `ApiEnvelopeAdvice.supports`不能只按声明`JsonResult`判断。`ResponseEntity<Result>`实际body可能是JsonResult；声明`Object`也是大量现存模式。显式迁移Controller后按受管边界+运行时类型装配，只补一个信封，不任意包装OAuth/文件/HTML。
2. requestId filter建立的临时ID只能在明确没有入站header且收到合法body ID后替换；MDC/response header同时更新。外层trace ID不覆盖内层chat requestId；异步回调用显式上下文，不从已清理ThreadLocal读取。
3. 解析`@RequestBody String/byte[]/Map`的原strict parser必须先解公共信封再把**data原对象**送业务validator；不能反复JSON.stringify/parse改变canonical字节。已有业务hash算法保持，输入对象覆盖范围显式固定。
4. 表单requestId通过header；multipart metadata、原文件、lease token分别处理。不能一刀切让全部Controller必须接JsonRequest，破坏multipart、原始字节或带lease的下载POST。
5. 响应空data与领域null分开；部分业务union要求缺省字段，不能全局打开ALWAYS；仅公共data必须出现。typed inputs此前有脱敏/Bean-to-Map改变null集合风险，真实serializer测试必须保留。
6. 同步切换范围包括缓存页面、独立Runtime、ops脚本和错误分支；没有验证客户端版本就发新公共类型，最容易出现“首页正常、上传/恢复/错误分支全坏”。

## 3. 发布风险结论

**最大风险不是多一层data，而是消费者、状态和不可变事实只迁一半。**

当前可确认：静态路由库存、关键入口字段与新增风险已落文档；不能确认：596条全部业务schema/错误枚举已闭合、所有实际消费者已就绪、线上启用范围、在途队列已可切换、任何新协议已验收。

因此：
- 不阻塞按原精确wire修复当前图片交付；该修复不应携带全局信封改造。
- 在目标迁移开发阶段先补裸DTO/原始JSON/关键执行闭包，再按真实serializer→parser验证；schema目录/清单不是发布证明。
- 正式切换前必须完成实际消费者盘点与在途恢复证据；不能为了快速“统一”清历史、改订单、丢弃未知结果、放宽认证或复活退役接口。
- 保持项目轻量化：本轮只有静态Markdown/JSON，未新增服务、依赖、通用协议平台或运行时扫描器。
