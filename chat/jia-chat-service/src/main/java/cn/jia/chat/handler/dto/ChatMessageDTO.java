package cn.jia.chat.handler.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ChatMessageDTO {
    private String conversationId;
    private String content;
    private String conversationType;
    private String senderType;
    private String senderName;
    private String model;
    private String conversationScopeType;
    private String conversationScopeKey;
    private String taskId;
    private String targetAgentId;
    private List<String> targetAgentIds;
    private Boolean forceNewConversation;
    /** Stable client idempotency identity. Old clients may omit it. */
    private String requestId;
    /** Monotonic request body revision; defaults to 1. */
    private Long requestRevision;
    /** Untrusted routing hint. Only chat and inspect are accepted by /chat/stream. */
    private String interactionHint;
    /** Advisory client watermark; never grants access. */
    private Map<String, Object> clientSeenVector;
    /** Candidate references re-authorized by the server before INSPECT. */
    private List<Map<String, Object>> inputRefs;
    private Map<String, Object> metadata;
}
