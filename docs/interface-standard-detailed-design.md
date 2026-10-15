# 统一接口规范迁移详设（字段与代码逻辑）

核对日期：2026-10-14。任务：`JYT-SKILL-SE2-20261013`；唯一Owner：本聊天主Agent。

**状态：设计已确定；本文中的“新增/修改/删除”均为待实施目标，不表示代码已实现或已上线。** 公共定义只引用[统一接口规范1.2](interface-standard.md)，差异来源见[影响分析](interface-standard-impact-20261014.md)。本文不启动协议迁移、DDL、构建或发布。

后续逐接口复查发现的补充/修正见 [接口目录](interface-catalog/README.md)及[风险校核](interface-catalog/risk-review-20261014.md)。裸DTO、会话技能执行、一次性凭据重放、202、SSE游标及费率以规范1.2和该目录的显式补充为准；本文未作源码迁移。

## 1. 范围、基线和明确决策

| 组件 | 固定源码 | 本文路径简写 |
| --- | --- | --- |
| API develop | `7e2d07a05b9e9b18461e97abcff44642504320ff`，tree `0c28979ec1363f27b9d90b3b11b37456002250a5` | A = `/home/isp/wsps/cyf/api` |
| API未发布修复候选 | `75a050c7d39b15dad22a9822256dcb9da6f3a7fe` | C = `/home/isp/wsps/worktrees/skill-install-lifecycle-20261014` |
| Web | `a900ea4cff8158decc9b28888392c091fd0c62fe` | W = `/home/isp/wsps/cyf/web` |
| Runtime受分析源码 | `b5126ea2a81a5c374f77c8f9c025b8796dd751ee` | R = `/home/isp/wsps/worktrees/skill-execution-20261014-runtime-111` |

本轮回读上述A/W/R HEAD一致；远端沿用影响分析的查询记录，未重新声称远端最新。以上均不是本轮线上核验。根工作区已有大量无关修改，只增补本任务文档。

确定的实现边界：

1. 在现有common、HTTP边界、WS handler、Runtime codec内改，不新增网关、协议服务、队列、通用工作流引擎。
2. 公共信封只解包一次。Web `useHttp.execute()`目标返回**业务DTO**；HTTP原始Response另存，不再返回整个JSON信封让各页面自己拆。
3. Java内部领域/DB类型不随wire统一批量改名；对外DTO明确映射。禁止全局把所有Long序列化成字符串（会破坏时间戳）。
4. 公共类型的所有活跃消费者必须同批就绪；“按模块开发”不等于可先单独上线common。聚义厅<2.0不保留旧/新双解析。
5. 当前图片交付的现行wire缺陷修复与本迁移分开。r18的Java UDS失败原因尚未证明，本文不把它归因为信封问题。
6. 仅取消同Owner接任务资格，不删认证、资源ACL或租户条件；不新增外单。技能费用只登记，不增执行费用授权；原商城订单/结算不删除。

## 2. 公共类型：字段级变更

### 2.1 Java实体（A下）

目录：`common/jia-common-core/src/main/java/cn/jia/core/entity/`。

| 文件/类型 | 当前字段或逻辑 | 目标及代码动作 |
| --- | --- | --- |
| `Result.java` | `String code="E0"; String msg="ok"` | 保留；不在基础Result中塞request上下文，避免影响非HTTP领域使用；禁止Controller裸返Result，由JsonResult承载 |
| `JsonResult.java` | 无协议版本/关联ID | 新增`int protocolVersion=1`、`String requestId`；版本固定，不允许调用方任意覆盖；边界填requestId |
| 同上 | `int status=200` | 删除字段及带status构造参数；调用处改`ResponseEntity.status(httpStatus)`或Servlet真实status；不得留下只设置body的假错误 |
| 同上 | `String location` | 删除；确有创建/重定向语义的调用点改HTTP `Location` header；不把location悄悄塞进业务data |
| 同上 | `T data` + 类级NON_NULL | data属性显式ALWAYS序列化；成功无值/错误无详情输出`data:null`；不可全局开启全部业务null字段 |
| 同上 | `success/failure`工厂 | 保留现有code/msg工厂语义；旧带HttpServletRequest国际化工厂不扩散。协议填充由服务层边界负责，不让core依赖新增Servlet上下文 |
| `JsonResultPage.java` | extends Result，根`pageNum,total,status`和列表`data` | 改为`extends JsonResult<PageData<T>>`，不再声明根分页字段。提供`page(items,pageNum,pageSize,total)`工厂；删除旧列表setter/构造器，编译暴露遗漏调用者 |
| 新增`PageData.java` | 无 | `List<T> items; int pageNum; int pageSize; String total`；items非null，total规范非负十进制；空页保留真实pageNum/pageSize |
| 新增`CursorPageData.java` | 无 | `List<T> items; String nextCursor; boolean hasMore`；nextCursor可null，终页false/null；不加total |
| `JsonRequestPage.java` | draw/pageNum/pageSize/search/extra_search/orderBy | 作为请求data DTO保留`Integer draw`（可选业务回显，不再作公共关联）、`Integer pageNum/pageSize`、`T search`、`String extraSearch/orderBy`；删除`@JsonProperty("extra_search")`，不设旧别名 |
| 新增`JsonRequest.java` | 无公共请求类型 | `record JsonRequest<T>(int protocolVersion,String requestId,T data)`；验证三个根字段都存在。data能否null由具体操作决定 |

现行JsonRequestPage默认/最大pageSize均100，setter将输入夹在1..100。本次不借统一规范改变已验证分页容量：先拒绝非整数/类型强转，保持当前数值夹取行为，响应返回**夹取后的实际pageSize**；其他接口各自原容量不强制改100。orderBy必须映射既有白名单，不拼接任意SQL。

`protocolVersion`不允许setter写任意值；反序列化请求缺失/非1拒绝。`requestId`不能为空，也不能在序列化时临时随机生成而与header不同。普通ID按既有ID语法保留不透明字符串（如`rti_...`）；只有数值型ID/Long计数才使用十进制字符串，不把不透明ID硬转数字。

### 2.2 HTTP装配位置及流程

在A的`common/jia-common-service/src/main/java/cn/jia/core/`中落地：

