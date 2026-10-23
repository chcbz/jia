# 开发前核对：源码证据、已确定处理与未闭合项

2026-10-14；任务`JYT-SKILL-SE2-20261013`；唯一Owner本聊天主Agent。规范1.2，用户范围不变：Web/API/Runtime/仓内脚本，允许停机，不等待或兼容旧在途。**本轮是源码核对，不是业务开发、接口运行验证或发布。**

## 1. 结论及覆盖边界

- 596条显式HTTP操作均提取了实际方法体返回表达式、行号和文件摘要，并登记目标传输类别，见[逐接口核对数据](predevelopment-audit.json)。没有把所有`Object`当裸DTO，也没有把所有`byte[]`当下载。
- API库存99个Controller未发现继承另一个类或实现接口的声明；本扫描范围内无基类映射需展开。框架生成的OAuth、Actuator、错误与预检仍单独处理，不计入596。
- Web/Runtime采用语法树检索调用候选，另扫描仓内脚本的路径字面量。**候选匹配不是完整调用图**；动态函数返回路径、注入api对象、返回DTO中的下载URL仍需绑定，不能以没匹配到调用者宣布接口无用。
- 源码仍是详设中的A/W/R；候选API `75a050c7d39b15dad22a9822256dcb9da6f3a7fe`的14文件增量单独核读，未覆盖或合并到他人工作树。源路径固定，不读取生产凭据。
- **全系统开工状态仍为未闭合**：全量嵌套字段/错误分支、动态调用绑定、旧执行输入的精确隔离方案尚未全部核完。JSON里的`readyForWholeSystemDevelopment=false`是明确结果，不把本轮扫描通过当开工通过。

## 2. 本轮新增发现及已经确定的代码处理

| 项目 | 源码证据 | 确定处理 |
| --- | --- | --- |
| 第二个JSON字节出口 | `AgentTaskContextPackController.contextPack/response`，`application/json`；先`requireSerializedScope`再返回byte[] | 与Workspace一起显式封装。先按原pack做scope/版本验证，再包装并计算最终Content-Length；不能对新信封根继续执行旧pack字段检查，不能删Native fence |
| 场景快照绕过JSON客户端 | Web `useHallBackendSceneState.js:78,560—568`，`responseType:text`、`quoteIntegerFields(sceneVersion)`及额外unwrap | 场景版本输出规范十进制String，快照改普通JSON入口，只解包一次；移除该快照的文本补引号和双层解包，SSE也按新字段取版本并保持原游标语义 |
| String不全是外部文本 | `OrgController.findNameById`、`UserController.findNameById`、`WxPayController.createScanPayQrcodeMode1(productId)`等 | 自有业务字符串仍是`R<String>`。GiftPay二维码链接调用去掉responseType:text，onSuccess拿业务字符串；真实hosts文件和HTML/外部回调另列 |
| 二进制stream与SSE共享入口 | Web `useHallArtifactTransfer.js:196`借`onStreamOpen`读取Uint8Array，其他stream消费者解析SSE | `useHttp`显式区分`streamMode:'events'|'bytes'`；SSE走消息parser，bytes不TextDecoder/JSON解析。调用方全部指定模式，不能看到stream就套消息信封 |
| 缩略图写完又返回字符串 | `MediaController.thumbnail`写图片输出流后`return "ok"`，还吞打印异常 | 改明确的二进制返回，移除尾部业务字符串；未提交时按统一错误，已开始传输则结束/报真实传输失败，不追加JSON。不是已线上复现 |
| 类型声明落后真实入口 | Web `useHttp.d.ts`只声明json/stream，未反映text/blob；大量Promise<any> | 与useHttp一起声明DTO返回、metadata第二回调参数、blob/text/stream专用返回及streamMode，样例和测试同改，不能只改JS |
| 幂等请求体外层变化 | Runtime `skill-conversation.mjs`的`request`和`readInput`分别发裸业务JSON/裸fence | JSON body请求都封装Q；下载POST只豁免响应字节，不豁免JSON请求。multipart保持表单。成功严格解包R后送原业务validator，不保留裸/包裹猜测 |
| UDS详设字段名称笔误 | 详设曾写回复`type/correlation/code`，规范使用`messageType/correlationId/code` | 已修正文档；Java/Node目标只接受规范字段，不留type或method别名 |
| GET副作用远不止工作流 | 源码筛出41条候选，含38条写动作候选、2个无操作占位、1个快照生成 | [逐项清单](get-mutation-review.md)。删除类改DELETE，其余写动作POST；快照GET保留；空实现不编造成功业务能力。密码/验证码不继续放query |
| 自递归的存量缺陷 | `WxPayController.queryComment:679`直接调用同签名的自身 | 已确认静态无限递归风险，不是信封问题。真实SDK返回契约/消费者尚未核定，不能为赶进度伪造成功或顺带新增评价功能；列入未闭合项 |

### 一个已排除的误报

`useHallTypedDeliberation.js:204`仍含inspection/discussion三元分支，但前面的`purpose !== 'CHAT'`已返回false；不能仅凭字符串出现就声称现页面会POST已退役inspection。`useHallBountyFollowup`有旧路径，但本轮src搜索未发现其import调用；是否保留为不启用历史代码与测试引用需分开登记。历史只读inspection查询不等于复活写入口。

### 字段复用边界

- 已有枚举、授权、事务和摘要算法沿源状态机引用，不为每个接口重新设计业务。固定对象的未知字段拒绝与显式可扩展字典分别处理，不一刀切禁止所有Map。
- Runtime已有`juyiting-typed-outcome.mjs`中的`StrictJsonParser`（重复键、尾随JSON、Unicode校验）。目标抽取纯parser供协议边界用；Web不能直接import含`node:crypto`/app-server-adapter的整个模块。移植纯函数并绑定同源fixture，不增加协议服务或运行时加载另一仓源码。
- `useHttp`的metadata必须是每请求局部对象，沿现有`onSuccess(dto,metadata)`第二参数传递，ETag并发不串单。鉴权、取消、缓存和原响应头不因解包丢失。

## 3. 非Controller出口与错误路径

| 出口 | 已定位位置 | 处理边界 |
| --- | --- | --- |
| Agent WS、普通聊天WS | `chat/.../config/WebSocketConfig.java:36,38` | 条件`jia.chat.service.websocket.enable=true`；两条均为自有协议，不能因未找到Web WS构造器就遗漏发送端 |
| SMS WS | `sms/.../websocket/WebSocketServer.java:22` | 独立`/websocket`，握手及JsonResult发送独立处理；查询token不得进入新消息/日志，身份校验不删除 |
| Runtime安全链 | `AgentRuntimeSecurityConfiguration`及`AgentRuntimeAuthenticationFilter` | @Order(0/1)，认证失败发生在MVC之前，须显式使用同一错误输出；不改变凭据lane选择 |
| Voice安全链 | `VoiceSecurityConfiguration` | `/chat/speech/**`独立@Order(1)，上传/音频的成功和错误媒体分别处理 |
| OAuth框架 | `AuthorizationServerConfig.authorizationServerSettings` | 框架标准端点与OIDC/OAuth报文保留；不能把本系统`/oauth/client/*`、`/resource`等自定义接口按模块名一律豁免 |
| 普通资源链 | `ResourceServerConfig`、`DefaultSecurityConfig`、`ApiKeyAuthorizationConfig` | 401/403发生在filter，局部Handler、全局Handler和filter必须同批处理；登录HTML重定向例外保留 |
| SSE | Scene、Task、Chat stream/conversation、Archive question | 客户端游标映射及终态须成对改；不得随机重造Last-Event-ID，不把EOF当成功 |
| sendBeacon/RUM | `useRequestRum.sendRumPayload` | 同源可配置采集端点，不能因为忽略响应就当第三方例外；实际启用/接收端绑定仍未闭合 |

