package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** Exact immutable workspace input requested for a v2 task execution grant. */
@Data
public class AgentTaskGrantInputDTO implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private String fileId;
    private Integer version;
    private String purpose;
}