| 位置 | 具体动作 |
| --- | --- |
| 修改既有`filter/RequestIdFilter.java`（不新增第二个filter） | 复用request属性/MDC及dispatch处理；自有接口按公共规范校验requestId，不沿用8..128正则作为新增协议限制。无header时先生成临时ID，body合法ID被接纳后更新属性/MDC；finally从最终属性取响应header，不能用进入filter时捕获的旧ID覆盖。外部入口不强制公共requestId |
| 新增`config/ApiEnvelopeAdvice.java` | 支持受管HTTP出口的JsonResult及其子类（含声明Result/Object但实际JsonResult的分支），填充protocolVersion/requestId、设置同值响应头；已存在不同requestId报内部协议错误，不静默覆盖；不包裸Map、字符串、流、OAuth |
| 新增`config/ApiRequestEnvelopeAdvice.java` | 仅匹配`JsonRequest<T>`参数；严格读信封及data，校验body/header；若无入站header，使用合法body.requestId替换最初生成的临时ID并更新上下文；类型化DTO校验通过后才进业务 |
| `resolver/GlobalExceptionResolver.java` | 统一真实HTTP status、JsonResult错误；删除`exception.getLocalizedMessage()`直出；不把ModelAndView的任意model Map当JSON信封 |
| `config/ExceptionHandlerAdvice.java` | 去除setStatus；显式ResponseEntity；与resolver定顺序，同一异常只处理一次。保留HTML视图响应边界 |
| `security/SensitiveResponseBodyAdvice.java` | 此类实际改写JSON，不是日志工具；禁止全局关闭。普通响应保留现有保护；已授权的签名/摘要DTO在构造边界排除秘密，再按精确方法声明保真输出，沿用`AllowSensitiveOutput`而非扩大路径前缀放行；外部回调按精确入口保留原始wire。凭据专属响应维持原认证及no-store |
| 既有安全filter/entryPoint/accessDeniedHandler | 401/403也走同一错误工厂和request属性；不能依赖MVC Advice兜住filter异常；不改变认证顺序/作用域 |

请求处理顺序：关联ID初始化 → 原认证链 → JSON解析/重复键检测 → 信封与声明身份校验 → DTO校验 → 资源授权 → Service事务 → DTO投影 → 已提交结果封装 → 实际HTTP输出。

- 解析器对本系统JSON启用重复键拒绝、尾随JSON拒绝；DTO禁止未知字段、标量隐式强转。专用ObjectMapper/reader限定自有接口，不能全局改Provider/OAuth解析。
- Java泛型反序列化保留`JsonRequest<具体DTO>`实际类型，不用裸Map绕过字段校验。GET/HEAD不读取JSON body；query逐字段转换，非法重复标量参数拒绝。
- requestId为空、header/body不一致为400；解析前认证失败仍按原401，不能泄露资源；不可解析body的错误使用已验证header或服务器生成ID。
- MVC Advice只补元数据不二次包装。直接写Servlet的JSON出口显式调用同一个错误/输出函数；不能遗漏404路由、415、405及安全异常出口。
- HTTP错误映射沿用规范；原稳定业务code不重编号。新增纯协议错误集中在现有错误常量处：`PROTOCOL_INVALID_ENVELOPE`(400)、`PROTOCOL_UNSUPPORTED_VERSION`(400)、`PROTOCOL_REQUEST_ID_MISMATCH`(400)。客户端的无效服务器响应属于本地`PROTOCOL_INVALID_RESPONSE`，不能伪装HTTP状态或业务成功。
- 非2xx错误不得带E0；2xx带非E0也算协议错误，不能触发成功回调。客户端对HTML/空网关错误保留真实HTTP status、requestId及network/http分类，不从任意message字段猜业务错误。

### 2.3 目标签名与最小调用逻辑

```java
// 设计签名，不是本轮已新增源码
record JsonRequest<T>(int protocolVersion, String requestId, T data) {}
record PageData<T>(List<T> items, int pageNum, int pageSize, String total) {}
record CursorPageData<T>(List<T> items, String nextCursor, boolean hasMore) {}
// Controller：授权对象从认证边界解析，不能从request.data中的tenantId构造可信身份。
// ResponseEntity<JsonResult<ResultDto>> execute(JsonRequest<CommandDto> request, Authentication auth)
//   scope = identities.resolve(auth);
//   result = service.execute(scope, request.data()); // 事务提交后返回
//   return ResponseEntity.ok(JsonResult.success(result)); // Advice只补公共元数据
```

响应示例（空页是DTO，不是第二层信封）：

```json
{"protocolVersion":1,"requestId":"req_page_1","code":"E0","msg":"ok","data":{"items":[],"pageNum":1,"pageSize":20,"total":"0"}}
```

## 3. Runtime HTTP：逐DTO映射

A目录：`agent/jia-agent-core/src/main/java/cn/jia/agent/entity/`；入口`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentRuntimeV1Controller.java`。路由`/agent/runtime/v1`及已验证资源路径不因信封统一改名；POST参数改`JsonRequest<原DTO>`，业务DTO只移入data一次。

| DTO | 字段目标（未列字段保持原业务语义） |
| --- | --- |
| `AgentRuntimeV1RuntimeRequest` | `installationId→runtimeInstallationId:String`；tenantId/clientId/canonicalAgentId/manifestVersion/health保留String；manifestSha256保持裸64hex |
| `AgentRuntimeV1SessionRequest` | 同上安装字段；hostId/runtimeInstanceId保留String；manifestVersion/manifestSha256保持；tenantId/clientId/canonicalAgentId只与可信身份比较 |
| `AgentRuntimeV1SessionResponse` | 安装字段改名；`sessionGeneration:long→String`；scheme/sessionToken/websocketPath/status保留原语义；凭据仅该私密响应可见 |
| `AgentRuntimeV1InstallationView` | 安装字段改名；`enrollmentExpiresAt:long`保留毫秒number；`lastHeartbeatAt:Long`保留可null毫秒number；其余String保持 |
| `AgentRuntimeV1AckRequest` | `installationId→runtimeInstallationId`；`sessionGeneration:long→String`、`deliveryVersion:Long→String?`；`expiresAt:String(ISO)→Long(epoch ms)`；messageId/correlationId/commandId/taskId/workItemId/payloadReference/status/hostId/runtimeInstanceId及作用域字段保留原业务语义 |

HTTP ACK请求中的`data.messageId/correlationId`是被提交的业务ACK标识；HTTP本身无需根messageId。path中的messageId与DTO按原语义比对，不得拿HTTP requestId代替commandId。HTTP信封requestId用于本次逻辑操作；业务data.requestId若代表chat request，保留其原语义、不可覆盖。

实体映射规则：
- `dto.runtimeInstallationId()`传给原service的installationId参数；不直接改表列或原安装主键。
- generation/deliveryVersion在边界以十进制校验后`Long.parseLong`并验证实际范围；输出用`Long.toString`。JS比较使用BigInt，不先Number后转字符串。
- 新命令expiresAt使用epoch毫秒；**历史原命令及fingerprint的ISO字符串不重写**。本次停机切换不等待或续跑旧在途，旧输入隔离于新执行路径，目标接口只发新格式；不能运行时兼容Date.parse(string)与number两套。
- header名称`X-Agent-Installation-Id`暂保持既有传输语义（仅指Runtime）；其值比较`data.runtimeInstallationId`，不是技能安装ID。其他X-Agent-*头与Authorization机制保持；generation头原本是文本，校验规范十进制并与会话记录比对。