## 4. 旧在途可忽略，但重启会自动恢复的具体入口

以下是已读源码入口，不是已实施隔离：

| 侧 | 入口/数据 | 风险与边界 |
| --- | --- | --- |
| API | `SkillInstallRecoveryService.recover`、`AgentSkillInstallRecoveryMapper.candidates/claimDelivery/claimOutbox` | 重投原wire；候选修复已删除expiry条件，不能等待旧expiresAt自然排除。只禁旧安装消息，不取消新安装恢复 |
| API | `AgentCommandReissueCoordinator.runOnce`、`AgentCommandRecoveryMapper.selectReconnectCandidates/selectDueCandidates`，WS注册signalReconnect | 定时及重连都会重新下发旧command；既有Rabbit持久/重试队列也不能由停API推定为空 |
| API | `PersonalWorkspaceExecutionMapper.listQueuedByTarget/listQueuedConversationsByTarget`对应查询及runtime lease/result入口 | 旧QUEUED记录会被新Runtime轮询取得；需要同时排除派发与直接旧execution恢复，历史查询仍保留 |
| API | `HostingRentReconciler.reconcileOne/reconcileFree` | 可重新执行prepareAndObserve且涉及资金保留；不能以“忽略旧在途”为由伪造失败/退款/成功或清账本 |
| Runtime | `execution-adapter.mjs:85—88`、`agent-client.mjs:6666—6678` | 会执行reconcileE05Results、replayAcks、processor.resume、report outbox、skill replay及两类文件轮询；只停一个resume不够 |
| Runtime本地 | inbox的pending/processing/recovery-required、ACK/report/chat状态 | 与sequence/thread binding等共存；应精确隔离执行输入，不直接删除整个agent state或codexHome |
| Runtime技能状态 | `skill-install-manager.mjs:974—979,1150—1195` | pending和sent结果会恢复；installed-registry及acknowledged包含有效安装/完整性证明，不能整目录移动。持久安装事实校验与新wire校验必须拆清，否则删除旧解析会让现有技能无法启动 |

**尚未闭合的不是用户授权，而是一次性操作的精确对象与可执行步骤。**本轮不增加兼容层，不修改旧数据，不写生产SQL，也不新增一个未经审查的全局cutoff配置来掩盖遗漏。

## 5. 验收选择已前置，不待开发完再选

以下是预期及既有测试落点，不代表本轮已执行或现有测试已覆盖目标协议。

| 类别 | 固定预期 | 复用/增补位置 |
| --- | --- | --- |
| HTTP信封 | 请求3字段，响应5字段；data:null；未知/重复字段、header/body不一致拒绝；非2xx不E0 | Web `tests/composables/useHttp.spec.js`；API公共序列化/异常测试补同fixture |
| 分页/大整数 | items/pageNum/pageSize/total一层DTO；total及revision用String，无Number精度丢失 | Web消息/列表及`juyiting-hall-backend-scene-state.test.js`；API JsonResultPage测试 |
| 响应头/并发 | 两个文件请求逆序完成仍各自ETag；身份切换后旧响应不回写 | Web `personal-workspace-multimedia.test.js`；API `PersonalWorkspaceHttpPersistenceIntegrationTest` |
| 文件/字节JSON | ContextPack/Workspace正确R；真实图片/zip不附JSON；下载长度/摘要一致 | API ContextPack/Workspace原测试、`PersonalWorkspaceRuntimeFileControllerTest`；Web成果下载 |
| 错误/权限 | 无凭据401、有凭据无权限403或资源防枚举404；错误不泄密；局部和filter均同结构 | `AgentRuntimeSecurityIntegrationTest`、`SessionRevocationResourceSecurityTest`；Web身份取消 |
| SSE | 新messageType/data；断线按原scope游标重连；EOF不伪报终态；bytes stream不进SSE parser | TaskEventStream、Scene、Archive既有测试及Chat流测试 |
| WS | 命令、ACK、技能结果/回执由同一新信封关联，raw持久对象不全局改名 | `AgentWebSocketRawCommandDispatchTest`；候选`SkillNativeResultMySqlTest`；Runtime对应测试 |
| UDS | capabilities/prepare/ensure/observe字段与messageType/correlationId严格对齐；不能只验Node | `UnixManagedHostingProvisionerTest`及Runtime `hosting-java-bridge.test.mjs` |
| 新任务幂等 | 同key同业务、异参409；一次性凭据不重放；原事务后回执 | `SkillMarketplaceRealTransactionTest`、`SkillNativeAckMySqlTest` |
| 停机切换 | 原数据仍在；旧消息不自动执行；新任务、有效安装与凭据可用 | 待精确隔离步骤闭合后写对应恢复/冷启动fixture，不使用生产旧任务试执行 |
| 图片完整链 | 上传固定图片及版本→识别可核对内容→修改指定元素→下载新文件，校核文件可解码/摘要/资料授权 | 已有图片业务入口，使用全新任务；不以版本号/目录存在代替实际识别和修改 |
| 仓内脚本 | 使用实际HTTP包装后的fixture，不再比较旧根items/code/data；健康探针依其真实媒体契约 | `specs/juyiting-execution-recovery/contract-pilot/check-http.mjs`及browser-entry；`ops/health/cyf-juyiting-health.py` |

已知r18 Java UDS失败回读：test耗时20.406秒、assertTrue失败，system-out/system-err为空。`probeCapabilities`将异常直接吞为false；现有证据不能区分transport与响应校验。Node私有探测成功不能替代Java结果，本轮没有编造根因。

## 6. 尚未允许转为全系统开发的具体缺口

1. **调用关系**：语法匹配只确认部分候选；需完成动态path函数、注入客户端、返回URL、RUM配置入口，以及无调用端点的明确分类。没有新增用户问题。
2. **业务字段/错误**：仍未把所有嵌套DTO/Map/SDK结果、错误分支和当前状态枚举闭合为明确映射。自递归queryComment与2个ISP空实现须区分既有缺陷与本次必要改动，不能假设它们已有可验证业务契约。
3. **停机隔离**：上述具体API/Runtime恢复源已定位，但保证旧输入不再执行且保留已安装技能证明的精确操作仍未完成；不得直接清库或改终态装作完成。

历史诊断/证据脚本不批量重写，其对应旧wire证据仍留原字节；真正日常工具及新fixture需要同批更新。上述三项未闭合前，保持不开工，不把“所有接口已扫描”表述成“所有接口已对齐”。

## 7. 本轮证据及检查结果

完整源证据：`/home/isp/state/cyf-diagnostics/interface-predev-20261014/source-review`；摘要、源码基线及工具边界见`check-summary.json`。481个JS/TS/Vue分析文件、867个语法调用候选（包含通用request函数，并非867次网络调用）、857个跨仓脚本/源码文本及1216处路径字面量；143条HTTP路由获得静态调用候选，其余不能据此判断无消费者。原始文件SHA、JSON、局部链接与diff检查通过，未运行应用测试。`game/config.js`唯一非法UTF-8位于第2行注释，解析分析副本而未改源文件编码；不是应用构建通过证明。

## 8. 继续核对：动态调用与局部字段（同日续查）

本节补充§2—§6，不把之前的143条语法候选升级成已完成调用图。A/W/R本地HEAD再次读取，仍与§1一致；未查远端/线上。

### 8.1 已人工绑定的调用

[手工绑定证据](manual-consumer-bindings.json)登记33条生产者—消费者关系、32个不同HTTP操作，含双方文件SHA；只是这些源码调用关系已确定，不是32条全字段/运行验收通过。与旧候选有重叠，不可相加计算覆盖率。

