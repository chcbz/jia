# 聚义厅消息页（2026-11-05 候选，未上线）

遵循 docs/interface-standard.md。仅增加读取接口，不变更执行/完成事实。

`GET /chat/conversation/messages?id=<conversationId>&cursor=<createTime>:<messageId>`

- cursor可省略，首次取最新50条；否则严格取该排序键以前50条。按(create_time,id)倒序LIMIT 51查库，以额外一条判断hasMore，响应items正序，nextCursor为本页最早消息键，仅hasMore时非null。不COUNT、无OFFSET、不自动翻完，不以页大小拒绝长会话。
- data: `{items: [...既有ConversationMessageResponse], hasMore: boolean, nextCursor: string|null}`。消息ID保持十进制字符串，附件parts仅批量查询本页消息；沿用现有live parent及owner/client/tenant隔离和任务线程禁入，不重新逐行鉴权。
- cursor为无授权含义的排序位置，格式为两个非负/正规范十进制long(createTime>=0,id>0)；schema允许的无时间戳记录以n:id标识，按SQL NULL最后规则读取，不伪造创建时间，无效参数400；不允许注入SQL。游标不代替身份，始终在已授权会话内查询。新消息到达不改变旧页边界；同时间戳以id断开。
- 保留旧content接口供未迁移调用方，本次聚义厅改用messages；不以此声称整个系统无全量读取。
- 展示加载旧页不得触发最终回复回调、SSE重启、当前轮恢复或消息发送；切换身份/会话后旧响应丢弃；失败保留已显示消息且可重试。页大小是展示批次，不是业务容量上限。
- 验证入口：ChatConversationMessagePageTest、ChatMessageScopedQueryTest、ChatBountyAssetProjector相关回归及Web hall message page测试。正式运行结果见根handoff，本文不是通过证据。

- 查询索引候选：chat/jia-chat-mapper/src/main/resources/db/chat-message-history-page-index-20261105.sql。2026-11-05只读EXPLAIN确认现有单列conversation_id索引仍filesort，需受控迁移后复验；不能仅凭LIMIT宣称数据库查询成本已经恒定。尚未改生产DDL。

## 请求目录内部读取（2026-11-05 追加候选，未上线）

`GET /chat/conversations/{conversationId}/requests`的wire、分页顺序及入口授权不变。
本页request/turn/step+execution-link投影改为3次集合查询（空页0次），不含原有会话/任务权限查询、watermark及目录页查询。仅传入已经选中的页行id，探针行不做投影；子行在SQL中绑定同一owner/client/tenant/conversation/generation/request/revision。可变执行进度实时读取，无历史缓存；不调用逐条`ChatDeliberationService.getRequest`，也不读取snapshot/final或运行成果提交校验。

原有缺失执行关联、PLANNING缺步骤的读失败语义保留。写入授权、完成事实、摘要校验不变。回归入口：`ChatBountyRequestIndexStoreTest`（实际H2 SQL、页大小1/100固定查询数、页外/身份大小写/代际/版本排除、进度变化、空页）与`ChatBountyRequestIndexPageTest`（映射、探针、权限、必要失败）。Java测试仍待Flow，不以新增测试源码代表通过。

此改动仅消除目录页内部N+1；前端自动翻完整目录、逐条typed-outcome和发送依赖全历史尚未收口。不得据此宣称长会话整体开销已经有界。

## 事件定向读取（2026-11-05 候选，未上线）

现有目录GET增加可选`requestId`，与`after/through/pageSize`互斥，可带`expectedGeneration`。只查已授权会话/代际中的这一请求，仍返回原Page：`after="0"`、`through=该项ordinal`、一个entry、`hasMore=false`、`nextAfter=null`。没有匹配项走既有NOT_FOUND_OR_FORBIDDEN，不泄露其他会话记录。不查询全目录watermark，不展开其他回合。互斥参数避免把单项查询误当分页；入口重复参数按现有公共协议拒绝。页面合并单项不得删除其他已加载项。

