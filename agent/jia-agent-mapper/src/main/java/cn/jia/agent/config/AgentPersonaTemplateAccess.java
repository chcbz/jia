package cn.jia.agent.config;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.beans.factory.annotation.Value;

/** Explicit read-only publication of tenant-zero persona templates, never bindings or runtimes. */
@Named
public final class AgentPersonaTemplateAccess {
    private final String sharedClientId;

    @Inject
    public AgentPersonaTemplateAccess(
            @Value("${jia.agent.persona.shared-template-client-id:}") String sharedClientId) {
        if (sharedClientId == null || (!sharedClientId.isEmpty()
                && !sharedClientId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}"))) {
            throw new IllegalArgumentException("Invalid shared persona template client");
        }
        this.sharedClientId = sharedClientId;
    }

    public String sharedClientId() { return sharedClientId; }

    public boolean isPublishedTemplate(String tenantId, String clientId) {
        return !sharedClientId.isEmpty() && "0".equals(tenantId) && sharedClientId.equals(clientId);
    }
}
