package cn.jia.chat.service;

import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.UserAdmissionScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;

/** Binds every conversation DAO callback to the current authorization transaction. */
@Service
public final class ConversationAdmissionService {
    public static final String MODULE_ID = "conversation";

    private final ObjectProvider<TenantModuleAdmissionGuard> guardProvider;
    private final ObjectProvider<ConversationAccountAdmissionGuard> accountGuardProvider;

    public ConversationAdmissionService(
            ObjectProvider<TenantModuleAdmissionGuard> guardProvider) {
        this(guardProvider, null);
    }

    @Autowired
    public ConversationAdmissionService(
            ObjectProvider<TenantModuleAdmissionGuard> guardProvider,
            ObjectProvider<ConversationAccountAdmissionGuard> accountGuardProvider) {
        this.guardProvider = Objects.requireNonNull(guardProvider, "guardProvider");
        this.accountGuardProvider = accountGuardProvider;
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
        if (scope.platformVersions().isEmpty()) {
            if (!"cyf".equals(scope.businessScope().dataDomainId().value())) {
                throw denied();
            }
            ConversationAccountAdmissionGuard accountGuard = accountGuardProvider == null
                    ? null : accountGuardProvider.getIfAvailable();
            if (accountGuard == null) throw denied();
            accountGuard.requireCurrent(scope);
            return work.apply(new ConversationDataScope(
                    scope.businessScope(), ConversationDataScope.Mode.LEGACY_CLIENT));
        }
        if (guard == null) throw denied();
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
