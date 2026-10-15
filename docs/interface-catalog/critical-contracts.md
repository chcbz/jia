# 关键链逐接口契约（目标设计，不是线上声明）

核对：2026-10-14；基线及全量索引见[目录](README.md)。公共信封只引用[统一规范](../interface-standard.md)，不复制第二套。本文补足上一版漏掉的上传、关联、凭据、下载、结果提交及退役入口；**不是596条接口均已完成业务核定**。§12—13的16条已有结构schema/合成fixture（target-finance-contracts.json、target-finance-fixtures.json），其余仍为字段设计；这些均未通过真实跨端测试。

## 0. 读表约定与公共约束

- `Q<T>` = `{protocolVersion:1,requestId:String,data:T}`；`R<T>` = `{protocolVersion:1,requestId:String,code:String,msg:String,data:T}`。表中请求/响应写的是data类型，不是允许裸DTO。
- 标记`无body`的GET/HEAD/DELETE无JSON请求；POST空业务输入使用Q<null>；multipart保持真实文件part，公共requestId通过header。响应JSON仍为R。
- `ID`是不透明String，使用对应资源原ID校验，不擅自转数值。`D0/D1`分别是非负/正十进制String，Java Long范围内；资源version原为int的另限制≤2147483647。`T`为非负安全整数epoch毫秒；`H`为裸64位小写hex；`?`表示显式可null，`[可选]`才表示可省略。固定对象禁止未知字段。
- `JWT`代表原用户JWT及其可信tenant/client/jiacn；`Native`代表已验证AgentRuntime凭据、当前安装/实例/generation与原资源授权；不是“带一个token就允许访问”。不得把同Owner资格取消套到文件归属或安装管理权限。
- 下文错误表为业务层+协议层目标；认证边界401、已认证无权限403、不可见资源404分别按防枚举契约返回。现行某些控制器将全部失败压403/404，迁移需显式纠正；未确认的业务分支不得随意换码。
- 幂等键除显式说明外来自`Idempotency-Key`，由原可信主体+操作+业务输入绑定；必须核既有持久约束，不用requestId去重。查询不会执行安装/Provider。
- 所有凭据、个人资料、执行及订单结果`Cache-Control: private, no-store`，秘密不进入日志/fixture。文件响应保留nosniff、Disposition及实际Content-Type。
- 本文`200/201/202+E0`只说明该操作语义成功：202仅受理，staged仅暂存，commit仅提交；不可等同完整任务成功。

## 1. Runtime安装与会话：7条

源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentRuntimeV1Controller.java`；DTO：`agent/jia-agent-core/src/main/java/cn/jia/agent/entity/AgentRuntimeV1*.java`、`AgentCommandAckResult.java`。

### 1.1 DTO完整字段（目标）

| DTO | required字段（?也必须出现，但可null） |
| --- | --- |
| InstallationCreate | runtimeInstallationId:ID, canonicalAgentId:ID, manifestVersion:String, manifestSha256:H, enrollmentSecretSha256:H, enrollmentExpiresAt:T |
| Installation | runtimeInstallationId:ID, tenantId:ID, clientId:ID, canonicalAgentId:ID, manifestVersion:String, manifestSha256:H, enrollmentExpiresAt:T, status:String(原安装状态枚举), lastHeartbeatAt:T? |
| Enrollment | runtimeInstallationId:ID, tenantId:ID, clientId:ID, canonicalAgentId:ID, manifestVersion:String, manifestSha256:H, enrollmentSecret:String(秘密) |
| Enrolled | installation:Installation, runtimeAuthorization:String(秘密) |
| SessionCreate | runtimeInstallationId:ID, tenantId:ID, clientId:ID, canonicalAgentId:ID, manifestVersion:String, manifestSha256:H, hostId:ID, runtimeInstanceId:ID |
| Session | runtimeInstallationId:ID, tenantId:ID, clientId:ID, canonicalAgentId:ID, hostId:ID, runtimeInstanceId:ID, sessionGeneration:D1, scheme:String(原认证方案), sessionToken:String(秘密), websocketPath:String, status:String |
| Heartbeat | runtimeInstallationId:ID, tenantId:ID, clientId:ID, canonicalAgentId:ID, manifestVersion:String, manifestSha256:H, health:String(原健康枚举) |
| Ack | messageId:ID, correlationId:ID, commandId:ID, taskId:ID, workItemId:ID?, tenantId:ID, clientId:ID, canonicalAgentId:ID, payloadReference:String?, expiresAt:T, status:String(原ACK状态枚举), runtimeInstallationId:ID, hostId:ID, runtimeInstanceId:ID, sessionGeneration:D1, deliveryVersion:D0? |
| AckResult | kind:ADVANCED或PRIOR, status:String, deliveryVersion:D0 |

安装/健康/ACK状态的完整enum必须从各自实际状态机固化到schema；不能凭字段String允许任意值。以下接口级设计已确定，但这些enum fixture仍是实施产物，不声称已验证。

| ID | HTTP接口 | 输入→响应 | 身份、幂等与失败 |
| --- | --- | --- | --- |
| RT01 | `POST /agent/runtime/v1/installations` | Q<InstallationCreate> → 200 R<Installation> | JWT安装管理权限，enrollmentExpiresAt不得改写旧安装。目标增加幂等header并接原安装唯一身份；同ID异manifest冲突409。精确密钥摘要不可作为普通资料回显 |
| RT02 | `GET /agent/runtime/v1/installations/{installationId}` | 无body → 200 R<Installation> | JWT、作用域内安装；path名称暂保留，语义唯一为runtimeInstallationId；不可见404 |
| RT03 | `POST /agent/runtime/v1/installations/{installationId}/revoke` | Q<null> → 200 R<null> | JWT、安装管理权限；同意图重复不再次生成状态变化。撤销影响会话，应按原状态机失效；不能body授权跨安装 |
| RT04 | `POST /agent/runtime/v1/enroll` | Q<Enrollment> → 200 R<Enrolled> | installer enrollmentSecret凭据校验，保持禁止Origin、URL query、X-API-Key的原边界；密钥过期/不匹配拒绝，不统一成用户JWT入口；秘密响应只此入口 |
| RT05 | `POST /agent/runtime/v1/session` | Q<SessionCreate> → 200 R<Session> | 原安装Bearer，不是Native会话token；签发新generation是状态变化。不得让公共HTTP自动重试悄悄多签；精确恢复语义须绑定现有服务，不能假定永远返回相同token |
| RT06 | `POST /agent/runtime/v1/heartbeat` | Q<Heartbeat> → 200 R<Installation> | 原安装Bearer；更新heartbeat、校验manifest/identity；不能使用技能安装ID |
| RT07 | `POST /agent/runtime/v1/commands/{messageId}/acks` | Q<Ack> → 200 R<AckResult> | Native/X-Agent-*逐项比对；path匹配原消息；原deliveryVersion、命令状态CAS；同状态重投PRIOR，旧fence拒绝，提交后返回 |

协议错误400；无认证401；有效认证但越界403；状态/幂等冲突409；依赖不可用503。现行控制器把部分业务拒绝统一403的隐藏策略仍须保留在需要防枚举的位置，不因为表中状态码暴露安装是否存在。RT04/05不新增“幂等缓存秘密”的实现。

## 2. 个人资料：13条，不只是上传一个接口

源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceController.java`；DTO：`agent/jia-agent-core/src/main/java/cn/jia/agent/entity/PersonalWorkspaceViews.java`。

### 2.1 返回类型

| 类型 | 目标字段 |
| --- | --- |
| Capabilities | upload/read/edit/preview/download:String（沿用各能力状态，不改成truthy判断） |
| File | fileId:ID, sourceKind:String, originKind:String, displayName:String, mediaFamily:String, state:String, metadataRevision:D0, latestVersion:D1, createdAt:T, capabilities:Capabilities |
| Version | fileId:ID, version:D1, originalFilename:String, contentMimeType:String, byteLength:D0, sha256:H, createdAt:T, previewState:String |
| FileList | items:File[], nextCursor:String?, hasMore:boolean（由真实服务是否还有下一页判定） |
| FileDetail | file:File, latestVersion:Version, versions:Version[], relations:数组, derivation:数组；relations/derivation在固定源码生产者均为[]；本版固定空数组，不发明未实现的对象结构 |
| Operation | operationId:ID, state:String, fileId:ID?, fileVersion:D1?, errorCode:String? |
| Upload | operation:Operation, file:File?, version:Version?；是否可null必须与异步处理中状态匹配 |
| Usage | impactRevision:D0, taskReferences:TaskReference[], activeExecutions:{executionId:ID}[]（实际ActiveExecutionView，不暴露lease/路径） |
| TaskReference | taskId:ID, relationId:ID, version:D1, role:String, relationRevision:D0, createdAt:T |
| Preview | state:String, parts:PreviewPart[], partial:boolean, reason:String? |
| PreviewPart | partId:ID, contentMimeType:String |

所有资源version线上的目标均为D1，即使DB是int；schemaVersion/attempt/pageSize不受此规则影响。2026-10-14续查已核实生产者：upload/read/download=AVAILABLE，edit=UNVERIFIED，preview=AVAILABLE|UNSUPPORTED；relations/derivation固定空列表，activeExecutions仅executionId。预览View.state=READY|FAILED|UNSUPPORTED；不以能力AVAILABLE代替本次解析成功。

前缀P=`/agent/personal-workspace`。每条JWT及scope内文件ACL；不存在/不可见均FILE_NOT_FOUND/404。

| ID | HTTP接口 | 请求data/query/part | 成功与状态/幂等 |
| --- | --- | --- | --- |
| PW01 | `GET /agent/personal-workspace/files` | query可选q/mediaFamily/state/cursor；无body | 200 R<FileList>；查询，不重置游标权限 |
| PW02 | `POST /agent/personal-workspace/files` | multipart file必填，displayName可选；Idempotency-Key必填 | 201 R<Upload>；相同key/字节/业务名重放原operation；异参409；存储未决202 R<Operation>，不新建第二个文件 |
| PW03 | `GET /agent/personal-workspace/files/{fileId}` | path fileId | 200 R<FileDetail> + ETag；缓存策略仍no-store；ETag用于并发控制不是授权 |
| PW04 | `PATCH /agent/personal-workspace/files/{fileId}` | Q<{displayName:String}>；If-Match及Idempotency-Key | 200 R<File>及新ETag；缺前置条件428、失配412；不可用requestId替代metadataRevision |
| PW05 | `GET /agent/personal-workspace/files/{fileId}/versions` | path fileId；目标增加可选cursor，现行尚无 | 200 R<{items:Version[],nextCursor:String?,hasMore:boolean}>。原service只取100条，故撤销无条件hasMore:false设计；沿现有100条窗口额外取1条探测，按version降序、cursor内lastVersion排除已读项，scope/fileId仍逐次鉴权；详见下段。同步修Map.of(null)缺陷 |
| PW06 | `POST /agent/personal-workspace/files/{fileId}/versions` | multipart file、expectedPreviousVersion:D1，displayName可选；Idempotency-Key | 201 R<Upload>；版本不符409；不能原地覆盖旧version字节 |
| PW07 | `GET /agent/personal-workspace/files/{fileId}/versions/{version}/content` | path固定version；无body | 200原文件字节；不自动加Range支持，当前控制器未实现成果目录同款Range逻辑 |
| PW08 | `GET /agent/personal-workspace/files/{fileId}/versions/{version}/preview` | query无或view=parts，禁止其他/重复值 | 200 R<Preview>；目标统一parts一种结构，删除legacyPreview分支，不按参数返回两种schema |
| PW09 | `GET /agent/personal-workspace/files/{fileId}/versions/{version}/preview/parts/{partId}` | 固定version+partId | 200对应预览字节；重新查ACL，不从客户端任意路径读取 |
| PW10 | `GET /agent/personal-workspace/files/{fileId}/usage` | 无body | 200 R<Usage>；impactRevision关联之后trash的影响确认，不可假造 |
| PW11 | `POST /agent/personal-workspace/files/{fileId}/trash` | Q<{impactRevision:D0,acknowledgeExistingReferences:boolean}>；If-Match、幂等key | 200 R<File>及ETag；保留引用影响确认与已授权执行快照；不删成果事实 |
| PW12 | `POST /agent/personal-workspace/files/{fileId}/restore` | Q<null>；If-Match、幂等key | 200 R<File>及ETag；与trash不同操作域，不能跨路由复用去重结果 |
| PW13 | `GET /agent/personal-workspace/operations/{operationId}` | path operationId | 200 R<Operation>；只查询，不触发新上传/新执行 |

