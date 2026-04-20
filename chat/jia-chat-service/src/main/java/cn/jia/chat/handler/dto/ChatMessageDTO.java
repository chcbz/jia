package cn.jia.chat.handler.dto;

import lombok.Data;

@Data
public class ChatMessageDTO {
    private String conversationId;
    private String content;
}
