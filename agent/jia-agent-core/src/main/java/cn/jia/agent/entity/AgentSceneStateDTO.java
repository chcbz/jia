package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentSceneStateDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String agentId;
    private String personaCode;
    private String behavior;
    private String originRegionId;
    private String targetRegionId;
    private String relatedType;
    private String relatedId;
    private String phase;
    private Long stateVersion;
    private String startedAt;
    private String expectedArrivalAt;
    private String expiresAt;
}
