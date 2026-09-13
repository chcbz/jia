package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** Command-bound target lease access; no caller-supplied lease token is accepted. */
@Data
public class AgentWorkItemReassignmentLeaseRequestDTO implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private String commandId;
    private Long expectedWorkItemVersion;
    private Long leaseDurationMillis;
}
