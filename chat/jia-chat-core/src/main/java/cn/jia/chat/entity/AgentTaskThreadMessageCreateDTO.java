package cn.jia.chat.entity;

import lombok.Data;

import java.util.Map;

@Data
public class AgentTaskThreadMessageCreateDTO {
    private String actorAgentId;
    private String content;
    private String senderName;
    private Map<String, Object> metadata;
}
