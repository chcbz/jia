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
