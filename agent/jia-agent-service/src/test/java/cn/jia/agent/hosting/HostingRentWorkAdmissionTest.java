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
        when(mapper.selectLatestLease("Tenant-A", "Client-A", "agt-test")).thenReturn(lease);
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
    @Test
    void installationsWithoutHostingSchemaDoNotQueryOrMutateHistoricalAgents() {
        var mapper = mock(EconomyHostingRentMapper.class);
        new HostingRentWorkAdmission(mapper, false).requireNewWork("Tenant-A", "Client-A", "agt-test");
        verifyNoInteractions(mapper);
    }
    private void verifyNoMoreInteractionsBeyondReads(EconomyHostingRentMapper mapper) {
        verify(mapper, times(5)).selectLatestLease("Tenant-A", "Client-A", "agt-test");
        verifyNoMoreInteractions(mapper);
    }
}
