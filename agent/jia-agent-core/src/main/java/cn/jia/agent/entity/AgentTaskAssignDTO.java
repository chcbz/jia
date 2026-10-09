package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentTaskAssignDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String agentId;
    private List<String> agentIds;
    private Boolean allowQueue;

    // Explicit v2 point-and-handle request fields. Absence preserves the legacy assign contract.
    private Integer workflowVersion;
    private String businessAction;
    private Long expectedTaskVersion;
    private Long requirementRevision;
    private List<String> requestedOperations;
    private String initialOperation;
    private List<AgentTaskGrantInputDTO> inputRefs;

    // Authority-looking client fields are represented only so the admission service can reject them.
    private String existingCostAuthorizationRef;
    private String costAuthorizationRef;
    private String permittedToolPolicyRef;
    private List<String> tools;
    private Boolean authorized;
    private Boolean paidExecutionAuthorized;
}