- API与Web共享报文：`chat/jia-chat-service/src/test/resources/contracts/hall-request-index-entry.json`、Web `tests/fixtures/hall-request-index-entry.json`，SHA256 `0acc016394c3e88d3859522db84038b5d5f9fdee0483bdd4e7c95b2d3e7a4fe9`。真实公共HTTP信封测试入口`ChatBountyRequestIndexHttpTest`；Java未运行。
- Web实时事件按requestId及已知父/子成果关系读取目录与typed-outcome；不是仅凭READY停止更新。删除目录深监听导致的全成果重查，同轮并发读取复用，身份/会话切换中止旧读。重放期间合并更新，在stream_ready统一恢复；断线前已推进游标但未恢复的提示跨重连保留。如果重连核对已执行完整恢复，不在ready重复读取。失败保留可重试状态，不用新轮询兜底。

### 改稿操作状态事件

`delivery_review_changed`复用既有`conversation.event`信封与持久事件日志。API是生产者，Web是消费者，Runtime不消费该浏览器投影事件。

改稿操作首次结算COMMITTED/REJECTED时，与操作结算和outbox确认同事务写事件，提交后由既有relay发布。重放已结算操作不重复写事件。除既有request/turn/conversation绑定字段，业务data含`parentOutcomeId`、`operationId`、`operationState`、字符串`operationStateVersion`、`childRequestId`（拒绝时null）。事件ID由operationId和状态版本稳定生成。

这是原成果状态发生变化的通知，**不是最终回复、任务完成或turn状态转换**，不写消息/覆盖turn stateVersion。Web读取原回合与新子回合的权威投影，不能依据事件直接宣布完成。已删除/不可访问任务的原拒绝处理仍不发布可读事件，不新增极端场景恢复机制。回归入口`ChatDeliveryReviewChangedEventTest`、`ChatDeliveryRevisionServiceTest`及Web重连/定向读取测试。

本阶段仍保留首次与显式resync全目录/全成果恢复。发送父成果仍依赖完整上下文，不能提前截断，也不能把本阶段当作全部历史读取有界。

## 当前轮恢复（2026-11-05 追加候选，未上线）

同一目录GET支持`latest=true`，与`requestId/after/through/pageSize`互斥，可携带`expectedGeneration`。该selector用于恢复当前回合，不用于发送时选择父成果；不以“最新”代替成果因果链。仍返回Page：after为0、through为最大ordinal、至多一个entry，无后续页；空会话through为0、entries为空。其他latest值走INVALID_REQUEST，避免歧义查询。授权及精确代际边界不变。

存储在精确scope内`ORDER BY id DESC LIMIT 1`，仅对这一轮执行既有集合投影查询，不先取全目录/全部成果。Web恢复取消逐页循环及历史request ID集合；旧状态重放只推进已认证会话游标，不接管当前轮。实时非当前轮状态仍触发该轮投影更新；未知其他会话事件不据此准入。切换会话/身份仍丢弃旧读，取消动作仍绑定恢复轮的精确turn/checkpoint。

回归入口：`ChatBountyRequestIndexStoreTest.latestSelectsOneOrdinalAfterExactScopeFiltering`、`ChatBountyRequestIndexPageTest`最新/空会话/互斥参数用例、`ChatBountyRequestIndexHttpTest.latestSelectorPreservesTheSharedPageEnvelope`、Web `juyiting-restored-request.test.js`（最大ordinal10001只发一次查询、历史重放与实时其他回合、空会话和切换竞态）。HTTP复用相同单项Page完整fixture；不新增公共信封。Java测试待Flow。

2026-11-05真实MySQL只读EXPLAIN：现有`idx_chat_request_context_order`已含scope+id，但11行样本优化器选择`idx_chat_request_conversation`并Using filesort。单次SQL约1ms只说明该样本，不是接口/长会话性能；未添加强制索引或生产DDL。上线前长会话查询计划仍需验证，不能将LIMIT1等同数据库常量工作量。


## 首次恢复与SSE接续（2026-11-05 追加候选，未上线）

确认另一个全量入口：`conversation/events`缺省after=0，旧事件重放可能重新追加页外旧消息。消息分页不能单独解决这个放大。

