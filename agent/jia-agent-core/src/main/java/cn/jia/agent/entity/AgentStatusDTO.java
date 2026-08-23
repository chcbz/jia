package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentStatusDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String status;
    private String currentTaskId;
    private String currentTaskTitle;
    private String errorMessage;
    private List<String> abilities;
}
