package cn.jia.chat.entity;

import cn.jia.core.tenant.BusinessScope;

import java.util.Objects;

/**
 * Exact storage scope selected only after tenant-module admission.
 *
 * <p>LEGACY_CLIENT preserves the historical tenant/client/owner boundary. DOMAIN
 * uses tenant/data-domain/owner as the resource boundary while retaining the caller
 * client for audit and newly-created row provenance.</p>
 */
public record ConversationDataScope(BusinessScope businessScope, Mode mode) {
    public enum Mode { LEGACY_CLIENT, DOMAIN }

    public ConversationDataScope {
        Objects.requireNonNull(businessScope, "businessScope");
        Objects.requireNonNull(mode, "mode");
        if (mode == Mode.LEGACY_CLIENT && !"0".equals(businessScope.tenantId().value())) {
            throw new IllegalArgumentException("Legacy conversation scope is restricted to tenant 0");
        }
    }

    public String tenantId() {
        return businessScope.tenantId().value();
    }

    public String dataDomainId() {
        return businessScope.dataDomainId().value();
    }

    public String ownerJiacn() {
        return businessScope.ownerJiacn();
    }

    public String callerClientId() {
        return businessScope.callerClientId();
    }
}
