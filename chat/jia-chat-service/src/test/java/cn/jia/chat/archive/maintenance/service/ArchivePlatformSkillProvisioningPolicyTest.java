package cn.jia.chat.archive.maintenance.service;

import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.maintenance.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ArchivePlatformSkillProvisioningPolicyTest {
    @Test void persistedGrantAllowsOnlyActiveScopedAppointmentManager() {
        var store=mock(ArchiveMaintenanceStore.class);var policy=new ArchivePlatformSkillProvisioningPolicy(store);
        var scope=new ArchiveActorScope("0","client-a","owner-a");
        when(store.findManagerGrant(scope,"platform-classics",true)).thenReturn(new ArchiveManagerGrantRecord("platform-classics","0","client-a","owner-a","appoint",1,"ACTIVE"));
        assertDoesNotThrow(()->policy.requireAllowed("0","client-a","owner-a","archive-maintainer",true));
        for(var grant:java.util.List.of(new ArchiveManagerGrantRecord("platform-classics","0","client-a","owner-a","publish",1,"ACTIVE"),
                new ArchiveManagerGrantRecord("platform-classics","0","client-a","owner-a","appoint",1,"REVOKED"),
                new ArchiveManagerGrantRecord("platform-classics","0","client-a","other-owner","appoint",1,"ACTIVE"))) {
            when(store.findManagerGrant(scope,"platform-classics",true)).thenReturn(grant);
            assertThrows(IllegalArgumentException.class,()->policy.requireAllowed("0","client-a","owner-a","archive-maintainer",true));
        }
    }
    @Test void noGrantAndUnknownCatalogSkillCannotAcquireProvisioningPermission() {
        var store=mock(ArchiveMaintenanceStore.class);var policy=new ArchivePlatformSkillProvisioningPolicy(store);
        assertThrows(IllegalArgumentException.class,()->policy.requireAllowed("0","client-a","owner-a","archive-maintainer",true));
        assertThrows(IllegalArgumentException.class,()->policy.requireAllowed("0","client-a","owner-a","unknown",true));
        verify(store,times(1)).findManagerGrant(any(),anyString(),eq(true));
    }
}