| 消费者 | 已确定的实际绑定及改动 |
| --- | --- |
| useHallDrafts | defaultAgentApi来自useHttp的`createApi('/agent')`；HallDraftEditor/JuyiHall不覆盖它。草稿6路由、submission/case/results、capabilities/execution/task读取均有真实Controller，不是无调用接口 |
| useHallOverview | defaultApi同为agentApi；内层request固定GET；overview/items/task必须同步移除unwrap，不能因调用点没有method推断无HTTP方法 |
| useHallSkillCapabilities | JuyiHall显式注入agentApi；完整路径为`/agent/tasks/{taskId}/skill-capabilities` |
| useHallSkillResults | BountyExecutionOutputs注入`createApi('/chat')`；完整路径为`/chat/conversations/{conversationId}/executions/{executionId}/result` |
| useHallBountyAcceptance | BountyAcceptancePanel及JuyiHall分别传createApi('/chat')/chatApi；`.list`实际上POST conversation/list。目标回调收到Page DTO后读取`items`，不能继续把回调值当数组 |
| usePersonalWorkspace.readBlob | suffix只有content或`preview/parts/{partId}`两个实际调用族；都为GET字节，不是任意后缀透传 |
| BountyExecutionOutputs.bytes | URL来自outputCatalogItems按request/step/output严格验证的downloadUrl/previewUrl；download=true仍是同一GET路由。保留MIME/长度/SHA校验，Blob返回方式与公共客户端同步，不能只改JSON读取 |
| TypedInspectionMaterializer | 固定sourcePath；materials还逐项比对downloadUrl；两条内部content GET是字节出口。保留Native身份、清单digest、同源和不重定向约束 |
| SkillConversationLane | BASE/taskId/runId构造conversation路径；已逐项绑定队列、lease/renew、inputs、resume、effect start/result、result-commit及上传/下载。multipart只豁免请求包装，JSON fence下载请求仍须Q |

RUM进一步定界：`main.js`和`useHttp.js`是配置入口，`useRequestRum.normalizeRumEndpoint`只允许同源无query/hash，未配置则不发送。仓内Web环境文件、API main源码及ops/ci定向搜索未发现实际采集端；**不能据此断言Flow注入值或线上一定未启用**。不新增采集服务；现有发送器若使用自有采集端，按Q发送，`data.requestId`是被观测请求ID，外层requestId是本次采集操作ID，避免重用为权限或去重凭证。发布前仅核验是否配置及其接收方归属，不输出配置秘密。

### 8.2 个人资料中已核实的Object与能力字段

源码：`PersonalWorkspaceViews`及`PersonalWorkspaceServiceImpl:82,229—254,285`，预览状态另见`PersonalWorkspacePreviewRenderer:505—522`。

- `FileDetailView.relations`、`derivation`当前生产者都明确返回空列表；目标这一版仍为`[]`，不编造关系/派生对象schema，也不新增功能。Java类型可收窄为有明确空集合契约的输出，不再把List<Object>当任意对象许可。
- `UsageView.activeExecutions`实际为`ActiveExecutionView(executionId)`数组；仅`executionId:ID`，不能带lease、token、路径或冒充完整ExecutionView。`taskReferences`沿TaskReferenceView6字段；版本/relationRevision按D1/D0传输。
- capabilities：upload/read/download现为AVAILABLE；edit为UNVERIFIED；preview为AVAILABLE或UNSUPPORTED。预览View状态实际为READY/FAILED/UNSUPPORTED；`partial:boolean`、`reason:String|null`保留。不可将“能力AVAILABLE”误写成“本次解析成功”。
- FileDetail.latestVersion必须能从版本中找到，否则服务抛STORAGE_CORRUPT；不是可null成功。Operation中的fileId/fileVersion/errorCode仍按状态明确可null，不套用该约束。
- **纠正先前PW05设计**：service.versions只取100条，DAO按version倒序limit；不能无条件`hasMore:false`。目标分页细节见critical-contracts PW05修订；这不是线上已支持分页。

### 8.3 Hall请求/回执字段及错误已前置定位

生产者为HallRequestDraftController、HallSubmissionController、HallReadController、HallPrivateMarkController；业务嵌套结构来自`HallRequestDraftService`、`HallReadService`、`HallPrivateMarkService`（agent-api）。

- create的Q.data完整字段：kind/originRef/sourceRef/caseId/taskId/conversationId/title/instruction/targetAgentId/outputMime/inputs/sourceOutputRef。replace仅title/instruction/targetAgentId/outputMime/inputs。discard为expectedRevision；submit为expectedRevision/authorizationAcknowledgement。inputs每项fileId/version；sourceRef为sourceType/sourceId/version；sourceOutputRef为executionId/outputId/fileId/fileVersion。已有null/必填和长度约束继续在对应业务validator执行，不把body里的owner当身份。
- `expectedRevision/revision/revisionNo/grantRevision`和资源`version/fileVersion`转十进制字符串，解析后传现有long/int服务；现有int资源版本上限仍2147483647，不借字符串化扩大存储范围。`savedAt/submittedAt/updatedAt/observedAt/asOf`保持安全毫秒number，不把asOf错归revision。原safe-integer业务上限也不因BigInt客户端放宽。
- DraftView字段：draftId/revision/state/savedAt/kind/editableFields/sourceSummary/submissionRef。editableFields与replace一致；sourceSummary为originRef/sourceRef/caseId/taskId/conversationId/sourceOutputRef。DraftSummary只含draftId/revision/state/savedAt/kind/title/targetAgentId/outputMime/sourceSummary，不泄露instruction和inputs；DraftPage新增hasMore，依真实nextCursor计算。
- SubmissionReceipt保留`ref/execution/task/submittedAt`两分支：TASK_CREATE是task非null、execution为null；执行类型相反。ExecutionView及其RuntimeCommand/Input递归定义仍需沿其实际服务完成，**不把这个入口表写成整个嵌套模型已闭合**。
- Overview的sections固定recent/needsAction；每section.partitions为private/task/draft；Items的section只含所选来源，`count`当前明确null。保留各分区status/sourceStatus/errorCode及部分失败语义，不能把它硬转成全局total分页。`personalMark.revision`单独转字符串，不再走timestamp validator。
- PrivateMark：Q.data为expectedRevision/archived/viewedResultRef；响应ref/revision/archived/viewedResultRef/updatedAt；viewedResultRef为null或executionId/manifestId。Web `expectedRevision + 1`改为校验范围后的BigInt加法再转String，**updatedAt仍用独立时间validator**；不能把复用的revision函数机械改成字符串后使时间字段全部失败。
- Hall当前ErrorBody.code→R.code、message→R.msg、独立traceId不再第二次UUID生成，使用统一requestId；retryable和details明确进入R.data（错误详情对象），不进入根。details目前已找到的业务producer仅sourceUnavailable的field/sourceType，空时null；不给任意异常Map自动公开权。
- 草稿已有错误状态保留：400 BAD_REQUEST、404 NOT_FOUND、409 IDEMPOTENCY_CONFLICT/STATE_CONFLICT/EXECUTION_CONFLICT、412 REVISION_CHANGED、422 SOURCE_UNAVAILABLE/SUBMISSION_UNAVAILABLE、503 STORAGE_UNAVAILABLE；对应code保留Controller各自明确的HALL_*名。认证依原scope为401/403。共享helper修改会同时影响其他Hall Controller，须同批更新，不能只迁一个方法。
- `parse(readBounded(...))`直接读ServletInputStream，不经过@RequestBody Advice：应在共享parse入口先严格解析Q和header/body requestId，再将data绑定业务request；不能靠ResponseBodyAdvice解决输入。原256KiB限制沿用，不新增性能硬门槛。

### 8.4 三个存量空实现/递归入口的范围决定

