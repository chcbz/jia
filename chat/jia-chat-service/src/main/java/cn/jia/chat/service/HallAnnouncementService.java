package cn.jia.chat.service;

import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.core.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HallAnnouncementService {
    private static final String CONVERSATION_TYPE_JUYITING = "juyiting";
    private static final String SYSTEM_JIACN = "juyiting-system";
    private static final String SYSTEM_CLIENT_ID = "juyiting";
    private static final String SYSTEM_TITLE = "Juyiting System Announcements";

    private final ChatConversationDao chatConversationDao;
    private final ChatMessageDao chatMessageDao;

    @Transactional(rollbackFor = Exception.class)
    public void recordAgentStatus(Map<String, ?> payload) {
        String agentId = stringValue(payload.get("agentId"));
        String status = stringValue(payload.get("status"));
        String content = "Agent " + defaultValue(agentId, "unknown") + " status changed to " + defaultValue(status, "unknown");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("eventType", "agent_status");
        metadata.put("agentId", agentId);
        metadata.put("status", status);
        metadata.put("payload", payload);
        saveSystemMessage(content, metadata);
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordTaskEvent(String eventType, Map<String, ?> payload) {
        String taskId = stringValue(payload.get("taskId"));
        String content = "Task " + defaultValue(taskId, "unknown") + " event: " + defaultValue(eventType, "task_event");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("eventType", "task_event");
        metadata.put("taskEventType", eventType);
        metadata.put("taskId", taskId);
        metadata.put("agentId", payload.get("assignedAgentId"));
        metadata.put("payload", payload);
        saveSystemMessage(content, metadata);
    }

    private void saveSystemMessage(String content, Map<String, Object> metadata) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.init4Creation();
        message.setConversationId(String.valueOf(ensureSystemConversation().getId()));
        message.setMessageType("SYSTEM");
        message.setContent(content);
        message.setMetadata(JsonUtil.toJson(metadata));
        message.setJiacn(SYSTEM_JIACN);
        message.setClientId(SYSTEM_CLIENT_ID);
        message.setSyncStatus("PENDING");
        message.setConversationType(CONVERSATION_TYPE_JUYITING);
        message.setSenderType("system");
        message.setSenderName("System");
        chatMessageDao.insert(message);
    }

    private ChatConversationEntity ensureSystemConversation() {
        ChatConversationEntity query = new ChatConversationEntity();
        query.setJiacn(SYSTEM_JIACN);
        query.setConversationType(CONVERSATION_TYPE_JUYITING);
        List<ChatConversationEntity> conversations = chatConversationDao.selectByEntity(query);
        if (conversations != null && !conversations.isEmpty()) {
            return conversations.getFirst();
        }
        ChatConversationEntity conversation = new ChatConversationEntity();
        conversation.init4Creation();
        conversation.setTitle(SYSTEM_TITLE);
        conversation.setJiacn(SYSTEM_JIACN);
        conversation.setClientId(SYSTEM_CLIENT_ID);
        conversation.setStatus(1);
        conversation.setConversationType(CONVERSATION_TYPE_JUYITING);
        chatConversationDao.insert(conversation);
        return conversation;
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
