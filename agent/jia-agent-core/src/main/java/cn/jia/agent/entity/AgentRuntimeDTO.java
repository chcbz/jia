package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentRuntimeDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String agentId;
    private String name;
    private String avatar;
    private String ownerJiacn;
    private String personaCode;
    private String personaName;
    private String title;
    private String starName;
    private Integer rankNo;
    private String visualConfig;
    private Boolean systemAgent;
    private Boolean bound;
    private Boolean boundToMe;
    private Boolean canBind;
    private Boolean canOperate;
    private List<String> abilities;
    private String status;
    private String endpoint;
    private String currentTaskId;
    private String currentTaskTitle;
    private Long lastSeenAt;
    private String errorMessage;
    private AgentStatsDTO stats;
}