PW05明确代码落点：PersonalWorkspaceController.versions增加可选cursor并拒绝未知/重复query；PersonalWorkspaceService增加分页读取边界，不改变get所用的内部versions语义；PersonalWorkspaceDao/Impl增加beforeVersion条件，仍保留tenant/client/owner/file精确匹配，该分页查询明确固定读取101条、返回100条；现有通用bounded(limit)最大100，不能沿用它静默截掉探测行，也不全局放宽其他查询。cursor编码fileId和lastVersion（D1，<=2147483647），解码固定字段并校验path一致；游标非授权。hasMore=true时nextCursor取最后实际返回项，最后一页false/null；不做总量推测。实际新增/现有版本分页消费者及对应测试按该DTO读取；当前usePersonalWorkspace主要消费FileDetail，不能假称已有loadVersions分页函数，也不为本次新增版本管理界面；FileDetail.versions仍是最近100条摘要，不能当作历史全集。这是对原“全量且无下一页”错误设计的修正，不是已实现的新能力。

错误集合：400 BAD_REQUEST；404 FILE_NOT_FOUND；409 IDEMPOTENCY_CONFLICT/FILE_VERSION_CONFLICT；412 METADATA_CHANGED；428 PRECONDITION_REQUIRED；415 FILE_TYPE_UNSUPPORTED；503 STORAGE_UNAVAILABLE/OPERATION_RECONCILIATION_REQUIRED/WORKSPACE_UNAVAILABLE。目标将原`202 + ErrorBody(OPERATION_PROCESSING)`改成`202 + E0 + Operation(state=原处理中状态)`，无operation引用时不能伪造202成功，应返回明确未决错误和可恢复关联。客户端状态机必须等待operation终态。

## 3. 资料关联：任务/会话各3条

源码：`PersonalWorkspaceTaskFileLinkController.java`、`PersonalWorkspaceConversationFileLinkController.java`（同上API目录）；服务类型在`agent/jia-agent-api/src/main/java/cn/jia/agent/service/PersonalWorkspace{Task,Conversation}LinkService.java`。

Link字段：`relationId,taskId或conversationId,fileId:ID; version:D1; role:String; state:String; relationRevision:D0; createdAt:T`。Task和Conversation是两个封闭DTO，不能同时带两个scope字段。List=`{items:Link[],nextCursor:String?,hasMore:boolean}`；Detach=`{link:Link,executionSnapshotsPreserved:boolean}`。

| ID | HTTP接口 | 输入 | 输出/权限/状态 |
| --- | --- | --- | --- |
| LK01 | `GET /agent/tasks/{taskId}/file-links` | cursor可选 | 200 R<List>；JWT+任务资料可见范围 |
| LK02 | `POST /agent/tasks/{taskId}/file-links` | Q<{fileId:ID,version:D1,role:INPUT或REFERENCE}> + 幂等key | 201 R<Link>+ETag；核任务权限及所选文件版本授权；OUTPUT只能由执行归档管理，不能手工关联 |
| LK03 | `DELETE /agent/tasks/{taskId}/file-links/{relationId}` | 无body；If-Match、幂等key | 200 R<Detach>+ETag；删除关联不删除原文件/执行快照 |
| LK04 | `GET /agent/conversations/{conversationId}/file-links` | cursor可选 | 200 R<List>；JWT+会话资料权限 |
| LK05 | `POST /agent/conversations/{conversationId}/file-links` | 同LK02 | 201 R<Link>；只能该会话授权版本 |
| LK06 | `DELETE /agent/conversations/{conversationId}/file-links/{relationId}` | 同LK03 | 200 R<Detach>；旧执行快照保留 |

原createCommand读取Number版version，目标改严格D1→int，不接受两种。两类分别保留原错误前缀：401 UNAUTHENTICATED；403对应scope forbidden；404 *_FILE_LINK_NOT_FOUND；409 IDEMPOTENCY_CONFLICT/OUTPUT_LINK_MANAGED_BY_EXECUTION；412 *_FILE_LINK_CHANGED；428 PRECONDITION_REQUIRED；503 *_FILE_LINK_UNAVAILABLE。

## 4. 技能商城与安装：10条

源码：`SkillMarketplaceController`、`SkillManagedCredentialController`、`SkillPackagePublicationController`、`SkillPackageController`及`agent/.../skill/{SkillMarketplaceHttp,SkillMarketplaceService,SkillManagedCredentials,SkillPackagePublicationService}.java`。

### 4.1 业务DTO

| DTO | 目标字段与映射 |
| --- | --- |
| Product | productId/name/description/productVersionId/skillKey/skillVersion:String；price:Money（原priceMicro）；permissions:String[]；canPurchase:boolean；denialReason:String?；deploymentRestriction:NONE或ADMIN_ONLY |
| QuoteRequest | productVersionId:ID, targetAgentId:ID, expectedAgentVersion:D1 |
| Quote | quoteId/productVersionId/targetAgentId:ID, expectedAgentVersion:D1, price:Money, expiresAt:T（原字符串转number） |
| Purchase | quoteId/productVersionId/targetAgentId:ID, expectedPrice:Money, expectedAgentVersion:D1, approvedPermissions:String[]（唯一，不任意扩权；原≤32保留） |
| Order | orderId/productVersionId/targetAgentId:ID, expectedAgentVersion:D1, status:String(原订单状态机) |
| Entitlement | entitlementId/targetAgentId/orderId/skillInstallationId/productVersionId/skillKey/skillVersion:ID，status:String，permissionGrantVersion:D0；原installationId明确是技能安装 |
| Credential | credentialId/targetAgentId:ID, version:D1, status:CREATED, apiKey:String(一次性秘密), recovery:ROTATE_WITH_NEW_KEY |
| PublishMetadata | productId/productVersionId/name/description/skillKey/skillVersion:String, price:Money, deploymentRestriction:NONE或ADMIN_ONLY, approvedPermissionsManifest:String(原权限manifest编码), packageSha256:H, manifestSha256:H?, expectedProductVersion:D0 |
| Publication | productId/productVersionId:ID, productVersion:D1, packageSha256:H, manifestSha256:H?, disposition:String(原出版结果枚举) |

Money=`{amountMinor:D0,currency:"SILVER"}`；这里的SILVER最小单位就是micro-silver，`1 SILVER=1,000,000 minor`，**priceMicro原整数不缩放**。它不是CNY，不能按100分换算；数据库与账本仍保持micro字段。原签名/报价输入摘要必须通过领域映射维持精确值，不拿新的wire字段直接重算历史订单摘要。

| ID | HTTP接口 | 输入→成功 | 权限/幂等/失败 |
| --- | --- | --- | --- |
| SK01 | `GET /economy/capabilities` | 无query/body → 200 R<{economyPreviewEnabled:boolean,skillMarketplaceEnabled:boolean,principalScopeSha256:H}> | JWT，原principalScopeFingerprint投影为principalScopeSha256；只标识当前可信scope，不是授权凭据 |
| SK02 | `GET /agent/skill-products` | 无query/body → 200 R<Product[]> | JWT；是否可购由原部署限制和管理权限判定 |
| SK03 | `GET /agent/skill-products/{productId}` | 无query/body → 200 R<Product> | JWT+商品可见性；未知商品按原隐藏策略 |
| SK04 | `POST /agent/skill-orders/quotes` | Q<QuoteRequest> + key → 200 R<Quote> | Agent安装管理权限/版本；精确授权报价，不是运行费用许可 |
| SK05 | `POST /agent/skill-orders` | Q<Purchase> + key → 200 R<Order> | 报价/价格/版本/批准权限绑定；原交易及安装命令事务不拆散；同key异参409 |
| SK06 | `GET /agent/skill-orders/{orderId}` | 无query/body → 200 R<Order> | JWT、订单作用域；只查状态，不能查完顺带重下单 |
| SK07 | `GET /agent/{agentId}/skill-entitlements` | 无query/body → 200 R<Entitlement[]> | JWT、该Agent管理可见性；有entitlement不等于当前Runtime可调用 |
| SK08 | `POST /agent/{agentId}/skill-install-credentials` | Q<{expectedAgentVersion:D1}> + key → 201 R<Credential> | 原管理权限；同key已执行返回409 SKILL_CREDENTIAL_SECRET_NOT_REPLAYABLE，不回放apiKey；托管凭据/在途安装原拒绝规则保留；响应丢失不自动用新key轮换 |
| SK09 | `POST /agent/skill-maintenance/product-versions` | multipart metadata=PublishMetadata、package=zip | 200 R<Publication>；JWT+ROLE_ADMIN；原包大小/摘要/权限校验，按原productVersion/package身份防重复；不向模型开放任意发布 |
| SK10 | `GET /internal/agent/skill-installations/{installationId}/package` | 无query/body；path语义skillInstallationId | Native+原安装订单授权，200 zip字节；403 SKILL_DOWNLOAD_FORBIDDEN；不靠URL知道ID就下载 |

共同保留`BAD_REQUEST(400)`、原SKILL_*权限/状态码、`IDEMPOTENCY_CONFLICT(409)`、`SKILL_STATE_UNAVAILABLE(503)`；完整枚举须从SkillMarketplaceException各require分支导出并按稳定语义核对，不能将这里的示例列表当穷尽。运行costInfo仅登记展示，不增加预算许可，安装商品交易与运行费用登记不是同一接口。

## 5. 图片技能当前入口、结果和字节：8条

| ID | HTTP接口 | 请求→成功 | 权限/状态/错误 |
| --- | --- | --- | --- |
| IM01 | `GET /agent/tasks/{taskId}/skill-capabilities` | 唯一query targetAgentId，无body → 200 R<TaskCatalog> | JWT、可信tenant/client一致、原任务授权；目录只读；不可见404、依赖503；不能以UNAVAILABLE自动安装 |
| IM02 | `POST /chat/conversations/{conversationId}/interactions/discussion` | Q<Discussion> + Idempotency-Key → 202 R<Accepted> | 当前有效议事入口；JWT、会话/任务/资料授权；同key同意图恢复，不触发第二次Provider；400/404/409/503沿用TYPED_DELIBERATION_* |
| IM03 | `GET /chat/conversations/{conversationId}/requests/{requestId}/typed-outcome` | 无body → 200 R<TypedOutcomeProjection> | 固定conversation/request/turn/revision读取；不是查latest。完整多态结果仍引用现有typed契约并按摘要命名闭包修改，不从Java早期record推断仅有ANSWER |
| IM04 | `GET /chat/conversations/{conversationId}/executions/{executionId}/result` | 无body → 200 R<SkillResult> | 先核数据库request/step/execution链，再核manifest摘要；404 SKILL_RESULT_404，503 SKILL_RESULT_503；仅COMPLETE+RESULT_COMMITTED提供可用成果 |
| IM05 | `GET /chat/requests/{requestId}/steps/{stepId}/outputs` | 无body → 200 R<Output[]> | JWT+原持久链及资料ACL，不能从任意新manifest投影；目录不代表字节已验证可下载 |
| IM06 | `GET /chat/requests/{requestId}/steps/{stepId}/outputs/{outputId}` | query download:boolean默认false；Range/If-Range可选 | 200/206字节；非法/不可满足单Range按现实现416（明确无body，保留Content-Range）；实际MIME、ETag、Disposition、Content-Length、Content-Range一致；404/503开始传输前统一错误 |
| IM07 | `HEAD /chat/requests/{requestId}/steps/{stepId}/outputs/{outputId}` | 同IM06资源/权限 | 同请求语义状态与响应头，无响应body，包括失败时；不能为统一JSON给HEAD追加错误body |
| IM08 | `GET /internal/agent/chat/requests/{requestId}/turns/{turnId}/materials/inputs/{sourceRefId}/content` | 唯一X-Materials-Manifest-Digest；无query/body | Native当前proof→固定manifest资料授权→读取→发送前再次fence检查；200字节；401/403/404/409/422/503沿用MATERIAL_INPUT_* |