R的`conf/cyf-agent-runtime-v1/lib/runtime-client.mjs`改动：
1. `identityOf(manifest)`显式输出runtimeInstallationId，manifest磁盘格式仍按其自身schema读取；不能全局搜索替换签名manifest。
2. `post`先构造JsonRequest，严格解析完整JsonResult、校验HTTP/code/requestId，再返回data；现有仅检查`json.data`的逻辑删除。
3. `validateRuntimeSession`用字符串/BigInt验证代际；`runtimeSessionHeaders`从新session DTO取值。
4. `validateCommandForManifest`字段集合更新，expiresAt只接受安全整数毫秒；保留身份比对、原安装生命周期与原任务到期规则的区分。
5. `validateRuntimeAckResult`及installation/enrollment解析同步改类型/字段；`classifyRuntimeError`保留AUTH/TLS/NETWORK/HTTP/ABORT等真实分类，不因规范异常改成可重试网络失败。
6. durable credential文件和manifest中的旧字段名属于**磁盘模型**，通过显式`toWireIdentity/fromWireSession`映射，不是网络双协议；如要改磁盘schema另立迁移，不把它混在本次DTO重命名。

## 4. WS/SSE：发送与接收必须成对改

### 4.1 公共消息代码

A在common-core的entity目录新增`ProtocolMessage<T>`（传输消息不是第二套HTTP响应）：字段`int protocolVersion; String requestId,messageType,messageId; T data`，可选`String correlationId,code,msg`。请求/独立事件禁止code/msg/correlationId，回复三者必填；不使用NON_NULL掩盖必填错误。构造入口分`event(...)`、`reply(original,...,code,msg,data)`，内部统一验证。HTTP信封不嵌进message.data。

A `chat/jia-chat-service/src/main/java/cn/jia/chat/handler/AgentWebSocketHandler.java`：
- `sendEvent(session,type,payload,...)`改为`sendMessage(session,ProtocolMessage<?> message,...)`；删除把payload铺根再追加type/channel/timestamp的逻辑。
- 原channel若仅标识已绑定WS通道则删除wire字段；业务真正需要的channel在对应DTO声明；timestamp若作为业务事件时间使用则改`data.occurredAt`(毫秒)，不能在所有消息根新增字段。
- 所有dispatch/广播/direct delivery入口先构造同一消息；删除`directCompatibilityType`及type/body猜测解包。业务service只交付DTO，不传已拼好的根Map让handler再包一次。
- 接收先严格解析公共信封，再按messageType路由精确DTO；验证连接身份及任务/资料授权后进入现有状态机；不可只改sendEvent而留下入站旧解析。
- SSE的`event/id/data`分别映射messageType/messageId/完整信封；序号在业务DTO中以字符串传递，断流不是done。

已定位发送名的**目标固定映射**（旧名只用于迁移对照，不能进入目标parser）：

| 现行发送名 | 目标messageType | data及关联处理 |
| --- | --- | --- |
| connected / protocol_hello / protocol_error | connection.established / protocol.hello / protocol.error | 握手DTO、能力DTO或安全错误详情；错误若回复某消息须correlationId |
| ping / pong | connection.ping / connection.pong | ping请求，pong回复关联ping.messageId；不靠timestamp关联 |
| started / delta / done / stopped | chat.stream.started / chat.stream.delta / chat.stream.completed / chat.stream.stopped | 保留流标识、片段、序号、终态；消息是否操作回复由具体事件schema固定 |
| agent_status | agent.status | 独立状态广播；ownerJiacn与tenantId分离，tenantId从已认证接收会话取值，不触发注册或业务执行 |
| agent_registered / agent_capability_index / agent_status_updated | agent.registered / agent.capability.index / agent.status.updated | 原业务字段移data，长期代际转字符串 |
| task_reported / chat_dispatch_acknowledged | task.report.receipt / chat.dispatch.receipt | 事务提交后成功回执，correlationId绑定提交消息 |
| agent_message / agent_message_delta / agent_message_saved | chat.message / chat.message.delta / chat.message.receipt | 消息保存回执不是执行成功；correlationId关联发送消息，数据库消息ID为data.persistedMessageId，不占用信封messageId |
| work.result.receipt / work.report.receipt | 同名 | 不再外套旧type；根code/msg区分本次处理成功失败 |
| 已规范点分messageType（command.dispatch、task.event等） | 保留已有点分常量值 | 删除并行type/actionType协议别名；业务动作枚举在data明确声明，不是根别名 |
| error | protocol.error或该操作失败回复 | 有可解析请求时回复原操作；不能把所有业务失败硬换protocol.error |

未逐条读取的注册/任务/聊天DTO不可据此表自动批量搬Map：实施使用现有handler字段集合补齐同源schema中的required/type/enum，并记录所有生产者和消费者。此表已确定命名与封装规则，不代表全WS消息库存已穷尽。

### 4.2 技能安装结果/回执

A `agent/jia-agent-service/src/main/java/cn/jia/agent/skill/SkillInstallResultService.java#receipt`；C的`chat/jia-chat-service/src/nativeSkillResultTest/java/cn/jia/chat/handler/SkillNativeResultMySqlTest.java`；R `conf/codex-ws-agent/skill-install-manager.mjs`及`conf/cyf-agent-runtime-v1/lib/execution-adapter.mjs`同批改。

| 现行字段 | 目标位置/类型 | 规则 |
| --- | --- | --- |
| schemaVersion | data.schemaVersion:number | 业务版本；结构变化发布新业务schema，不能把公共protocolVersion替代它 |
| messageType/messageId/correlationId | 公共根，String | correlationId严格等于收到的结果messageId |
| resultType/receiptStatus | data，String枚举 | SKILL_INSTALL_RESULT、ACCEPTED保持业务语义，不把ACCEPTED等同所有安装成功 |
| installationId（技能） | data.skillInstallationId:String | 从原技能安装记录投影，禁止adapter注入Runtime安装值 |
| commandId/targetAgentId | data，String | 绑定原命令、目标Agent |
| attempt | data.attempt:number | 原正整数规则；重投不自增 |
| fencingToken/deliveryEpoch | data，十进制String | 拒绝过时fence；保留原CAS约束 |
| 原执行runtimeInstanceId | data.executionRuntimeInstanceId:String | 结果声明绑定原启动实例，重连不能替换 |
| 当前传输runtimeInstanceId | data.runtimeInstanceId:String（仅需要会话声明的DTO） | 与认证连接匹配；没有需要的回执不滥加身份字段 |

执行逻辑：解包→信封校验→连接身份→原命令/安装/订单/fence校验→幂等及摘要比对→同事务写安装状态与结果→提交→创建/重放确定的receipt→发送。发送失败保持已提交事实，等待同原结果重投；相同messageId异参拒绝，不能再次安装。

receipt.messageId沿用确定性派生的思路，但目标消息的摘要输入/域标签及完整fixture必须固定；不将旧receipt字节原地重签。Runtime只有验证成功回执的correlationId、安装ID、commandId、attempt/fence/epoch全部匹配后才清理对应pending，其他回执不得清队列。

adapter改为两份明确对象：`transportIdentity=currentSessionIdentity`、`businessResult=immutableExecutionResult`；显式构造目标DTO，禁止`{...businessResult,...identity}`和“先覆盖再恢复”的补丁分支。当前会话认证与原始执行事实分别验证。

## 5. UDS托管控制：不另起协议

A `agent/jia-agent-service/src/main/java/cn/jia/agent/hosting/UnixManagedHostingProvisioner.java`与R `conf/cyf-agent-runtime-v1/lib/{hosting-wire,hosting-server,hosting-control}.mjs`一起改；私有socket、权限、连接及一行JSON framing保持。

