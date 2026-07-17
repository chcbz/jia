package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentScenePhaseResultDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String reportId;
    private Long stateVersion;
    private String result;
}