目标DTO：
- TaskCatalog=`{catalogRevision:D0?,items:SkillCapability[]}`；SkillCapability=`{skillKey,skillVersion,title:String,state:CALLABLE|UNAVAILABLE,reasonCode:String?,costInfo:CostInfo}`；CALLABLE必须reasonCode=null。revision当前Runtime确为十进制String，浏览器原typedId校验需改数值串校验。
- CostInfo目标封闭对象：`{schemaVersion:2,mode:FREE|FIXED|METERED|UNKNOWN,rate:{amountDecimal:String,currency:String,unit:String}?,description:String?,source:String?,updatedAt:T?}`，所有字段required。amountDecimal为非负普通十进制，禁止浮点、指数、隐式币种；FREE/UNKNOWN的rate=null，FIXED/METERED缺少可靠单价可rate=null并保留说明，不推断免费。原costInfo.amount/currency/unit显式投影到rate；updatedAt原metadata文本只有经明确绝对时间解析才映射毫秒，无法解析为null并留安全诊断，不伪造当前时间。原签名包不改，API/Web DTO同批变更；不因为费用未知阻止技能执行。
- Discussion当前业务schemaVersion=2，字段必须齐全：`schemaVersion,materialScope:TASK_DEFAULT|EXPLICIT|NONE,intent:DISCUSSION|CLARIFICATION_REPLY,taskId:ID,expectedAssignmentRevision:D0,content:String,parentOutcomeId:ID?,expectedParentStateVersion:D0?,pendingQuestionId:ID?,expectedPendingQuestionStateVersion:D0?,sourceSelectors:Selector[]`。正文与选择均空非法；NONE不得选择资料；DISCUSSION待问字段必须null，parent两字段成对；CLARIFICATION_REPLY四个关联字段均非null。现行≤32个selector与200000字符限制保留，不新设门槛。
- Selector精确联合：`{kind:TASK_LINKED_WORKSPACE_VERSION,fileId,version:D1,purpose:INPUT|REFERENCE}`；`{kind:CURRENT_CONVERSATION_ASSET,assetId,assetRevision:D1}`；`{kind:EXECUTION_RESULT,executionId,resultSha256:H}`。后者由原resultDigest改名，**请求schemaVersion须随此破坏性字段变更升级至3**；版本2只能作为历史事实，目标parser不双解。
- Accepted字段：`schemaVersion:number,intent,requestId,userMessageId:String,turnIds:String[],state:String,stateVersion:D0,eventCursor:D0,statusUrl,typedOutcomeUrl:String,replay:boolean,pendingQuestionId:ID?`。外层HTTP requestId与这里业务requestId绝不能混用。
- SkillResult：`schemaVersion:2,conversationId,taskId,requestId,stepId,executionId:ID,conversationGeneration:D0,state,phase:String,executionStateVersion:D0,resultSha256:H?,data:JSON?,outputs:Output[]`；phase目标READY/RUNNING/WAITING_PROVIDER/RESULT_UNKNOWN/RESULT_READY/ARCHIVING/COMPLETE/FAILED/CANCELLED（以SkillExecutionMapper持久状态为准，原Web额外接受QUEUED/VALIDATING却漏READY/RESULT_READY）。
- Output：`outputId:ID,contentMimeType:String,sha256:H,byteLength:D0,previewUrl:String?,downloadUrl:String,assetRef:{assetId:ID,revision:D1}?,replaces:{requestId:ID,stepId:ID,outputId:ID,sha256:H}?`。两种URL必须是固定request/step/output路由，不能接受外部任意URL。

TypedOutcomeProjection的历史v1-v4包含不同签名对象；不能只将HTTP一处resultDigest改名就结束。`hallTypedDeliberation.js`的selector、actionProgress、finalDigest、messageSource及服务端投影必须逐层定义新schema；**此完整多态schema尚待字段闭合**，是明确发布阻塞，而非容忍旧新并存的理由。

## 6. Runtime文件执行：7条（补上原遗漏）

源码：`PersonalWorkspaceRuntimeFileController.java`及`agent/jia-agent-api/.../PersonalWorkspaceExecutionService.java`。Native+原run/task授权；当前会话与原执行实例分别校验；默认禁止query。

| ID | HTTP接口 | 输入→成功 | 状态/幂等 |
| --- | --- | --- | --- |
| RF01 | `GET /internal/agent/tasks/workspace-executions/commands` | 无body → 200 R<{items:RuntimeQueuedCommand[]}> | 仅本人队列；原批量16行为不借规范扩大；队列消息schema必须引用统一消息结构，不把旧messageType/payload强塞成新命令 |
| RF02 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/start` | Q<{commandId:ID,messageId:ID}> → 200 R<{executionId,taskId,runId,state}> | 原消息幂等、fence与事务；开始不代表成功 |
| RF03 | `GET /internal/agent/tasks/{taskId}/runs/{runId}/inputs` | 无body → 200 R<RuntimeInput[]> | RuntimeInput=`{inputRef,fileId,version:D1,originalFilename,contentMimeType,byteLength:D0,sha256:H}` |
| RF04 | `GET /internal/agent/tasks/{taskId}/runs/{runId}/inputs/{inputRef}/content` | 无body → 200字节 | 固定版本输入授权；读取后发送前重新核当前fence，不能用本地文件路径代替inputRef |
| RF05 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/outputs/{outputId}/content` | multipart file/taskId/runId/outputId/sha256/byteLength:D0（原length改名） | 201 R<{outputId,sha256:H,byteLength:D0,state}>；路径/表单声明严格相等；真实字节长度/hash必须匹配；相同run/output异字节冲突，不能仅依赖headerkey |
| RF06 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/output-commits/{manifestId}` | Q<{outputs:[{outputId:ID,sha256:H,byteLength:D0}]}>（现行限1保留） | 200 R<{manifestId,state,items:CommitItem[]}>；原manifest身份、暂存字节、事务与幂等检查；不可因提交HTTP成功伪造完整任务完成 |
| RF07 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/failure` | Q<{code:String}> → 200 R<ExecutionView> | code为业务失败原因，不是外层公共code；只允许原失败状态转换，不覆盖已提交成果 |

CommitItem=`{outputId:ID,fileId:ID?,fileVersion:D1?,sha256:H,contentMimeType:String,byteLength:D0}`。ExecutionView/RuntimeQueuedCommand存在嵌套command及scope字段，必须按当前Native执行契约补完整schema；此处不把Java类型引用当成已审定全部字段。错误转换：目标401无认证、403不允许、404原资源不可见、409状态/摘要冲突、400格式、503存储/依赖；当前controller将Service Failure统一404的分支需逐reason核验防枚举，不机械全放出。

**并非只有这7条Runtime文件路由。** `PersonalWorkspaceConversationRuntimeController`还有带会话执行fence的conversation inputs/staging/commit分支，已入全量索引；不能拿RF01-RF07替代它们或删除其租约证明。它们完整字段尚待单独闭合，发布范围必须包含实际图片执行所用分支。

### 6.1 真正的会话技能执行分支：另13条

此节补齐上面指出的遗漏。源码`PersonalWorkspaceConversationRuntimeController.java`，完整字段来源`SkillExecutionService.java`。共同Native认证、原task/run/execution授权、fence与幂等状态机；lease token仅私密Native传输，不能到浏览器、普通回执或日志。

字段词典：
- Fence=`{version:D1,token:String(秘密租约证明)}`；Lease=`{executionId:ID,version:D1,token:String,expiresAt:T}`。原version为long；目标字符串解析不强转。不可用sessionGeneration替代lease.version。
- EffectStart=`{executionId:ID,snapshotSha256:H,fence:Fence,effectSha256:H,providerBindingRevision:D0}`；EffectResult加`status:String,providerRequestId:ID?,resultSha256:H?`。分别映射原snapshotDigest/effectDigest/resultDigest，先校原摘要对象，不重算旧事实。
- EffectReceipt=`{schemaVersion:number,disposition:String,status:String,providerRequestId:ID?,resultSha256:H?}`，具体状态集合要与效果台账固定fixture。
- SkillInputs=`{schemaVersion:number,executionId:ID,leaseVersion:D1,snapshotSha256:H,noReferencedMaterials:boolean,inputs:RuntimeInputV3[]}`；source精确联合引用`PersonalWorkspaceExecutionService.RuntimeSource`的既有三种来源，不能把所有union分支填null。
- SkillResultCommit=`{fence:Fence,snapshotSha256:H,data:JSON?,artifacts:[{outputId:ID,sha256:H,byteLength:D0}],inputReceipts:[{inputRef:ID,sha256:H,carrier:String,delivery:String}]}`；data按技能result schema验证，不是任意扩大对象字段。
- SkillResultReceipt=`{manifestId:ID,state:String,phase:String,resultSha256:H}`；Resume=`{executionId:ID,snapshotSha256:H}`；ResumeReceipt=`{lease:Lease,phase:String,allowedWork:String[],resultManifest:原持久manifest投影?}`。历史manifest本体的签名字段不得直接更名；如对外完整暴露它须有独立schema/摘要版本，不能打包后声称已符合全部命名规则。