| 现行wire | 目标 |
| --- | --- |
| protocol="runtime-hosting-v1"、method | 删除这两个根字段；protocolVersion=1，messageType=`hosting.capabilities/prepare/ensure/observe` |
| 响应protocol/method回显 | 对应messageType后缀`.result`，correlationId=request.messageId；requestId同请求 |
| tenantId/clientId/ownerJiacn | data内String声明，与可信本机配置允许scope精确核验 |
| canonicalAgentId/bindingId/leaseId/initialIntentId/operationId | data内String，原操作关联关系不变 |
| operationKind | data内INITIAL或REPROVISION；不接受任意命令/路径 |
| reservedAt/requestedAt | data内毫秒number |
| validUntil | `data.validUntilAt`，INITIAL为null，REPROVISION为有效毫秒number；保留时间先后验证 |
| installationId/manifestSha256/provisionGeneration | `data.runtimeInstallationId:String`、`manifestSha256:64hex`、`provisionGeneration:String`；ensure/observe必填 |
| available/hostId | capabilities结果data内boolean/String |
| outcome | data.outcome保留PREPARED/SERVICE_READY/UNKNOWN/RECOVERY_REQUIRED；传输/输入拒绝用非E0和安全code，不冒充UNKNOWN业务事实 |
| sessionGeneration | SERVICE_READY结果data内十进制String |
| serviceReadyAt/registeredAt | SERVICE_READY结果data内毫秒number |
| runtimeInstanceId/executorReady/durableReady/evidenceRef | data内String/boolean，原可信在线证明比对保留 |
| prepare响应manifest/enrollmentExpiresAt | data内原类型；manifest为有自身schema/签名的独立对象，不用公共命名规则原地改签名材料 |

`hosting-wire.validateHostingRequest`先验信封，然后按messageType选精确data字段集合；不能继续用旧protocol/method字段表验新报文。`hosting-control`内部association/journal可以维持原本地结构，由边界映射validUntilAt、runtimeInstallationId及generation；映射不会把旧网络格式继续接受为兼容协议。

Java `exchange`生成messageId/requestId，严格检查回复messageType/correlationId/code后返回类型化data。`probeCapabilities`仍fail-closed，但catch必须记录安全的`requestId,stage,errorCode,causeClass`；不能输出凭据/完整wire，也不能只留false。`UNKNOWN`只说明未取得完成证明，仍走原observe/恢复路径，不新建order/intent。

`SERVICE_READY`只有同时通过当前数据库registered proof、scope、host、runtime实例、generation、时间关系及executor/durable ready条件才成立。规范化不能只凭`code=E0`绕过这些检查。原frame限制与真实网络timeout保持原依据，不新增SLO硬门槛。

## 6. Web消费：取消各页各自猜测

### 6.1 公共HTTP入口

W `src/composables/useHttp.js`：已有fetch，不是axios。

新增W `src/utils/apiEnvelope.js`（无网络/无状态的纯函数），职责仅：
- `encodeApiRequest(data,requestId)`返回三字段信封；
- `decodeApiResponse(text,{httpStatus,requestId,responseRequestId})`严格解析五字段，返回`{value,requestId}`；
- 复用/抽取仓内已验证的重复键解析器；若无可复用实现，增加最小JSON token检测或经项目依赖流程引入严格解析器，不能用JSON.parse后声称能检测重复键，也不写正则扫描JSON。

`useHttp.execute`目标流程：
1. 每个逻辑请求确定requestId并设置X-Request-Id；重试沿用同一requestId及Idempotency-Key。RUM采样是否启用不影响请求ID。
2. 自有JSON POST/PUT/PATCH及有明确body的DELETE封装一次；GET/HEAD不发body。`FormData`原样发送，不手设boundary；不把上传文件base64化。
3. fetch返回命名`httpResponse`，赋给外层`response.value`，避免现有局部response变量遮蔽；读取text一次送严格decoder。
4. 合法成功：`data.value=decoded.value; onSuccess(decoded.value, metadata); return decoded.value`。对调用方返回的一律业务DTO，普通接口无内容返回null。metadata是本次请求局部构造的`{status,headers,requestId}`，headers为小写键普通对象，不是第二层wire信封；沿用已有onSuccess第二参数传递。`createApi`每次创建临时useHttp实例，调用方不能依赖其未暴露的response ref；需ETag/Content-Disposition的资料操作在本次调用的回调闭包捕获metadata，await后再作身份代际检查，不读取跨请求共享的最后响应。仅作观察的response ref仍保留原始httpResponse。
5. 非2xx或code错误：抛现有Error扩展字段`status,code,requestId,failureClass`，不再猜`message`；失效认证仍触发现有重新登录，generation变更仍取消旧请求。
6. 明确的204/HEAD返回null，不解析JSON；blob/text/stream保持专用返回类型。二进制开始前的JSON错误按Content-Type识别，不能保存为损坏图片。
7. OAuth/Provider等外部请求放回其明确专用客户端。不得新增`autoDetectEnvelope`或失败后退回旧响应的开关；自有JSON客户端只接受目标规范。

### 6.2 逐调用文件

| W下文件 | 改动逻辑 |
| --- | --- |
| `src/composables/juyiting/useHallSkillResults.js` | 删除`response?.data?.schemaVersion`、`response?.data?.data ?? response?.data ?? response`；`const dto=await ...; skillResultProjection(dto,reference)` |
| `src/composables/juyiting/hallSkillResults.js` | 仍只验证业务DTO；摘要目标字段改resultSha256，与服务端投影同步；保留conversation/task/request/execution精确绑定、COMPLETE+RESULT_COMMITTED判定 |
| `src/composables/juyiting/hallSkillCapabilities.js` | 接收一次解包后的目录DTO，保留封闭字段、代际及目标Agent校验，不因封装变化放宽 |
| `src/composables/juyiting/useHallTaskActions.js` | 删除undefined/null/0/200成功码集合；HTTP成功由公共边界保证，业务动作只处理DTO及状态，不判断code |
| `src/composables/juyiting/useHallData.js` | 删除data/root分支及宽松成功码；分页改读DTO.items/pageNum/pageSize/total |
| `src/composables/useSkillMarket.js` | 不再自己判断body.code；购买/安装读取DTO，失败catch稳定code；不能把HTTP成功等同安装ACTIVE |
| `src/composables/usePersonalWorkspace.js` | await返回即DTO；移除额外response.data访问，保留执行状态/实例/取消逻辑 |
| `src/composables/commandObservabilityApi.js`、`economyReadOnlyPreviewApi.js` | 同一边界；保留既有只读语义，不因接口统一触发操作 |
| `src/stores/api.js` | OAuth access_token/token_type/expires_in保持外部协议；自有JSON调用迁到公共decoder；身份清理/重新认证不删 |
| `src/composables/juyiting/bountyOutputCatalog.js` | 继续验证固定request/step/output与assetRef/revision；字节长度按§7字符串规则改BigInt校验；预览/下载URI严格绑定原资源，不从任意URL抓图 |

