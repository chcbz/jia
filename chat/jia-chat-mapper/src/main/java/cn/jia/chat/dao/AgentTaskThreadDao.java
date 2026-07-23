package cn.jia.chat.dao;

import cn.jia.chat.entity.AgentTaskThreadEntity;

public interface AgentTaskThreadDao {
    int insert(String tenantId, String clientId, AgentTaskThreadEntity thread);

    AgentTaskThreadEntity findByTaskThread(
            String tenantId, String clientId, String taskId, String threadType, String threadKey);

    AgentTaskThreadEntity findByConversationId(
            String tenantId, String clientId, String conversationId);
}
