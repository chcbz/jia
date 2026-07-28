package cn.jia.chat.dao.impl;

import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.entity.AgentTaskThreadEntity;
import cn.jia.chat.mapper.AgentTaskThreadMapper;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.Objects;

@Named
public class AgentTaskThreadDaoImpl implements AgentTaskThreadDao {
    private final AgentTaskThreadMapper mapper;

    @Inject
    public AgentTaskThreadDaoImpl(AgentTaskThreadMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public int insert(String tenantId, String clientId, AgentTaskThreadEntity thread) {
        requireScope(tenantId, clientId);
        if (thread == null) {
            throw new IllegalArgumentException("thread is required");
        }
        requireId(thread.getTaskId(), "taskId");
        requireId(thread.getThreadType(), "threadType");
        requireId(thread.getThreadKey(), "threadKey");
        requireId(thread.getConversationId(), "conversationId");
        requireId(thread.getCreatedByAgentId(), "createdByAgentId");
        requireId(thread.getStatus(), "status");
        thread.setTenantId(tenantId);
        thread.setClientId(clientId);
        thread.init4Creation();
        return mapper.insert(thread);
    }

    @Override
    public AgentTaskThreadEntity findByTaskThread(
            String tenantId, String clientId, String taskId, String threadType, String threadKey) {
        requireScope(tenantId, clientId);
        requireId(taskId, "taskId");
        requireId(threadType, "threadType");
        requireId(threadKey, "threadKey");
        return mapper.findExactByTaskThread(
                tenantId, clientId, taskId, threadType, threadKey);
    }

    @Override
    public AgentTaskThreadEntity findByTaskThreadForUpdate(
            String tenantId, String clientId, String taskId, String threadType, String threadKey) {
        requireScope(tenantId, clientId);
        requireId(taskId, "taskId");
        requireId(threadType, "threadType");
        requireId(threadKey, "threadKey");
        return mapper.findExactByTaskThreadForUpdate(
                tenantId, clientId, taskId, threadType, threadKey);
    }

    @Override
    public AgentTaskThreadEntity findByConversationId(
            String tenantId, String clientId, String conversationId) {
        requireScope(tenantId, clientId);
        requireId(conversationId, "conversationId");
        return mapper.findExactByConversationId(tenantId, clientId, conversationId);
    }

    @Override
    public AgentTaskThreadEntity findAnyByConversationId(String conversationId) {
        requireId(conversationId, "conversationId");
        return mapper.findAnyExactByConversationId(conversationId);
    }

    private void requireScope(String tenantId, String clientId) {
        requireId(tenantId, "tenantId");
        requireId(clientId, "clientId");
    }

    private void requireId(String value, String name) {
        if (StringUtil.isBlank(value) || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