`createApi`及其导出的task/phrase/kefu/user/msg/agent/vote/tip/chat/dwz/gift/wx/economy封装全部继承返回值变化。必须逐个调用点改`response.data`和分页，而不是只改上表几处后上线。页面的业务`dto.data`（如技能数据型结果）合法，不能机械删掉所有“.data”。

本次W/src定向搜索未定位实际WebSocket构造端，不臆造一个文件名。若浏览器有独立订阅包、生成代码或网关转发，实施清单必须固定真实位置；没有浏览器WS消费者不必为了规范新建一条连接。API Agent WS与R消费者仍是已确认必改项。

## 7. 技能结果、资料和字段精度

A `chat/jia-chat-service/src/main/java/cn/jia/chat/api/{TaskSkillCapabilityController,ChatSkillResultController,ChatBountyMediaController}.java`及对应projection/service：

| 对象 | 当前→目标 | 处理 |
| --- | --- | --- |
| 能力/结果HTTP外壳 | JsonResult旧外壳→目标五字段 | 去setStatus；ResponseEntity真实status；目录不可用与无权限不混同 |
| SkillResult业务DTO | schemaVersion、conversationId、conversationGeneration、taskId、requestId、stepId、executionId、state、phase、data、outputs | 保留语义；conversationGeneration已字符串继续；schemaVersion随业务字段破坏性变化升级至2；data字段仍是技能数据输出，允许null，不是重复信封 |
| 结果摘要投影 | resultDigest→resultSha256 | HTTP投影明确从已经校验的manifest.resultDigest读取；不重写历史manifest及摘要输入；Web reference/比较器同步重命名其UI投影字段 |
| 原持久manifest摘要 | 原resultDigest及其canonical对象 | 存储schema维持原定义与验签；对外投影字段可改名但不是重算摘要；目标新存储schema若要改名另列hash版本及迁移 |
| 资料/成果sha256 | 保留裸64hex | byte hash不是JSON hash，下载必须逐字节匹配 |
| byteLength | 现浏览器safe number→十进制String（Java原Long显式输出） | Web BigInt判非负；需要进Blob/显示时单独验证范围，不用Number做权威等值；传输Content-Length仍遵循HTTP |
| assetRef | `{assetId,revision}` | 原ID字符串、revision正十进制字符串保持；固定版本授权，不取latest |
| replaces | requestId/stepId/outputId/sha256 | 精确替换关系不改；修改图片形成新成果，不覆盖原资料 |
| 金额登记 | 结算金额→`{amountMinor:String,currency:String}`；运行单价→明确rate对象 | SILVER的minor就是micro，原整数一对一映射；费率使用精确amountDecimal及unit；不增加支付/预算准入 |

`ChatSkillResultController`先用原canonical算法验证manifest，再映射resultSha256；不能对映射后的API DTO重新算旧resultDigest。固定业务DTO字段校验更新schemaVersion与允许集合，保留所有状态/隔离校验。

资料链路维持：上传multipart→登记固定资料引用→检查Agent任务授权及该资料版本ACL→技能使用引用→归档结果→提交目录→下载再次授权。仅是资料附件不代表Runtime已经看见图像；识别要验证实际输入装配及模型输出，修改要验证真实新文件及摘要。平台信封不新增INSPECT_INPUTS专属调度框架。

## 8. 幂等、持久消息与摘要：禁止随格式重算历史

### 8.1 HTTP副作用

优先使用原订单/操作幂等存储，不增加通用幂等服务。边界取Idempotency-Key，业务以`(可信主体,操作,key)`唯一约束与规范化业务输入摘要比对；requestId不进入业务去重键，认证token不进入摘要。同key异参409，同key同参读原结果；一次性凭据不回放秘密，按显式409恢复契约处理；事务失败不能留下伪成功缓存。并发检查与写入依靠原事务/唯一约束，而非进程Map先查再写。

尚无幂等存储的具体副作用接口，迁移前必须列出原实体可用键；确需DDL另列精确表/列/备份验证，不假称本文授权DDL。重试传输失败不自动生成新key；不可推定超时未扣款或未执行。

### 8.2 四类对象分别处理

| 对象 | 本次设计 |
| --- | --- |
| HTTP/WS新投影对象 | 按目标序列化；明确其自身schema/fixture，不能拿旧raw hash做新wire hash |
| 历史command/outbox/inbox/pending raw | 原字节、messageId、fingerprint、expiresAt、attempt/fence保留；旧输入退出自动投递/恢复路径，不等待旧闭包完成，不添加旧wire兼容 |
| 历史结果/执行事实 | 原executionRuntimeInstanceId、原canonical摘要保留；新查询是显式投影，不修改事实 |
| manifest/typedInspection/凭据及托管journal | 各自schema不因公共信封自动变；由明确adapter读内部字段，不迁路径、不重签、不改profile |

新消息摘要仍使用业务现有canonical算法；新增字段/算法版本必须写入业务schema，不额外发明公共`hash(anyMap)`。同源fixture同时固定“摘要覆盖的原对象、规范化字节、预期SHA-256、最终完整报文”，防止各语言对null/数值/键序不同。公共requestId和当前认证会话不能悄悄加入已冻结业务摘要。

### 8.3 协调切换顺序

1. 在现有handoff固定API/Web/Runtime/UDS与仓内脚本版本及fixture摘要；用户确认没有其他调用方。所有受影响生产者和消费者同批修改，不分模块发布。
2. 在源码实施前核清旧消息自动投递/恢复入口；制定停机后精确隔离旧执行输入的操作与备份，不等待旧pending/回执完成，不实现旧任务续跑。不得清空资料/订单/账本，不伪造ACK或安装成功。
3. 同批制品验证完成后，在用户已授权的协调窗口停止相关服务和业务入口；不为了排空延长等待。与其他聊天共享的Runtime操作仍需协调，不抢停无关服务。
4. 安装API/Web/Runtime对应制品及仓内脚本，完成旧执行输入隔离，UDS双端同时就绪。重新认证建会话，缓存页面重新加载；不混用新API和旧页面，不接受旧wire。停机不是自动清除持久队列，必须核验启动不会自动重放旧执行。
5. 以全新任务验证HTTP/WS/UDS和图片上传→识别→修改→下载，确认成功后开放业务入口；旧任务完成率不作验收条件。新任务的认证、ACL、幂等、事务和恢复仍验证。
6. 新消息开始写入后禁止单组件回退；如需回退须全端一致，不用旧备份覆盖新订单/成果。记录版本、制品、维护窗口和实际验收结果。

## 9. 配置和部署影响清单

