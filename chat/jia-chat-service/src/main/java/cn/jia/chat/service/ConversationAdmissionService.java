package cn.jia.chat.service;

import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.UserAdmissionScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.function.Function;

/** Binds every conversation DAO callback to the current authorization transaction. */
@Service
public final class ConversationAdmissionService {
    public static final String MODULE_ID = "conversation";

    private final ObjectProvider<TenantModuleAdmissionGuard> guardProvider;

    public ConversationAdmissionService(
            ObjectProvider<TenantModuleAdmissionGuard> guardProvider) {
        this.guardProvider = Objects.requireNonNull(guardProvider, "guardProvider");
    }

    public <T> T read(
            UserAdmissionScope scope, Function<ConversationDataScope, T> work) {
        return admit(scope, TenantModuleAdmissionGuard.Operation.READ, work);
    }

    public <T> T write(
            UserAdmissionScope scope, Function<ConversationDataScope, T> work) {
        return admit(scope, TenantModuleAdmissionGuard.Operation.WRITE, work);
    }

    private <T> T admit(
            UserAdmissionScope scope,
            TenantModuleAdmissionGuard.Operation operation,
            Function<ConversationDataScope, T> work) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(work, "work");
        TenantModuleAdmissionGuard guard = guardProvider.getIfAvailable();
        if (guard == null) {
            // Compatibility lane for the existing shared tenant-zero deployment.
            // Nonzero areas never degrade to tenant zero when registration is absent.
            if (!"0".equals(scope.businessScope().tenantId().value())
                    || !"cyf".equals(scope.businessScope().dataDomainId().value())) {
                throw denied();
            }
            return work.apply(new ConversationDataScope(
                    scope.businessScope(), ConversationDataScope.Mode.LEGACY_CLIENT));
        }
        return guard.withAdmission(scope, MODULE_ID, operation, mode -> work.apply(
                new ConversationDataScope(scope.businessScope(),
                        mode == TenantModuleAdmissionGuard.Mode.DOMAIN
                                ? ConversationDataScope.Mode.DOMAIN
                                : ConversationDataScope.Mode.LEGACY_CLIENT)));
    }

    private static IllegalStateException denied() {
        return new IllegalStateException("Conversation tenant admission is unavailable");
    }
}
