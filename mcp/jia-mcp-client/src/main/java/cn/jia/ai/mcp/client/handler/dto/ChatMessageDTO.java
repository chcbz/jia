package cn.jia.ai.mcp.client.handler.dto;

import lombok.Data;

@Data
public class ChatMessageDTO {
    private String conversationId;
    private String content;
}