`IspController`的两个refreshSSL方法未调用服务；`WxPayController.queryComment`直接自递归。本次不实现SSL/DNS删除/微信评价新能力，也不调用未经核实的SDK；目标明确返回`501 + R(code=OPERATION_NOT_IMPLEMENTED,data=null)`，保留现有认证。SSL动作使用POST、DNS删除DELETE、queryComment保留POST；错误不伪装E0。对原GET写入口不保留重定向执行。此为**目标代码处理**，当前源代码尚未改；上述定向Web源码检索未命中调用，不据此宣布不存在脚本调用。

### 8.5 停机方案发现的新增必改点

1. 安装`_recoverRegistry`对非QUARANTINED注册调用`_reconcilePreparedRecord`；即使ACTIVE也执行`_ensureResultRecord`，缺pending/acknowledged就重建pending。所以只移走pending/sent**确定不成立**。PREPARED还能实际publish staging；不能只阻止sendResult就声称旧安装不执行。
2. `initialize`顺序为scanResults→recoverRegistry→reconcileActiveFailureContradictions。pending/acknowledged失败证据会参与有效安装冲突裁决；不能先移走失败证据再校验ACTIVE，否则可能隐藏原已失败安装。不能把正常旧消息简单放入现用quarantine目录：该目录有JSON就会使healthError为IO_FAILED。
3. 正确实现边界已确定：本地持久安装事实校验与新网络消息解析分离；旧安装完整性/原命令摘要/marker/path identity保持原字节，不再把历史证明当待发送wire。离线处置先识别完整安装事实和冲突，再隔离恢复输入；未确认PREPARED不自动激活，不伪造acknowledged或已完成回执。**具体逐记录标记/迁移及API侧SQL闭包仍未定完，不能宣称可直接执行。**
4. 浏览器同样有持久恢复输入：`cyf.hall.submission-recovery.v1`、`cyf.hall.private-mark-intent.v1`；archive/abandonment/finalization还使用sessionStorage。草稿恢复主要GET、标记恢复不自动PATCH，不能夸大成全部自动重放；但新解析不能读取旧数字字段后自动转新意图。应版本化这些业务恢复key并停止读取旧key，保留原存储及登录身份，不清空localStorage/sessionStorage。其余恢复key还需逐项核完。
5. HostingRentReconciler不只是重发wire：FUNDS_RESERVED会推进PROVISIONING_UNKNOWN，SERVICE_READY还会prepareAndObserve再settle。新UDS适配器上线后旧意图也可被重新生成新请求；**严格拒绝旧JSON不能替代旧意图隔离**。账本、资金保留和已装Runtime身份不能清理或伪终态。

### 8.6 本轮结束状态

已消除以上具体误判并回写设计，但剩余仍是工程核对而非待用户澄清：全量活跃客户端/脚本绑定与SDK/嵌套字段、实际RUM配置归属、API/Runtime/浏览器旧输入的精确停机隔离。`readyForWholeSystemDevelopment`继续false。本轮无业务代码、生产配置、数据库、服务、应用测试或构建变更。

## 9. 路由冲突、绕行客户端及恢复入口续查（2026-10-14）

本节沿用A/W/R固定源码，不代表远端最新或线上验证；无应用代码/配置/服务变更。

### 9.1 全量目标路由复核与支付参数位置

`target-route-audit.json`逐项列出596条源操作的目标method/path，61条发生变化，ANY剩余0；将path变量名归一后目标method/path重复为0。这只证明显式路由表不重复，不替代Spring实际映射、参数绑定或业务schema核验。

- GET `/wx/pay/createOrder` → POST `/wx/pay/createOrder/product`；GET `/wx/pay/unifiedOrder` → POST `/wx/pay/unifiedOrder/product`。原有两个同名SDK POST入口保留原路径；不能机械只改@GetMapping，否则撞路由。
- 新补漏：GET `/wx/pay/closeOrder/{outTradeNo}`实际调用SDK关闭订单，改POST原路径；GET副作用复核总候选由41改为42（39写动作候选、2空实现、1只读快照）。没有据此宣称所有GET间接副作用均已穷尽。
- 未限定method表的真实路径是`/wx/mp/customservice/kfsession/getwaitcase`，不是`/wx/mp/kfsession/getwaitcase`。
- `PayInfoServiceImpl.findWxPayService`和`MpInfoServiceImpl.findWxMpService`从`request.getParameter("appid")`选账号。目标保留明确的query选择器`appid`（URL编码），其余JSON业务参数放Q.data；DTO中不再同时接受appid。它只选Provider配置，不是认证凭据，原认证/授权检查不变。multipart沿原文件协议。不能把appid直接移到JSON而不改该读取点。
- product createOrder的Q.data为`productId? / jiacn? / outTradeNo? / tradeType`；product unifiedOrder为`productId / openid / tradeType`。字符串可选性沿现有方法声明，业务校验不放宽；`jiacn`不能提升权限。关闭订单path含outTradeNo，JSON请求Q.data=null，非自动重试。
- `GiftPay.vue:216`与`Phrase.vue:181`改用`wxApi.post('/pay/createOrder/product?appid='+encodeURIComponent(appid), {outTradeNo,tradeType:'JSAPI'}, options)`；createApi/useHttp负责Q封装。onSuccess直接接DTO，移除旧wxData.data与code判定；保留onError，不把失败回执送入weixinPay。
- 支付结果R.data保留SDK的明确分支，不额外再包一层公共结果：JSAPI为`appId/timeStamp/nonceStr/packageValue/signType/paySign`；APP为`sign/prepayId/partnerId/appId/packageValue/timeStamp/nonceStr`；NATIVE为`codeUrl`；MWEB为`mwebUrl`，上述字段均String。由请求tradeType选定响应分支；无效tradeType须在副作用前拒绝，不等到switch后返回null成功。`timeStamp`为参与签名的秒数字符串，不能改成平台毫秒number；签名字段保持原大小写/内容，作为标准§1.5的外部协议边界例外，而非豁免外层R。
- SDK字段依据本地依赖4.6.0的class签名回读，版本来源`SharedDependencyManagementPlugin.java`；没有调用真实支付。SDK统一订单响应的其余嵌套schema、错误/退款回调仍未闭合。`parseRefundNotifyResult`目前返回SDK解析对象，不能把它登记为已正确实现外部确认回执，更不能在此轮伪造支付成功。

### 9.2 公共客户端之外的XHR与RUM

- `web/src/stores/api.js:225 wxJsToken`是同步XHR，GET读取jsapi/signature，未走useHttp。目标仍保留当前同步函数签名（本轮不扩展异步功能），改POST `/wx/mp/jsapi/signature?appid=<encoded>`，Q.data=`{url}`；生成同一requestId置body/header，严格校验HTTP状态、协议版本、requestId与E0，只缓存R.data。url不再拼query；不增加旧信封回退。cache prefix从`wx_js_token_`改为`wx_js_token_wire1_`避免读取旧形状。仓内仅找到该方法定义，不能把未找到调用者当删除依据。
- `installed-rum-check.json`通过已安装kit的index引用定位唯一入口JS，AST回读确认启动参数`endpoint:void 0`，编译env也无VITE_RUM_ENDPOINT，因此该磁盘入口默认不发送RUM。未执行应用，未核实时HTTP路由，不推广到其他安装或未来Flow配置。不新增采集后端；发布候选若配置采集地址仍需明确接收方契约。

### 9.3 API恢复源：旧意图可以生成新协议，拒绝旧报文不足以隔离

