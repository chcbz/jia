package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** Strict server-validated issue body; it carries no client authority fields. */
@Data
public class AgentTaskProviderCostConsentIssueDTO implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private Integer schemaVersion;
    private String assignmentIdempotencyKey;
    private AgentTaskAssignDTO assignment;
    private ProviderBinding providerBinding;
    private String acknowledgement;

    public record ProviderBinding(String bindingId, String bindingEpoch) implements Serializable { }
}
