package cn.jia.chat.service;

import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.UserAdmissionScope;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationAdmissionServiceTest extends BaseMockTest {
    private static final String DIGEST = "a".repeat(64);
    private static final String CREDENTIAL = "c".repeat(64);

    @Mock
    ObjectProvider<TenantModuleAdmissionGuard> guardProvider;
    @Mock
    TenantModuleAdmissionGuard guard;

    @Test
    void tenantZeroUsesLegacyCompatibilityOnlyWhenGuardIsAbsent() {
        when(guardProvider.getIfAvailable()).thenReturn(null);
        ConversationAdmissionService service = new ConversationAdmissionService(guardProvider);

        ConversationDataScope scope = service.read(area("0", "cyf", "web"), value -> value);

        assertEquals(ConversationDataScope.Mode.LEGACY_CLIENT, scope.mode());
        assertEquals("0", scope.tenantId());
        assertEquals("cyf", scope.dataDomainId());
        assertEquals("owner", scope.ownerJiacn());
        assertEquals("web", scope.callerClientId());
    }

    @Test
    void nonzeroTenantNeverFallsBackToTenantZeroWhenGuardIsAbsent() {
        when(guardProvider.getIfAvailable()).thenReturn(null);
        AtomicBoolean called = new AtomicBoolean();
        ConversationAdmissionService service = new ConversationAdmissionService(guardProvider);

        assertThrows(IllegalStateException.class,
                () -> service.read(area("1", "cyf", "web"), scope -> {
                    called.set(true);
                    return scope;
                }));

        assertFalse(called.get());
    }

    @Test
    void guardSelectsDomainModeAndOperationBeforeWorkRuns() {
        UserAdmissionScope admission = area("1", "cyf", "web");
        when(guardProvider.getIfAvailable()).thenReturn(guard);
        doAnswer(invocation -> applyGuardCallback(invocation.getArgument(3),
                TenantModuleAdmissionGuard.Mode.DOMAIN))
                .when(guard).withAdmission(eq(admission), eq("conversation"),
                        eq(TenantModuleAdmissionGuard.Operation.READ), any());
        doAnswer(invocation -> applyGuardCallback(invocation.getArgument(3),
                TenantModuleAdmissionGuard.Mode.DOMAIN))
                .when(guard).withAdmission(eq(admission), eq("conversation"),
                        eq(TenantModuleAdmissionGuard.Operation.WRITE), any());
        ConversationAdmissionService service = new ConversationAdmissionService(guardProvider);

        ConversationDataScope read = service.read(admission, scope -> scope);
        ConversationDataScope write = service.write(admission, scope -> scope);

        assertEquals(ConversationDataScope.Mode.DOMAIN, read.mode());
        assertEquals(ConversationDataScope.Mode.DOMAIN, write.mode());
        assertEquals(admission.businessScope(), read.businessScope());
        assertEquals(admission.businessScope(), write.businessScope());
    }

    @Test
    void guardRejectionPreventsBusinessCallback() {
        UserAdmissionScope admission = area("1", "cyf", "web");
        when(guardProvider.getIfAvailable()).thenReturn(guard);
        when(guard.withAdmission(eq(admission), eq("conversation"),
                eq(TenantModuleAdmissionGuard.Operation.WRITE), any()))
                .thenThrow(new IllegalStateException("revoked"));
        AtomicBoolean called = new AtomicBoolean();

        assertThrows(IllegalStateException.class,
                () -> new ConversationAdmissionService(guardProvider).write(admission, scope -> {
                    called.set(true);
                    return null;
                }));

        assertFalse(called.get());
    }

    @Test
    void realGuardRejectsMissingAndWrongTransactionModesBeforeBusinessWork() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:conversation_admission;MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", "");
        TenantModuleAdmissionGuard realGuard = new TenantModuleAdmissionGuard(
                new JdbcTemplate(source), "local-1", DIGEST, "test-pool", CREDENTIAL, 1, 1);
        when(guardProvider.getIfAvailable()).thenReturn(realGuard);
        ConversationAdmissionService service = new ConversationAdmissionService(guardProvider);
        UserAdmissionScope admission = area("1", "cyf", "web");
        AtomicBoolean called = new AtomicBoolean();

        assertThrows(IllegalStateException.class,
                () -> service.read(admission, scope -> called.getAndSet(true)));
        assertFalse(called.get());

        TransactionTemplate writeTx = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertThrows(IllegalStateException.class, () -> writeTx.execute(status ->
                service.read(admission, scope -> called.getAndSet(true))));
        assertFalse(called.get());

        TransactionTemplate readTx = new TransactionTemplate(new DataSourceTransactionManager(source));
        readTx.setReadOnly(true);
        assertThrows(IllegalStateException.class, () -> readTx.execute(status ->
                service.write(admission, scope -> called.getAndSet(true))));
        assertFalse(called.get());
        verify(guard, never()).withAdmission(any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private static Object applyGuardCallback(Object callback, TenantModuleAdmissionGuard.Mode mode) {
        return ((Function<TenantModuleAdmissionGuard.Mode, Object>) callback).apply(mode);
    }

    private static UserAdmissionScope area(String tenant, String domain, String client) {
        return new UserAdmissionScope(new BusinessScope(
                TenantId.of(tenant), DataDomainId.of(domain), "owner", client),
                9L, 4L, 2L, 5L, 8L);
    }
}
