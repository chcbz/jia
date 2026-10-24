package cn.jia.agent.security;

import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.TrustedTenantContext;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/** Keeps a machine-authenticated scope present through Spring transaction completion. */
@Component
final class AgentRuntimeTrustedScope {
    private final TransactionTemplate transactions;

    @Autowired
    AgentRuntimeTrustedScope(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
    }

    private AgentRuntimeTrustedScope() {
        this.transactions = null;
    }

    static AgentRuntimeTrustedScope lexicalOnly() {
        return new AgentRuntimeTrustedScope();
    }

    <T> T call(BusinessScope scope, Supplier<T> work) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(work, "work");
        BusinessScope current = TrustedTenantContext.currentScope().orElse(null);
        if (current != null && !current.equals(scope)) throw AgentRuntimeAuthenticationService.denied();
        if (transactions != null && TransactionSynchronizationManager.isActualTransactionActive()
                && current == null) {
            // An inner method cannot keep a lexical ThreadLocal alive through an unknown root
            // transaction's commit. The root boundary (HTTP today, M02 V1 later) must own scope.
            throw AgentRuntimeAuthenticationService.denied();
        }
        return TrustedTenantContext.withScope(scope, () -> transactions == null
                ? work.get() : transactions.execute(status -> work.get()));
    }
}
