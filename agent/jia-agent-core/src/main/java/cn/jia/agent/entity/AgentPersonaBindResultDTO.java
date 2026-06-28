package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentPersonaBindResultDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private AgentRuntimeDTO agent;
    private String mode;
    private String agentId;
    private String profileId;
    private String workdir;
    private String codexHome;
    private String profilesFile;
    private String apiKey;
    private Boolean serverProfileCreated;
    private Boolean serverProfileAlreadyExists;
    private String message;
    private String envExample;
    private String profileExample;
    private List<String> commands;
}
