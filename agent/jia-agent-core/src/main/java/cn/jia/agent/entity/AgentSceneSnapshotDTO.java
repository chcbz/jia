package cn.jia.agent.entity;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

public class AgentSceneSnapshotDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String sceneId;
    private Long sceneVersion;
    private Long generatedAt;
    private List<AgentSceneAgentDTO> agents = List.of();
    private List<AgentSceneStateDTO> states = List.of();

    public String getSceneId() {
        return sceneId;
    }

    public void setSceneId(String sceneId) {
        this.sceneId = sceneId;
    }

    public Long getSceneVersion() {
        return sceneVersion;
    }

    public void setSceneVersion(Long sceneVersion) {
        this.sceneVersion = sceneVersion;
    }

    public Long getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Long generatedAt) {
        this.generatedAt = generatedAt;
    }

    public List<AgentSceneAgentDTO> getAgents() {
        return immutableAgentCopies(agents);
    }

    public void setAgents(List<AgentSceneAgentDTO> agents) {
        this.agents = immutableAgentCopies(agents);
    }

    public List<AgentSceneStateDTO> getStates() {
        return immutableStateCopies(states);
    }

    public void setStates(List<AgentSceneStateDTO> states) {
        this.states = immutableStateCopies(states);
    }

    private static List<AgentSceneAgentDTO> immutableAgentCopies(List<AgentSceneAgentDTO> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream().map(AgentSceneAgentDTO::copyOf).toList();
    }

    private static List<AgentSceneStateDTO> immutableStateCopies(List<AgentSceneStateDTO> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream().map(AgentSceneStateDTO::copyOf).toList();
    }
}
