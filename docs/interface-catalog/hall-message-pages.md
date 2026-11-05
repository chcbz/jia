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

- API与Web共享报文：`chat/jia-chat-service/src/test/resources/contracts/hall-request-index-entry.json`、Web `tests/fixtures/hall-request-index-entry.json`，SHA256 `5ae7686403a866c47799b3d90c2b500f97db806794ff71abb60728b4f321adcf`。真实公共HTTP信封测试入口`ChatBountyRequestIndexHttpTest`；Java未运行。
- Web实时事件按requestId及已知父/子成果关系读取目录与typed-outcome；不是仅凭READY停止更新。删除目录深监听导致的全成果重查，同轮并发读取复用，身份/会话切换中止旧读。重放期间合并更新，在stream_ready统一恢复；断线前已推进游标但未恢复的提示跨重连保留。如果重连核对已执行完整恢复，不在ready重复读取。失败保留可重试状态，不用新轮询兜底。

### 改稿操作状态事件

`delivery_review_changed`复用既有`conversation.event`信封与持久事件日志。API是生产者，Web是消费者，Runtime不消费该浏览器投影事件。

改稿操作首次结算COMMITTED/REJECTED时，与操作结算和outbox确认同事务写事件，提交后由既有relay发布。重放已结算操作不重复写事件。除既有request/turn/conversation绑定字段，业务data含`parentOutcomeId`、`operationId`、`operationState`、字符串`operationStateVersion`、`childRequestId`（拒绝时null）。事件ID由operationId和状态版本稳定生成。

这是原成果状态发生变化的通知，**不是最终回复、任务完成或turn状态转换**，不写消息/覆盖turn stateVersion。Web读取原回合与新子回合的权威投影，不能依据事件直接宣布完成。已删除/不可访问任务的原拒绝处理仍不发布可读事件，不新增极端场景恢复机制。回归入口`ChatDeliveryReviewChangedEventTest`、`ChatDeliveryRevisionServiceTest`及Web重连/定向读取测试。

本阶段仍保留首次与显式resync全目录/全成果恢复。发送父成果仍依赖完整上下文，不能提前截断，也不能把本阶段当作全部历史读取有界。
