package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentSceneEventDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long sceneVersion;
    private String eventType;
    private AgentSceneStateDTO state;
    private String occurredAt;
}