| ID | HTTP接口 | 请求→成功 | 不可丢失的状态约束 |
| --- | --- | --- | --- |
| CR01 | `GET /internal/agent/tasks/conversation-executions/commands` | 无query/body → 200 R<{items:Command[]}> | 只返回已授权Native命令；目标新执行消息统一schema，不允许回落旧private工作区命令 |
| CR02 | `GET /internal/agent/tasks/conversation-executions/controlled-image-v3-commands` | 无query/body → 200 R<{items:历史命令[]}> | 现行仍有只读映射；目标切换前核是否有在途，不能仅因类中还有方法就重开旧provider-start写路由 |
| CR03 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/lease` | Q<{commandId:ID,messageId:ID}> → 200 R<Lease> | 原命令领取租约，不代表Provider已开始；幂等重投按原租约语义 |
| CR04 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/lease/renew` | Q<Fence> → 200 R<Lease> | 核原token/version并原子推进，不绕当前Native会话fence |
| CR05 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/inputs` | Q<Fence> → 200 R<SkillInputs> | 有证明的读取，不因为POST名称误认新执行；资料是固定snapshot |
| CR06 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/inputs/{inputRef}/content` | Q<Fence> → 200字节 | POST只因私密lease证明不放URL；保留JSON请求+字节响应这一明确传输映射 |
| CR07 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/outputs/{outputId}/content` | multipart file/version:D1/token/sha256:H/byteLength:D0（原length） | 201 R<StagedOutput>；当前lease+原执行+实测hash/长度；不是RF05那套无lease表单 |
| CR08 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/output-commits/{manifestId}` | Q<{fence:Fence,outputs:[{outputId,sha256,byteLength:D0}]}> → 200 R<CommitView> | 保留当前单成果限制及事务，不能伪造技能数据型结果 |
| CR09 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/effects/{effectId}/start` | Q<EffectStart> → 200 R<EffectReceipt> | 原effect幂等、snapshot/providerBinding/fence均匹配后才允许外部副作用；不引入费用授权 |
| CR10 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/effects/{effectId}/result` | Q<EffectResult> → 200 R<EffectReceipt> | 记录已发生事实；超时未知不重开effect/new provider request |
| CR11 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/resume` | Q<Resume> → 200 R<ResumeReceipt> | allowedWork由状态机决定；不能通过resume再生成原已提交图片 |
| CR12 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/result-commits/{manifestId}` | Q<SkillResultCommit> → 200 R<SkillResultReceipt> | 资料使用回执+成果/数据原子提交；是图片技能结果链关键入口，不能漏在通用outputs提交之外 |
| CR13 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/failure` | Q<{fence:Fence,code:String}> → 200 R<ExecutionView> | 失败业务码放data，不覆盖外层E0；只允许合法状态转换 |

代码中`providerStart/controlledProviderStart/controlledProviderStartV3/inputsV3`等公开方法已移除mapping注解，不是活动HTTP接口；不能扫描public方法就将其登记/重新映射。CR01 Command嵌套parameters/outputs及历史manifest、错误全枚举仍须闭合，同一文档明确这些缺口，而不声称13条均可立即发布。

## 7. 退役接口与历史读取：不复活

在当前API基线确认以下方法直接调用`RetiredSkillWriteEndpoint.response()`：

| HTTP接口 | 目标 |
| --- | --- |
| `POST /chat/conversations/{conversationId}/interactions` | 410 R<null>, code=LEGACY_SKILL_WRITE_RETIRED |
| `POST /chat/conversations/{conversationId}/interactions/preview` | 同上 |
| `POST /chat/conversations/{conversationId}/interactions/provider-consents` | 同上 |
| `POST /chat/conversations/{conversationId}/interactions/provider-consents/{consentId}/revoke` | 同上 |
| `POST /chat/conversations/{conversationId}/interactions/inspection` | 同上 |

这些不是“旧版兼容执行入口”。不再定义可执行请求schema，也不自动转IM02。实际HTTP尚可能先经认证/参数绑定失败，目标保留认证边界并移除无用的旧body/幂等必填绑定，使到达退役处理器的请求始终得到安全410。

历史GET仍只读：inspection/request、inspection-outcome、interactions/provider-consents/request、interactions/request等见chat索引；不因保留查询就允许重新执行旧写操作。`AgentInspectionInputController`的旧inspection字节GET仍须原manifest header、Native身份与资源权限，不以“退役”允许任意资料读取。

## 8. UDS逐操作与WS/SSE边界

UDS目标均为统一message，私有socket不变；A `UnixManagedHostingProvisioner`与R `hosting-{wire,server,control}.mjs`双端协调。

| messageType | 请求data | 成功回复/关联 |
| --- | --- | --- |
| hosting.capabilities | tenantId/clientId/ownerJiacn:ID | hosting.capabilities.result；data同3字段+available:boolean+hostId:ID；code=E0不代表available=true |
| hosting.prepare | 上述scope+canonicalAgentId/bindingId/leaseId/initialIntentId/operationId:ID；operationKind:INITIAL或REPROVISION；reservedAt/requestedAt:T；validUntilAt:T? | hosting.prepare.result；原association+outcome=PREPARED、runtimeInstallationId、manifest、manifestSha256、enrollmentSecretSha256:H、enrollmentExpiresAt、provisionGeneration:D1、hostId；manifest保持自身签名schema |
| hosting.ensure | prepare字段+runtimeInstallationId/manifestSha256/provisionGeneration | hosting.ensure.result；原association+安装证明+outcome；新操作事实只能由状态机推进 |
| hosting.observe | 同ensure | hosting.observe.result；只观测，不新建租约；SERVICE_READY增加runtimeInstanceId/sessionGeneration:D1/serviceReadyAt/registeredAt/executorReady/durableReady/evidenceRef |

回复公共correlationId必须=request.messageId，requestId相同；错误带稳定code、data:null，不泄露路径/秘密；UNKNOWN/RECOVERY_REQUIRED是业务outcome，不能用网络异常猜READY。完整可选字段集合需分别为PREPARED/UNKNOWN/RECOVERY_REQUIRED/SERVICE_READY建封闭变体，不将多个变体全nullable。

补查出的3个WS入口：
1. `/ws/agent/channel`：AgentWebSocketHandler，Native handshake；按详设消息映射及安装结果/回执处理。
2. `/ws/chat`：ChatWebSocketHandler，现行入站`{conversationId,content}`、出站裸文本和`[EOM]`；属于自有协议差异，不是Provider外部格式豁免。目标chat.message请求与started/delta/completed/error信封，须先核实际认证/会话授权及消费者，不能仅统一字符串。
3. `/websocket`：sms WebSocketServer，现行query access_token及JsonResult混合消息。目标为主体认证后的sms.*消息；凭据传输先核真实客户端能力，不能机械塞到普通data。是否上线需查条件装配，不从源码推断。

已定位SSE入口：chat stream、conversation events、agent task events、scene events、archive question events。每条必须保留原授权、事件持久序号及重放窗口；`Last-Event-ID`当前可能是十进制sequence，迁移后不能替换为随机UUID导致丢失断线恢复。可将同一资源流内序号映射为可逆messageId（带资源前缀），Last-Event-ID用同一ID，由服务端解析回scope内序号；独立query cursor仍按该流schema校验。未知/错scope游标拒绝或发明确resync事件，不默默从最新开始。

## 9. 本文覆盖的真实边界

本文件给出关键接口的请求/响应类型、字段映射和权限/状态/幂等设计；普通标量字段已经细化，现有Map/Object、多态typed outcome、Runtime会话执行及部分枚举仍显式标记待闭合。全量目录保留所有596条声明并定位到源码行，不用“都有目录”冒充“每个接口已成为完整可执行契约”。接下来补这些字段应继续在本目录收口，不另建协议或兼容层。

## 10. 执行嵌套对象补核（2026-10-14，固定A/W/R基线）

本节是目标字段设计与源码追踪，不是已生成可执行schema/完整fixture，也不覆盖全部596条接口。沿用公共规范1.2。`ID`是不透明字符串，数字型ID才用十进制字符串；D0/D1为非负/正十进制字符串，H为裸64位小写hex。普通对象拒绝未知字段；下面明确声明的技能schema扩展点不是任意Map透传。

### 10.1 PersonalWorkspaceExecutionService的实际嵌套对象

来源：`agent/jia-agent-api/.../PersonalWorkspaceExecutionService.java:195–250`；生产者`PersonalWorkspaceExecutionServiceImpl.view/requestView/runtimeInput/command/committed`，浏览器`usePersonalWorkspaceExecution.js`及`useFormalTaskExecution.js`。不要仅根据Java record引用宣布字段已齐。

| 对象 | 完整字段及目标类型 | 约束/分支 |
| --- | --- | --- |
| ExecutionView | executionId/taskId/runId/targetAgentId:ID；conversationId:ID或null；state:String；failureCode/failureMessage:String或null；grantRevision:D1；outputContentMimeType:String或null；inputs:RuntimeInput[]；runtimeCommand:RuntimeCommand或null；executionMode:PRIVATE\|TASK\|CONVERSATION；businessTaskId/workItemId:ID或null；workItemState:String或null | 所有投影字段显式存在。不是所有mode都能套private表单校验：协议4创建时outputContentMimeType=null，runtimeCommand=null，且不会出现在旧RuntimeCommand队列中。TASK的businessTaskId等于taskId，PRIVATE/CONVERSATION为null。 |
| RuntimeInput | inputRef/fileId:ID；version:D1；originalFilename/contentMimeType:String；byteLength:D0；sha256:H | private/task历史输入；version需能精确转换到Java int正数；输入资料必须固定版本，不能改读latest。 |
| RuntimeCommand | taskId/runId:ID；inputManifest:RuntimeInputCommand[]；outputManifest:RuntimeOutput[] | 此旧桥接投影仅QUEUED、非协议4且输入及输出格式支持时非null；最多128输入。它不是新Skill Command。 |
| RuntimeInputCommand | inputId:ID；relativePath/downloadPath:String；length:D0；sha256:H | inputId来自inputRef；relativePath=`inputs/{inputRef}{受支持扩展名}`；downloadPath绑定`/internal/agent/tasks/{taskId}/runs/{runId}/inputs/{inputRef}/content`。不能接受任意URL/本机路径。 |
| RuntimeOutput | outputId:ID；relativePath/contentType/uploadPath:String；maxLength:D1 | 当前生产者恰好1项，outputId=output_1；relativePath=`outputs/result{扩展名}`；uploadPath绑定同task/run的`/outputs/output_1/content`。maxLength来自现用storage配置，不新增大小门槛。 |
| ExecutionSummary | executionId/targetAgentId:ID；state:String；outputContentMimeType:String或null；createdAt:安全epoch-ms number | 不含lease、token、prompt或storage URI；不能从摘要重构执行命令。 |
| ExecutionCursor | createdAt:安全epoch-ms number；executionId:ID | nextCursor=null或此对象；GET query必须同时给beforeCreatedAt/beforeExecutionId。 |
| ExecutionHistoryView | items:ExecutionSummary[]；nextCursor:ExecutionCursor或null | limit默认20、1..100；createdAt DESC、executionId DESC稳定排序。 |
| ExecutionCapabilities | allowedMimeTypes:String[]；inputMimeTypes:String[]；generationEnabled:boolean | 两数组非null且唯一；不是模型成功证明。输入支持text/plain；服务支持的输出集合也包含text/plain，Web当前输出白名单未包含，须按能力对象而非硬编码六类判断；不为此打开服务未启用的格式。 |
| StagedOutput / OutputDeclaration | StagedOutput={outputId:ID,sha256:H,byteLength:D1,state:STAGED}；OutputDeclaration={outputId:ID,sha256:H,byteLength:D1} | staging不等于commit；长度必须精确匹配实际字节及原storage限制。 |
| CommitItem / CommitView | item={outputId:ID,fileId:ID或null,fileVersion:D1或null,sha256:H,contentMimeType:String,byteLength:D1}；view={manifestId:ID,state:COMMITTED,items:CommitItem[]} | CONVERSATION输出没有workspace file，两个文件字段都允许null，不强行unbox或伪造文件引用。 |

`state`必须按对象/执行模式分流，不能共用一套宽松字符串校验：private常规状态QUEUED/INPUTS_REVOKED/OUTPUT_COMMITTED/FAILED；TASK存在OUTPUT_STAGED；技能CONVERSATION存在RESULT_COMMITTED。浏览器现有`EXECUTION_STATES`只列四项，不能将其机械扩成全系统状态机或用它否定合法技能结果。`workItemState`在TASK分支来自`AgentTaskWorkItemStatus.value()`的pending/ready/claimed/running/blocked/submitted/completed/failed/cancelled，或查不到时UNAVAILABLE；其他mode为null。

创建请求固定`{conversationId:ID|null,targetAgentId:ID,taskId:ID|null,instruction:String,outputContentMimeType:String|null,inputs:[{fileId:ID,version:D1}]}`。instruction非空、最多4000 code points；inputs必给、0..128、fileId不能重复；taskId非null要求conversationId非null。outputContentMimeType省略/null仍沿原DOCX默认，不扩展业务。输入元素null显式400，不能让stream.map NPE走503。Idempotency-Key必需且长度/字符按既有100上限；revoke body仅`{expectedGrantRevision:D1}`。

PersonalWorkspaceExecutionController的目标错误逐reason：BAD_REQUEST→400/BAD_REQUEST；NOT_FOUND→404/EXECUTION_NOT_FOUND；IDEMPOTENCY_CONFLICT→409/IDEMPOTENCY_CONFLICT；GRANT_CHANGED→409/GRANT_CHANGED；GRANT_REVOKED→409/GRANT_REVOKED；CAPABILITY_UNAVAILABLE→422/CAPABILITY_UNAVAILABLE；TASK_CONFLICT→409/TASK_CONFLICT；OUTPUT_CONFLICT/OUTPUT_MISSING/STORAGE_UNAVAILABLE→503/EXECUTION_UNAVAILABLE（本次保留该浏览器入口既有聚合语义）。格式错误400，未知异常503；认证失败必须在安全层401/403，不能继续当scope解析400。失败只改外层R，不覆盖ExecutionView.failureCode。写结果不明先原键GET，禁止自动换键重建；资料授权仍按可信主体，不因取消同Owner执行资格而放开文件ACL。

### 10.2 SkillExecutionService扩展点与固定字段

来源：`SkillExecutionService.java`、`PersonalWorkspaceExecutionServiceImpl:2794–3181`、`ChatSkillSourceAccessService.java`；消费为Runtime `skill-conversation.mjs`、`skill-executor.mjs`、`skill-manifest.mjs`。协议4业务对象不等于公共protocolVersion4，公共wire仍为1。

- Command完整字段：`executionProtocolVersion=4, executionId, taskId, runId, conversationId, conversationGeneration:D1, commandId, messageId, skill, snapshotDigest:H, parameters, instruction, inputRefs:ID[], outputs`。skill严格`{key,version,packageSha256:H,manifestSha256:H}`；outputs严格`{dataSchema:JSONSchema|null,mimeTypes:String[],multiple:boolean,artifactsRequired:boolean}`。参数/输出schema取已校验package/manifest摘要绑定的安装描述符，使用既有SkillManifestCodec/compileSkillSchema支持的schema子集；不是让平台逐技能硬编码，也不是接受调用方任意提供schema。
- Inputs固定`{schemaVersion:4,executionId,leaseVersion:D1,snapshotDigest:H,noReferencedMaterials:boolean,inputs:SkillInput[]}`，最多32项、inputRef唯一并与command.inputRefs一一对应；noReferencedMaterials严格等于数组为空。SkillInput固定`{inputRef,sourceKind,sourceIdentity,contentMimeType,byteLength:D0,sha256:H}`。
- sourceIdentity为精确三分支：TASK_LINKED_WORKSPACE_VERSION=`{kind,fileId,version:D1,purpose:INPUT|REFERENCE}`；CURRENT_CONVERSATION_ASSET=`{kind,conversationId,conversationGeneration:D1,assetId,assetRevision:D1,producerRequestId,producerRequestRevision:D1,producerStepId,producerExecutionId,producerRunId,producerOutputId}`；EXECUTION_RESULT=`{kind,executionId,resultDigest:H}`。其中kind必须等于外层sourceKind。不用旧受控图片RuntimeSource替代此联合，后者缺producerRequestRevision且结构不同。sourceIdentity属于冻结snapshot的摘要材料，保留已冻结名称/字节，不能对其中resultDigest机械重命名。
- Fence=`{version:D1,token:UUID}`；Lease=`{executionId,version:D1,token:UUID,expiresAt:安全epoch-ms number}`。version当前Java long/JS safe number→新wire字符串，API进入service前精确解析；Runtime只在显式边界转换，token不进入浏览器/日志。
- EffectStart=`{executionId,snapshotDigest,fence,effectDigest:H,providerBindingRevision:String|null}`；EffectResult再加`status:ACCEPTED|SUCCEEDED|FAILED|UNKNOWN,providerRequestId:ID|null,resultDigest:H|null`；SUCCEEDED要求resultDigest非null。EffectReceipt=`{schemaVersion:1,disposition:NEW|RECORDED|KNOWN|UNKNOWN,status:START_RECORDED|ACCEPTED|SUCCEEDED|FAILED|UNKNOWN,providerRequestId:ID|null,resultDigest:H|null}`。receipt所有键显式存在；同effectId不同digest/binding拒绝，已SUCCEEDED/FAILED只允许相同结果重投，不重复调用Provider。
- Resume=`{executionId,snapshotDigest}`；ResumeReceipt=`{lease,phase,allowedWork:String[],resultManifest:ResultManifest|null}`。allowedWork只有三组：有manifest→STAGE_OUTPUTS/COMMIT_RESULT/PROJECT_RESULT；无effects且READY/RUNNING→EXECUTE；否则QUERY_EFFECT/RECOVER_LOCAL_RESULT。START_RECORDED在恢复中转UNKNOWN，不能转成可再次生图。
- phase来自既有READY/RUNNING/WAITING_PROVIDER/RESULT_UNKNOWN/RESULT_READY/ARCHIVING/COMPLETE/CANCELLED/FAILED；不能把HTTP200、effect ACCEPTED或ARCHIVING当COMPLETE。
- ResultCommit=`{fence,snapshotDigest,data:动态数据|null,artifacts:OutputDeclaration[],inputReceipts:InputReceipt[]}`。artifacts最多128，receipt最多32且与输入一一对应。InputReceipt严格`{inputRef,sha256,carrier:native-text|native-image|native-audio|parsed-text|provider-image|local-file,delivery:DELIVERED}`；local-file仅local-script runner可用。data由被冻结outputs.dataSchema核验，无schema则必须null；没有artifacts而有合法data仍可能是有效结果，不强制所有技能产文件。
- ResultManifest为已持久化事实`{schemaVersion:1,data,artifacts,inputReceipts,resultDigest,manifestId}`。现有canonical预像仅前四项，artifacts.byteLength为安全整数number。**此原始签名/摘要对象明确保留自身类型，不套普通byteLength字符串投影规则**；外层业务投影仍用字符串。Runtime `skillResultPayload/validateCommittedSkillResult`验证原始canonical预像，不能把映射后的DTO重算成“旧摘要”。ResultCommit新wire的artifacts.byteLength为D1，进入业务canonical前精确解析到原有安全范围；输出raw ResultManifest和浏览器输出DTO是两个明确类型，不做猜测回退。

会话技能入口的错误不是普通浏览器错误表：Controller.skillCall包装后BAD_REQUEST→400/BAD_REQUEST，NOT_FOUND→404/CONVERSATION_EXECUTION_UNAVAILABLE，TASK_CONFLICT/IDEMPOTENCY_CONFLICT/GRANT_CHANGED/GRANT_REVOKED/OUTPUT_CONFLICT/OUTPUT_MISSING→409/CONVERSATION_EXECUTION_CONFLICT，CAPABILITY_UNAVAILABLE/STORAGE_UNAVAILABLE→503/CONVERSATION_EXECUTION_UNAVAILABLE。旧非skillCall入口仍存在Service Failure一律404的适配差异，需逐入口保留不可见性，不机械全局改reason映射。Runtime目前把非预期status聚合成SKILL_API_RESPONSE_UNCONFIRMED；目标公共decoder应保留真实status/code/requestId，底层传输错误不伪造服务端code。此节没有宣布所有旧入口错误分支已全部核定。

## 11. 议事多态结果：字段级补充核对（2026-10-15）

本节是§5的补充目标，不是已实现协议。固定API/Web基线见机器核对表；实际producer为`ChatTypedDeliberationController → ChatTypedDeliberationService.read → ChatActionFinalService.read/projection/actionProgress`，历史事实读取由`HistoricalChatActionReader`及旧typed reader承担。不能把早期`TypedProjection` Java record当作实际v4返回类型。

### 11.1 必须区分的四种版本

- 公共信封`protocolVersion=1`不变。
- 浏览器新Discussion入参`schemaVersion=3`，对应§5的`resultSha256` selector；`Accepted.schemaVersion=3`与新入参一致。
- **统一结果展示投影`schemaVersion=5`**：新HTTP/SSE业务投影只输出这一种schema；浏览器不继续`[1,2,3,4].includes()`多版本猜测。
- 已存模型事实`outcomeContractVersion=1|2|3|4`仍指原canonical事实的算法版本，不伪造为5。API先按事实版本验证原始字节，再投影到5；历史事实只读不是旧wire兼容，不能启动旧proposal/INSPECT或恢复旧在途操作。新模型action协议及其canonical对象不得仅为HTTP投影改名而重写。

### 11.2 完整投影字段及联合

下表对象除明确说明的技能参数外封闭；所有列出的字段都required，`?`表示允许null，不表示可以缺字段。ID/D0/D1/H/T沿用本文定义。字符串内容保留原文，禁止trim后当成摘要输入。

| 对象 | 字段/约束 |
| --- | --- |
| TypedOutcomeProjection | `schemaVersion:5,route:CHAT|INSPECT,conversationId:ID,conversationGeneration:D1,requestId:ID,requestRevision:D1,turnId:ID,state:PENDING|READY,outcome:Outcome?,inspection:Inspection?,actionProgress:ActionProgress?` |
| Outcome共通 | `outcomeContractVersion:1|2|3|4,outcomeId:ID,taskId:ID,assignmentRevision:D0,assistantMessageId:D1,finalSha256:H,kind:ANSWER|CLARIFY|EXECUTION_PROPOSAL|ACTION_REQUEST,text:String,clarification:Clarification?,proposal:Proposal?,action:Action?,deliverable:boolean?,messageSource:MessageSource?,deliveryRelation:DeliveryRelation?` |
| Clarification | `pendingQuestionId:ID,state:OPEN|ANSWERED,stateVersion:D0,question:String,requiredFacts:String[],replyRequestId:ID?`；非空、无重复facts；OPEN严格`0/null`，ANSWERED严格`1/ID`。v1/v2 facts只允许`SOURCE_SELECTION/REFERENCE_REQUIRED/REQUIREMENT_DETAILS/OPERATION_CHOICE`；v3/v4是非空文本事实，不能硬改成旧enum |
| Proposal（历史只读） | `proposalId:ID,state:PROPOSED,stateVersion:"0",operation:GENERATE_IMAGE|EDIT_IMAGE,instruction:String,sourceRefIds:ID[],sourceSelectors:Selector[],parent:{requestId:ID,stepId:ID}?`；两数组等长，GENERATE_IMAGE.parent=null；EDIT_IMAGE恰好一个CURRENT_CONVERSATION_ASSET且parent非null；禁止从此投影调用工具 |
| Action | `actionRequestId:ID,actionId:String,instruction:String,sourceRefIds:String[],parameters:Object?`；v4参数为安装manifest声明的JSON对象，v3历史动作参数投影null，禁止补`{}`伪造原参数；sourceRefIds唯一且最多32；actionId/sourceRefId按原action标识最大512 UTF-16单元/无ISO控制符，不套普通ID100限制 |
| MessageSource | `turnId:ID,messageId:D1,snapshotId:ID,finalSha256:H`；只能在ANSWER且deliverable=true出现；必须与父projection.turnId、outcome.assistantMessageId/finalSha256精确一致 |
| DeliveryRelation | `mode:APPEND|REPLACE|RESET,parentOutcomeId:ID,parentFinalSha256:H,targetOutcomeId:ID?,targetFinalSha256:H?`；target成对；只有REPLACE允许非null target；不能指向自己；ACTION_REQUEST仅APPEND/RESET且target均null。ANSWER必须deliverable=true才允许relation。REPLACE未指定target时仍按既有明确父节点语义，不按latest猜测 |
| Inspection | `authorizationId:ID,manifestSha256:H,sourceRefIds:String[],inputSummary:InputSummary?`；历史INSPECT为非空唯一sources≤32；CHAT无对应旧inspection授权时为null，不能凭用户选择资料就捏造读取证据 |
| InputSummary | `inputSha256:H,sources:ReceiptSource[]`；来源数量/顺序严格等于Inspection.sourceRefIds |
| ReceiptSource | `sourceRefId:String,sha256:H,byteLength:D0,carrier:DIRECT_TEXT|LOCAL_IMAGE|LOCAL_AUDIO|PARSED_TEXT,contributionSha256:H` |
| ActionProgress | `actionRequestId:ID,state:QUEUED|RUNNING|WAITING_PROVIDER|RESULT_UNKNOWN|ARCHIVING|COMPLETED|FAILED|CANCELLED,dispatchVersion:D0,childRequestId:ID?,childRoute:INSPECT|EXECUTE?,childStateVersion:D0?,executionId:ID?,executionStateVersion:D0?,phase:READY|RUNNING|WAITING_PROVIDER|RESULT_UNKNOWN|RESULT_READY|ARCHIVING|COMPLETE|FAILED|CANCELLED?,resultSha256:H?` |

联合约束：
1. PENDING严格outcome/actionProgress=null；有inspection时inputSummary=null。READY必须outcome非null。ANSWER其他三分支=null；CLARIFY只有clarification非null；EXECUTION_PROPOSAL只有proposal非null且只能原版本1/2；ACTION_REQUEST只有action非null且只能3/4。不可把旧提案升级成当前技能执行。
2. deliverable缺省的历史事实投影为null；显式false仍false；不补造messageSource。模型事实缺省与null的原始差异保留在canonical对象，不对投影重算旧final。
3. 原版本4只在CHAT，ACTION_REQUEST的childRoute只能EXECUTE；版本3历史childRoute可INSPECT/EXECUTE。childRequestId=null时所有child/execution/phase/hash=null且state仅QUEUED/FAILED；有child必须有childStateVersion。旧v3没有execution/version/phase/result事实时四者null，不伪造执行ID。
4. 当前v4 phase→state必须同producer：READY/RUNNING/RESULT_READY→RUNNING，WAITING_PROVIDER/RESULT_UNKNOWN/ARCHIVING/FAILED/CANCELLED同名，COMPLETE→COMPLETED。COMPLETE还要求child aggregate、step、link、result均RESULT_COMMITTED且摘要非null，不能只核phase。
5. 老版本记录有数据缺损时按原失败边界返回错误，不为了凑齐schema制造事实。GET读取不做重派发、安装、购买、文件保存或Provider调用。

### 11.3 摘要映射的准确位置

`finalDigest→finalSha256`、`parentFinalDigest→parentFinalSha256`、`targetFinalDigest→targetFinalSha256`、`manifestDigest→manifestSha256`、`inputDigest→inputSha256`、`contributionDigest→contributionSha256`仅在**已经验证原事实后的对外投影**去掉精确`sha256:`前缀；`resultDigest→resultSha256`原本裸hex，禁止再strip任意前缀。输入H必须64小写hex，不能用大小写转换让无效摘要通过。

反向边界（新Selector、成果确认messageSource及delivery basis）先验证新DTO、资源scope及版本，再构造内部原canonical引用字段；不得原地改持久事实、原result manifest、messageId或outbox fingerprint。浏览器只使用新字段，不在组件层补旧前缀；HTTP requestId不是业务requestId。

### 11.4 实际不一致及确定的改法

- **正文限制不同**：`discussionBody`目前4000 Unicode code points并排斥部分控制符；Java parse允许200000 UTF-16单元的合法scalar。目标新Discussion沿已接受的服务端边界：两端均合法Unicode scalar、≤200000 UTF-16单元、原文保留；正文/选择不能同时为空。不是给系统新增限额。历史v1/v2 outcome文本仍按其原validator，不把历史验签放宽。
- **进度比较缺执行版本**：`actionProgressAdvances`同版本比较仅progressFields六项，漏executionId/phase/resultDigest；phase取技能执行、childStateVersion取chat request，不能视为同一个版本。已追到`SkillExecutionMapper.compareAndSet`：phase/effects/result_manifest/last_error同一UPDATE并使version+1，初值0；`skillCas`回填加一的row.version。因此直接复用此持久CAS版本，不新增表/计数器：`SkillExecutionService.ResultView`增加`long executionStateVersion`，`PersonalWorkspaceExecutionServiceImpl.result`传`skill.getVersion()`，`ChatActionFinalService.actionProgress`输出十进制字符串；无child或历史v3投影null。
  - Web以`(dispatchVersion,childStateVersion,executionStateVersion)`分别比较BigInt；已存在child/execution后身份必须稳定，三个已知版本均不倒退；只要任一倒退整包弃用，不能字段拼接成不存在的状态。
  - 新旧executionStateVersion相同则phase/resultSha256必须相同；所有版本相同则全部进度字段相同；仅skill版本前进允许同child版本phase变化。COMPLETED/FAILED/CANCELLED不可换终态；manifest摘要一旦非null不得换值/清空。
  - §5 SkillResult业务schema2同批增加`executionStateVersion:D0`，从同一ResultView投影；`hallSkillResults.js/useHallSkillResults.js`采用相同不倒退规则。版本只描述读到的状态，不授权写入或跳过scope/fence。
  - 此项已有持久版本来源和写路径证据，不再列为“未知版本源”；仍需开发时用交错响应fixture验证，而不是声称现实现已正确。
- **资料读取证据不是inspection UI字段**：v4 `ChatActionFinalValidator`从materialsInput验证receipt并纳入canonical final；`ChatActionFinalService.read`却仅在旧route INSPECT填inspection，而v4绑定只允许CHAT。因此`inspection=null`不等于模型未读图；本次统一wire不能据此伪判识图失败，也不新增“强制INSPECT”流程。原始输入receipt保持事实验证；是否增加展示字段不作为本次改造的新需求。
- **实际结果phase不一致**：`ChatSkillResultController`直出`SkillExecutionService.ResultView.phase`，而`hallSkillResults.js`允许QUEUED/VALIDATING却漏掉真实READY/RESULT_READY。目标schema2按持久phase枚举；`skillResultReferences`从硬编码schema4改为展示schema5且outcomeContractVersion=4，沿原execution/child精确绑定。READY等非COMPLETE不得显示成可用成果；API目标非COMPLETE时data=null/outputs=[]，不改变原manifest事实。COMPLETE同时核view.state和request/step/link均RESULT_COMMITTED、原manifest摘要匹配，才投影data/outputs。
- **成果确认的最后一跳已定位**：`useHallBountyFinalization.js`的messageFields/validMessageSource/selectionMatches同步finalSha256；`chat/.../AgentTaskSelectedOutputFinalizationController.parse`的JSON parser仅在新DTO入口将经验证H构造成内部原MessageSource.finalDigest。`AgentSelectedOutputFinalizationServiceImpl`约第630行hash仍使用内部原带前缀字符串，不能对新wire直接hash。字段转换不能改变同key业务意图、资料ACL或原持久成果事实。
- `hallTypedDeliberation.js` strict parser、`useHallTypedDeliberation.js`sameFinal/apply/readOne/parent选择、`bountyOutputCatalog.js`文本与媒体合并/来源哈希/替换链、成果确认请求组装必须同步新字段和schema5；只改GET结果会导致后续下载/交付找不到来源。
- `ChatTypedDeliberationController`当前400对应INVALID_REQUEST/INVALID_ROUTE/GAP，404 NOT_FOUND_OR_FORBIDDEN，409 CONFLICT，503 PERSISTENCE_ERROR/UNAVAILABLE；新公共认证边界401，错误data=null/no-store，不能全变200。旧inspection读取目前通过message包含source/unsupported选422；目标用类型化原因固定映射，不继续按msg分支。退役POST inspection仍410，不复活。

**闭合边界**：本节给出多态展示字段目标和真实producer差异，不代表新WS/模型final/HTTP成果确认全链fixture已经闭合；进度版本的实现验证及所有成果消费者仍需沿实际调用补证据。不能据本节把全局ready改true。

## 12. 技能商城7条HTTP入口：完整DTO与提交语义续核（2026-10-15）

只补SK01—SK07；不把任务同Owner资格取消扩散到安装管理/购买权限。Actor来自已认证JWT的sub、client_id、jiacn；租户沿现行固定部署`0`，不是接受业务body.tenantId。OwnerResolver仍核sub与持久用户身份及账户可认证状态。运行costInfo展示和本节SILVER商品交易是不同事项。

### 12.1 全字段目标

所有字段required，`?`允许null。GET无body/query；POST用Q包业务对象，公共requestId/header先核一致。`Idempotency-Key`为单一、规范小写36字符UUID（来自HostingRentHttp.key），不是普通任意ID。

| DTO | 字段 |
| --- | --- |
| Capabilities | `economyPreviewEnabled:boolean,skillMarketplaceEnabled:boolean,principalScopeSha256:H` |
| Product | `productId:ID,name:String?,description:String?,productVersionId:ID,skillKey:String,skillVersion:String,price:Money,permissions:String[],canPurchase:boolean,denialReason:String?,deploymentRestriction:NONE|ADMIN_ONLY`；canPurchase=true时denialReason=null，否则SKILL_ADMIN_REQUIRED；nullable保守依据producer未做非空校验，不臆造线上列约束 |
| QuoteRequest | `productVersionId:String,targetAgentId:String,expectedAgentVersion:D1` |
| Purchase | `quoteId:String,productVersionId:String,targetAgentId:String,expectedPrice:Money,expectedAgentVersion:D1,approvedPermissions:String[]` |
| Quote | `quoteId:ID,productVersionId:ID,targetAgentId:ID,expectedAgentVersion:D1,price:Money,expiresAt:T`；只将当前字符串epoch毫秒改JSON安全整数，不改变五分钟报价有效期 |
| Order | `orderId:ID,productVersionId:ID,targetAgentId:ID,expectedAgentVersion:D1,status:FUNDS_HELD|INSTALLING|ACTIVE|REFUNDED` |
| Entitlement | `entitlementId:ID,targetAgentId:ID,orderId:ID,skillInstallationId:ID,productVersionId:ID,skillKey:String,skillVersion:String,status:PENDING_INSTALLATION|ACTIVE|FAILED,permissionGrantVersion:D0`；原installationId只在此投影改名，不误改runtimeInstallationId |

沿§4 Money统一目标：priceMicro→price、expectedPriceMicro→expectedPrice，Money.amountMinor为精确Long范围非负十进制字符串、currency严格SILVER，数值一对一不缩放。API先校验Money再映射内部expectedPriceMicro参与原hash，不把JSON对象串直接代替原金额字符串。请求String沿现有HostingRentHttp.exact最大100 UTF-8字节、无边缘空白/ISO控制符/非法surrogate；permissions≤32且不重复，规范化排序后参与原请求hash，不能trim去重悄悄改变批准意图。返回permissions来自已批准manifest，不是前端随意选择的授权值。

SK01→200 R<Capabilities>；SK02→200 R<Product[]>（现有最多100条，不伪造分页）；SK03→200 R<Product>；SK04→200 R<Quote>；SK05→200 R<Order>；SK06→200 R<Order>；SK07→200 R<Entitlement[]>。响应均private,no-store。

### 12.2 事务、重放与状态必须保留

- quote/purchase先按actor+tenant/client+key查原回执，原hash仅规范化**业务data**，不将公共requestId/信封键加进旧hash。无回执才准备包、核当前注册proof，在原fence内进入事务；actor锁、Agent版本CAS、权限、当前商品版本、安装唯一性、订单/权益/outbox/回执/冻结在一个REQUIRED事务闭包；不在数据库事务内启动Runtime/Provider。
- **购买返回FUNDS_HELD不是“安装成功”也不是新缺陷**：同事务已将DB订单标INSTALLING，但返回和幂等回放使用已保存FUNDS_HELD receipt；GET order查询当前INSTALLING/ACTIVE/REFUNDED。源码测试明确断言此差异。目标保持该语义，不能随意把重放响应改当前状态造成同key结果不稳定；前端再GET追踪状态。
- 订单ACTIVE须已有成功安装回执及相应结算；REFUNDED是失败后释放事实，不能将HTTP超时直接设退款。零价不产生escrow，不能据此绕开安装权限。安装生命周期修复候选75a050c必须合并保留。
- SK07看似GET，但`requireOwned(...ready=false)`会ensure/advance内部Agent CAS观察版本；它不发新安装/扣款。保留GET只读业务语义，不能像业务写动作一样机械改POST；规范化序列化不能多调用一次此service。

### 12.3 错误映射：以已查可达源码为界

| HTTP | code与来源 |
| --- | --- |
| 400 | BAD_REQUEST（缺key、重复key头、非UUID、字段/类型/Long/重复权限/未知query）；包读取可能抛SKILL_ARCHIVE_INVALID、SKILL_ENTRYPOINT_INVALID、SKILL_IDENTITY_MISMATCH；这些是已批准包的服务端读取错误，目标在商城读取边界映射503/SKILL_PACKAGE_UNAVAILABLE，日志保留原cause，不误报用户body400（维护上传接口另按其真实输入错误定义） |
| 401 | UNAUTHENTICATED（非已认证JWT） |
| 403 | AGENT_OWNER_UNPROVEN（身份绑定缺失）、AGENT_FORBIDDEN（非安装管理者或系统Agent）、SKILL_ADMIN_REQUIRED（受限商品） |
| 404 | SKILL_PRODUCT_NOT_FOUND、SKILL_QUOTE_NOT_FOUND、SKILL_ORDER_NOT_FOUND（作用域内不可见） |
| 409 | IDEMPOTENCY_CONFLICT、SKILL_ALREADY_ORDERED、SKILL_QUOTE_USED、SKILL_QUOTE_EXPIRED、SKILL_QUOTE_MISMATCH、SKILL_PERMISSIONS_MISMATCH、SKILL_PRODUCT_CHANGED、SKILL_AGENT_REGISTRATION_REQUIRED、AGENT_NOT_READY、AGENT_VERSION_CONFLICT、SKILL_STATE_CONFLICT |
| 422 | INSUFFICIENT_FUNDS |
| 503 | SKILL_MARKETPLACE_DISABLED、SKILL_STATE_UNAVAILABLE、SKILL_PACKAGE_UNAVAILABLE、SKILL_PACKAGE_PREPARE_IN_TRANSACTION、SKILL_PACKAGE_STORE_INVALID；未分类运行时异常按SKILL_STATE_UNAVAILABLE |

`EconomyPostingException`当前除INSUFFICIENT_FUNDS外均409，不能把这段现实现当目标规范。已追到EconomyPostingServiceImpl的validate/replay：目标明确穷尽switch，FEATURE_DISABLED→403/ECONOMY_PREVIEW_DISABLED；INVALID_COMMAND、IMBALANCED_TRANSACTION、JOURNAL_CORRUPT→500/各原code（本商城的账务command由服务端生成，非浏览器任意账务行输入）；AMOUNT_RANGE_EXCEEDED、INSUFFICIENT_FUNDS→422/各原code；ACCOUNT_NOT_FOUND、ACCOUNT_NOT_ACTIVE、IDEMPOTENCY_CONFLICT、JOURNAL_ID_CONFLICT、CONCURRENCY_CONFLICT、ESCROW_CONFLICT→409/各原code。安全msg，不输出账务实体。500/503及网络未知保持原key只读追查，不能自动换key重买；409只有明确CONCURRENCY_CONFLICT允许原key重试，其余先查业务状态。此为错误HTTP映射对齐，不修改posting事务或计算。沿AgentService/托管新任务准入抛出的未分类异常目前落通用503，不能把私有cause/msg泄露到响应。错误统一R、data=null、安全msg；以上是映射规则和已查可达业务码，不是宣称穷尽所有基础设施故障。

### 12.4 Web具体改动，不保留猜测解包

- `useSkillMarket.js`金额读取price.amountMinor/校验currency=SILVER，Purchase发expectedPrice对象；不再读priceMicro/发expectedPriceMicro，页面money formatter只接已校验amountMinor。移除readSkillApiPayload多层data/code猜测；products/entitlements只接收上述数组，不继续items/list/records/products兜底。Quote.expiresAt只接受安全整数毫秒；取消decimalEpochMilliseconds字符串转换。
- **已证实幂等键格式冲突**：当前crypto.randomUUID缺失会回退`skill-order-${Date.now()}-${Math.random()}`，后端必拒400。目标使用安全randomUUID或getRandomValues生成标准UUIDv4；均不可用时本地明确报错且不POST，不采用弱随机或改后端放宽。请求重试沿原key，不生成新key绕未知订单。
- `economyPreviewCapability.js`接收中央客户端已解包的三字段DTO，严格boolean/H；`SkillMarketRoute.vue`、`useHostingRent.js`、`JuyiHall.vue`作用域key同步principalScopeSha256。此hash仅隔离浏览器状态，不授予权限。
- `economyReadOnlyPreviewPolicy.js`是另一个六字段本地契约，不能只因为含principalScopeFingerprint就认定是这三字段API消费者。实际装配已查清：`economyReadOnlyPreviewApi.js`调用独立`/economy/preview/capabilities`，由EconomyReadOnlyPreviewController提供，路由守卫/UserProfile/只读页引用；按§13迁移，禁止合并两份业务DTO或添加假features/actions。

本节闭合DTO形状/主要可达错误/事务及确定消费者改法；机器全局状态仍需与其余接口、间接调用证据共同核定，不能以7条详设替代全量可开发结论。

## 13. 只读经济预览9条：独立业务DTO，同一公共协议（2026-10-15）

实际调用链`router/index.js/UserProfile.vue/EconomyReadOnlyPreview.vue → economyReadOnlyPreviewClient → createApi('/economy/preview')`已核实；这不是§12同一接口的第二套信封。API端`EconomyReadOnlyPreviewController.PreviewResult`重命名msg为message的分叉必须删除，统一R；业务能力的feature/actions仍保留，不合并成商城三字段Capabilities。

### 13.1 路由和输入

全部JWT认证、private,no-store、200 R<下表类型>；GET无body，未列query的接口禁止query，重复query项拒400。path productId/agentId按现有exact≤100 UTF-8字节，无边缘空白/控制符/非法scalar。

| operation | 输入 | 输出 |
| --- | --- | --- |
| GET /economy/preview/capabilities | 无 | PreviewCapabilities |
| GET /economy/preview/wallet | 无 | Wallet |
| GET /economy/preview/ledger | `cursor:String?`，规范无padding22字符base64url；`pageSize:int32`默认50/1..100（原limit改名） | LedgerPage |
| GET /economy/preview/skill-products | `cursor:String?`（缺省从offset0）；`pageSize:int32`默认50/1..100；解码offset+pageSize不得int32溢出 | ProductPage |
| GET /economy/preview/skill-products/{productId} | path | PreviewProduct |
| GET /economy/preview/agents/{agentId}/skills | path | AgentSkills |
| GET /economy/preview/hosting-plan | 无 | HostingPlan |
| GET /economy/preview/agents/{agentId}/hosting-lease | path | HostingLease |
| POST /economy/preview/bounty-estimates | Q<{grossBountyAmount:Money,minimumAcceptedPayout:Money,estimatedTokens:TokenClasses,worstTokens:TokenClasses}> | BountyEstimate |

估算是纯计算、不创建回执/订单/报价、不持久化/扣费、不调用模型；不因POST就新增Idempotency-Key账表。estimated四类token逐项≤worst；Long溢出与算术越界拒400。token四类不重叠，cachedInput不是input的子集，沿原计算器不重新解释。

### 13.2 全字段定义

均required；`?`明确允许null，不允许省略。D0为Long非负十进制，D1为正数，T为安全整数epoch毫秒，H为裸64hex；Money沿§4，PositiveMoney.amountMinor为D1且currency=SILVER；root currency保留为资产单位声明，所有嵌套Money必须与它一致。分页继续遵守公共规范，不能因原record缺hasMore而省略目标字段。

| DTO | 目标字段 |
| --- | --- |
| PreviewCapabilities | `contractVersion:"economy-readonly-v2",mode:READ_ONLY_PREVIEW,enabled:boolean,principalScopeSha256:H,features:Features,actions:Actions`；版本升级是业务字段fingerprint改名，不改公共protocolVersion |
| Features | `wallet,ledger,catalog,installationStatus,hostingPlan,hostingLease:boolean`；当前producer均等于enabled |
| Actions | `estimate:boolean,issue:false,purchase:false,settle:false,refund:false,install:false,hostingActivate:false,hostingRenew:false`；estimate=enabled，不能从商城能力复制购买true |
| Wallet | `currency:SILVER,available:Money,held:Money,version:D0` |
| LedgerPage | `items:LedgerItem[],nextCursor:String?,hasMore:boolean`；limit+1探测，hasMore与nextCursor非null等价 |
| LedgerItem | `transactionId:ID,entryId:String,businessType:String,businessRef:ID,direction:CREDIT|DEBIT,amount:PositiveMoney,status:POSTED,postedAt:T`；entryId原上限140字节；businessType沿源账务业务类型，不向前端引入未知业务执行分支 |
| ProductPage | `items:PreviewProduct[],nextCursor:String?,hasMore:boolean`；内部保留offset查询，以`offset:<规范非负int32十进制>`编码cursor；pageSize+1探测，末页null/false；不伪造total，不增第三种offset wire分页 |
| PreviewProduct | `productId:ID,name:String,description:String,productVersionId:ID,skillKey:String,skillVersion:String,price:Money,permissions:String[],deploymentRestriction:String,priceSource:CATALOG,purchaseAllowed:false`；service验证name非空/≤255字节、description非null；deploymentRestriction现行仅exact≤20字节，目标按批准商品来源NONE/ADMIN_ONLY校验，异常持久值503，不降级NONE |
| AgentSkills | `agentId:ID,entitlements:PreviewEntitlement[],installationEvidence:InstallationEvidence[],observedAt:T` |
| PreviewEntitlement | `entitlementId:ID,orderId:ID,skillInstallationId:ID,productVersionId:ID,skillKey:String,skillVersion:String,status:PENDING_INSTALLATION|ACTIVE|FAILED,permissionGrantVersion:D0`；现行非负校验，商城同为D0；安装初建1不代表可省略读取下界定义 |
| InstallationEvidence | `skillInstallationId:ID,skillKey:String,skillVersion:String,status:VERIFIED_INSTALLED|UNCONFIRMED,evidenceKind:PERSISTED_INSTALL_RESULT|NO_PERSISTED_INSTALLATION|PERSISTED_INSTALLATION_UNVERIFIED,verifiedAt:T?` |
| HostingPlan | `source:CONFIGURATION_REFERENCE|PERSISTED_REFERENCE,planVersion:D1,amount:PositiveMoney,periodSeconds:D1,currency:SILVER,activationAllowed:false`；配置descriptor读取和持久plan读取都是参考，均不创建plan或激活 |
| HostingLease | `agentId:ID,applicability:NOT_APPLICABLE|APPLICABLE,lease:Lease?`；非托管必null，托管未找到lease也允许null，不制造PROVISIONING |
| Lease | `leaseId:ID,version:D0,status:PROVISIONING|ACTIVE|REFUNDED,planVersion:D1,amount:PositiveMoney,periodSeconds:D1,paidFrom:T?,paidThrough:T?`；时间成对null或paidThrough>paidFrom；移除当前NON_NULL导致的缺字段，仅改投影不更改原租期 |
| TokenClasses | `input:D0,cachedInput:D0,output:D0,reasoning:D0` |
| BountyEstimate | `mode:SIMULATION,charged:false,persisted:false,currency:SILVER,priceBookVersion:String,rateProvenance:String,estimatedTokens:TokenClasses,worstTokens:TokenClasses,estimatedCompute:Money,worstCompute:Money,platformFee:Money,estimatedAgentPayout:Money,worstAgentPayout:Money,budgetHeadroom:Money,budgetCovered:boolean` |

分页cursor不是授权：解码16字节big-endian postedAt≥0与rowId>0，query仅提供分页点，Mapper仍使用可信scope。保持现行无paddingcanonical校验，不新增cursor加密/签名机制。

### 13.3 权限、事实与错误

- Principal从JWT映射：walletTenantId=jiacn；marketplace/hosting/agentRegistryTenantId均0；client_id/jiacn/sub必须exact、非0/anonymousUser，authentication.name=sub。**不能为统一规范把wallet查询租户改成0**，会跨写入域或读空账本。
- agentSkills与hostingLease继续查OwnerResolver和持久Agent管理关系；不属于“接任务同Owner资格”。角色/Agent切换按前端authGeneration+requestGeneration丢弃旧响应，scope hash非认证凭证。
- InstallationEvidence只有entitlement ACTIVE且安装SUCCEEDED，并且agent/installation/order/productVersion/skillKey/version匹配、installedAt非负，才VERIFIED_INSTALLED/PERSISTED_INSTALL_RESULT/非null verifiedAt。其他均UNCONFIRMED且时间null；缺记录与有未确认记录区分，不按entitlement单独报已安装。
- 源service只依赖SELECT mapper、配置描述符、纯估算器；GET不创建钱包、不补订单、不初始化租约。Mapper查询和作用域仍必须保留；不调用商城prepare包或写服务替代。
- 401 UNAUTHENTICATED；403 PREVIEW_SCOPE_UNAVAILABLE/PREVIEW_DISABLED；400 PREVIEW_BAD_REQUEST（query/body/cursor/数值/估算边界）；404 PREVIEW_RESOURCE_NOT_FOUND（商品/Agent不可见）；503 PREVIEW_DATA_UNAVAILABLE/PREVIEW_PLAN_UNAVAILABLE（缺失/歧义/损坏/读取故障）。Capabilities即使disabled仍200返回false能力，其余disabled403。错误data=null，安全msg；不能把依赖故障当空列表或免费。
- 上述状态枚举来自既有核心状态机。当前service部分仅验证非空status，目标DTO输出前明确白名单，异常数据503，不把未知值转大写或映射ACTIVE。

### 13.4 同批消费者字段逻辑

`economyReadOnlyPreviewApi.js`get/post直接返回中央客户端DTO，删除readEconomyReadOnlyPreviewPayload信封再解包；`useEconomyReadOnlyPreview.js`对roster也不重复解包。`economyReadOnlyPreviewPolicy.js`严格六字段DTO改contractVersion v2及principalScopeSha256；其内部响应绑定变量scopeFingerprint可保留为本地变量，不冒充wire字段。`EconomyReadOnlyPreview.vue`的postedAt/observedAt/verifiedAt/paidFrom/paidThrough显示改接T；产品分页nextOffset→nextCursor、入参offset/limit→cursor/pageSize；流水limit→pageSize；hasMore用于按钮，传回服务端原cursor，不自行拼next offset。旧用例的message、旧字串时间、旧fingerprint必须改同一fixture，不能保留兼容读取。

这9条已明确DTO及调用链设计，尚非应用验证证据；其他业务域未因此自动闭合。

预览金额同批映射：`availableMicro→available,heldMicro→held,amountMicro→amount,priceMicro→price,grossBountyAmountMicro→grossBountyAmount,minimumAcceptedPayoutMicro→minimumAcceptedPayout,estimatedComputeMicro→estimatedCompute,worstComputeMicro→worstCompute,platformFeeMicro→platformFee,estimatedAgentPayoutMicro→estimatedAgentPayout,worstAgentPayoutMicro→worstAgentPayout,budgetHeadroomMicro→budgetHeadroom`。每个目标均Money（正数约束按表）；Vue表单允许本地保留数字文本，但出站构造Money，API入站显式解为原long计算；不改计算器/账本金额，不用Number承载Long。

## 14. 成果提交：3条HTTP与技能结果交汇（2026-10-15续核）

源码入口是chat模块`AgentTaskSelectedOutputFinalizationController`，不是`AgentTaskFormalDeliveryController`。服务为`ChatSelectedOutputFinalizationService`、`AgentSelectedOutputFinalizationServiceImpl`；摘要为agent-api的`SelectedOutputFinalizationDigest`。本节是源代码核对及目标设计，不是线上失败复现或修复验收。

### 14.1 入口、请求与回执

三个入口均JWT可信tenant/client/owner作用域、`private,no-store`、禁止query；只有POST执行业务编排。保留现有feature flag `chat.selected-output-finalization.enabled`，不擅自开启。

| operation | 输入 | 成功 |
| --- | --- | --- |
| POST /agent/tasks/{taskId}/finalizations | Idempotency-Key；Q<FinalizeCommand> | COMPLETED→200 R<Receipt>；PENDING→202 R<Receipt>；FAILED见下表，不返回E0 |
| GET /agent/tasks/{taskId}/finalizations/request | Idempotency-Key；无body | 200 R<Receipt>，查询持久回执，不驱动恢复 |
| GET /agent/tasks/{taskId}/finalizations/{operationId} | 无body | 200 R<Receipt>，查询持久回执，不驱动恢复 |

path ID沿`[A-Za-z0-9][A-Za-z0-9._:-]{0,99}`；幂等键沿`[A-Za-z0-9][A-Za-z0-9._:-]{7,159}`，不能为统一规范改成商城UUID约束。

- FinalizeCommand精确字段：`expectedTaskVersion:D0,expectedAssignmentRevision:D0,conversationId:ID,summary:String,selectedOutputs:Selection[1..99]`。两个版本从number改十进制string，**仍≤9007199254740991**，不借wire改型扩张服务域。summary保留4000 UTF-16单元上限、非blank、无首尾空白/NUL/控制字符；不与discussionBody的200000上限混淆。
- Selection是精确互斥union，不允许混入另一分支字段。文件：`requestId,stepId,outputId:ID,sha256:H,title,purpose:String`。完成消息：`requestId:ID,messageSource:MessageSource,sha256:H,title,purpose:String`。title/purpose均原255 UTF-16单元上限及文本约束。消息来源：`turnId:ID,messageId:D1,snapshotId:ID,finalSha256:H`；messageId≤Long.MAX_VALUE。同一来源重复拒400；保留原选择顺序，不能排序改变摘要。
- Receipt精确字段：`operationId,taskId,conversationId:ID,state:PENDING|COMPLETED|FAILED,stateVersion:D1,stage:PROMOTING|READY_TO_SUBMIT|SUBMITTED|ACCEPTING|TASK_COMPLETED,expectedTaskVersion:D0,expectedAssignmentRevision:D0,selectedOutputs:Selection[],deliveryId:ID?,deliveryState:SUBMITTED|ACCEPTED|CHANGES_REQUESTED|null,taskState:OPEN|PLANNING|ASSIGNED|RUNNING|REVIEWING|BLOCKED|COMPLETED|FAILED|CANCELLED|ARCHIVED,taskVersion:D0,errorCode:String?,retryable:boolean`。nullable字段显式出现。版本域继续服务的safe整数上限；未知持久状态拒500，不任意toUpperCase。
- `COMPLETED ⇔ stage=TASK_COMPLETED`，此时deliveryState=ACCEPTED、taskState=COMPLETED、deliveryId非null、errorCode=null、retryable=false。FAILED必须有已知errorCode；同一stateVersion回执全部业务字段一致；不能只比时间或只比stage。
- 内部持久state仍原lowercase。投影通过枚举显式映射，不能改SQL状态机、原摘要或历史事实。入站`messageSource.finalSha256`验证后恢复内部`finalDigest="sha256:"+hex`，保持`MMD_SELECTED_OUTPUT_FINALIZATION_V1`长度前缀摘要域及所有组成值；controller出站同样经显式投影，不直接暴露内部record。
- `parse`严格Q信封和data精确字段/重复键/尾随tokens；`derivedMaxBody`按data的固定语法、两个带引号版本、重命名后的消息hash和两种Selection最大值重新推导，并保留空白折叠算法。公共规范只规定requestId非空、未设长度上限，不能虚构“requestId规范上限”后预算整个Q；改为流式解析信封并仅对data执行原推导限制，重复键/尾随tokens检查仍覆盖完整信封。不能用旧裸body预算截断整个Q，也不加未经推导的上传大小门禁。

### 14.2 已核实的RESULT_COMMITTED断点及最小修复设计

现行`ChatSelectedOutputFinalizationService.resolve`文件分支四处硬编码OUTPUT_COMMITTED（request、StepView、持久Step、ExecutionLink）。技能执行产生RESULT_COMMITTED；`ChatBountyAssetProjector`和`readConversationOutput`已支持两种事实，但finalization尚未对齐，源码上会拒绝技能文件来源。**不是简单把四处判断改成无条件“任一状态”**：

1. 先在可信scope内取真实execution及skill result，绑定task/conversation/generation/request/step/target/run，不接受客户端自报protocol或phase。统一接口切换不恢复旧执行协议；对既存可读OUTPUT_COMMITTED事实保留原验证，技能RESULT_COMMITTED只走最新技能证明分支。
2. 技能分支要求底层execution protocol=4、execution.state=RESULT_COMMITTED、skill.phase=COMPLETE，request/StepView/持久Step/link全部精确RESULT_COMMITTED，所有原ID、revision、grant、scope、generation一致性条件保持。当前ExecutionView不暴露protocol字段，但`PersonalWorkspaceExecutionServiceImpl.result`已经从持久行验证protocol=4并执行requireSkillConversationAccess；直接经既有`SkillExecutionService.result`取得证明，无需新增协议字段或另一条查询接口，不向浏览器新增可伪造开关。
3. 复用既有manifest canonical校验逻辑（`ChatBountyAssetProjector.verifiedResult/verifyArtifacts`所覆盖的键、摘要、artifact目录关联），抽为chat内部共享验证逻辑，finalization额外要求COMPLETE；不能把projector允许ARCHIVING的投影前置条件等同用户验收完成。所选output必须存在于该manifest且outputId/sha256/byteLength与持久目录一致；data-only结果不能造一个文件提交。
4. 原`store.source`限定当前会话资产生命周期；继续通过`readConversationOutput`读取已提交bytes，保留任务根锁、授权、资料隔离、MIME/hash/长度和真实bytes复核；不再实现一套文件读取、不信任浏览器文件路径。多个选择仍必须同target/grant/grantVersion/generation。
5. 原`AgentTaskExecutionGrantServiceImpl.verifyPromotionAdmission`已验证ACTIVE当前grant、assignment epoch、target和当前需求revision，返回FINALIZE_SELECTED_OUTPUTS admission；**不按GENERATE_IMAGE白名单排除EXECUTE_SKILL**，无需扩大权限或增加费用授权。`resolveSelectedOutputPromotion`和agent prepare原锁/授权路径保留；完成消息分支不借此次改动绕过scope。
6. 原reconcile必须先于再次检查来源：已提交Agent事务优先恢复，不能因文件晋升后不再处于原conversation output态，将成功操作判失败。幂等operationId和原request hash不变；同key不同输入409，不生成第二份交付。

此处仅对齐现有技能结果→成果提交；不新增外单、不取消安装/文件Owner ACL、不更改费用规则。历史事实可读不是保留旧wire双轨。

### 14.3 HTTP失败与恢复一致性

现行submit给FAILED选4xx/5xx，但`response`始终`JsonResult.success`，造成HTTP失败+E0矛盾。目标显式错误表，禁止`code.contains`推断：

| code | HTTP | 持久回执retryable |
| --- | --- | --- |
| FINALIZATION_BAD_REQUEST | 400 | false |
| FINALIZATION_NOT_FOUND / FINALIZATION_SOURCE_UNAVAILABLE | 404 | false |
| FINALIZATION_CONFLICT / FINALIZATION_GRANT_CHANGED / FINALIZATION_DELIVERY_CHANGES_REQUESTED | 409 | false |
| FINALIZATION_IDEMPOTENCY_CONFLICT | 409 | 不改变原key回执 |
| FINALIZATION_STORAGE_UNAVAILABLE | 503 | true |
| FINALIZATION_PERSISTENCE_ERROR / FINALIZATION_INTERNAL_ERROR | 500 | true（仅服务已持久化分类时） |

认证失败401，已认证身份不可用于该操作403；不可见资源维持404。未知异常安全500，不泄漏exception文本。POST失败使用R<null>且code非E0；不能把失败Receipt塞进error.data破坏公共规范。GET成功读到state=FAILED仍为200 E0，这是“查询成功”，不是“任务成功”。在回执写入失败等场景，不伪造retryable或operationId。

`useHallBountyFinalization.js`改为统一客户端解包后校验Receipt，大写状态、D0出站版本和finalSha256全量同步；POST失败后以**原key、原task和原主体**GET回执呈现错误/恢复按钮。只对明确retryable回执以同key同body恢复，GET404/网络不确定均不生成新key。不自动循环提交/扣款；保留authGeneration、operationId、stateVersion和stage防回退检查。浏览器本地意图存储版本同步更新，旧形状明确失效，不保留猜测兼容。

`bountyOutputCatalog.js`目前三处`schemaVersion===4 ? RESULT_COMMITTED : OUTPUT_COMMITTED`必须改为display schemaVersion=5且按`outcomeContractVersion`选事实状态；不能仅将4全局替换5，否则historical投影与skill来源混淆。两处schemaVersion白名单及完成消息source字段同批更新。

最早定向用例：协议4/COMPLETE成功；ARCHIVING或混合四态拒绝；manifest输出遗漏/摘要/长度错拒绝；跨资料域拒绝；已成功晋升后同key重放恢复；同key异body409；POST500非E0后GET持久FAILED；GET404不换key；前端投影5+outcome4可选、非COMPLETE不可选。尚未执行这些应用用例。

## 15. 资料关联消费者补闭合（2026-10-15）

六条资料关联HTTP（Conversation/Task各list/create/detach）实际Web消费已定位：`usePersonalWorkspaceConversationLinks.js`和`usePersonalWorkspaceTaskLinks.js`的本地request→默认createApi('/agent')→api.execute；ChatPanel、TaskMaterialLinks不覆盖默认API。不是无消费者接口。公共字段详设沿原关联合同，此节补之前漏记的真实适配代码。

1. 删除两处unwrap循环及`response.headers`猜测；中央useHttp返回DTO，头由本次`onSuccess(dto,metadata)`取得，局部保存metadata.headers；不读取全局最后一次response。仍保留AbortController、generation/contextKey、可信身份变化取消。
2. `LIST_FIELDS`由items/nextCursor改items/nextCursor/hasMore；validList验证布尔与nextCursor一致。version保持业务原int32范围但按统一版本字段以十进制String传递，relationRevision以正Long十进制String；比较BigInt，etag按原精确`"relationId:relationRevision"`构造，不能用Number舍入或将引号去掉。createdAt为安全整数毫秒。
3. POST仍原fileId/version/role，封Q；DELETE无JSON请求体，保留If-Match与Idempotency-Key。请求/回执的业务ID、角色、范围及原不可变版本不变。幂等键沿此关联服务自己的校验，不能套商城UUID格式限制。
4. conversation原适配强依赖ETag，task已有“头未暴露则只读查询”恢复分支。目标优先使用真实响应metadata；跨源暴露ETag由既有CORS配置对齐。若仍不可读，只读确认原关联，不二次POST/DELETE、不伪造服务端header。
5. task `confirmWithoutExposedEtag`当前只读第一页：不能因目标关联不在第一页判定写入失败。恢复采用同一身份generation下的cursor遍历，直到匹配原relationId及完整回执或nextCursor=null；检测重复cursor/重复relation，身份改变立即放弃。未读全/传输失败为“未确认”，不是“未执行”；严禁换key重试。conversation如加入同样恢复，复用该局部逻辑而非新建全局重试平台。
6. 共享SSE辅助`authenticatedSse.js`的实际两调用者Chat.vue、useHallConversation.js都传GET `/chat/conversation/events?id=...`，hall额外带Last-Event-ID。绑定到原ChatController.conversationEvents，不计为三个新接口；响应body仍事件流，不能整体当R JSON解析。认证代际、清理和游标恢复保留。

最早用例：新DTO+真实局部metadata回执；二页关联确认；无ETag+断流保持未知；身份切换丢弃旧响应；If-Match冲突不重发；SSE断线游标不混入JSON解包。本轮只查源码，应用用例在开发阶段执行，不因“尚未开发所以没有新测试成功”反复阻止设计收口。
