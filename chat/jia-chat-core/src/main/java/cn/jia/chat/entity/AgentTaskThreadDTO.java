package cn.jia.chat.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AgentTaskThreadDTO {
    private String taskId;
    private String threadType;
    private String threadKey;
    private String conversationId;
    private String createdByAgentId;
    private String status;
    private Long createdAt;
}
