package cn.jia.agent.hosting;

import cn.jia.economy.entity.EconomyHostingLeaseEntity;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostingRentWorkAdmissionTest {
    @Test
    void historicalLocalAndRefundedAgentsAreUnaffectedButExpiredAndPendingManagedAgentsCannotStartWork() {
        var mapper = mock(EconomyHostingRentMapper.class);
        var admission = new HostingRentWorkAdmission(mapper, true);
        assertDoesNotThrow(() -> admission.requireNewWork("Tenant-A", "Client-A", "agt-test"));
        var lease = new EconomyHostingLeaseEntity().setStatus("ACTIVE").setPaidThrough(1L);
        when(mapper.selectLatestLeaseForUpdate("Tenant-A", "Client-A", "agt-test")).thenReturn(lease);
        assertEquals("HOSTING_RENT_RENEWAL_REQUIRED", assertThrows(HostingRentApplicationException.class,
                () -> admission.requireNewWork("Tenant-A", "Client-A", "agt-test")).code());
        lease.setPaidThrough(Long.MAX_VALUE);
        assertDoesNotThrow(() -> admission.requireNewWork("Tenant-A", "Client-A", "agt-test"));
        lease.setStatus("PROVISIONING").setPaidThrough(null);
        assertThrows(HostingRentApplicationException.class,
                () -> admission.requireNewWork("Tenant-A", "Client-A", "agt-test"));
        lease.setStatus("REFUNDED");
        assertDoesNotThrow(() -> admission.requireNewWork("Tenant-A", "Client-A", "agt-test"));
        verifyNoMoreInteractionsBeyondReads(mapper);
    }
    @Test void lockedAndFinishingAreNotNewAssignmentEligibility() {
        var mapper = mock(EconomyHostingRentMapper.class);
        var admission = new HostingRentWorkAdmission(mapper, true);
        for (String status : java.util.List.of("LOCKED", "FINISHING")) {
            when(mapper.selectLatestLeaseForUpdate("tenant", "client", "agent"))
                    .thenReturn(new EconomyHostingLeaseEntity().setStatus(status).setPaidThrough(Long.MAX_VALUE));
            assertThrows(HostingRentApplicationException.class, () -> admission.requireNewWork("tenant", "client", "agent"));
        }
    }

    @Test
    void installationsWithoutHostingSchemaDoNotQueryOrMutateHistoricalAgents() {
        var mapper = mock(EconomyHostingRentMapper.class);
        new HostingRentWorkAdmission(mapper, false).requireNewWork("Tenant-A", "Client-A", "agt-test");
        verifyNoInteractions(mapper);
    }
    @Test void skillMutationRulesAreCentralAndReadOnly() {
        var mapper = mock(EconomyHostingRentMapper.class);
        var admission = new HostingRentWorkAdmission(mapper, true);
        var lease = new EconomyHostingLeaseEntity().setStatus("LOCKED").setPaidThrough(Long.MAX_VALUE);
        when(mapper.selectLatestLeaseForUpdate("tenant", "client", "agent")).thenReturn(lease);
        for (String action : java.util.List.of("INSTALL", "UPGRADE"))
            assertThrows(HostingRentApplicationException.class, () -> admission.requireSkillChange("tenant", "client", "agent", action));
        for (String action : java.util.List.of("ENABLE", "DISABLE", "UNINSTALL"))
            assertDoesNotThrow(() -> admission.requireSkillChange("tenant", "client", "agent", action));
        lease.setStatus("FINISHING");
        for (String action : java.util.List.of("INSTALL", "UPGRADE", "ENABLE", "DISABLE", "UNINSTALL"))
            assertThrows(HostingRentApplicationException.class, () -> admission.requireSkillChange("tenant", "client", "agent", action));
        verify(mapper, times(10)).selectLatestLeaseForUpdate("tenant", "client", "agent");
        verifyNoMoreInteractions(mapper);
    }
    @Test void absentSchemaSkipsSkillRentalQueries() {
        var mapper = mock(EconomyHostingRentMapper.class);
        var admission = new HostingRentWorkAdmission(mapper, false);
        admission.requireSkillChange("tenant", "client", "agent", "INSTALL");
        admission.requireSkillChange("tenant", "client", "agent", "UNINSTALL");
        verifyNoInteractions(mapper);
    }
    private void verifyNoMoreInteractionsBeyondReads(EconomyHostingRentMapper mapper) {
        verify(mapper, times(5)).selectLatestLeaseForUpdate("Tenant-A", "Client-A", "agt-test");
        verifyNoMoreInteractions(mapper);
    }
}
