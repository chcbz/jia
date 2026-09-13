package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** Strict E05 operator request. Tenant, client, operator and coordinator come from authentication/path. */
@Data
public class AgentWorkItemReassignmentRequestDTO implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private Long expectedTaskVersion;
    private Long expectedWorkItemVersion;
    private String expectedPreviousAgentId;
    private String targetAgentId;
    private String sourceCommandId;
    private String reason;
}