- latest=true的Page增加`eventCursor`（非负十进制long字符串）。与最新request及其投影在既有REPEATABLE_READ只读事务中读取已提交事件的MAX(event_sequence)，绑定同一owner/client/tenant/conversation/generation。普通分页和requestId单项读的该字段为null，不额外读watermark。
- 前端事项会话恢复按“最新回合及cursor → 最新消息页 → 从cursor建立SSE”执行。消息页可能比回合快照更新，依靠既有消息ID去重；快照后至连接建立之间的提交仍由原事件重放补齐，不使用连接时最新watermark跳过这一窗口。不新增连接/轮询/缓存或消息副本。
- cursor只初始化尚未建连且没有cursor的恢复会话。显示刷新不覆盖已运行/重连中的cursor；加载旧消息页不接触它。身份/会话生命周期继续清理，其他会话事件不获准。首次latest响应缺少/格式错误的cursor使该次恢复失败可重试，不能静默回到全历史重放。
- `eventCursor`是只读接续位置，不是身份或写入授权。业务完成/幂等/成果校验保持原责任层，不新增逐帧检查。它沿用现有事件序列提交/重放语义，不在本次重建事件协议。
- 此补充只覆盖已有latest=true事项会话恢复入口；bootstrap/typed receipt直接接管、普通非事项会话等旁路尚未据此声明已优化。发送上下文/成果列表初始全量依赖仍未收口。

共享fixture：单项`hall-request-index-entry.json`新增eventCursor:null，SHA256 `0acc016394c3e88d3859522db84038b5d5f9fdee0483bdd4e7c95b2d3e7a4fe9`；最新快照`hall-request-latest.json`使用大于JS安全整数的字符串cursor，SHA256 `f65865e99b408f1e0fe1efe1e566da1fe26f369687093c4131e85b37b1bafb91`。两端完整报文相同，前端既有解码器接受新增字段；其他分页核心字段不变。

回归：SQL cursor精确scope/空会话、服务latest才查询cursor、HTTP shared fixture、ChatConversationEventsHttpTest.restoredSnapshotCursorSkipsOldEventsButReplaysTheConnectionGap；Web恢复50条消息后使用精确大整数Last-Event-ID、收到窗口内新delta而非旧历史。Java编译/并发事务验证待Flow，不能以测试源码或mock证明上线。2026-11-05真实库只读查询使用idx_chat_conversation_event_replay覆盖索引，13行样本单次约0.674ms，无事件正文读取；仅SQL样本，不是端到端/长历史性能结论。

## 2026-11-05 候选：发送上下文的关系索引（尚未切换读取）

当前发送仍依赖前端加载完整目录和成果图，本节只是解除该依赖的持久化基础，**不代表首次成果加载已经有界**。

在现有 `chat_typed_outcome` 增加两个可空字段，不能作为新的执行授权：

- `delivery_target_agent_id`：已验证 `CHAT` final 的 `targetAgentId`；仅可交付 `ANSWER`、`ACTION_REQUEST`、`SUBMISSION_REQUEST` 填写。
- `delivery_parent_outcome_id`：上述 final 已验证 `deliveryRelation.parentOutcomeId`；独立根为空。不能用 admission 的 parent 替代，该 parent 也可能表示澄清或其他上下文关系。
- 两个复合索引以现有精确 scope、task、assignment revision、agent 为前缀，分别支持成果消息顺序与父子关系查询；不对 MEDIUMTEXT JSON 扫描/排序。查询执行计划与长历史耗时尚未验证，不能由新增索引推出恒定成本。

责任及成本：`ChatActionFinalService.persist` 使用已经验证的 final，在原事务的同一个 INSERT 写关系字段；无额外 SELECT/UPDATE、后台扫描、缓存表或 Runtime 字段。重复 final 仍直接返回原记录，不重写关系；canonical `outcome_json`、binding、facts 与 final digest 不变。普通回答、澄清及 INSPECT 不被标记为可交付成果。原历史写入构造器保留空值，不把旧合同猜测升级为当前成果。

`ACTION_REQUEST` 的索引项仅为候选，**绝不证明执行完成**。后续发送上下文查询须使用真实 child/step/execution 的提交状态；失败子动作不得遮蔽可用父成果，进行中动作不得冒充成功。多独立根/分支仍须明确选择，不按最大 message ID 选父成果。明确选中父成果的准入沿用既有权威权限、状态、保留输出与幂等处理，不重算已接纳请求的正文摘要。

迁移边界：`db/chat-outcome-delivery-index-20261105.sql` 仅为加字段/索引候选，与新建库 DDL 和 schema-maintenance 描述同步，无 CHECK。未在生产执行。旧库须在获授权的备份/受控迁移中先加列，不能直接安装会写这些列的代码；该脚本**没有完成历史回填**。切换新读路径前必须完成各历史合同的关系提取与覆盖核对，不得将未回填 null 行当成没有成果，也不得在每次请求中全历史 JSON 扫描作为兼容兜底。已添加下节只读端点及单页消费模块；尚未切换发送主入口或删除前端现有全量路径。

