package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentScenePhaseReportDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String reportId;
    private String agentId;
    private Long stateVersion;
    private String phase;
    private String regionId;
    private String occurredAt;
}
