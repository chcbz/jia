package cn.jia.core.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrustedTenantContextTest {
    private static final BusinessScope AREA_ONE = scope("1", "owner-1");
    private static final BusinessScope AREA_TWO = scope("2", "owner-2");

    @AfterEach
    void clearContext() {
        TrustedTenantContext.clear();
    }

    @Test
    void missingContextFailsClosedWithoutTenantZeroFallback() {
        assertFalse(TrustedTenantContext.currentScope().isPresent());
        assertThrows(IllegalStateException.class, TrustedTenantContext::requireScope);
        assertThrows(IllegalStateException.class, TrustedTenantContext::requireTenantId);
    }

    @Test
    void nestedScopesRestoreThePreviousScopeAndClearTheOutermostScope() {
        String result = TrustedTenantContext.withScope(AREA_ONE, () -> {
            assertEquals(AREA_ONE, TrustedTenantContext.requireScope());
            return TrustedTenantContext.withScope(AREA_TWO, () -> {
                assertEquals(AREA_TWO, TrustedTenantContext.requireScope());
                return TrustedTenantContext.requireTenantId().value();
            });
        });

        assertEquals("2", result);
        assertFalse(TrustedTenantContext.currentScope().isPresent());
    }

    @Test
    void exceptionRestoresThePreviousScopeAndDoesNotLeak() {
        TrustedTenantContext.runWithScope(AREA_ONE, () -> {
            assertThrows(IllegalStateException.class, () ->
                    TrustedTenantContext.runWithScope(AREA_TWO, () -> {
                        throw new IllegalStateException("boom");
                    }));
            assertEquals(AREA_ONE, TrustedTenantContext.requireScope());
        });

        assertFalse(TrustedTenantContext.currentScope().isPresent());
    }

    @Test
    void childThreadsDoNotInheritAuthenticatedScope() throws Exception {
        AtomicReference<BusinessScope> inherited = new AtomicReference<>();

        TrustedTenantContext.runWithScope(AREA_ONE, () -> {
            Thread child = new Thread(() ->
                    inherited.set(TrustedTenantContext.currentScope().orElse(null)));
            child.start();
            try {
                child.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            assertEquals(AREA_ONE, TrustedTenantContext.requireScope());
        });

        assertEquals(null, inherited.get());
        assertFalse(TrustedTenantContext.currentScope().isPresent());
    }

    private static BusinessScope scope(String tenantId, String owner) {
        return new BusinessScope(TenantId.of(tenantId), DataDomainId.of("cyf"), owner, "web");
    }
}
