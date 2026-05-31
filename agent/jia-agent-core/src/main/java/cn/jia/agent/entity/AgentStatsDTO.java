package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentStatsDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Integer completedTaskCount = 0;
    private Integer failedTaskCount = 0;
    private Long averageDurationSeconds;
    private Integer power;
    private Integer intelligence;
    private Integer leadership;
}
