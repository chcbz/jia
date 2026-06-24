package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentCapabilityDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String agentId;
    private String name;
    private String personaCode;
    private String personaName;
    private List<String> abilities;
    private List<String> roles;
    private String status;
    private String currentTaskId;
    private String currentTaskTitle;
    private Integer currentLoad;
    private Double successRate;
    private Integer recentScore;
    private String collaborationHint;
    private Boolean systemAgent;
    private Boolean canOperate;
}