| 配置/资产 | 是否需要改 | 明确边界 |
| --- | --- | --- |
| Java公共JSON配置/Advice注册 | 是，源码配置 | 限自有JSON；先验证注册顺序及filter错误出口，不全局套Provider |
| CORS/proxy | 条件性 | 实测若缺失，allow X-Request-Id/Idempotency-Key，expose X-Request-Id及实际需要的Location；不能放宽origin、credentials或所有认证头 |
| Web VITE_API_BASE_URL、认证配置 | 否 | 地址、OAuth不因改信封换服务；不新增“宽松模式”环境变量 |
| Runtime API/socket地址、Provider/model | 否 | 原传输目标不变；不改模型/价格/密钥 |
| Runtime各角色codexHome | 否 | 保留`/home/isp/state/cyf-agent-runtime-v1/unified/agents/{wuyong,linchong,lujunyi}/codex-home` |
| workspace policy | 否 | 保留`/home/isp/conf/cyf-agent-runtime-v1/workspace-policies.json`，不复活旧apps路径 |
| typedInspectionProfileId、证据、隔离根 | 否 | 不借规范更新套旧candidate，不重标contractReady或重测模型 |
| 数据库 | 默认无DDL | DTO映射即可；持久消息/幂等不足时单列授权，不批量改列名 |
| 技能包/费用 | 无公共协议专属配置 | 包只声明业务输入输出及费用登记；不为图片/PDF/Excel/视频各添一套协议配置 |
| 构建依赖/服务 | 默认不新增 | 共用schema/fixture即可，不引入运行时schema注册服务/代码生成平台 |

## 10. 同源schema与验证：具体产物和断言

目标产物（尚未创建）：A `docs/contracts/interface-v1/`内：
- `http-request.schema.json`、`http-response.schema.json`、`message.schema.json`：公共结构唯一来源；`additionalProperties:false`，required固定；data由operation schema限定。
- `operations/{runtime-session,runtime-ack,skill-install-result,skill-result-receipt,hosting-capabilities,hosting-prepare,hosting-ensure,hosting-observe,skill-result,page}.schema.json`：引用公共定义，按不同请求/回复组成封闭schema，不用allOf错误地禁止被扩展的公共字段。
- `fixtures/`：成功、错误、空data、大整数、不同安装ID、重连实例、重复key原始`.json.txt`及摘要原对象/预期值；包含完整请求及对应回复，不能只存data。
- `manifest.json`：列schema/fixture相对路径、SHA-256与API源码基线；Web/Runtime测试从固定制品或本地固定目录读取同一bundle，拒绝摘要不符。分发可复制不可变制品，但不手工维护第二份规范。

最早有效反馈：Java生产serializer输出到临时完整wire文件→真实Runtime/Web parser读该文件；Runtime生产serializer结果→真实Java parser读；输出字节与固定fixture/摘要断言。只用Map模拟生产结构的测试不算跨端通过。

| 测试位置/类型（拟新增名称） | 必测内容 |
| --- | --- |
| common-core `JsonEnvelopeSerializationTest` | 五字段、null、无status/location、分页、时间number/Long string、禁止double envelope |
| common-service `ApiEnvelopeBoundaryTest` | 200/204/400/401/403/404/409/500/503，header/body关联，真实security filter与MVC异常各走一次；无敏感信息 |
| agent Runtime Controller测试 | installation/session/heartbeat/ACK各完整请求响应；header身份不匹配、失效generation、ISO expiry拒绝 |
| C现有`SkillNativeResultMySqlTest`目标迁移版 | 实际事务→真实WS回执→真实Runtime解析；提交失败无成功ACK；重投同结果、异参冲突、两个安装ID不同 |
| `UnixManagedHostingProvisionerTest` | 真Java UDS→真Node，capabilities及prepare/observe/ensure；重复/未知字段、错误correlation、false与异常可区分 |
| R runtime-client/execution-adapter/skill-install-manager相邻测试 | 超大generation、原执行实例不被新会话覆盖、过期fence拒绝、pending只按匹配回执清理 |
| W useHttp/apiEnvelope及上述composable测试 | 只解包一次、code仅E0、2xx错误码拒绝、网关HTML、401身份切换、分页total精度、blob下载错误 |
| 业务端到端 | 上传图片→识别图中实际内容→按指令修改→下载实际新图；保存原/新文件SHA、资料版本与授权拒绝证据 |

共通负例：缺字段、未知根字段、重复key、null/缺省混淆、布尔/浮点冒充整数、`9007199254740993`代际精度、过大Long溢出、前导零、uppercase hash、错误schemaVersion、跨tenant/任务/资料访问。成功码、ACK、ACTIVE、RESULT_COMMITTED、可下载分别断言，不能互相替代。

本机仅做最早定向诊断。正式Web测试/构建/制品走Flow4403172；API按固定干净源码、读最新build.gradle后经原orchestrator串行运行受影响测试、依赖完整性和validateLayering；不为文档启动构建。Runtime沿用已有测试/发布方式并绑定真实commit。

## 11. 改动批次、遗漏控制与完成定义

### 2026-10-15 重排开发计划与工作量

范围以本文末节纠偏为准；取消“重新制定方案/全业务审计”开发任务。唯一Owner仍为本聊天主Agent。API、Web、Runtime、仓内实际消费者作为同一变更集实施，以下是内部工作包，不是模块分批发布。保留已有修复候选，不覆盖无关脏工作区；不新增Reviewer或人工阶段审批。

估算依据：已定位的公共边界、逐调用文件和目标协议复杂度，不以596条库存逐条计时；尚无此次改造实际速度及Flow排队实测。每包预计20—30分钟，为拆分粒度而非硬截止；超出时报告具体差异，不删验证或虚标完成。16包合计320—480分钟（5小时20分—8小时）有效工作；流水线/依赖下载/正式构建执行/共享发布锁等待另计，当前不能准确承诺墙钟上线时间。已有源码/设计分析不重复计费计时。

| 工作包 | 具体开发内容 | 完成依据 | 预计有效工作 |
| --- | --- | --- | --- |
| P01 | 固定本批各仓源码，隔离已有脏修改；在现有handoff绑定范围及同源公共fixture | 源码/变更归属明确，fixture可被各端引用；不是再设计业务 | 20—30分钟 |
| P02 | Q/R及PageData公共类型；移除公共status/location，data:null固定 | Java调用签名和字段一致，无第二套信封 | 20—30分钟 |
| P03 | 请求严格解码、既有RequestIdFilter及响应Advice | 重复/未知字段拒绝；header/body/dispatch关联一致 | 20—30分钟 |
| P04 | MVC/security/direct Servlet/timeout错误出口与脱敏边界 | 真实HTTP错误、无重复写入、敏感信息仍受保护 | 20—30分钟 |
| P05 | Controller自有JSON返回适配，包括裸DTO/String/JSON_BYTES | 明确包装一次，外部及文件/流不误包 | 20—30分钟 |
| P06 | Controller请求、分页及已确定路由动作映射 | 保留生效分页容量，字段重命名同步调用方 | 20—30分钟 |
| P07 | Web公共encode/decode、useHttp及类型声明 | 返回一次解包DTO；metadata局部化；无字符串兜底 | 20—30分钟 |
| P08 | Web页面/composable的响应、分页及错误消费迁移 | 移除旧根字段和多层解包；更新相关fixture/mock | 20—30分钟 |
| P09 | Web资料ETag、结果投影、blob/text/SSE边界 | 保留版本/身份取消及原字节下载，不串请求 | 20—30分钟 |
| P10 | API/Runtime HTTP DTO与调用端同步 | session/heartbeat/ACK/install等请求与回执同约定 | 20—30分钟 |
| P11 | API/Runtime WS、UDS双端codec及关联字段 | 不改变内部签名事实/持久化摘要，不保留旧wire解析 | 20—30分钟 |
| P12 | 仓内实际网络脚本、探针及旧客户端退役；更新受影响测试 | 脚本不再依赖旧wire；非网络语法候选不扩成任务 | 20—30分钟 |
| P13 | 跨仓定向静态检查及公共serializer→真实consumer测试 | 旧构造器/多重解包/路由差异可追踪；真实完整wire而非自造mock | 20—30分钟 |
| P14 | 抽样HTTP/WS/UDS回归及既有候选整合验证 | 错误/分页/大整数/认证隔离/幂等摘要/第三方豁免分别覆盖 | 20—30分钟 |
| P15 | 固定版本并提交正式构建发布流程；整理同批制品与恢复步骤 | Web Flow4403172、API干净源码本地orchestrator及Runtime制品均可追溯 | 20—30分钟，机器/队列等待另计 |
| P16 | 授权窗口同批切换、在线协议及图片闭环验收，更新记录 | 上传→识别→修改→下载真实证据；失败如实列阻塞 | 20—30分钟，协调/模型处理等待另计 |

