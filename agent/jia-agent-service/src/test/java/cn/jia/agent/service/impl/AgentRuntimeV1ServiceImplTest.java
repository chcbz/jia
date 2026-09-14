package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentRuntimeV1InstallationDao;
import cn.jia.agent.entity.AgentCommandAck;
import cn.jia.agent.entity.AgentCommandAckResult;
import cn.jia.agent.entity.AgentRuntimeV1AckRequest;
import cn.jia.agent.entity.AgentRuntimeV1EnrollmentRequest;
import cn.jia.agent.entity.AgentRuntimeV1InstallationEntity;
import cn.jia.agent.entity.AgentRuntimeV1InstallationRequest;
import cn.jia.agent.service.AgentCommandAckService;
import cn.jia.agent.service.AgentIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRuntimeV1ServiceImplTest {
    private static final long NOW = 1_000L;
    private AgentRuntimeV1InstallationDao installations;
    private AgentIdentityService identities;
    private AgentCommandAckService acks;
    private AgentRuntimeV1ServiceImpl service;

    @BeforeEach void setUp() {
        installations = mock(AgentRuntimeV1InstallationDao.class);
        identities = mock(AgentIdentityService.class);
        acks = mock(AgentCommandAckService.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentCommandAckService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(acks);
        service = new AgentRuntimeV1ServiceImpl(installations, identities, provider);
    }

    @Test void webCreationStoresOnlyProvidedDigestAndReturnsRedactedView() {
        when(identities.requireCanonicalAgentIdInScope("tenant-a", "client-a", "owner-a", AGENT))
                .thenReturn(AGENT);
        when(installations.insert(any())).thenAnswer(invocation -> {
            AgentRuntimeV1InstallationEntity entity = invocation.getArgument(0);
            entity.setId(4L);
            return 1;
        });
        String digest = "a".repeat(64);
        var view = service.create("tenant-a", "client-a", "owner-a",
                new AgentRuntimeV1InstallationRequest(AGENT, "1", digest, digest, NOW + 1), NOW);
        assertEquals("PENDING", view.status());
        assertNull(view.lastHeartbeatAt());
        var captor = org.mockito.ArgumentCaptor.forClass(AgentRuntimeV1InstallationEntity.class);
        verify(installations).insert(captor.capture());
        AgentRuntimeV1InstallationEntity persisted = captor.getValue();
        assertArrayEquals(java.util.HexFormat.of().parseHex(digest), persisted.getEnrollmentSecretHash());
        assertNull(persisted.getRuntimeAuthorizationHash());
    }

    @Test void enrollmentIsSingleUseAndNeverReturnsEnrollmentMaterial() {
        AgentRuntimeV1InstallationEntity pending = installation("PENDING").setEnrollmentSecretHash(sha("enroll"));
        when(installations.lock("rti-1")).thenReturn(pending);
        when(installations.activate(eq(pending), any(byte[].class), eq(NOW))).thenReturn(1);
        var result = service.enroll(enrollment("enroll"), NOW);
        assertEquals("ACTIVE", result.installation().status());
        assertTrue(result.runtimeAuthorization().startsWith("rta1_"));
        assertFalse(result.installation().toString().contains("enroll"));
        verify(installations).activate(eq(pending), any(byte[].class), eq(NOW));

        pending.setEnrollmentConsumedAt(NOW);
        assertThrows(AgentServiceImpl.AgentBizException.class, () -> service.enroll(enrollment("enroll"), NOW + 1));
    }

    @Test void revokedOrWrongScopeRuntimeAuthorizationCannotHeartbeatOrAck() {
        when(installations.findActiveByAuthorizationHash(any())).thenReturn(null);
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> service.heartbeat("revoked", runtime("tenant-a", AGENT), NOW));
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> service.acknowledge("revoked", "msg-1", ack("tenant-a", AGENT), NOW));
        verifyNoInteractions(acks);
    }

    @Test void manifestMismatchRequiresRebindAndValidAckIsBoundToInstallationIdentity() {
        AgentRuntimeV1InstallationEntity active = installation("ACTIVE").setRuntimeAuthorizationHash(sha("auth"));
        when(installations.findActiveByAuthorizationHash(any())).thenReturn(active);
        assertEquals("REBINDS_REQUIRED", service.session("auth",
                new cn.jia.agent.entity.AgentRuntimeV1RuntimeRequest("rti-1", "tenant-a", "client-a", AGENT,
                        "wrong", HASH, "ok"), NOW).status());
        when(acks.acknowledge(any(AgentCommandAck.class), eq(NOW)))
                .thenReturn(new AgentCommandAckResult(AgentCommandAckResult.Kind.ADVANCED, "RECEIVED", 2));
        var result = service.acknowledge("auth", "msg-1", ack("tenant-a", AGENT), NOW);
        assertEquals(AgentCommandAckResult.Kind.ADVANCED, result.kind());
        service.acknowledge("auth", "msg-1", ackWithCorrelation("tenant-a", AGENT, "corr-2"), NOW);
        var captor = org.mockito.ArgumentCaptor.forClass(AgentCommandAck.class);
        verify(acks, times(2)).acknowledge(captor.capture(), eq(NOW));
        AgentCommandAck first = captor.getAllValues().getFirst();
        assertEquals("tenant-a", first.tenantId());
        assertEquals(AGENT, first.registeredAgentId());
        // Wire correlation is distinct from the active delivery message. The adapter
        // preserves the former in its derived sender id and passes the latter to A06.
        assertEquals("msg-1", first.correlationId());
        assertNotEquals("msg-1", first.messageId());
        assertNotEquals(first.messageId(), captor.getAllValues().get(1).messageId());

        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> service.acknowledge("auth", "msg-1", ack("tenant-b", AGENT), NOW));
    }

    private static final String AGENT = "agt_0123456789abcdef0123456789abcdef";
    private static final String HASH = "b".repeat(64);
    private static AgentRuntimeV1InstallationEntity installation(String status) {
        AgentRuntimeV1InstallationEntity installation = new AgentRuntimeV1InstallationEntity()
                .setId(1L).setVersion(0L).setInstallationId("rti-1").setCanonicalAgentId(AGENT)
                .setManifestVersion("1").setManifestSha256(HASH).setEnrollmentExpiresAt(10_000L).setStatus(status);
        installation.setTenantId("tenant-a");
        installation.setClientId("client-a");
        return installation;
    }
    private static AgentRuntimeV1EnrollmentRequest enrollment(String secret) {
        return new AgentRuntimeV1EnrollmentRequest("rti-1", "tenant-a", "client-a", AGENT, "1", HASH, secret);
    }
    private static cn.jia.agent.entity.AgentRuntimeV1RuntimeRequest runtime(String tenant, String agent) {
        return new cn.jia.agent.entity.AgentRuntimeV1RuntimeRequest("rti-1", tenant, "client-a", agent, "1", HASH, "ok");
    }
    private static AgentRuntimeV1AckRequest ack(String tenant, String agent) {
        return ackWithCorrelation(tenant, agent, "corr-1");
    }
    private static AgentRuntimeV1AckRequest ackWithCorrelation(String tenant, String agent, String correlationId) {
        return new AgentRuntimeV1AckRequest("msg-1", correlationId, "cmd-1", "task-1", null,
                tenant, "client-a", agent, "ref", "9999", "RECEIVED");
    }
    private static byte[] sha(String value) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