| 恢复入口 | 持久源/实际动作 | 隔离必须覆盖的边界 |
| --- | --- | --- |
| ChatBountyBootstrapRelay → claimNextAvailable | agent_task_bounty_bootstrap_outbox；PENDING/RETRY或过期CLAIMED；重新admission/reconcile | discovery、claim、admission三个入口均不得重新激活旧意图 |
| ChatDeliberationOutboxRelay | chat_dispatch_outbox；READY/RETRY/AWAITING_ACK及过期CLAIMED；deliver | 查询和发送前复核，不能只更新序列化器 |
| ChatBountyExecutionRelay → coordinator.pending/coordinate | chat_interaction_step JOIN chat_step_execution_link；EXECUTE、可重试step、WAITING_ADMISSION、execution_id IS NULL | 列表及coordinate直接调用均隔离；否则重启后生成新execution |
| ChatBountyAssetRelay → projector.pending/project | 上述step/link JOIN agent_personal_workspace_execution；RUNNING链接及已提交/失败/撤销执行 | 不调用Provider，但写资产/消息；需独立界定旧结果投影，不能误称只读 |
| ArchiveQuestionWorker | archive_outbox的READY/过期LEASED；archive_question为业务记录 | claim、过期耗尽处理、executeProvider均覆盖；不清question/mutation/event历史 |
| ArchiveQuestionEventDelivery.recoverOnce | archive_outbox JOIN archive_question，published_sequence < current_sequence | 这是历史事件发布而非Provider执行；不能把发布游标伪造推进为已发送 |

Archive真实表名为`archive_outbox`（`JdbcArchiveQuestionStore:201,253,277,295`），不能猜成archive_question_outbox。原agent outbox、Rabbit、hosting租用恢复、workspace执行、安装恢复仍在范围内。只读SSE历史重放、VoiceLeaseGuard租约和状态监视与执行恢复分开；JuyitingAgentRelayService的请求触发replayAdmission也不能漏，但不是自主定时器。**隔离所用逐表条件、写入口约束和离线迁移仍未冻结，本表不是可执行SQL。** 不新增通用迁移框架，不造成功/失败终态，不释放或抹掉资金事实。

### 9.4 浏览器恢复key：补齐执行与资金边界

以下为已定位的恢复存储，不是登录或偏好设置；旧字节留存，不全清storage：

| 源码 | 现用prefix | 目标处置 |
| --- | --- | --- |
| useHallDrafts | cyf.hall.submission-recovery.v1 | 新业务恢复命名空间v2；旧记录不转成新提交 |
| useHallPrivateMark | cyf.hall.private-mark-intent.v1 | v2；旧记录不转成新PATCH |
| useHallConversationArchive | juyiting:archive-intent:v1 | v2，保留identity/conversation/asset/revision作用域 |
| useHallExecutionAbandonment | juyiting:execution-abandon:v1 | v2；不得从旧key主动放弃执行 |
| useHallBountyFinalization | juyiting:finalization:v1 | v2；不得从旧key主动finalize |
| hallPointAndStartIntent | cyf.juyiting.point-and-start.v1 | v2；新store只收当前业务schema，不再将旧schema1读成可恢复意图 |
| useHallTypedDeliberation | cyf:hall:typed-deliberation:v1 | v2；typed输出历史由只读接口读取，旧body不进入新POST |
| hallRequirementCreateIntent | cyf.juyiting.requirement-create.v1 | 涉及创建/资金，不能仅换key当ABSENT；待资金隔离闭合 |
| hallBountyFollowupIntent | cyf.juyiting.bounty-followup.v3 | 涉及后续任务意图，须与资金/幂等关系一起闭合，不能机械删除 |
| economyRequestIntent | cyf.juyiting.economy-request-intent.v1 | 保留原key/body及未决标记；不得因换命名空间生成新扣费意图 |
| useSkillMarket | cyf.skill-market.purchase-journal.v3 | 有orderId的恢复走订单GET；无orderId时resumeOperation会重发quote/order POST，必须禁用旧发送路径而保留资金不确定提示与幂等事实 |

`cyf.skill-market.purchase-operation.v1`是跨页操作锁，不是网络消息；保留互斥，不因修改wire换锁名让旧新页面同时购入。useHallOnboarding为显示偏好，不在执行恢复隔离范围。这里纠正“所有恢复key直接升版本就能安全切换”的过度简化；尤其商城/经济请求仍需确定无orderId结果未知时的只读追查能力。不能丢掉未决锁定再允许同意图重新购买。

### 9.5 开发条件

已完成目标路由静态冲突消除、两个额外客户端边界定位和上述恢复源枚举；并未完成596条嵌套字段/错误/调用方逐项对齐。全系统开工标志仍为false。没有新的用户业务选择待确认；剩余是源码工程核对，不可挪到最终验收才决定。

## 10. 剩余两类核对续查（2026-10-14 23:22 +08:00起）

唯一Owner仍为本聊天主Agent；仅源码/文档，不调用模型、Provider、业务API或维护脚本，不再次清理已批准数据、不构建/重启/部署。A/W/R HEAD与已有固定基线相同；未查远端最新。已批准49行/7文件清理完成，本轮不再将测试安装/假设未决资金列作阻塞。

### 10.1 实际修正了路由截断与operationId冲突，不是新增后端接口

`AgentTaskDeliverableController.previewContent.GET`的Java注解是两段常量字符串相加。原扫描器`pathvals`只取第一段，错误登记为`/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{artifactVersion}`。真实路径末尾还含`/preview/parts/{partId}`。前端`useOutputs.outputReadAdapter.preview`确实调用后者。

修正提取器后，对固定源码684处mapping重新提取，对比所有596个operation的source SHA和路由：**只有此一条路径变更，operation数仍596，未发现未解析的路径表达式**。已同步源码库存、http-agent、审计和目标路由；不能把错误短路径当作一个兼容路由保留。原始历史证据不覆盖。该检查仍是直接注解静态检查，不冒充Spring运行时注册枚举。

此外，交叉检查发现596条库存只有595个唯一operationId：IspController两个refreshSSL重载（SSL refresh与DNS record delete）共用同一ID。现对这两个冲突ID追加路由SHA前12位作确定性后缀，保持方法/路径不变，使596条均有独立ID；否则以operationId为key的schema/测试表会悄悄覆盖一条。原来的method/path无冲突结论不能代替operationId唯一性检查。

### 10.2 已明确的动态调用增加78条绑定

`manual-consumer-bindings.json`由33条/32个operation补至**111条/104个operation**。本轮新增78条人工source-bound关系；不是新增78个接口、不是验收通过。所有引用的producer文件SHA逐条一致。

| 消费者 | 实际解析链/实施要求 |
| --- | --- |
| commandObservabilityApi / economyReadOnlyPreviewApi | get/post局部封装→固定namespace→4条看板/9条预览路由；移除自己的信封判断，保留DTO验证及只读预览语义。 |
| usePersonalWorkspaceExecution / useHallPrivateMark | request options→api.execute；7条执行/roster及2条标记关系；expectedGrantRevision、revision、嵌套manifest同步，不更改资料ACL。 |
| useHallBackendSceneState | 3个常量URL；snapshot是JSON、events是SSE、phases是POST。不能按统一stream字节处理，也不能继续用text替换大整数。 |
| useHallWorkItemPlan / useHallRequirementCreate | send/route闭包分别2条/4条；creation-operations与/v2是业务版本分支，不能误认为公共protocolVersion。 |
| useHallPointAndStart、ControlledBridge、CostConsent、BountyFollowup | task/conversation+suffix拼接已逐条落到producer；读旧操作不等于恢复退役写接口；取消平台费用授权的既定方向不变。 |
| useOutputs | task/conversation目录、workspace/artifact下载及preview两套精确引用，共8条；包括上述原库存遗漏的preview/parts。 |
| useArchiveReader | 15条绑定；loadBlock选择preface/chapter，loadPage选择bookmarks/notes；query事件是SSE，ETag/304不能改成JSON200。 |
| HallMessageMedia | hallMessageParts.conversationAssetContentPath→会话资产bytes；当前response.data instanceof Blob，目标必须直接检查公共客户端返回的Blob。否则即使下载200，页面仍会报无法读取。 |
| useHostingRent | hostingRentContract.hostingRequest五分支落到4条POST；现有托管购买/续费与技能费用登记是不同业务，不取消商城账本/余额校验。 |
| RuntimeV1Client | 4条POST明确enroll、session、heartbeat、commands/{messageId}/acks；enrollment secret、bearer runtimeAuthorization、session proof不可混用。nativeFetch仍是原始Response传输层，业务decoder只解包一次。 |
| SkillInstallManager._download | 精确/internal/agent/skill-installations/{installationId}/package；ZIP直传，维持same-origin/size/hash/session proof。该installationId是技能安装，不是Runtime安装。 |
| ops/health/cyf-juyiting-health.py | 无认证GET /agent/map检查401/403；当前为空401或多字段关键字猜测。目标精确检查R、HTTP与认证code，不再接受猜测方言。仓内脚本已脏，记录工作树SHA，不说它等于某历史commit；此轮不安装监控。 |
| contract-pilot/check-http.mjs | 实际Vue adapter→createApi→loopback Java，GET执行列表；wire fixture改body.data，adapter断言仍DTO.items。未运行该测试，不伪报通过。 |