执行：P01固定输入后，按依赖连续完成P02—P12的整套源码修改，不形成单组件上线；必要的编译/定向反馈随实施进行，不重新展开业务审计。P13—P14集中抽样验证受影响路径，P15—P16同批发布及验收。机器工作可与不依赖它的准备工作重叠，但单Owner人工任务不虚算成并行压缩。

抽样不能豁免公共边界检查：公共Q/R成功/错误、分页、multipart/blob、SSE、第三方回调、WS/UDS、认证/资料隔离、幂等/摘要各覆盖至少一个真实受影响分支；第三方保护清单逐入口静态核验。正式构建仍保留依赖完整性和validateLayering。

明确不包含：历史权限/字典/支付/SMS业务修复、外单功能、费用授权、DB重构及新模型配置。如果图片验收复现旧业务缺陷（含finalization提交态或既有UDS问题），准确记录阻塞，不谎称是信封改造已解决，也不自动扩展本批范围。预计工作量不是保证全部历史图片故障在同一时限内修好。

遗漏控制必须依照**现有真实消费者**，最少扫描：
- API所有`JsonResult/JsonResultPage/JsonRequestPage/Result`作为边界返回或参数的位置；`setStatus/getStatus/setLocation/getLocation`、局部ExceptionHandler、Servlet手写JSON、安全失败及分页构造。不要把所有名为getStatus的领域方法误删。
- Web所有useHttp/createApi调用、直接fetch、业务码判断、response.data、分页根total、流消息解析；包括测试mock。明确标记OAuth/文件等有限例外。
- Runtime所有发HTTP/WS/UDS与durable重放入口、命令精确字段集合和结果回执parser；内部磁盘模型与网络模型分别清单。
- 仓内脚本和探针逐路由登记；用户确认没有额外外部客户端。**本次已详设确认入口，不声称全仓逐端点枚举完毕**；未定位消费者是发布前需补齐的事实，不授权临时加兼容兜底。

每项实施验收记录至少含：文件/符号、route或messageType、旧/新字段、producer/consumer、身份/资源范围、幂等键、持久字段/摘要影响、测试selector/fixture摘要、源码与制品版本。沿用现有handoff/发布记录，不新增运行时台账服务。

## 12. 本次文档交付与尚未解决事项

本次仅新增本文，补规范/影响分析/AGENTS入口及原handoff；没有改业务代码、依赖、生产配置或服务。文档JSON与链接检查不等于功能测试。

原图片交付仍需定位r18 Java UDS失败、完成API修复正式发布、核对原安装恢复及真实识别/修改/下载。接口迁移尚未实施；完整公共消费者枚举、各操作schema/fixture和跨端测试属于上述开发交付，不得凭本文标为完成。

### 2026-10-14 二次校核结论

逐接口补充已覆盖关键HTTP、13条会话Runtime执行、UDS及WS/SSE漏项，详见接口目录。全量静态声明已索引，不等于每条嵌套DTO/枚举/实际消费者已验证。`resultDigest`命名不能只改单一HTTP结果，必须连同selector/actionProgress/effect/回执按新业务schema闭合；历史签名对象保持原字节。发现的Map.of(null)源码缺陷本轮未修复，不报图片验收完成。

### 2026-10-14 开发前源码核对补充（未实施）

逐项证据及待闭合项见[开发前核对](interface-catalog/predevelopment-review-20261014.md)和[逐接口核对数据](interface-catalog/predevelopment-audit.json)。新增必须处理：ContextPack也是JSON byte[]；Scene快照的text/大整数修补、GiftPay自有String返回须接统一decoder；useHttp.d.ts与JS同改；streamMode区分SSE事件与真实字节；Media.thumbnail不得图片后追加字符串。42条GET写动作候选含39条写动作候选、2个空实现和1个快照生成，不能仅凭路由名统一改POST。以上没有把全系统状态改成已完成核对或允许开发。

## 开发前续查补充（2026-10-14；仍未进入业务开发）

