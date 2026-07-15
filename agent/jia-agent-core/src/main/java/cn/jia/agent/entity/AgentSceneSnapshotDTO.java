package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentSceneSnapshotDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String sceneId;
    private Long sceneVersion;
    private String generatedAt;
    private List<AgentRuntimeDTO> agents;
    private List<AgentSceneStateDTO> states;
}
