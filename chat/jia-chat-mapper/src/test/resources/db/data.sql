-- Chat 模块测试数据

-- 插入聊天会话测试数据
INSERT INTO chat_conversation (id, title, jiacn, status, create_time, update_time, client_id, tenant_id) VALUES
(1, '测试会话1', 'test_jiacn_001', 0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001'),
(2, '测试会话2', 'test_jiacn_001', 0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001'),
(3, '历史会话', 'test_jiacn_002', 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_002');

-- 插入聊天消息测试数据
INSERT INTO chat_message (id, conversation_id, message_type, content, metadata, create_time, update_time, client_id, tenant_id, jiacn) VALUES
(1, 1, 'USER', '你好，请介绍一下你自己', '{"timestamp": 1713000000000, "messageType": "USER", "conversationId": "1"}', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001', 'test_jiacn_001'),
(2, 1, 'ASSISTANT', '你好！我是Jia Chat，一个智能对话助手。我可以帮助你完成各种任务，包括回答问题、提供信息、协助编程等。有什么我可以帮助你的吗？', '{"timestamp": 1713000001000, "messageType": "ASSISTANT", "conversationId": "1"}', 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001', 'test_jiacn_001'),
(3, 1, 'USER', '你能做什么？', '{"timestamp": 1713000002000, "messageType": "USER", "conversationId": "1"}', 2, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001', 'test_jiacn_001'),
(4, 1, 'ASSISTANT', '我可以帮助你：\n1. 回答各种问题\n2. 提供信息和建议\n3. 协助编程和代码问题\n4. 文本生成和翻译\n5. 等等...', '{"timestamp": 1713000003000, "messageType": "ASSISTANT", "conversationId": "1"}', 3, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001', 'test_jiacn_001'),
(5, 2, 'USER', '你好', NULL, 0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001', 'test_jiacn_001'),
(6, 2, 'ASSISTANT', '你好，有什么可以帮助你的？', NULL, 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_001', 'test_jiacn_001'),
(7, 3, 'SYSTEM', '你是一个有帮助的AI助手', NULL, 0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'test_client', 'test_tenant_002', 'test_jiacn_002');
