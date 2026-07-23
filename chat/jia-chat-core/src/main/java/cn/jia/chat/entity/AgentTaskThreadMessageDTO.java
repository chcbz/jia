package cn.jia.chat.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AgentTaskThreadMessageDTO {
    private Long messageId;
    private String conversationId;
    private String messageType;
    private String content;
    private String metadata;
    private String senderType;
    private String senderName;
    private Long createdAt;
}
