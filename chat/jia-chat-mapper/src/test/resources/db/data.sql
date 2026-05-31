-- Chat 模块测试数据

-- 插入聊天会话测试数据
INSERT INTO chat_conversation (id, title, jiacn, status, conversation_type, create_time, update_time, client_id, tenant_id) VALUES
(1, '测试会话1', 'test_jiacn_001', 0, 'normal', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001'),
(2, '测试会话2', 'test_jiacn_001', 0, 'normal', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001'),
(3, '历史会话', 'test_jiacn_002', 1, 'normal', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_002'),
(4, '聚义厅聊天', 'test_jiacn_001', 0, 'juyiting', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001');

-- 插入聊天消息测试数据
INSERT INTO chat_message (id, conversation_id, message_type, content, metadata, create_time, update_time, client_id, tenant_id, jiacn, sync_status, conversation_type, sender_type, sender_name) VALUES
(1, 1, 'USER', '你好，请介绍一下你自己', '{"timestamp": 1713000000000, "messageType": "USER", "conversationId": "1"}', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001', 'test_jiacn_001', 'SYNCED', 'normal', 'user', '测试用户'),
(2, 1, 'ASSISTANT', '你好！我是Jia Chat，一个智能对话助手。我可以帮助你完成各种任务，包括回答问题、提供信息、协助编程等。有什么我可以帮助你的吗？', '{"timestamp": 1713000001000, "messageType": "ASSISTANT", "conversationId": "1"}', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001', 'test_jiacn_001', 'SYNCED', 'normal', NULL, NULL),
(3, 1, 'USER', '你能做什么？', '{"timestamp": 1713000002000, "messageType": "USER", "conversationId": "1"}', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001', 'test_jiacn_001', 'SYNCED', 'normal', 'user', '测试用户'),
(4, 1, 'ASSISTANT', '我可以帮助你：
1. 回答各种问题
2. 提供信息和建议
3. 协助编程和代码问题
4. 文本生成和翻译
5. 等等...', '{"timestamp": 1713000003000, "messageType": "ASSISTANT", "conversationId": "1"}', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001', 'test_jiacn_001', 'SYNCED', 'normal', NULL, NULL),
(5, 2, 'USER', '你好', NULL, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001', 'test_jiacn_001', 'SYNCED', 'normal', 'user', '测试用户'),
(6, 2, 'ASSISTANT', '你好，有什么可以帮助你的？', NULL, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001', 'test_jiacn_001', 'SYNCED', 'normal', NULL, NULL),
(7, 3, 'SYSTEM', '你是一个有帮助的AI助手', NULL, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_002', 'test_jiacn_002', 'SYNCED', 'normal', 'system', NULL),
(8, 4, 'SYSTEM', '【系统公告】宋江进入了聚义厅', '{"senderType":"system","eventType":"agent_online"}', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001', 'test_jiacn_001', 'SYNCED', 'juyiting', 'system', NULL),
(9, 4, 'USER', '宋江，你的下一个任务是什么？', '{"senderType":"user","senderName":"测试用户"}', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001', 'test_jiacn_001', 'SYNCED', 'juyiting', 'user', '测试用户'),
(10, 4, 'USER', '我正在执行留守任务，保护山寨安全', '{"senderType":"agent","senderName":"宋江"}', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001', 'test_jiacn_001', 'SYNCED', 'juyiting', 'agent', '宋江');
