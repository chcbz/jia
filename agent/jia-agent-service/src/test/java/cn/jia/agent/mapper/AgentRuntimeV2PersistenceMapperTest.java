package cn.jia.agent.mapper;

import cn.jia.agent.dao.AgentRuntimeV2BindingDao;
import cn.jia.agent.dao.impl.AgentRuntimeV2BindingDaoImpl;
import cn.jia.agent.entity.AgentRuntimeV2AuthorityScope;
import cn.jia.agent.entity.AgentRuntimeV2BindingEntity;
import cn.jia.agent.entity.RuntimeReceiverScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Executes runtime-v2 production mapper SQL against a private H2/MySQL-mode database. */
class AgentRuntimeV2PersistenceMapperTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String BINDING = "00000000-0000-4000-8000-000000000001";
    private static final String DISPATCH = "00000000-0000-4000-8000-000000000002";
    private JdbcTemplate jdbc;
    private AgentRuntimeV2BindingMapper bindingMapper;
    private AgentRuntimeV2BindingDao bindings;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:runtime_v2_r1_" + UUID.randomUUID()
                + ";MODE=MYSQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword("");
        jdbc = new JdbcTemplate(source);
        createTables(jdbc);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentRuntimeV2BindingMapper.class);
        GlobalConfig global = new GlobalConfig();
        global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        global.setBanner(false);
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(global);
        SqlSessionFactory factory = bean.getObject();
        SqlSessionTemplate sessions = new SqlSessionTemplate(factory);
        bindingMapper = sessions.getMapper(AgentRuntimeV2BindingMapper.class);
        bindings = new AgentRuntimeV2BindingDaoImpl(bindingMapper);
    }

    @Test
    void activeV1InstallationProofAndBindingReadsAreByteExact() {
        AgentRuntimeV2AuthorityScope scope = scope("1", "owner-a");
        insertInstallation("install-active", "ACTIVE", "1", "client-a", "agent-a");
        insertInstallation("install-inactive", "REVOKED", "1", "client-a", "agent-a");
        insertInstallation("Install-case", "ACTIVE", "1", "client-a", "agent-a");
        insertInstallation("install-space ", "ACTIVE", "1", "client-a", "agent-a");
        insertInstallation("install-agent-case", "ACTIVE", "1", "client-a", "Agent-a");
        insertInstallation("install-agent-space", "ACTIVE", "1", "client-a", "agent-a ");

        AgentRuntimeV2BindingEntity active = v1Binding("install-active");
        assertEquals(1, insertTrusted(scope, active));
        assertNotNull(active.getRuntimeBindingId());
        assertEquals("RUNTIME_V1_INSTALLATION",
                findExact(scope, active.getRuntimeBindingId()).getCredentialKind());
        assertNull(findExact(scope("1", "Owner-a"), active.getRuntimeBindingId()));
        assertNull(findExact(scope("2", "owner-a"), active.getRuntimeBindingId()));

        assertEquals(0, insertTrusted(scope, v1Binding("install-inactive")));
        assertEquals(0, insertTrusted(scope, v1Binding("install-missing")));
        assertEquals(0, insertTrusted(scope, v1Binding("install-case")));
        assertEquals(0, insertTrusted(scope, v1Binding("install-space")));
        assertEquals(0, insertTrusted(scope, v1Binding("install-agent-case")));
        assertEquals(0, insertTrusted(scope, v1Binding("install-agent-space")));
    }

    @Test
    void bindingLifecycleIsMonotonicAndTerminalFactsCannotBeRewritten() {
        AgentRuntimeV2AuthorityScope scope = scope("1", "owner-a");
        AgentRuntimeV2BindingEntity active = insertApiBinding(scope, "runtime-disconnect",
                null, null, null);
        long disconnectedAt = NOW + 10;
        assertEquals(1, transition(scope, active, "DISCONNECTED",
                disconnectedAt, null, null, disconnectedAt));
        assertEquals(0, transition(scope, active, "DISCONNECTED",
                disconnectedAt, null, null, disconnectedAt));

        AgentRuntimeV2BindingEntity disconnected = lockExact(
                scope, active.getRuntimeBindingId());
        assertEquals("DISCONNECTED", disconnected.getStatus());
        assertEquals(disconnectedAt, disconnected.getDisconnectedAt());
        assertThrows(IllegalArgumentException.class, () -> transition(scope, disconnected,
                "DISCONNECTED", disconnectedAt, null, null, NOW + 20));
        assertThrows(IllegalArgumentException.class, () -> transition(scope, disconnected,
                "REVOKED", disconnectedAt + 1, NOW + 20, "revoked", NOW + 20));

        long revokedAt = NOW + 20;
        assertEquals(1, transition(scope, disconnected, "REVOKED",
                disconnectedAt, revokedAt, "revoked", revokedAt));
        assertEquals(0, transition(scope, disconnected, "REVOKED",
                disconnectedAt, revokedAt, "revoked", revokedAt));
        AgentRuntimeV2BindingEntity revoked = lockExact(scope, active.getRuntimeBindingId());
        assertEquals("REVOKED", revoked.getStatus());
        assertEquals(disconnectedAt, revoked.getDisconnectedAt());
        assertEquals(revokedAt, revoked.getRevokedAt());
        assertEquals("revoked", revoked.getRevokeReason());
        assertThrows(IllegalArgumentException.class, () -> transition(scope, revoked,
                "DISCONNECTED", NOW + 30, null, null, NOW + 30));
        assertThrows(IllegalArgumentException.class, () -> transition(scope, revoked,
                "REVOKED", disconnectedAt, NOW + 30, "rewrite", NOW + 30));

        AgentRuntimeV2BindingEntity activeRevoke = insertApiBinding(scope, "runtime-revoke",
                null, null, null);
        assertEquals(1, transition(scope, activeRevoke, "REVOKED",
                null, NOW + 40, "hard-stop", NOW + 40));
        AgentRuntimeV2BindingEntity directRevoked = findExact(
                scope, activeRevoke.getRuntimeBindingId());
        assertNull(directRevoked.getDisconnectedAt());
        assertEquals("hard-stop", directRevoked.getRevokeReason());
    }

    @Test
    void routingLeaseCasRequiresExpiryBeforeForeignTransfer() {
        AgentRuntimeV2AuthorityScope scope = scope("1", "owner-a");
        AgentRuntimeV2BindingEntity current = insertApiBinding(scope, "runtime-route",
                "replica-a", 4L, NOW + 100);
        assertEquals(1, moveRoutingLease(scope, current,
                "replica-a", 4, NOW + 100, "replica-a", 4, NOW + 200, NOW));
        assertEquals(0, moveRoutingLease(scope, current,
                "replica-a", 4, NOW + 100, "replica-a", 4, NOW + 300, NOW));

        AgentRuntimeV2BindingEntity renewed = lockExact(scope, current.getRuntimeBindingId());
        assertEquals("replica-a", renewed.getSocketOwnerInstanceId());
        assertEquals(4L, renewed.getSocketOwnerEpoch());
        assertEquals(NOW + 200, renewed.getSocketOwnerLeaseUntil());
        assertEquals(1L, renewed.getVersion());
        assertThrows(IllegalArgumentException.class, () -> moveRoutingLease(scope, renewed,
                "replica-a", 4, NOW + 200, "replica-a", 5, NOW + 300, NOW));
        assertThrows(IllegalArgumentException.class, () -> moveRoutingLease(scope, renewed,
                "replica-a", 4, NOW + 200, "replica-b", 4, NOW + 300, NOW));
        assertThrows(IllegalArgumentException.class, () -> moveRoutingLease(scope, renewed,
                "replica-a", 4, NOW + 200, "replica-a", 4, NOW + 150, NOW));
        assertThrows(IllegalArgumentException.class, () -> moveRoutingLease(scope, renewed,
                "replica-a", 4, NOW + 200, "replica-b", 5, NOW + 300, NOW));

        AgentRuntimeV2BindingEntity stillOwned = lockExact(
                scope, current.getRuntimeBindingId());
        assertEquals("replica-a", stillOwned.getSocketOwnerInstanceId());
        assertEquals(4L, stillOwned.getSocketOwnerEpoch());
        assertEquals(NOW + 200, stillOwned.getSocketOwnerLeaseUntil());
        assertEquals(1L, stillOwned.getVersion());

        AgentRuntimeV2BindingEntity expired = insertApiBinding(scope, "runtime-expired-route",
                "replica-old", 8L, NOW - 1);
        assertThrows(IllegalArgumentException.class, () -> moveRoutingLease(scope, expired,
                "replica-old", 8, NOW - 1, "replica-old", 8, NOW + 100, NOW));
        assertEquals(1, moveRoutingLease(scope, expired,
                "replica-old", 8, NOW - 1, "replica-new", 9, NOW + 100, NOW));
        assertEquals(0, moveRoutingLease(scope, expired,
                "replica-old", 8, NOW - 1, "replica-loser", 10, NOW + 200, NOW));
        AgentRuntimeV2BindingEntity transferred = findExact(
                scope, expired.getRuntimeBindingId());
        assertEquals("replica-new", transferred.getSocketOwnerInstanceId());
        assertEquals(9L, transferred.getSocketOwnerEpoch());
        assertEquals(1L, transferred.getVersion());

        AgentRuntimeV2BindingEntity boundary = insertApiBinding(scope, "runtime-boundary-route",
                "replica-boundary", 12L, NOW);
        assertThrows(IllegalArgumentException.class, () -> moveRoutingLease(scope, boundary,
                "replica-boundary", 12, NOW, "replica-boundary", 12, NOW + 100, NOW));
        assertEquals(1, moveRoutingLease(scope, boundary,
                "replica-boundary", 12, NOW, "replica-after-boundary", 13, NOW + 100, NOW));
    }

    @Test
    void routingLeaseMapperCannotBypassExpiryOrExpectedFences() {
        AgentRuntimeV2AuthorityScope scope = scope("1", "owner-a");
        AgentRuntimeV2BindingEntity live = insertApiBinding(scope, "runtime-live-mapper",
                "replica-live", 2L, NOW + 50);
        assertEquals(0, moveRoutingLeaseDirect(scope, live, live.getVersion(),
                "replica-live", 2, NOW + 50, "replica-foreign", 3, NOW + 100, NOW));
        AgentRuntimeV2BindingEntity liveAfterAttack = findExact(
                scope, live.getRuntimeBindingId());
        assertEquals("replica-live", liveAfterAttack.getSocketOwnerInstanceId());
        assertEquals(2L, liveAfterAttack.getSocketOwnerEpoch());
        assertEquals(0L, liveAfterAttack.getVersion());

        AgentRuntimeV2BindingEntity expired = insertApiBinding(scope, "runtime-expired-mapper",
                "replica-old", 8L, NOW - 1);
        assertEquals(0, moveRoutingLeaseDirect(scope, expired, expired.getVersion(),
                "replica-old", 8, NOW - 1, "replica-old", 8, NOW + 100, NOW));
        assertEquals(0, moveRoutingLeaseDirect(scope, expired, expired.getVersion(),
                "Replica-old", 8, NOW - 1, "replica-new", 9, NOW + 100, NOW));
        assertEquals(0, moveRoutingLeaseDirect(scope, expired, expired.getVersion(),
                "replica-old", 7, NOW - 1, "replica-new", 9, NOW + 100, NOW));
        assertEquals(0, moveRoutingLeaseDirect(scope, expired, expired.getVersion(),
                "replica-old", 8, NOW - 2, "replica-new", 9, NOW + 100, NOW));
        assertEquals(0, moveRoutingLeaseDirect(scope, expired, expired.getVersion() + 1,
                "replica-old", 8, NOW - 1, "replica-new", 9, NOW + 100, NOW));
        assertEquals(0, moveRoutingLeaseDirect(scope("1", "Owner-a"), expired,
                expired.getVersion(), "replica-old", 8, NOW - 1,
                "replica-new", 9, NOW + 100, NOW));
        assertEquals(1, moveRoutingLeaseDirect(scope, expired, expired.getVersion(),
                "replica-old", 8, NOW - 1, "replica-new", 9, NOW + 100, NOW));
        assertEquals(0, moveRoutingLeaseDirect(scope, expired, expired.getVersion(),
                "replica-old", 8, NOW - 1, "replica-loser", 10, NOW + 200, NOW));
        AgentRuntimeV2BindingEntity winner = findExact(
                scope, expired.getRuntimeBindingId());
        assertEquals("replica-new", winner.getSocketOwnerInstanceId());
        assertEquals(9L, winner.getSocketOwnerEpoch());
        assertEquals(1L, winner.getVersion());
    }

    @Test
    void version2BindingRoundTripKeepsCurrentReceiptAndDatabaseOwnsProvenanceAndGeneratedColumns() {
        RuntimeReceiverScope scope = receiver(scope("1", "owner-a"));
        String bindingId = "00000000-0000-4000-8000-000000000071";
        String receiptId = "00000000-0000-4000-8000-000000000072";
        byte[] receiptSha = hash(72);
        AgentRuntimeV2BindingEntity entity = new AgentRuntimeV2BindingEntity()
                .setRuntimeBindingId(bindingId).setCredentialKind("API_KEY")
                .setCredentialReference("api-key-roundtrip").setContractVersion(2)
                .setEvidenceProvenance("LEGACY_PRE_R2B1")
                .setLiveRegistrationSubjectSha256("caller-forged-generated")
                .setLiveBusinessSubject("caller-forged-generated")
                .setRegistrationSubjectSha256(hash(1))
                .setRegistrationAuthorityId("00000000-0000-4000-8000-000000000073")
                .setRegistrationAuthorityRevision(12L).setProcessPublicKeySpki(new byte[44])
                .setProcessPublicKeySha256(hash(2)).setVerifierVersion(4L)
                .setVerifierKeyId("verifier-key").setReconnectVerifier(hash(3))
                .setCurrentRegistrationReceiptId(receiptId)
                .setCurrentRegistrationReceiptSha256(receiptSha)
                .setRuntimeInstanceId("runtime-roundtrip").setBindingGeneration(3L)
                .setStatus("ACTIVE").setConnectedAt(NOW).setCurrentSessionFence("fence-roundtrip")
                .setSocketOwnerInstanceId("replica-roundtrip").setSocketOwnerEpoch(1L)
                .setSocketOwnerLeaseUntil(NOW + 60_000).setVersion(0L);
        entity.setCreateTime(NOW); entity.setUpdateTime(NOW);

        assertEquals(1, bindingMapper.insert(scope.tenantId().value(), scope.dataDomainId().value(),
                scope.executionPoolId(), scope.runtimeCredentialClientId(), scope.ownerJiacn(),
                scope.agentId(), scope.claimantDeploymentId(), scope.claimantWorkerGeneration(),
                scope.receiverRegistrationRevision(), scope.admissionEpoch(), scope.moduleId(),
                scope.migrationEpoch(), entity));

        AgentRuntimeV2BindingEntity stored = bindingMapper.findExact(scope.tenantId().value(),
                scope.dataDomainId().value(), scope.executionPoolId(), scope.runtimeCredentialClientId(),
                scope.ownerJiacn(), scope.agentId(), scope.claimantDeploymentId(),
                scope.claimantWorkerGeneration(), scope.receiverRegistrationRevision(),
                scope.admissionEpoch(), scope.moduleId(), scope.migrationEpoch(), bindingId);
        assertNotNull(stored);
        assertEquals("R2B1_ENFORCED", stored.getEvidenceProvenance());
        assertEquals("generated", stored.getLiveRegistrationSubjectSha256());
        assertEquals("generated", stored.getLiveBusinessSubject());
        assertEquals(receiptId, stored.getCurrentRegistrationReceiptId());
        assertArrayEquals(receiptSha, stored.getCurrentRegistrationReceiptSha256());
    }

    /* R2a2 dispatch/receipt/lease tests live in AgentRuntimeV2WirePersistenceTest; the
       accepted R2a1 binding mapper tests above remain byte-exact and executable. */
    private void removedR1CombinedScopeExamples() {
        /*
        AgentRuntimeV2AuthorityScope scope = scope("1", "owner-a");
        assertNotEquals(scope.producerDeploymentId(), scope.claimantDeploymentId());
        AgentRuntimeV2BindingEntity binding = insertApiBinding(scope, "runtime-dispatch",
                "replica-a", 2L, NOW + 1_000);
        AgentRuntimeV2AuthorityScope foreignOwnerScope = scope("1", "owner-b");
        AgentRuntimeV2BindingEntity foreignOwnerBinding = insertApiBinding(
                foreignOwnerScope, "runtime-owner-b", null, null, null);
        AgentRuntimeV2AuthorityScope foreignAgentScope = scope("1", "owner-a", "agent-b");
        AgentRuntimeV2BindingEntity foreignAgentBinding = insertApiBinding(
                foreignAgentScope, "runtime-agent-b", null, null, null);
        AgentRuntimeV2AuthorityScope caseAliasScope = scope("1", "Owner-a");
        AgentRuntimeV2BindingEntity caseAliasBinding = insertApiBinding(
                caseAliasScope, "runtime-owner-case", null, null, null);

        insertInbox(100, scope);
        insertInbox(101, scope);
        insertInbox(102, scope);
        insertInbox(103, scope);
        assertNull(dispatches.prepare(foreignOwnerScope, 101, 0,
                foreignOwnerBinding.getRuntimeBindingId(), 3, "runtime-owner-b", NOW));
        assertNull(dispatches.prepare(foreignAgentScope, 102, 0,
                foreignAgentBinding.getRuntimeBindingId(), 3, "runtime-agent-b", NOW));
        assertNull(dispatches.prepare(caseAliasScope, 103, 0,
                caseAliasBinding.getRuntimeBindingId(), 3, "runtime-owner-case", NOW));

        AgentRuntimeV2BindingEntity trailing = insertApiBinding(scope, "runtime-trailing",
                null, null, null);
        jdbc.update("UPDATE agent_runtime_v2_binding SET owner_jiacn=? WHERE runtime_binding_id=?",
                "owner-a ", trailing.getRuntimeBindingId());
        insertInbox(104, scope);
        assertNull(dispatches.prepare(scope, 104, 0, trailing.getRuntimeBindingId(),
                3, "runtime-trailing", NOW));

        String dispatchId = dispatches.prepare(scope, 100, 0, binding.getRuntimeBindingId(),
                3, "runtime-dispatch", NOW);
        assertNotNull(dispatchId);
        AgentConsumerInboxEntity prepared = dispatches.lock(
                scope, binding.getRuntimeBindingId(), dispatchId);
        assertNotNull(prepared);
        assertNull(dispatches.lock(scope("1", "owner-b"), binding.getRuntimeBindingId(), dispatchId));
        assertNull(dispatches.lock(scope("1", "owner-a", "agent-b"),
                binding.getRuntimeBindingId(), dispatchId));
        assertEquals(0, dispatches.projectLease(scope("1", "Owner-a"), prepared,
                null, 0, NOW + 1, NOW + 100, NOW + 1));
        assertEquals(0, dispatches.projectLease(scope("1", "owner-a", "agent-b"), prepared,
                null, 0, NOW + 1, NOW + 100, NOW + 1));

        jdbc.update("UPDATE agent_runtime_v2_binding SET owner_jiacn=? WHERE runtime_binding_id=?",
                "owner-a ", binding.getRuntimeBindingId());
        assertNull(dispatches.lock(scope, binding.getRuntimeBindingId(), dispatchId));
        assertEquals(0, dispatches.projectLease(scope, prepared,
                null, 0, NOW + 1, NOW + 100, NOW + 1));
        jdbc.update("UPDATE agent_runtime_v2_binding SET owner_jiacn=? WHERE runtime_binding_id=?",
                "owner-a", binding.getRuntimeBindingId());
        jdbc.update("UPDATE agent_command_delivery SET owner_jiacn=? WHERE id=?",
                "owner-a ", 100L);
        assertNull(dispatches.lock(scope, binding.getRuntimeBindingId(), dispatchId));
        assertEquals(0, dispatches.projectLease(scope, prepared,
                null, 0, NOW + 1, NOW + 100, NOW + 1));
        jdbc.update("UPDATE agent_command_delivery SET owner_jiacn=? WHERE id=?",
                "owner-a", 100L);
        jdbc.update("UPDATE agent_command_delivery SET target_agent_id=? WHERE id=?",
                "Agent-a", 100L);
        assertNull(dispatches.lock(scope, binding.getRuntimeBindingId(), dispatchId));
        assertEquals(0, dispatches.projectLease(scope, prepared,
                null, 0, NOW + 1, NOW + 100, NOW + 1));
        jdbc.update("UPDATE agent_command_delivery SET target_agent_id=? WHERE id=?",
                "agent-a", 100L);

        assertEquals(1, dispatches.projectLease(scope, prepared,
                null, 0, NOW + 1, NOW + 100, NOW + 1));
        AgentConsumerInboxEntity leased = dispatches.lock(
                scope, binding.getRuntimeBindingId(), dispatchId);
        assertEquals(0L, leased.getExecutionLeaseRevision());

        AgentRuntimeV2BindingEntity active = lockExact(scope, binding.getRuntimeBindingId());
        assertEquals(1, transition(scope, active, "DISCONNECTED",
                NOW + 2, null, null, NOW + 2));
        assertNotNull(dispatches.lock(scope, binding.getRuntimeBindingId(), dispatchId));
        assertEquals(0, dispatches.projectLease(scope, leased,
                0L, 1, NOW + 2, NOW + 200, NOW + 2));
    }

    void appendOnlyReceiptsFenceAdjacentScopeAndDatabaseRejectsAckAliases() {
        AgentRuntimeV2AuthorityScope scope = scope("1", "owner-a");
        AgentCommandV2AckReceiptEntity started = receipt("ack-a", "STARTED", hash(1));
        assertEquals(1, receipts.insert(scope, started));
        assertNotNull(started.getReceiptId());
        assertEquals("ack-a", receipts.findByAckMessageId(scope, "ack-a").getAckMessageId());
        assertNull(receipts.findByAckMessageId(scope("1", "Owner-a"), "ack-a"));
        assertNull(receipts.findByAckMessageId(scope("2", "owner-a"), "ack-a"));

        AgentCommandV2AckReceiptEntity reusedAck = receipt("ack-a", "SUCCEEDED", hash(2));
        assertThrows(DuplicateKeyException.class, () -> receipts.insert(scope, reusedAck));
        AgentCommandV2AckReceiptEntity differentAckSameStatus = receipt("ack-b", "STARTED", hash(1));
        assertThrows(DuplicateKeyException.class,
                () -> receipts.insert(scope, differentAckSameStatus));
    }

    void immutableLeaseHistoryReturnsLatestOnlyInsideExactAuthorityLane() {
        AgentRuntimeV2AuthorityScope scope = scope("1", "owner-a");
        AgentCommandV2ExecutionLeaseFactEntity grant = fact(0, 0, "START_GRANTED", null);
        assertEquals(1, facts.insert(scope, grant));
        AgentCommandV2ExecutionLeaseFactEntity renew = fact(1, 1, "RENEWED", grant.getLeaseFactId());
        assertEquals(1, facts.insert(scope, renew));

        AgentCommandV2ExecutionLeaseFactEntity latest = facts.lockLatest(scope, BINDING, DISPATCH);
        assertEquals(1L, latest.getFactSequence());
        assertEquals(grant.getLeaseFactId(), latest.getPreviousLeaseFactId());
        assertNull(facts.lockLatest(scope("1", "Owner-a"), BINDING, DISPATCH));

        AgentCommandV2ExecutionLeaseFactEntity duplicateSequence = fact(
                1, 2, "REVOKED", renew.getLeaseFactId());
        assertThrows(DuplicateKeyException.class, () -> facts.insert(scope, duplicateSequence));
        */
    }

    private int insertTrusted(AgentRuntimeV2AuthorityScope s, AgentRuntimeV2BindingEntity e) { return bindings.insertTrusted(receiver(s), e); }
    private AgentRuntimeV2BindingEntity findExact(AgentRuntimeV2AuthorityScope s, String id) { return bindings.findExact(receiver(s), id); }
    private AgentRuntimeV2BindingEntity lockExact(AgentRuntimeV2AuthorityScope s, String id) { return bindings.lockExact(receiver(s), id); }
    private int transition(AgentRuntimeV2AuthorityScope s, AgentRuntimeV2BindingEntity e, String status, Long disconnectedAt, Long revokedAt, String reason, long now) { return bindings.transition(receiver(s), e, status, disconnectedAt, revokedAt, reason, now); }
    private int moveRoutingLease(AgentRuntimeV2AuthorityScope s, AgentRuntimeV2BindingEntity e, String expectedOwner, long expectedEpoch, long expectedLease, String owner, long epoch, long lease, long now) { return bindings.moveRoutingLease(receiver(s), e, expectedOwner, expectedEpoch, expectedLease, owner, epoch, lease, now); }
    private static RuntimeReceiverScope receiver(AgentRuntimeV2AuthorityScope s) { return new RuntimeReceiverScope(s.tenantId(), s.dataDomainId(), s.executionPoolId(), s.clientId(), s.ownerJiacn(), s.agentId(), s.claimantDeploymentId(), s.claimantWorkerGeneration(), 17, s.admissionEpoch(), s.moduleId(), s.migrationEpoch()); }

    private AgentRuntimeV2BindingEntity insertApiBinding(AgentRuntimeV2AuthorityScope scope,
            String runtimeInstanceId, String routeOwner, Long routeEpoch, Long routeLeaseUntil) {
        AgentRuntimeV2BindingEntity entity = newBinding("API_KEY", "api-key-" + runtimeInstanceId,
                null, runtimeInstanceId, routeOwner, routeEpoch, routeLeaseUntil);
        assertEquals(1, insertTrusted(scope, entity));
        return findExact(scope, entity.getRuntimeBindingId());
    }

    private int moveRoutingLeaseDirect(AgentRuntimeV2AuthorityScope scope,
            AgentRuntimeV2BindingEntity binding, long expectedVersion, String expectedOwner,
            long expectedEpoch, long expectedLease, String owner, long epoch, long leaseUntil,
            long now) {
        return bindingMapper.moveRoutingLease(scope.tenantId().value(),
                scope.dataDomainId().value(), scope.executionPoolId(), scope.clientId(),
                scope.ownerJiacn(), scope.agentId(), scope.claimantDeploymentId(), scope.claimantWorkerGeneration(), 17,
                scope.admissionEpoch(), scope.moduleId(), scope.migrationEpoch(),
                binding.getRuntimeBindingId(), binding.getBindingGeneration(),
                binding.getRuntimeInstanceId(), expectedVersion, expectedOwner, expectedEpoch,
                expectedLease, owner, epoch, leaseUntil, now);
    }

    private static AgentRuntimeV2BindingEntity v1Binding(String installationId) {
        return newBinding("RUNTIME_V1_INSTALLATION", installationId, installationId,
                "runtime-" + installationId, null, null, null);
    }

    private static AgentRuntimeV2BindingEntity newBinding(String credentialKind,
            String credentialReference, String installationId, String runtimeInstanceId,
            String routeOwner, Long routeEpoch, Long routeLeaseUntil) {
        AgentRuntimeV2BindingEntity entity = new AgentRuntimeV2BindingEntity()
                .setCredentialKind(credentialKind).setCredentialReference(credentialReference)
                .setRuntimeInstallationId(installationId).setRuntimeInstanceId(runtimeInstanceId)
                .setBindingGeneration(3L).setStatus("ACTIVE").setConnectedAt(NOW - 10_000)
                .setSocketOwnerInstanceId(routeOwner).setSocketOwnerEpoch(routeEpoch)
                .setSocketOwnerLeaseUntil(routeLeaseUntil).setVersion(0L);
        entity.setCreateTime(NOW - 10_000);
        entity.setUpdateTime(NOW - 10_000);
        return entity;
    }

    private void insertInstallation(String installationId, String status, String tenantId,
            String clientId, String agentId) {
        jdbc.update("""
                INSERT INTO agent_runtime_v1_installation(
                  installation_id,status,tenant_id,client_id,canonical_agent_id)
                VALUES(?,?,?,?,?)
                """, installationId, status, tenantId, clientId, agentId);
    }

    private void insertInbox(long id, AgentRuntimeV2AuthorityScope scope) {
        String commandId = "command-" + id;
        jdbc.update("""
                INSERT INTO agent_command_delivery(
                  id,protocol_version,data_domain_id,execution_pool_id,admission_epoch,
                  producer_deployment_id,module_id,migration_epoch,claimed_deployment_id,
                  claimed_worker_generation,command_id,owner_jiacn,target_agent_id,tenant_id,client_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, 2, scope.dataDomainId().value(), scope.executionPoolId(),
                scope.admissionEpoch(), scope.producerDeploymentId(), scope.moduleId(),
                scope.migrationEpoch(), scope.claimantDeploymentId(),
                scope.claimantWorkerGeneration(), commandId, scope.ownerJiacn(), scope.agentId(),
                scope.tenantId().value(), scope.clientId());
        jdbc.update("""
                INSERT INTO agent_consumer_inbox(
                  id,protocol_version,data_domain_id,execution_pool_id,admission_epoch,
                  producer_deployment_id,module_id,migration_epoch,claimant_deployment_id,
                  claimant_worker_generation,command_id,delivery_id,version,tenant_id,client_id,update_time)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, 2, scope.dataDomainId().value(), scope.executionPoolId(),
                scope.admissionEpoch(), scope.producerDeploymentId(), scope.moduleId(),
                scope.migrationEpoch(), scope.claimantDeploymentId(),
                scope.claimantWorkerGeneration(), commandId, id, 0, scope.tenantId().value(),
                scope.clientId(), NOW - 1);
    }

    private static byte[] hash(int marker) {
        byte[] value = new byte[32];
        value[0] = (byte) marker;
        return value;
    }

    private static AgentRuntimeV2AuthorityScope scope(String tenant, String owner) {
        return scope(tenant, owner, "agent-a");
    }

    private static AgentRuntimeV2AuthorityScope scope(String tenant, String owner, String agent) {
        return new AgentRuntimeV2AuthorityScope(TenantId.of(tenant), DataDomainId.of("cyf"),
                "pool-a", "client-a", owner, agent, "producer-a", "claimant-a",
                7, 11, "agent-command", 13);
    }

    private static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE agent_runtime_v1_installation (
                  installation_id VARCHAR_IGNORECASE(100) PRIMARY KEY,
                  status VARCHAR_IGNORECASE(20) NOT NULL,
                  tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
                  client_id VARCHAR_IGNORECASE(50) NOT NULL,
                  canonical_agent_id VARCHAR_IGNORECASE(100) NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE agent_runtime_v2_binding (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  runtime_binding_id VARCHAR_IGNORECASE(36) NOT NULL UNIQUE,
                  credential_kind VARCHAR_IGNORECASE(32) NOT NULL,
                  credential_reference VARCHAR_IGNORECASE(200) NOT NULL,
                  runtime_installation_id VARCHAR_IGNORECASE(100),
                  contract_version INT NOT NULL,
                  evidence_provenance VARCHAR_IGNORECASE(24) NOT NULL DEFAULT 'R2B1_ENFORCED',
                  registration_subject_sha256 BINARY(32),
                  live_registration_subject_sha256 VARCHAR_IGNORECASE(64)
                    GENERATED ALWAYS AS ('generated'),
                  live_business_subject VARCHAR_IGNORECASE(64)
                    GENERATED ALWAYS AS ('generated'),
                  registration_authority_id VARCHAR_IGNORECASE(36),
                  registration_authority_revision BIGINT,
                  process_public_key_spki BINARY(44),
                  process_public_key_sha256 BINARY(32),
                  verifier_version BIGINT,
                  verifier_key_id VARCHAR_IGNORECASE(100),
                  reconnect_verifier BINARY(32),
                  current_registration_receipt_id VARCHAR_IGNORECASE(36),
                  current_registration_receipt_sha256 BINARY(32),
                  data_domain_id VARCHAR_IGNORECASE(64) NOT NULL,
                  execution_pool_id VARCHAR_IGNORECASE(100) NOT NULL,
                  owner_jiacn VARCHAR_IGNORECASE(100) NOT NULL,
                  agent_id VARCHAR_IGNORECASE(100) NOT NULL,
                  producer_deployment_id VARCHAR_IGNORECASE(100),
                  claimant_deployment_id VARCHAR_IGNORECASE(100) NOT NULL,
                  claimant_worker_generation BIGINT NOT NULL,
                  receiver_registration_revision BIGINT NOT NULL,
                  admission_epoch BIGINT NOT NULL,
                  module_id VARCHAR_IGNORECASE(64) NOT NULL,
                  migration_epoch BIGINT NOT NULL,
                  runtime_instance_id VARCHAR_IGNORECASE(100) NOT NULL,
                  binding_generation BIGINT NOT NULL,
                  status VARCHAR_IGNORECASE(20) NOT NULL,
                  connected_at BIGINT NOT NULL,
                  disconnected_at BIGINT,
                  revoked_at BIGINT,
                  revoke_reason VARCHAR_IGNORECASE(500),
                  socket_owner_instance_id VARCHAR_IGNORECASE(100),
                  socket_owner_epoch BIGINT,
                  socket_owner_lease_until BIGINT,
                  current_session_fence VARCHAR_IGNORECASE(100),
                  version BIGINT NOT NULL,
                  tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
                  client_id VARCHAR_IGNORECASE(50) NOT NULL,
                  create_time BIGINT NOT NULL,
                  update_time BIGINT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_delivery (
                  id BIGINT PRIMARY KEY,
                  protocol_version SMALLINT NOT NULL,
                  data_domain_id VARCHAR_IGNORECASE(64) NOT NULL,
                  execution_pool_id VARCHAR_IGNORECASE(100) NOT NULL,
                  admission_epoch BIGINT NOT NULL,
                  producer_deployment_id VARCHAR_IGNORECASE(100) NOT NULL,
                  module_id VARCHAR_IGNORECASE(64) NOT NULL,
                  migration_epoch BIGINT NOT NULL,
                  claimed_deployment_id VARCHAR_IGNORECASE(100) NOT NULL,
                  claimed_worker_generation BIGINT NOT NULL,
                  command_id VARCHAR_IGNORECASE(100) NOT NULL,
                  owner_jiacn VARCHAR_IGNORECASE(100) NOT NULL,
                  target_agent_id VARCHAR_IGNORECASE(100) NOT NULL,
                  tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
                  client_id VARCHAR_IGNORECASE(50) NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE agent_consumer_inbox (
                  id BIGINT PRIMARY KEY,
                  protocol_version SMALLINT NOT NULL,
                  data_domain_id VARCHAR_IGNORECASE(64) NOT NULL,
                  execution_pool_id VARCHAR_IGNORECASE(100) NOT NULL,
                  admission_epoch BIGINT NOT NULL,
                  producer_deployment_id VARCHAR_IGNORECASE(100) NOT NULL,
                  module_id VARCHAR_IGNORECASE(64) NOT NULL,
                  migration_epoch BIGINT NOT NULL,
                  claimant_deployment_id VARCHAR_IGNORECASE(100) NOT NULL,
                  claimant_worker_generation BIGINT NOT NULL,
                  command_id VARCHAR_IGNORECASE(100) NOT NULL,
                  delivery_id BIGINT NOT NULL,
                  version BIGINT NOT NULL,
                  runtime_dispatch_id VARCHAR_IGNORECASE(36),
                  runtime_binding_id VARCHAR_IGNORECASE(36),
                  runtime_binding_generation BIGINT,
                  dispatch_runtime_instance_id VARCHAR_IGNORECASE(100),
                  dispatch_prepared_at BIGINT,
                  execution_started_at BIGINT,
                  execution_lease_until BIGINT,
                  execution_lease_revision BIGINT,
                  ack_runtime_binding_id VARCHAR_IGNORECASE(36),
                  ack_runtime_binding_generation BIGINT,
                  ack_committed_at BIGINT,
                  tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
                  client_id VARCHAR_IGNORECASE(50) NOT NULL,
                  update_time BIGINT NOT NULL,
                  UNIQUE(runtime_dispatch_id))
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_v2_ack_receipt (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, receipt_id VARCHAR_IGNORECASE(36) NOT NULL,
                  runtime_dispatch_id VARCHAR_IGNORECASE(36) NOT NULL, inbox_id BIGINT NOT NULL,
                  delivery_id BIGINT NOT NULL, ack_message_id VARCHAR_IGNORECASE(100) NOT NULL,
                  source_message_id VARCHAR_IGNORECASE(100) NOT NULL, status VARCHAR_IGNORECASE(20) NOT NULL,
                  ack_at BIGINT NOT NULL, request_fingerprint BINARY(32) NOT NULL,
                  runtime_binding_id VARCHAR_IGNORECASE(36) NOT NULL, dispatch_binding_generation BIGINT NOT NULL,
                  ack_holder_binding_generation BIGINT, runtime_credential_client_id VARCHAR_IGNORECASE(50),
                  runtime_instance_id VARCHAR_IGNORECASE(100) NOT NULL, data_domain_id VARCHAR_IGNORECASE(64) NOT NULL,
                  execution_pool_id VARCHAR_IGNORECASE(100) NOT NULL, owner_jiacn VARCHAR_IGNORECASE(100) NOT NULL,
                  agent_id VARCHAR_IGNORECASE(100) NOT NULL, producer_deployment_id VARCHAR_IGNORECASE(100) NOT NULL,
                  claimant_deployment_id VARCHAR_IGNORECASE(100) NOT NULL, claimant_worker_generation BIGINT NOT NULL,
                  receiver_registration_revision BIGINT,
                  admission_epoch BIGINT NOT NULL, module_id VARCHAR_IGNORECASE(64) NOT NULL,
                  migration_epoch BIGINT NOT NULL, postcommit_delivery_version BIGINT NOT NULL,
                  postcommit_inbox_version BIGINT NOT NULL, lease_fact_id VARCHAR_IGNORECASE(36),
                  receipt_wire_bytes BLOB, receipt_wire_sha256 BINARY(32),
                  receipt_wire_contract_version INT,
                  execution_origin_snapshot_sha256 BINARY(32),
                  ack_holder_generation_fact_id VARCHAR_IGNORECASE(36),
                  ack_holder_generation_snapshot_sha256 BINARY(32),
                  contract_version SMALLINT NOT NULL, committed_at BIGINT NOT NULL,
                  tenant_id VARCHAR_IGNORECASE(50) NOT NULL, client_id VARCHAR_IGNORECASE(50) NOT NULL,
                  create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
                  UNIQUE(receipt_id), UNIQUE(ack_message_id), UNIQUE(runtime_dispatch_id,status))
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_v2_execution_lease_fact (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, lease_fact_id VARCHAR_IGNORECASE(36) NOT NULL,
                  runtime_dispatch_id VARCHAR_IGNORECASE(36) NOT NULL, inbox_id BIGINT NOT NULL,
                  delivery_id BIGINT NOT NULL, fact_sequence BIGINT NOT NULL, lease_revision BIGINT NOT NULL,
                  fact_type VARCHAR_IGNORECASE(20) NOT NULL, previous_lease_fact_id VARCHAR_IGNORECASE(36),
                  effective_at BIGINT NOT NULL, lease_until BIGINT, causing_receipt_id VARCHAR_IGNORECASE(36),
                  runtime_binding_id VARCHAR_IGNORECASE(36) NOT NULL, dispatch_binding_generation BIGINT NOT NULL,
                  holder_binding_generation BIGINT,
                  runtime_instance_id VARCHAR_IGNORECASE(100) NOT NULL,
                  runtime_credential_client_id VARCHAR_IGNORECASE(50), data_domain_id VARCHAR_IGNORECASE(64) NOT NULL,
                  execution_pool_id VARCHAR_IGNORECASE(100) NOT NULL, owner_jiacn VARCHAR_IGNORECASE(100) NOT NULL,
                  agent_id VARCHAR_IGNORECASE(100) NOT NULL, producer_deployment_id VARCHAR_IGNORECASE(100) NOT NULL,
                  claimant_deployment_id VARCHAR_IGNORECASE(100) NOT NULL, claimant_worker_generation BIGINT NOT NULL,
                  receiver_registration_revision BIGINT,
                  admission_epoch BIGINT NOT NULL, module_id VARCHAR_IGNORECASE(64) NOT NULL,
                  migration_epoch BIGINT NOT NULL, tenant_control_operation_id VARCHAR_IGNORECASE(100),
                  tenant_control_revision BIGINT NOT NULL, tenant_control_cutoff_at BIGINT,
                  module_control_operation_id VARCHAR_IGNORECASE(100), module_mode_revision BIGINT NOT NULL,
                  module_control_cutoff_at BIGINT, deployment_control_operation_id VARCHAR_IGNORECASE(100),
                  deployment_registration_revision BIGINT NOT NULL, deployment_control_cutoff_at BIGINT,
                  reason VARCHAR_IGNORECASE(500) NOT NULL, created_at BIGINT NOT NULL,
                  execution_origin_snapshot_sha256 BINARY(32),
                  holder_generation_fact_id VARCHAR_IGNORECASE(36),
                  holder_generation_snapshot_sha256 BINARY(32),
                  tenant_id VARCHAR_IGNORECASE(50) NOT NULL, client_id VARCHAR_IGNORECASE(50) NOT NULL,
                  create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
                  UNIQUE(lease_fact_id), UNIQUE(runtime_dispatch_id,fact_sequence),
                  UNIQUE(runtime_dispatch_id,holder_binding_generation,lease_revision,fact_type))
                """);
    }
}
