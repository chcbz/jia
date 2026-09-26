package cn.jia.chat.handler.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ChatMessageDTO {
    private String conversationId;
    private String content;
    private String conversationType;
    /** @deprecated accepted for wire compatibility but ignored by the server. */
    @Deprecated
    private String senderType;
    /** @deprecated accepted for wire compatibility but ignored by the server. */
    @Deprecated
    private String senderName;
    private String model;
    private String conversationScopeType;
    private String conversationScopeKey;
    private String taskId;
    private String targetAgentId;
    private List<String> targetAgentIds;
    private Boolean forceNewConversation;
    private Map<String, Object> metadata;
}
