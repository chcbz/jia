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
    private cn.jia.agent.dao.AgentIdentityRegistryDao registry;
    private cn.jia.agent.security.AgentRuntimeAuthenticationService sessions;

    @BeforeEach void setUp() {
        installations = mock(AgentRuntimeV1InstallationDao.class);
        identities = mock(AgentIdentityService.class);
        acks = mock(AgentCommandAckService.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentCommandAckService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(acks);
        registry = mock(cn.jia.agent.dao.AgentIdentityRegistryDao.class);
        sessions = mock(cn.jia.agent.security.AgentRuntimeAuthenticationService.class);
        service = new AgentRuntimeV1ServiceImpl(installations, identities, registry, provider, sessions);
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
                new AgentRuntimeV1InstallationRequest("rti_0123456789abcdef0123456789abcdef", AGENT, "1", digest, digest, NOW + 1), NOW);
        assertEquals("PENDING", view.status());
        assertEquals("rti_0123456789abcdef0123456789abcdef", view.installationId());
        assertNull(view.lastHeartbeatAt());
        var captor = org.mockito.ArgumentCaptor.forClass(AgentRuntimeV1InstallationEntity.class);
        verify(installations).insert(captor.capture());
        AgentRuntimeV1InstallationEntity persisted = captor.getValue();
        assertArrayEquals(java.util.HexFormat.of().parseHex(digest), persisted.getEnrollmentSecretHash());
        assertNull(persisted.getRuntimeAuthorizationHash());
    }

    private static final String INTERNAL_ID = "rti_0123456789abcdef0123456789abcdef";
    private cn.jia.agent.entity.AgentRuntimeV1InstallationRequest candidate(String manifest, String secret, long expires) {
        return new AgentRuntimeV1InstallationRequest(INTERNAL_ID, AGENT, "1", manifest, secret, expires);
    }
    private void internalOwner() {
        var identity = new cn.jia.agent.entity.AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT).setOwnerJiacn("owner-a");
        identity.setTenantId("0"); identity.setClientId("client-a");
        when(registry.findExactByCanonicalInScope("0", "client-a", "owner-a", AGENT)).thenReturn(identity);
    }
    private AgentRuntimeV1InstallationEntity internalInstallation(String status) {
        var row = installation(status).setInstallationId(INTERNAL_ID).setEnrollmentSecretHash(java.util.HexFormat.of().parseHex("b".repeat(64)))
                .setManifestSha256("a".repeat(64)).setEnrollmentExpiresAt(2000L);
        row.setTenantId("0"); return row;
    }
    @Test void internalEnsureCreatesThenExactlyReplaysWithoutRotatingOrExposingSecrets() {
        internalOwner();
        var row = internalInstallation("PENDING");
        when(installations.findInScope("0", "client-a", INTERNAL_ID)).thenReturn(null, row);
        when(installations.lock(INTERNAL_ID)).thenReturn(row);
        var request = candidate("a".repeat(64), "b".repeat(64), 2000);
        var created = service.ensureInstallation("0", "client-a", "owner-a", request, NOW);
        assertEquals(created, service.ensureInstallation("0", "client-a", "owner-a", request, NOW + 1));
        verify(installations, times(1)).insertCandidateIfAbsent(any());
        var order = inOrder(installations);
        order.verify(installations).findInScope("0", "client-a", INTERNAL_ID);
        order.verify(installations).insertCandidateIfAbsent(any());
        order.verify(installations).lock(INTERNAL_ID);
        verify(installations, never()).insert(any());
        verifyNoInteractions(identities, sessions, acks);
        assertFalse(created.toString().contains("enrollmentSecret"));
        assertFalse(created.toString().contains("b".repeat(64)));
    }
    @Test void internalEnsureActiveReplayAfterEnrollmentExpiryIsNotReenrollment() {
        internalOwner(); var row = internalInstallation("ACTIVE").setEnrollmentConsumedAt(1500L);
        when(installations.findInScope("0", "client-a", INTERNAL_ID)).thenReturn(row);
        when(installations.lock(INTERNAL_ID)).thenReturn(row);
        assertEquals("ACTIVE", service.ensureInstallation("0", "client-a", "owner-a",
                candidate("a".repeat(64), "b".repeat(64), 2000), 3000).status());
        verify(installations, never()).insertCandidateIfAbsent(any()); verify(installations, never()).activate(any(), any(), anyLong());
    }
    @Test void internalEnsureRejectsDigestExpiryIdentityAndRevokedWinnerWithoutOverwrite() {
        internalOwner(); var row = internalInstallation("ACTIVE");
        when(installations.findInScope("0", "client-a", INTERNAL_ID)).thenReturn(row);
        when(installations.lock(INTERNAL_ID)).thenReturn(row);
        for (var request : java.util.List.of(candidate("c".repeat(64), "b".repeat(64), 2000),
                candidate("a".repeat(64), "c".repeat(64), 2000), candidate("a".repeat(64), "b".repeat(64), 2001))) {
            assertThrows(AgentServiceImpl.AgentBizException.class, () -> service.ensureInstallation("0", "client-a", "owner-a", request, NOW));
        }
        row.setStatus("REVOKED");
        assertThrows(AgentServiceImpl.AgentBizException.class, () -> service.ensureInstallation("0", "client-a", "owner-a",
                candidate("a".repeat(64), "b".repeat(64), 2000), NOW));
        verify(installations, never()).insertCandidateIfAbsent(any());
        row.setStatus("ACTIVE"); row.setClientId("other-client");
        assertThrows(AgentServiceImpl.AgentBizException.class, () -> service.ensureInstallation("0", "client-a", "owner-a",
                candidate("a".repeat(64), "b".repeat(64), 2000), NOW));
    }
    @Test void internalEnsureRejectsUnprovenOwnerAndExpiredNewCandidateBeforeInsert() {
        var request = candidate("a".repeat(64), "b".repeat(64), 2000);
        assertThrows(AgentServiceImpl.AgentBizException.class, () -> service.ensureInstallation("0", "client-a", "other-owner", request, NOW));
        verifyNoInteractions(installations); internalOwner();
        assertThrows(AgentServiceImpl.AgentBizException.class, () -> service.ensureInstallation("0", "client-a", "owner-a", request, 3000));
        verify(installations, never()).insertCandidateIfAbsent(any());
    }

    @Test void absentHintUsesUniqueInsertThenExactWinnerReadAndRejectsConcurrentDifferentDigest() {
        internalOwner();
        var winner = internalInstallation("PENDING").setManifestSha256("c".repeat(64));
        when(installations.findInScope("0", "client-a", INTERNAL_ID)).thenReturn(null);
        doNothing().when(installations).insertCandidateIfAbsent(any()); // another candidate won
        when(installations.lock(INTERNAL_ID)).thenReturn(winner);
        assertThrows(AgentServiceImpl.AgentBizException.class, () -> service.ensureInstallation("0", "client-a", "owner-a",
                candidate("a".repeat(64), "b".repeat(64), 2000), NOW));
        var order = inOrder(installations);
        order.verify(installations).findInScope("0", "client-a", INTERNAL_ID);
        order.verify(installations).insertCandidateIfAbsent(any()); order.verify(installations).lock(INTERNAL_ID);
        verify(installations, never()).activate(any(), any(), anyLong());
    }
    @Test void expiredNewCandidateNeverObtainsMissingKeyGapLock() {
        internalOwner();
        assertThrows(AgentServiceImpl.AgentBizException.class, () -> service.ensureInstallation("0", "client-a", "owner-a",
                candidate("a".repeat(64), "b".repeat(64), 2000), 3000));
        verify(installations, never()).lock(anyString()); verify(installations, never()).insertCandidateIfAbsent(any());
    }

    @Test void concurrentAbsentInstallationCandidatesUseActualUniqueInsertAndOneExactWinnerInH2Transactions() throws Exception {
        // Real mapper SQL + Spring transactions; H2 is NOT evidence of MySQL gap-lock semantics.
        internalOwner();
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:installation_race_" + java.util.UUID.randomUUID() + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        jdbc.execute("CREATE TABLE agent_runtime_v1_installation(id BIGINT AUTO_INCREMENT PRIMARY KEY,installation_id VARCHAR(100) UNIQUE,"
                + "canonical_agent_id VARCHAR(100),manifest_version VARCHAR(100),manifest_sha256 VARCHAR(64),enrollment_secret_hash BINARY(32),"
                + "enrollment_expires_at BIGINT,status VARCHAR(32),version BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50))");
        var factory = new org.mybatis.spring.SqlSessionFactoryBean(); factory.setDataSource(source);
        var configuration = new org.apache.ibatis.session.Configuration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(cn.jia.agent.mapper.AgentRuntimeV1InstallationMapper.class); factory.setConfiguration(configuration);
        var mapper = new org.mybatis.spring.SqlSessionTemplate(java.util.Objects.requireNonNull(factory.getObject()))
                .getMapper(cn.jia.agent.mapper.AgentRuntimeV1InstallationMapper.class);
        var bothAbsent = new java.util.concurrent.CountDownLatch(2);
        when(installations.findInScope("0", "client-a", INTERNAL_ID)).thenAnswer(call -> {
            var hint = mapper.selectByInstallationInScope("0", "client-a", INTERNAL_ID);
            if (hint == null) {
                bothAbsent.countDown(); assertTrue(bothAbsent.await(5, java.util.concurrent.TimeUnit.SECONDS));
            }
            return hint;
        });
        doAnswer(call -> { mapper.insertCandidateIfAbsent(call.getArgument(0)); return null; })
                .when(installations).insertCandidateIfAbsent(any());
        when(installations.lock(INTERNAL_ID)).thenAnswer(call -> mapper.selectByInstallationForUpdate(INTERNAL_ID));
        var transactions = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var request = candidate("a".repeat(64), "b".repeat(64), 2000);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<cn.jia.agent.entity.AgentRuntimeV1InstallationView> ensure = () ->
                    transactions.execute(status -> service.ensureInstallation("0", "client-a", "owner-a", request, NOW));
            var first = executor.submit(ensure); var second = executor.submit(ensure);
            assertEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v1_installation", Integer.class));
            verify(installations, times(2)).insertCandidateIfAbsent(any()); verify(installations, times(2)).lock(INTERNAL_ID);
            assertEquals("PENDING", transactions.execute(status -> service.ensureInstallation("0", "client-a", "owner-a", request, 3000)).status());
            assertThrows(AgentServiceImpl.AgentBizException.class, () -> transactions.execute(status -> service.ensureInstallation(
                    "0", "client-a", "owner-a", candidate("c".repeat(64), "b".repeat(64), 2000), NOW)));
            assertEquals("a".repeat(64), jdbc.queryForObject("SELECT manifest_sha256 FROM agent_runtime_v1_installation", String.class));
        } finally { jdbc.execute("DROP ALL OBJECTS"); }
    }

    @Test void rejectsInstallationIdThatCannotBeBoundIntoTheManifest() {
        assertThrows(AgentServiceImpl.AgentBizException.class, () -> service.create("tenant-a", "client-a", "owner-a",
                new AgentRuntimeV1InstallationRequest("rti-not-opaque", AGENT, "1", "a".repeat(64),
                        "b".repeat(64), NOW + 1), NOW));
        verifyNoInteractions(identities, installations);
    }

    @Test void existingDirectLegacyCanonicalIdentityCanUseOnlyTheV1Protocol() {
        String legacyCanonical = "jyt-client-a-linchong";
        when(identities.requireCanonicalAgentIdInScope("tenant-a", "client-a", "owner-a", legacyCanonical))
                .thenReturn(legacyCanonical);
        when(installations.insert(any())).thenAnswer(invocation -> {
            ((AgentRuntimeV1InstallationEntity) invocation.getArgument(0)).setId(5L);
            return 1;
        });

        var view = service.create("tenant-a", "client-a", "owner-a",
                new AgentRuntimeV1InstallationRequest("rti_abcdefabcdefabcdefabcdefabcdefab", legacyCanonical, "1", "a".repeat(64),
                        "b".repeat(64), NOW + 1), NOW);

        assertEquals(legacyCanonical, view.canonicalAgentId());
        verify(identities).requireCanonicalAgentIdInScope(
                "tenant-a", "client-a", "owner-a", legacyCanonical);
    }

    @Test void enrollmentIsSingleUseAndNeverReturnsEnrollmentMaterial() {
        String enrollmentSecret = "runtime-v1-enrollment-secret-must-not-leak";
        AgentRuntimeV1InstallationEntity pending = installation("PENDING").setEnrollmentSecretHash(sha(enrollmentSecret));
        when(installations.lock("rti-1")).thenReturn(pending);
        when(installations.activate(eq(pending), any(byte[].class), eq(NOW))).thenReturn(1);
        var result = service.enroll(enrollment(enrollmentSecret), NOW);
        assertEquals("ACTIVE", result.installation().status());
        assertTrue(result.runtimeAuthorization().startsWith("rta1_"));
        assertFalse(result.installation().toString().contains(enrollmentSecret));
        assertFalse(pending.toString().contains("enrollmentSecretHash"));
        assertFalse(pending.toString().contains("runtimeAuthorizationHash"));
        verify(installations).activate(eq(pending), any(byte[].class), eq(NOW));

        pending.setEnrollmentConsumedAt(NOW);
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> service.enroll(enrollment(enrollmentSecret), NOW + 1));
    }

    @Test void revokedOrWrongScopeRuntimeAuthorizationCannotHeartbeatOrAck() {
        when(installations.findActiveByAuthorizationHash(any())).thenReturn(null);
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> service.heartbeat("revoked", runtime("tenant-a", AGENT), NOW));
        when(sessions.verify(any())).thenThrow(new IllegalArgumentException("rejected"));
        assertThrows(IllegalArgumentException.class,
                () -> service.acknowledge("revoked", "msg-1", ack("tenant-a", AGENT), NOW));
        verifyNoInteractions(acks);
    }

    @Test void manifestMismatchRequiresRebindAndValidAckIsBoundToInstallationIdentity() {
        AgentRuntimeV1InstallationEntity active = installation("ACTIVE").setRuntimeAuthorizationHash(sha("auth"));
        when(installations.findActiveByAuthorizationHash(any())).thenReturn(active);
        when(installations.lock("rti-1")).thenReturn(active);
        assertThrows(AgentServiceImpl.AgentBizException.class, () -> service.session("auth",
                new cn.jia.agent.entity.AgentRuntimeV1SessionRequest("rti-1", "tenant-a", "client-a", AGENT,
                        "wrong", HASH, "host-1", "boot-1"), NOW));
        var proof = new cn.jia.agent.security.AgentRuntimeAuthenticationService.Proof(
                new cn.jia.agent.security.AgentRuntimeAuthentication.Scope("tenant-a", "client-a", "owner-a", AGENT, "boot-1"),
                "rti-1", "host-1", 1, "a".repeat(64), 1, 0);
        when(sessions.verify(any())).thenReturn(proof);
        when(sessions.withFence(eq(proof), eq(false), any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(2)).get());
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

    @Test void wrongStatusOrNonMonotonicD06ReceiptCannotBeClaimedAsCommit() {
        var proof = new cn.jia.agent.security.AgentRuntimeAuthenticationService.Proof(
                new cn.jia.agent.security.AgentRuntimeAuthentication.Scope("tenant-a", "client-a", "owner-a", AGENT, "boot-1"),
                "rti-1", "host-1", 1, "a".repeat(64), 1, 0);
        when(sessions.verify(any())).thenReturn(proof);
        when(sessions.withFence(eq(proof), eq(false), any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(2)).get());
        for (var result : java.util.List.of(
                new AgentCommandAckResult(AgentCommandAckResult.Kind.ADVANCED, "STARTED", 2),
                new AgentCommandAckResult(AgentCommandAckResult.Kind.ADVANCED, "RECEIVED", 1),
                new AgentCommandAckResult(AgentCommandAckResult.Kind.PRIOR, "RECEIVED", 0))) {
            when(acks.acknowledge(any(), eq(NOW))).thenReturn(result);
            assertThrows(AgentServiceImpl.AgentBizException.class, () -> service.acknowledge("auth", "msg-1", ack("tenant-a", AGENT), NOW));
        }
    }
    @Test void managementRequiresExactOwnerWithinSharedTenantBeforeReturningOrRevoking() {
        AgentRuntimeV1InstallationEntity row = installation("ACTIVE");
        row.setTenantId("0");
        when(installations.findInScope("0", "client-a", "rti-1")).thenReturn(row);
        when(installations.lock("rti-1")).thenReturn(row);
        for (String owner : java.util.List.of("owner-b", "OWNER-A", "owner-a ")) {
            assertThrows(AgentServiceImpl.AgentBizException.class,
                    () -> service.status("0", "client-a", owner, "rti-1"));
            assertThrows(AgentServiceImpl.AgentBizException.class,
                    () -> service.revoke("0", "client-a", owner, "rti-1", NOW));
        }
        verify(installations, never()).revoke(any(), anyLong());
        verifyNoInteractions(identities, acks);
    }

    @Test void ownerCanInspectPendingAndRevokeWithoutReactivatingIdentity() {
        AgentRuntimeV1InstallationEntity row = installation("PENDING");
        row.setTenantId("0");
        var identity = new cn.jia.agent.entity.AgentIdentityRegistryEntity()
                .setCanonicalAgentId(AGENT).setOwnerJiacn("owner-a").setLifecycleStatus("PROVISIONED");
        identity.setTenantId("0"); identity.setClientId("client-a");
        when(registry.findExactByCanonicalInScope("0", "client-a", "owner-a", AGENT)).thenReturn(identity);
        when(installations.findInScope("0", "client-a", "rti-1")).thenReturn(row);
        when(installations.lock("rti-1")).thenReturn(row);
        when(installations.revoke(row, NOW)).thenReturn(1);
        assertEquals("PENDING", service.status("0", "client-a", "owner-a", "rti-1").status());
        identity.setLifecycleStatus("SUSPENDED");
        service.revoke("0", "client-a", "owner-a", "rti-1", NOW);
        verify(installations).revoke(row, NOW);
        verifyNoInteractions(identities, acks);
    }

    @Test void corruptScopedIdentityCannotAuthorizeManagement() {
        AgentRuntimeV1InstallationEntity row = installation("REVOKED");
        row.setTenantId("0");
        var foreign = new cn.jia.agent.entity.AgentIdentityRegistryEntity()
                .setCanonicalAgentId(AGENT).setOwnerJiacn("owner-b");
        foreign.setTenantId("0"); foreign.setClientId("client-a");
        when(registry.findExactByCanonicalInScope("0", "client-a", "owner-a", AGENT)).thenReturn(foreign);
        when(installations.findInScope("0", "client-a", "rti-1")).thenReturn(row);
        when(installations.lock("rti-1")).thenReturn(row);
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> service.status("0", "client-a", "owner-a", "rti-1"));
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> service.revoke("0", "client-a", "owner-a", "rti-1", NOW));
        verify(installations, never()).revoke(any(), anyLong());
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
                tenant, "client-a", agent, "ref", "9999", "RECEIVED",
                "rti-1", "host-1", "boot-1", 1, 1L);
    }
    private static byte[] sha(String value) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
