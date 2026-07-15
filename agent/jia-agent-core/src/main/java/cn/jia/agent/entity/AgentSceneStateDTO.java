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
    private Long startedAt;
    private Long expectedArrivalAt;
    private Long expiresAt;

    public static AgentSceneStateDTO copyOf(AgentSceneStateDTO source) {
        if (source == null) {
            return null;
        }
        AgentSceneStateDTO copy = new AgentSceneStateDTO();
        copy.setAgentId(source.getAgentId());
        copy.setPersonaCode(source.getPersonaCode());
        copy.setBehavior(source.getBehavior());
        copy.setOriginRegionId(source.getOriginRegionId());
        copy.setTargetRegionId(source.getTargetRegionId());
        copy.setRelatedType(source.getRelatedType());
        copy.setRelatedId(source.getRelatedId());
        copy.setPhase(source.getPhase());
        copy.setStateVersion(source.getStateVersion());
        copy.setStartedAt(source.getStartedAt());
        copy.setExpectedArrivalAt(source.getExpectedArrivalAt());
        copy.setExpiresAt(source.getExpiresAt());
        return copy;
    }
}
