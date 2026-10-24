package cn.jia.tenantcard;

import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantCardC01Test {
    private static final String TENANT = "1";
    private static final String OTHER_TENANT = "0";
    private static final String CLIENT = "client-a";
    private static final String OWNER = "owner-a";
    private static final DataDomainId DATA_DOMAIN = DataDomainId.of("cyf");

    @Test
    void legacyClientPreservesAuthenticatedTenantOneScope() {
        BusinessScope businessScope = businessScope(TENANT);

        ConversationDataScope scope = new ConversationDataScope(
                businessScope, ConversationDataScope.Mode.LEGACY_CLIENT);

        assertSame(businessScope, scope.businessScope());
        assertEquals(ConversationDataScope.Mode.LEGACY_CLIENT, scope.mode());
        assertEquals(TENANT, scope.tenantId());
        assertEquals(DATA_DOMAIN.value(), scope.dataDomainId());
        assertEquals(OWNER, scope.ownerJiacn());
        assertEquals(CLIENT, scope.callerClientId());
    }

    @Test
    void legacyClientRetainsTenantZeroCompatibility() {
        BusinessScope businessScope = businessScope(OTHER_TENANT);

        ConversationDataScope scope = new ConversationDataScope(
                businessScope, ConversationDataScope.Mode.LEGACY_CLIENT);

        assertSame(businessScope, scope.businessScope());
        assertEquals(ConversationDataScope.Mode.LEGACY_CLIENT, scope.mode());
        assertEquals(OTHER_TENANT, scope.tenantId());
        assertEquals(CLIENT, scope.callerClientId());
    }

    @Test
    void rejectsMissingBusinessScopeOrMode() {
        BusinessScope businessScope = businessScope(TENANT);

        assertThrows(NullPointerException.class,
                () -> new ConversationDataScope(null, ConversationDataScope.Mode.LEGACY_CLIENT));
        assertThrows(NullPointerException.class,
                () -> new ConversationDataScope(businessScope, null));
    }

    @Test
    void domainModeRetainsTenantDomainOwnerAndCallerSemantics() {
        BusinessScope businessScope = businessScope(TENANT);

        ConversationDataScope scope = new ConversationDataScope(
                businessScope, ConversationDataScope.Mode.DOMAIN);

        assertSame(businessScope, scope.businessScope());
        assertEquals(ConversationDataScope.Mode.DOMAIN, scope.mode());
        assertEquals(TENANT, scope.tenantId());
        assertEquals(DATA_DOMAIN.value(), scope.dataDomainId());
        assertEquals(OWNER, scope.ownerJiacn());
        assertEquals(CLIENT, scope.callerClientId());
    }

    private static BusinessScope businessScope(String tenantId) {
        return new BusinessScope(TenantId.of(tenantId), DATA_DOMAIN, OWNER, CLIENT);
    }
}
