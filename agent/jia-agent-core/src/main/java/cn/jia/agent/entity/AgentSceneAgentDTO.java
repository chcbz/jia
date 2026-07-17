package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentSceneAgentDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String agentId;
    private String personaCode;
    private String status;

    public static AgentSceneAgentDTO copyOf(AgentSceneAgentDTO source) {
        if (source == null) {
            return null;
        }
        AgentSceneAgentDTO copy = new AgentSceneAgentDTO();
        copy.setAgentId(source.getAgentId());
        copy.setPersonaCode(source.getPersonaCode());
        copy.setStatus(source.getStatus());
        return copy;
    }
}
