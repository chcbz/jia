package cn.jia.agent.entity;

import java.io.Serial;
import java.io.Serializable;

public class AgentSceneEventDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long sceneVersion;
    private String eventType;
    private AgentSceneStateDTO state;
    private Long occurredAt;

    public Long getSceneVersion() {
        return sceneVersion;
    }

    public void setSceneVersion(Long sceneVersion) {
        this.sceneVersion = sceneVersion;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public AgentSceneStateDTO getState() {
        return AgentSceneStateDTO.copyOf(state);
    }

    public void setState(AgentSceneStateDTO state) {
        this.state = AgentSceneStateDTO.copyOf(state);
    }

    public Long getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Long occurredAt) {
        this.occurredAt = occurredAt;
    }
}
