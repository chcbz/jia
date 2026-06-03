package cn.jia.chat.handler.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ChatMessageDTO {
    private String conversationId;
    private String content;
    private String conversationType;
    private String senderType;
    private String senderName;
    private String model;
    private Map<String, Object> metadata;
}
