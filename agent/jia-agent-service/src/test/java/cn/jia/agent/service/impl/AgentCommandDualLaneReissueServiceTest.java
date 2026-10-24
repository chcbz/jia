package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandManualReissueResult;
import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandReconnectScope;
import cn.jia.agent.entity.AgentCommandReissueScanResult;
import cn.jia.agent.service.AgentCommandReissueService;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.ProducerScope;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.WorkerScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandDualLaneReissueServiceTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void manualAndReconnectSelectExactlyOneProtocolLane() {
        AgentCommandRecoveryDao dao = mock(AgentCommandRecoveryDao.class);
        AgentCommandReissueService legacy = mock(AgentCommandReissueService.class);
        AgentCommandReissueService scoped = mock(AgentCommandReissueService.class);
        WorkerScope worker = workerScope();
        AgentCommandDualLaneReissueService service =
                new AgentCommandDualLaneReissueService(dao, legacy, scoped, worker);
        AgentCommandOperationRequest legacyRequest = request("0", 41L);
        AgentCommandOperationRequest scopedRequest = request("1", 42L);
        AgentCommandDeliveryEntity legacyHint = legacyHint(41L);
        AgentCommandDeliveryEntity scopedHint = scopedHint(42L, producerScope());
        AgentCommandManualReissueResult legacyResult = result(41L, "legacy");
        AgentCommandManualReissueResult scopedResult = result(42L, "scoped");
        when(dao.findDeliveryProtocolHint("0", "web", 41L)).thenReturn(legacyHint);
        when(dao.findDeliveryProtocolHint("1", "web", 42L)).thenReturn(scopedHint);
        when(legacy.reissueManually(legacyRequest, NOW)).thenReturn(legacyResult);
        when(scoped.reissueManually(scopedRequest, NOW)).thenReturn(scopedResult);

        assertEquals(legacyResult, service.reissueManually(legacyRequest, NOW));
        assertEquals(scopedResult, service.reissueManually(scopedRequest, NOW));

        verify(legacy).reissueManually(legacyRequest, NOW);
        verify(scoped).reissueManually(scopedRequest, NOW);

        AgentCommandReconnectScope legacyReconnect = reconnect("0");
        AgentCommandReconnectScope scopedReconnect = reconnect("1");
        when(legacy.reissueForReconnect(legacyReconnect, 10, NOW))
                .thenReturn(new AgentCommandReissueScanResult(1, 1, 41));
        when(scoped.reissueForReconnect(scopedReconnect, 10, NOW))
                .thenReturn(new AgentCommandReissueScanResult(1, 1, 42));
        assertEquals(41, service.reissueForReconnect(legacyReconnect, 10, NOW)
                .lastVisitedDeliveryId());
        assertEquals(42, service.reissueForReconnect(scopedReconnect, 10, NOW)
                .lastVisitedDeliveryId());
    }

    @Test
    void mixedOrAdjacentManualProvenanceNeverFallsThroughToAnotherLane() {
        AgentCommandRecoveryDao dao = mock(AgentCommandRecoveryDao.class);
        AgentCommandReissueService legacy = mock(AgentCommandReissueService.class);
        AgentCommandReissueService scoped = mock(AgentCommandReissueService.class);
        AgentCommandDualLaneReissueService service = new AgentCommandDualLaneReissueService(
                dao, legacy, scoped, workerScope());
        AgentCommandOperationRequest mixedRequest = request("0", 51L);
        AgentCommandDeliveryEntity mixed = legacyHint(51L).setDataDomainId("cyf");
        when(dao.findDeliveryProtocolHint("0", "web", 51L)).thenReturn(mixed);
        AgentCommandOperationRequest adjacentRequest = request("2", 52L);
        when(dao.findDeliveryProtocolHint("2", "web", 52L)).thenReturn(
                scopedHint(52L, new ProducerScope(TenantId.of("2"), DataDomainId.of("cyf"),
                        "test-pool", "local-1", 3, "agent-command", 4)));

        assertEquals("MANUAL_REISSUE_PROTOCOL_LANE_REJECTED",
                assertThrows(IllegalStateException.class,
                        () -> service.reissueManually(mixedRequest, NOW)).getMessage());
        assertEquals("MANUAL_REISSUE_PROTOCOL_LANE_REJECTED",
                assertThrows(IllegalStateException.class,
                        () -> service.reissueManually(adjacentRequest, NOW)).getMessage());
        verify(legacy, never()).reissueManually(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(scoped, never()).reissueManually(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        assertThrows(UnsupportedOperationException.class,
                () -> service.reissueDue(10, 0, NOW));
    }

    private AgentCommandOperationRequest request(String tenant, long deliveryId) {
        return new AgentCommandOperationRequest(tenant, "web", deliveryId, "task-1",
                "agent-1", "source-1", "requester", "approver", "operator reason",
                "ticket-1");
    }

    private AgentCommandReconnectScope reconnect(String tenant) {
        return new AgentCommandReconnectScope(tenant, "web", "agent-1", "agent-1",
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
    }

    private AgentCommandDeliveryEntity legacyHint(long id) {
        AgentCommandDeliveryEntity delivery = new AgentCommandDeliveryEntity().setId(id);
        delivery.setTenantId("0");
        delivery.setClientId("web");
        return delivery;
    }

    private AgentCommandDeliveryEntity scopedHint(long id, ProducerScope scope) {
        AgentCommandDeliveryEntity delivery = new AgentCommandDeliveryEntity().setId(id);
        delivery.setTenantId(scope.tenantId().value());
        delivery.setClientId("web");
        AgentCommandProtocolSupport.apply(delivery, scope);
        return delivery;
    }

    private AgentCommandManualReissueResult result(long id, String prefix) {
        return new AgentCommandManualReissueResult(id, prefix + "-command",
                prefix + "-source", prefix + "-message", prefix + "-event", 1, 2);
    }

    private ProducerScope producerScope() {
        return new ProducerScope(TenantId.of("1"), DataDomainId.of("cyf"),
                "test-pool", "local-1", 3, "agent-command", 4);
    }

    private WorkerScope workerScope() {
        return new WorkerScope(TenantId.of("1"), DataDomainId.of("cyf"),
                "test-pool", "local-1", 4, 11, 3, "agent-command", 4);
    }
}
