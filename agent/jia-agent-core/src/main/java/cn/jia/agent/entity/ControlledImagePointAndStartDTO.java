package cn.jia.agent.entity;

import java.io.Serial;
import java.io.Serializable;

public final class ControlledImagePointAndStartDTO {
    private ControlledImagePointAndStartDTO() { }
    public record ProviderConsent(String consentId,String expectedVersion) implements Serializable {
        @Serial private static final long serialVersionUID=1L;
    }
    public record Request(Integer schemaVersion,AgentTaskAssignDTO assignment,
            ProviderConsent providerConsent) implements Serializable {
        @Serial private static final long serialVersionUID=1L;
    }
    public record Receipt(int schemaVersion,AgentTaskExecutionGrantDTO grant,
            AgentTaskProviderCostConsentDTO providerConsent) implements Serializable {
        @Serial private static final long serialVersionUID=1L;
    }
}
