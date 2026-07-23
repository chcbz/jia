package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskArtifactQueryDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String workItemId;
    private Integer limit;
}