定向回归入口（已添加源码、尚未运行Java）：
- `ChatTypedOutcomeDeliveryIndexTest`：实际JDBC插入/三种现有读取入口、原正文/摘要、空历史关系、大小写owner/代际隔离；插入不增元数据查询。
- `ChatOutcomeDeliveryIndexTest`：当前已验证文字/提交关系、独立根、非交付回答、INSPECT、admission parent 不混用、重复 final 不改写。
- `ChatActionOutcomeV5Test`：真实native v6 facts/v5 final写入路径的候选关系、动作仍QUEUED；澄清不产生关系索引。


## 2026-11-05 候选：当前成果选择页

`GET /chat/conversations/{conversationId}/delivery-context`。响应沿用公共信封；完整两端fixture为 `hall-delivery-context.json`。只读、no-store，不授予执行/修改/提交权限。

查询参数：
- `expectedGeneration`、`expectedAssignmentRevision`：可选当前界面上下文；不匹配返回409，不将换指派误判为空列表。
- `beforeMessageId`、`beforeOutcomeId`：必须成对提供；翻页必须同时提供上述两个上下文值。按 `(assistant_message_id,outcome_id)` 倒序keyset读取50+1项；不使用offset或COUNT。
- task、好汉由已有会话/任务授权及binding确定，不接受客户端覆盖。每页使用同一REPEATABLE_READ事务，不锁task或成果。

数据字段：`schemaVersion=1`，`scope={conversationId,conversationGeneration,taskId}`，`assignmentRevision`、`targetAgentId`，`pendingExecution`，`items`，`hasMore`，`nextBeforeMessageId`、`nextBeforeOutcomeId`。所有BIGINT为规范十进制字符串；items仅含 `outcomeId/requestId/assistantMessageId/finalSha256/kind/label`，label是已存正文前60字符，无正文、snapshot、facts或sourceCatalog展开。无更多时两个next字段均null。

选择规则由API负责：仅标记的CHAT成果候选；最终turn必须已提交并绑定该消息。ACTION需实际outbox→同key admission→child request→EXECUTE step→execution link的已提交状态，非执行动作/计划文本不能制造可用成果。仅真实可用子成果才排除其父成果；独立根和分支全部保留为选择，不把最新一项当唯一父成果。进度读取无副作用，不重跑final校验器或调用重型逐条详情。

`pendingExecution`只表示尚未完成/无法确认终态的动作存在，用于避免自动选取旧成果；**不是拒绝新议事的条件**。失败或取消动作不成为可用成果，也不遮蔽父成果；SUBMISSION候选延续现有成果选择语义，不冒充任务完成。

单页存储读为当前binding、候选heads、pending EXISTS（另有复用的会话/任务授权读取），不是逐行SQL/HTTP循环。查询头部使用关系反关联，返回及应用对象有界，但索引扫描工作仍可能随历史长度增加；正式MySQL EXPLAIN/长历史成本未验证。两个分页请求之间成果可变化；发送时仍由原准入重新验证显式父成果与幂等，不能把只读页当写授权或永久快照。

实现入口：`ChatBountyRequestIndexController/Service` 复用已有身份与会话/任务授权方法；`ChatDeliveryContextStore`执行关系及提交事实查询；Web `hallDeliveryContext.js`只做服务端结果解析与一次GET，没有全历史fallback或自动翻完所有页。当前模块尚未接入 `useHallTypedDeliberation.submit/refresh`，所以**初始全目录、全部成果读取仍未取消**。前端不再需要重新构造历史因果图是下一步目标，尚不是现行行为。

回归：`ChatDeliveryContextStoreTest`（隔离SQL、独立根/分支、真实执行、失败/取消/缺失link、scope/指派/好汉隔离、keyset）；`ChatDeliveryContextPageTest`（复用授权、上下文冲突、50+1、无历史目录展开）；`ChatBountyRequestIndexHttpTest`（真实信封、完整fixture）；Web `juyiting-delivery-context.test.js`（BIGINT、绑定、分页、单次GET及无扫描fallback）。Java回归源码尚未运行，前端轻量357项通过，不是线上验收。