以下扫描候选不能当成网络调用：Runtime app-server-adapter的`this.request('thread/start'...)`是进程RPC；root里的阿里云SDK `*Request`构造不是CYF HTTP；诸如requestKey、scheduleAuthoritativeRequestReadback、requests.push也不能按名字计入接口清单。历史evidence目录中的脚本是证据，不因字面路径命中就加入待发布脚本。未逐项分类的候选仍保留未核实状态，不宣布全部867个候选已闭合。

### 10.3 嵌套对象与错误已补到具体字段

见`critical-contracts.md`§10：ExecutionView及其12类关联投影、RuntimeCommand/input/output、技能Command/Inputs/sourceIdentity三分支、Fence/Lease、EffectStart/Result/Receipt、Resume/ResultCommit/输入回执/原始结果manifest，和浏览器/技能两套真实错误映射。

本轮关键对齐：
- 源码协议4创建的outputContentMimeType/runtimeCommand都可为null；Web私有执行校验不能冒充所有执行模式的schema。
- CURRENT_CONVERSATION_ASSET包含producerRequestRevision，旧受控图片RuntimeSource不是新技能sourceIdentity的替代类型。
- 参数与data输出由已验证manifest内schema约束，是**有明确边界的技能扩展点**，不需要将未来PDF/Excel/视频每种业务字段硬编码进平台；固定外壳、身份/资料授权及输入回执仍严格。
- 原ResultManifest的artifacts.byteLength是canonical摘要预像中的number；wire普通DTO的字符串迁移不能破坏原始摘要事实。已明确raw manifest独立类型及边界精确转换，不新增另一套公共信封。
- 原Controller.skillBody按整个根对象exact字段校验；实施时必须先校验Q再对Q.data执行原exact字段/嵌套Fence规则，否则加公共外壳后所有skill写请求都会400。
- Runtime readInput虽然**响应是字节**，请求却是JSON Fence；仍要将请求包Q，不能因binary响应而跳过请求规范。multipart输出上传则不JSON包裹文件流。

### 10.4 微信回调SDK核实结果与未闭合边界

固定依赖weixin-java-pay4.6.0的JAR/字节码核实：`WxPayNotifyResponse.success/fail`返回String；不存在可直接调用的`WxScanPayNotifyResponse`类，不能编一个SDK API写入详设。退款解析返回`WxPayRefundNotifyResult`，包含原/解密reqInfo和继承字段，不是对支付平台的业务处理成功回执。

源码`WxPayController.parseRefundNotifyResult`本身标TODO且只parse后返回，**没有退款业务落账/幂等处理**。必须作为EXTERNAL_CALLBACK例外核对，不能被通用JSON advice包装后声称回调成功。本次修正审计分类：EXTERNAL_CALLBACK从4→5，SELF_JSON从545→544，其余不变。支付订单通知在未找到订单时当前也落到success；扫码通知返回unifiedOrder上游XML而非已经明确核验过的通知回执。这三处均不能靠统一外壳自动解决。

本轮仅定位到真实实现与SDK类型，**尚未冻结这些回调的逐字段provider成功/失败ack及业务提交条件**；不得伪报已补齐，不在本轮默默实现退款新功能。管理代理JSON与这些外部回调必须分开，签名原始字节不能变。

### 10.5 本轮状态

`readyForWholeSystemDevelopment=false`。两类任务仍未全量闭合：
1. 其余业务/SDK完整嵌套字段与错误分支（包括上述回调）；527条SOURCE_INDEXED不能当作已定义完整schema。
2. 剩余动态调用与仓内实际脚本分类/绑定；111条关系只是已经核实的子集。

已清数据不是第三个待用户决策的阻塞。后续实物仍应逐接口补字段/绑定，不再反复要求用户确认已确定的调用方、停机、无兼容策略。全量fixture及代码验证在实施阶段另行记录；原图片“上传→识别→修改→下载”仍未验收。

## 11. 存量字段与嵌套写入续查（2026-10-14 23:49 +08:00）

范围仍为剩余两项；同一Owner、同一API/Web基线，无应用改动或线上调用。详表见[23条存量字段设计](ordinary-contracts-review5.md)。本节更新机器表但不重标ready。

### 11.1 实际补漏

- 新增4条嵌套GET写入：`/dwz/restore`通过view更新PV；`/msg/readall`、`/msg/recycle`、`/msg/restore`通过service更新行。目标POST原路径，保留query，空输入Q<null>。已查候选42→46，目标改动61→65；公开短链接导航仍GET，不混入JSON写动作。
- Dict7/Dwz7/Msg9已写字段、nullable、状态映射、页码/计数、排序白名单、请求字段和HTTP/code；保留清楚的未闭合身份/数据语义，不把这23条从未闭合项扣除。
- 消息readall的DAO只按userId更新，连DELETED也变READ；目标范围必须包含可信tenant/client/uid且仅UNREAD→READ。同Owner任务资格取消与消息资源授权无关。
- Dict cleanCache只更新某行时间，不删除Redis；selectAll使用全局DICT_LIST、无租户过滤。不能将现行注释当成功能证据。
- ShortLink当前成功data是String；Message当前宽松猜data/list/records及数值total。两处调用方更改落到字段/方法/组件事件，不留猜测式兼容。
- 手工关系111→117、覆盖operation104→110。增加ShortLink两条、message四条（list内部两种读取合为同一producer-consumer关系）。前一机器表manualBindingEvidence仍残留33条旧值，本轮同步纠正；历史证据不覆盖。

### 11.2 授权与缓存不能漏在统一客户端之外

资源链Order3在已查source prod配置匹配dict/msg/dwz/wx；只要求authenticated，源码未找到方法安全开启注解。已声明@PreAuthorize不能写成“权限已验证”。可信uid来自AccountSecurityJwtClaims，不是自报userId，也不是不存在的context.userId。

Service Worker只排除/api/与/ws/，其他同源GET可能缓存；未核实际路由可达性，不能声称已经发生泄露，但也不能归为“非业务静态资源所以无需改”。目标静态资产范围与响应no-store/private约束见续查详表。CDP调试请求、TMX资源、注入request回调另分类，不计虚假业务binding。

### 11.3 支付回调继续追到实际处理器，不增加退款功能

源码继续核对PayOrderParse、AbstractPayOrderParseService、TipPayOrderParse和GiftPayOrderService：动态注册两类前缀TIP/GIF；orderNotifyResult分别更新打赏记录/礼品使用记录状态。不能只核TIP后声称所有处理器已查。控制器先更新PayOrder.transactionId，再调用处理器；当前方法未见将两步整体包住的事务与重复通知条件，**不能认定“SDK验签通过=业务原子提交成功”**。扫码分支先创建使用/打赏记录，再调用上游并保存PayOrder，缺少整体一致性证明；传入openid却由处理器当jiacn查用户也必须对齐身份语义。