具体调用绑定、Hall请求/错误映射、个人资料Object实际结构及停机恢复反例见[开发前核对§8](interface-catalog/predevelopment-review-20261014.md#8-继续核对动态调用与局部字段同日续查)。这是本详设的补充约束：

- 注入api和response URL必须追到实际base/生产者，不按搜索未命中删接口；新增手工证据33条关系/32个操作，不等于全量闭合。
- PW05旧设计的固定hasMore:false撤销，按[关键契约PW05](interface-catalog/critical-contracts.md)的真实截断检测/游标处理；个人资料Object字段按已核producer收窄。
- Hall私有标记revision字符串化须同步BigInt加法、时间校验及持久恢复key；共享readBounded/parse必须显式解包Q，响应Advice不能替代请求处理。
- 两个ISP空实现和queryComment自递归不补新业务，目标明确501/OPERATION_NOT_IMPLEMENTED，不返回伪成功。
- 仅移动安装outbox仍会由ACTIVE注册表重建；历史安装证据与新wire解析必须分离；不能清空隔离目录/失败证据掩盖不健康，也不伪造安装成功。具体停机隔离闭包未完，不把本节当可执行生产迁移脚本。

## 路由及恢复边界续查（2026-10-14）

以[开发前核对§9](interface-catalog/predevelopment-review-20261014.md)为本详设补充：目标路由596条/变更61条/静态重复0；两条product支付路由显式改名，appid query选择器保留，签名秒字符串禁止毫秒化；同步wxJsToken独立改Q/R；已安装kit默认RUM不发送不等于在线核验。新增Chat/Archive后台旧意图入口、浏览器11类恢复存储纳入停机设计。资金未决记录不能靠换storage key消失；API逐表隔离及完整schema/调用绑定仍未闭合，不授权按此直接迁移。

### 开发前字段/消费者补核（2026-10-14续查）

执行嵌套对象、技能扩展schema边界及原始manifest摘要类型见[关键契约§10](interface-catalog/critical-contracts.md#10-执行嵌套对象补核2026-10-14固定awr基线)；实际消费者和一处字符串拼接路由库存纠正见[开发前核对§10](interface-catalog/predevelopment-review-20261014.md)。实现须处理HallMessageMedia直接Blob、监控认证错误精确匹配、skillBody先解Q.data、readInput的JSON请求/二进制响应非对称；不将旧raw manifest按普通DTO改写。退款通知属于外部XML回调，不套自有JSON信封；其ack/业务提交契约仍未闭合。审计ready仍false，不代表已实施。

### 存量接口续查补充（2026-10-14）

Dict/Dwz/Msg的23条字段映射、权限缺口及Web改动见[字段级续查](interface-catalog/ordinary-contracts-review5.md)。新增4条嵌套GET写入须同批改POST；目标路由表当前65条变化。消息readall范围与状态谓词、字典缓存真实失效、Service Worker业务数据缓存边界不能只靠统一信封修复；尚未闭合的语义已明列，不代表本轮开发或上线。

### 2026-10-15集中续查：当前状态覆盖旧阶段数字

当前目标路由596条、69条method/path变化；当前消费者249条关系/215个operation，旧Runtime helper关系单列而非恢复退役入口。新增短语/SMS GET写入、SMS provider回调、语音JSON→binary、直接XHR及无后缀脚本边界见[开发前核对§12](interface-catalog/predevelopment-review-20261014.md)。

全量字段/权限/错误与调用证明仍未闭合，不授权按旧阶段“先关键链再分批”进入开发；用户要求一次性改造前对齐。本文件早期测试数据/安装清理未完的阶段描述已由机器表approvedTestDataCleanup完成证据替代，不继续作为待用户决策项。原测试/发布证据仍保留历史，不伪称本轮验收。

### 关键链续查补充（2026-10-15，同基线）

实施时一并采用[关键契约§11—14](interface-catalog/critical-contracts.md)：TypedOutcome显示schema5/原事实版本分离，SkillResult phase与既有CAS版本对齐；商城/只读预览16条完整字段映射与结构fixture；finalization三入口状态/错误/同key恢复及RESULT_COMMITTED成果证明链。`SkillExecutionService.result`已有protocol4及资料访问校验，`readConversationOutput`已有双提交态读取和原任务根锁，不新增另一套权限/读取服务。详情包含controller、service、Web消费字段及最早定向用例。

结构schema见`interface-catalog/target-finance-contracts.json`；40组合成报文仅结构检查，不是完整业务断言或线上验收。当前仍不是全系统可开发结论；不把本文进展转成授权部署或恢复旧Runtime入口。

### 第三方回调禁止机械迁移（2026-10-15用户明确）

公共规范§7.1及[第三方回调保护清单](interface-catalog/external-callback-contracts.md)优先约束通用改造。当前识别10条直接Controller回调（含GitHub/微信/微博OAuth的4条HTML_OR_REDIRECT），另有1条框架配置候选；10条目标method/path均未变。统一信封、请求/响应拦截、异常处理、认证入口及GET副作用迁移不得改变这些外部契约；特别不能要求第三方使用Q/R、本站JWT、requestId或Idempotency-Key。原始报文先验签，再适配内部业务。第三方管理API不是回调豁免。当前ACK/事务未闭合项仍需依提供方协议核定，不因“外部协议保护”误标完成。


## 追加：本次迁移范围纠偏与公共边界裁决（2026-10-15）

本节覆盖此前将全业务审计作为迁移前置条件的表述；不重写历史核验结果。规范仍为1.2。API/Web HEAD本轮回读与§1一致；未重新查询远端或线上。

### 范围

本次只改自有wire和直接生产/消费边界：Q/R、字段/类型、真实HTTP错误、分页、路由动作、HTTP/WS/UDS解包及固定摘要投影。保留身份认证、资料隔离、幂等、事务及现有资金规则。

不借此修复权限关系getId/getPermsId、开启全局方法安全、重构角色租户、字典缓存/公开策略、支付/SMS事务或成果finalization对RESULT_COMMITTED的业务支持。它们保留为独立历史发现，不再作为协议开发前提。finalization的“失败HTTP却E0”属于wire不一致，仍在本次范围。

### 有限实施边界（同批改动，不按模块分批切换）

| 边界/文件 | 明确代码动作 | 定向验证 |
| --- | --- | --- |
| common-core `entity/JsonResult.java`、`JsonResultPage.java`、`JsonRequestPage.java`及新增Q/PageData | 按§2字段替换；分页整数归一化保持现有1..100，响应实际pageSize；不能全局把业务Long/数值改字符串 | 完整Q/R、null、分页有效值/非法类型/Long精度 |
| common-service `filter/RequestIdFilter.java`及§2两项Advice | 复用唯一上下文；body接纳的ID同步属性/MDC/header；响应Advice只填元数据、不猜裸Map | 无header有body、header/body冲突、error/async dispatch最终ID一致 |
| `config/ExceptionHandlerAdvice.java`、`resolver/GlobalExceptionResolver.java`、common-starter `deadline/RequestDeadlineExceptionHandler.java`及既有security/direct Servlet出口 | MVC泛异常真实500，参数错误400，认证401/拒权403；保留既有业务code。resolver仅找到定义，不能称已启用；修改共用实体时同步其编译引用，不新增注册。timeout复用同一ID上下文，保持503/504及业务detail，不重新用8..128正则生成ID。断连/已提交流不得再写成功信封 | HTTP/status/code一致；已有异常分支；无双写/无秘密异常文本 |
| `security/SensitiveResponseBodyAdvice.java` | 保留普通输出保护；精确授权DTO保真与外部入口分流，不能移除脱敏器图省事 | 普通敏感字段仍隐藏，canonical摘要不被输出重写 |
| `web/src/composables/useHttp.js`及`.d.ts`、§6调用方 | 请求仅包装一次；JSON完整校验后返回R.data，metadata为本次局部结果；删除JSON失败退化字符串及猜code/message分支；保留blob/text/stream/bodyless显式模式 | 真实R→实际parser；非JSON错误保留真实status；并发ETag不串请求；不双解包 |
| Runtime §3—5 HTTP/WS/UDS codec及仓内实际网络脚本 | 同源fixture；仅改传输投影/解包，签名原文和内部fingerprint不变；旧可执行客户端明确退役，不做兼容 | 请求/回复双向、关联ID、重复消息、摘要原字节不变 |
| Controller/DTO及调用方索引 | 逐已声明SELF_JSON/JSON_BYTES入口替换返回/输入，裸String与JSON byte[]明确适配；字段改动同步现有绑定；库存未绑定不等于无调用 | 静态查旧构造器/旧字段/多重解包，按受影响边界抽测 |
| 外部回调、文件、SSE、HTML/OAuth、HEAD/204/304 | 依据精确operation分流，禁止wx/oauth整个模块泛豁免；第三方成功和错误均不套Q/R或新JWT | 10条已登记回调method/path/input/ACK保持；文件原字节；流不尾拼JSON |

分页裁决是保留现行行为，不是增加旧wire兼容。错误、请求ID和脱敏裁决不授权新业务功能。上述是实施范围，不是所有596条接口字段均已验证的证明；旧NOT_FIELD_CLOSED库存不能再当作准确剩余任务计数。