Source prod中的ignore-uris列有order/scan通知，但Order3已匹配/wx/**且只放行checksignature；Order100的ignore不能证明回调可达。目标只对精确Provider通知路由按Provider身份验证放行，不开放整个/wx/**。本轮未调用支付、未检查线上覆盖配置、未更改过滤链。

现有退款入口仍只parse无落账；不借统一规范新增退款业务或声称成功。Provider逐字段ack和各处理器业务提交条件仍未冻结，不得临近验收才发现。此项是工程核对，不是重新要求用户承担费用授权决策。

### 11.4 状态与验证边界

全量目标method/path静态冲突仍0；源码hash/消费者hash与文档链接在本轮证据中检查。修正临时目标路由脚本对“POST + 目标路径”单元格的解析，不改应用脚本。全系统开发标记仍false，剩余两类没有变成更多用户审批项；测试数据49行/7文件已清结论保持。未构建、未重启、未运行正式测试，也未验收原图片链路。

## 12. 集中核对续查（2026-10-15；尚未完成全量语义闭合）

范围：固定API/Web/Runtime源码，统一规范1.2，Owner仍本聊天主Agent；没有子Agent、线上请求、模型/Provider调用、清理、业务实现或部署。用户已确认事项不重新询问。当前进展不能改写为“本次全量核对已完成”。

### 12.1 调用关系实物与筛选方式

`manual-consumer-bindings.json`目前249条关系、215个不同operation；相较review5增加132条关系。已新增直接import/createApi词法绑定、JuyiHall注入链、条件路径展开、Runtime技能/资料桥接，以及直接fetch/XHR/SSE入口。每条保存生产/消费文件SHA；部分保存注入点SHA。静态默认factory存在不证明线上启用，关系中的SOURCE_BOUND_NOT_TESTED保持原意。

- `createApi`的list/search/create/update实际均POST；getById只是原uri附加id query；delete同时支持已有标量id与options调用形式。不能凭方法名假设PATCH、/资源/{id}等新路由。
- JuyiHall实际导入agentApi/chatApi，再注入useHallData/Conversation/TaskActions/TypedDeliberation等；不把同名参数自动当成这个客户端。typed outcome的INSPECT/CHAT路径、abandonment的GET/POST、finalization的operation/key两种读分支都分别绑定。
- 普通页面Gift/Phrase/Task/HelpCenter/WxMpManager、技能市场，以及正式交付、资料、成果、议事调用一并纳入。HelpCenter与PhraseFeedbackDialog使用FormData，统一公共请求不能把multipart变JSON；其成功回调仅收DTO，不能继续依赖code/data二次解包。
- api.js的`revokeAllSessions`是204成功，无JSON；失败才统一错误。`getUserInfo`直接fetch/user/my需要同批解包。wxJsToken为同步XHR，目标jsapi/signature POST，不能只改useHttp漏掉它；签名时间单位仍以原外部协议为准。
- Chat与Hall两个调用点都将确切/chat/conversation/events传入authenticatedSse；该公共工具接受动态URL，不能把其全部用途自动认作同一业务端点。
- 语音转写multipart与合成JSON→audio/wav分别处理；合成JSON请求同样需Q，响应不能被包R后当音频播放。
- SkillConversationLane已绑定10个操作；workspace-file-bridge新增5条资料下载/start/failure/输出上传/提交。精确URL、same-origin、native proof、文件hash及原始manifest不可变边界保留。

复用扫描器并增加局部词法解析检查（仅临时诊断脚本，不引入运行时库）：291个Web源码文件中解析到94个能静态确定namespace+URL形状的调用；不是所有调用。排除示例无斜杠拼接和useOutputs动态前缀两个已手工展开的点，避免错误新增路由。867个旧候选已逐行登记分类于`consumer-candidate-review.json`，但仍有未核定项；分类数量不能冒充全部调用证明。尤其“同文件已绑定”“像内置集合操作”不是逐调用闭合。

### 12.2 退役Runtime代码不再误算待迁移生产调用

追到`agent-client.mjs`的实际profile bootstrap：源码明确将旧nativeBountyExecutionRuntime/controlledImageV3SourceRuntime设为configReady=false；有效会话构造的是通用protocol4 SkillConversationLane。旧NativeConversationLane及两个controlled-image factory在本次固定源码中只找到测试调用。

曾匹配到的24条旧helper→现存API关系已移出当前消费者计数，单列`retired-runtime-consumers.json`。另外4条旧路径provider-start、provider-start-controlled-image、inputs-v3、provider-start-controlled-image-v3没有Controller mapping：是已退役方法撤掉注解，不是新发现应补的API。**不得为了让旧测试/旧helper跑通恢复这些入口，不新增兼容层。** 这是源码启动路径结论，不是本轮检查了所有线上Runtime进程。

根仓`docs/agent-client.mjs`却仍是可执行旧WS客户端（type/根级业务字段/API key URL方式），不能仅因位于docs就假设历史不可执行；部署接入说明也仍有独立客户端操作说明。本轮列为接入文档/示例的待收口项，不宣称现网使用它，也不默默恢复旧认证方式。统一发布需将可执行示例与现行Runtime接入入口对齐，避免形成第二套客户端。

### 12.3 再确认4个GET写入及一个回调分类遗漏

累计已查GET候选50条，目标method/path变化69条；596个目标operation静态无重复。

| 源码入口 | 查到的实际副作用 | 已确定迁移/边界 |
| --- | --- | --- |
| GET /phrase/read | MatPhraseServiceImpl.read更新pv/updateTime | POST原路径，query id保留、Q<null>；Phrase.vue和chat/Chat.vue原getById同改POST。不是公开资源导航的附带计数。 |
| GET /sms/validate | 校验后useSmsCode将记录置不可用 | POST，Q.data含phone:String/smsType:int32/smsCode:String；验证码不放query，保持原校验错误含义；原DAO查找作用域/一次性并发仍需核。 |
| GET /sms/use | 不仅查询，而是取出验证码并置已用 | POST，Q.data含phone:String/smsType:int32；这是秘密提取能力，不得因统一信封降低授权/缓存保护；实际允许主体未闭合。 |
| GET /sms/buy | 事务中新增SmsBuy并增加配置remain | POST，业务输入移Q.data；当前Double→BigDecimal.valueOf并非精确金额wire证明；金额单位/币种及购买授权未核定，不猜CNY分，也不将其混同技能费用登记。 |
| GET /sms/receive | 回调服务查send并锁定去重、可转发replyUrl、保存reply | 改列EXTERNAL_CALLBACK，不机械改method/封装；真实provider签名/ack仍待核定。测试只证明当前code/msg，不等于上游完整合同。 |

media/thumbnail内部生成衍生缓存文件，仍是GET图片读取，不当成需要用户POST的业务写；但已写字节后return "ok"、吞流异常/关闭空流等现存风险需在二进制响应迁移核定，不允许在图片尾拼JSON。

新增SMS外部例外后，响应模式SELF_JSON543、EXTERNAL_CALLBACK6，其余不变。例外必须在边界按精确路由处理，不全局豁免整个SMS模块；SDK管理代理仍自有JSON。

### 12.4 仓内脚本搜索范围补足，不执行脚本

原扩展名过滤漏看无后缀可执行脚本。本轮补查8个tracked shebang脚本，文件SHA及线索见`extensionless-script-review.json`。只有`cyf-api-kit`的本机Actuator健康读取和`cyf-web-flow-deploy`的HTML制品流/hash校验被确认网络用途；其余命中多为部署路径字面量，不当作业务API调用。Actuator/HTML不套自有JSON信封、不为统一规范改发布健康语义。

root ops中健康监控的/agent/map已绑定；admit/complete-installed-schema的安装路径和健康检查不是新的业务端点；execution_history_check调用既有contract-pilot，实际HTTP消费者由该pilot记录。历史evidence目录不能全部当当前发布输入，但仍需在具体runner存在引用时追踪；不删除旧证据。Web game/config.js编码警告仍明示，未修改文件编码。

### 12.5 真实余量：不能用批量改状态收口

机器表保留`readyForWholeSystemDevelopment=false`及596条NOT_FIELD_CLOSED。这表示**目前未建立596条完整闭合证明**，不表示已做工作为零；不能反过来说有关键链详设就全量已完成。

当前仍有381个operation没有已验证消费者关系；其中包含未使用管理接口、外部/框架入口和待解析调用，不能自动判定“无人调用”，也不能直接删除。215个有绑定的operation也不代表其所有消费者和全部字段已闭合。

尚未满足开发前要求的具体类别：
1. 各存量模块/SDK返回的完整字段、nullable、业务枚举、错误码与身份作用域；关键链TypedOutcome多态、交易/归档状态与provider回调提交/ack仍需逐项闭合。原23条详设中的授权/缓存问题仍保留，不能靠统一外壳宣称修复。
2. 剩余实际调用及候选分类：未解析的wrapper/运行时构造、旧文档可执行客户端、具体历史runner是否仍被调用，及自有/外部采集边界。现成绑定与旧退役模块已分别列清，不反复查同一条。

不扩大成修复所有历史业务的新项目：与公共wire无关的旧缺陷独立标记；但会改变媒体类型、HTTP语义、认证范围、秘密暴露、摘要/事务回执含义的缺口不能通过文档假设绕过。已批准49行/7文件清理完成，不再作为第三项阻塞。本轮无新增需要用户拍板的业务选择，也没有取得可开工结论。

## 13. 关键链与商城/预览字段续核（2026-10-15，主Agent连续执行）

不需要新的用户确认；基线沿原API/Web/Runtime及安装修复候选，未查远端最新。详设增量集中在[关键契约§11—14](critical-contracts.md)。

- TypedOutcome显示schema5与原canonical事实版本1—4分开；输入/输出hash显式适配，原摘要不重算。executionStateVersion复用已有skill行CAS version，不新建计数器。SkillResult phase采用真实九态，READY/RESULT_READY不再被Web漏判；inspection=null不代表v4没有图片输入证明。
- 商城7条、只读预览9条已补完整业务DTO表、Money映射、作用域、错误与消费者改动。商城POST保持持久FUNDS_HELD幂等回执，GET显示当前态；预览钱包tenant=jiacn与其他域tenant=0保持区分。UUID fallback、分页、安装证据与时间字段同步设计。
- 新增`target-finance-contracts.json`及`target-finance-fixtures.json`：16条operation、34定义、40组合成报文（20正/20负）；现有Ajv结构检查及7个Long边界检查通过。**只覆盖结构，不证明UTF-8字节限制、授权、事务、HTTP、跨字段全部不变量或真实计算结果。** 没有安装依赖、运行应用测试或调用模型。
- 发现真实源码断点：技能生成RESULT_COMMITTED，而ChatSelectedOutputFinalizationService文件分支四处只接受OUTPUT_COMMITTED。§14定义protocol4+COMPLETE+manifest+目录+真实bytes及原scope/grant/锁的完整证明分支，复用已有result和readConversationOutput，不新增权限/费用授权。
- 同控制器POST失败返回4xx/5xx却包装E0；明确改R<null>错误，GET原key回执仍可200返回FAILED，保持同key恢复及先reconcile。schema5消费不能直接把bountyOutputCatalog的版本4全局替换5；状态分支以outcomeContractVersion决定。

进度口径不变：596条均未达到全字段/消费者/应用证据闭合；249条人工关系覆盖215个operation，不能冒充596条均核完。上述16条增加结构实物，3条finalization增加字段/状态设计，均不自动标为FIELD_CLOSED。全系统`readyForWholeSystemDevelopment=false`，原图片链尚未验收。剩余是全量业务/SDK/回调字段错误语义及真实调用绑定两类工程核对，不是用户待确认项；不重新清理测试数据。

续核普通接口：ordinary-contracts-review5.md§7已确定USER uid及现有legacy唯一账号映射；JWT权限不只缺方法安全启用证据，也缺登录module-func权限到JWT链的对齐。Dict内部同时承载SMS/邮件配置，公开locale不能将tenant0配置全量输出。未查生产秘密、未变更认证；权限DAO/公开分类/缓存失效仍待闭合。

权限DAO续核§7.4：PermsServiceImpl两个映射误用关系id而非permsId，且角色/组查询无tenant/client谓词。已给出具体改动和relation.id≠permsId用例；共享角色/目录边界仍需核定，未改实际权限。

本轮只读源码/设计检查证据：`/home/isp/state/cyf-diagnostics/interface-predev-20261014/manual-review-7-20261015-072851/check-summary.json`；40组合成结构fixture、7 Long边界、596源路由/249绑定一致性无错误。快照先于本证据指针写入，不代表应用测试或全量闭合。

## 14. 动态调用候选与外部边界续核（2026-10-15）

Owner主Agent，仍同源码基线；无业务开发/构建/线上调用。已完成135条原UNRESOLVED_CALL_CANDIDATE逐调用分类，理由与源文件SHA写入consumer-candidate-review.json；其中包含本地状态/DOM、Map、文件校验、下载制品、共享HTTP/SSE、Provider外呼、真实资料关联等，不能将名称含request/fetch一概当业务API。分类结束不是全图结束：369条已绑定文件内调用仍需逐项确认，124条builtin形状候选与116条测试候选等没有自动抹去。

新增7条源码消费者关系：六条Conversation/Task资料关联与共享conversation SSE；累计256条关系/221个operation（原249/215）。资料关联适配器原来循环解包并从DTO猜headers，task无ETag恢复还只读第一页，具体改法见关键契约§15；没有实施或冒报恢复成功。

第三方边界见external-callback-contracts.md新增源链核对；保护外部协议的设计已确定，ACK/支付业务提交缺陷与迁移例外分开。官方文档检索未取得可保存正文，不标“官方协议已核完”。SMS外部转发→本地落库的失败窗不等于端到端幂等；OAuth state已有原子消费，不改成requestId。

**开发前与开发后分开**：设计就绪要求字段/路由/消费者/外部边界及具体实施逻辑无未决，不要求尚未开发的目标已经通过真实应用测试。以前“596条未达到全字段/消费者/应用证据闭合”的表述混入开发后验收，现明确纠正：缺真实测试本身不构成开发前阻塞；本次ready仍false是因为实际契约/调用覆盖尚有缺口，不能为改口径批量改成ready。图片验收、部署成功继续独立记录。

review8最终只读检查证据：`/home/isp/state/cyf-diagnostics/interface-predev-20261014/manual-review-8-20261015-080153/check-summary.json`。596路由、256关系/221个operation、135条候选分类源SHA、10条外部回调路由保护与结构fixture检查无错误；不是应用验收。ordinary§7.5补权限写链范围遗漏：tenant0默认迁移不证明跨域授予，不能仅改读谓词。快照先于本指针写入。


## 2026-10-15 范围纠偏（当前口径）

此前全业务字段/权限/支付事务核查和全量语法候选分类，不再作为本次接口迁移前置条件。当前有限实施矩阵及公共边界裁决见`interface-standard-detailed-design.md`末节（目录文件从上级查阅）。第三方回调保持原协议；历史业务缺陷单列，不顺手修复。596是源码库存总数，不是剩余未核对或需改代码数量。审计JSON保留历史remaining并记录currentMigrationScope；未据此虚标全系统设计/开发/验收完成。本轮仅文档及本地源码回读，不构建、不部署、不访问生产数据。
