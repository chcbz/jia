package cn.jia.agent.hosting;

import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentRuntimeV1Service;
import cn.jia.economy.entity.*;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import cn.jia.oauth.service.ApiKeyService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagedHostingCredentialsTest {
    private final EconomyHostingRentMapper rent = mock(EconomyHostingRentMapper.class);
    private final ApiKeyService keys = mock(ApiKeyService.class);
    private final HostingRentOwnerResolver owners = mock(HostingRentOwnerResolver.class);
    private final AgentIdentityService identities = mock(AgentIdentityService.class);
    private final AgentPersonaBindingDao bindings = mock(AgentPersonaBindingDao.class);
    private final AgentRuntimeV1Service runtime = mock(AgentRuntimeV1Service.class);
    private final ManagedHostingProvisioner.Preparation p = new ManagedHostingProvisioner.Preparation("0", "Client-A", "Owner-A",
            "agt_0123456789abcdef0123456789abcdef", "hri-original", "hrl-one", "17", 1000);
    private final ManagedHostingProvisioner.Candidate candidate = new ManagedHostingProvisioner.Candidate(
            "rti_0123456789abcdef0123456789abcdef", "a".repeat(64), "b".repeat(64), 2000, 1, "host-fixture");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private ManagedHostingCredentials service;
    private EconomyHostingProvisioningIntentEntity base;
    private EconomyHostingLeaseEntity lease;
    @BeforeEach void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:managed_install_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        manager = new DataSourceTransactionManager(source); jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE association(id INT PRIMARY KEY,installation VARCHAR(100),manifest VARCHAR(64),generation BIGINT)");
        jdbc.update("INSERT INTO association VALUES(1,NULL,NULL,0)");
        jdbc.execute("CREATE TABLE installation_lock(id INT PRIMARY KEY)"); jdbc.update("INSERT INTO installation_lock VALUES(1)");
        jdbc.execute("CREATE TABLE free_request(id INT PRIMARY KEY,target BIGINT)"); jdbc.update("INSERT INTO free_request VALUES(1,NULL)");
        jdbc.execute("CREATE TABLE effects(name VARCHAR(100))");
        ObjectProvider<ApiKeyService> provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(keys);
        service = new ManagedHostingCredentials(provider, rent, owners, identities, bindings, manager, runtime);
        base = new EconomyHostingProvisioningIntentEntity().setIntentId(p.intentId()).setLeaseId(p.leaseId()).setAgentId(p.agentId())
                .setQuotePurpose("INITIAL").setPrincipalType("USER").setPrincipalId("login-sub").setReservedAt(1000L)
                .setStatus("PROVISIONING_UNKNOWN").setTenantId("0").setClientId("Client-A").setPersonaCode("wuyong");
        when(rent.selectIntentForUpdate("0", "Client-A", p.intentId())).thenAnswer(call -> jdbc.queryForObject(
                "SELECT * FROM association WHERE id=1 FOR UPDATE", (rs, row) -> new EconomyHostingProvisioningIntentEntity()
                        .setIntentId(base.getIntentId()).setLeaseId(base.getLeaseId()).setAgentId(base.getAgentId())
                        .setQuotePurpose(base.getQuotePurpose()).setPrincipalType(base.getPrincipalType()).setPrincipalId(base.getPrincipalId())
                        .setReservedAt(base.getReservedAt()).setStatus(base.getStatus()).setTenantId(base.getTenantId()).setClientId(base.getClientId())
                        .setPersonaCode(base.getPersonaCode()).setRefundTransactionId(base.getRefundTransactionId()).setCaptureTransactionId(base.getCaptureTransactionId())
                        .setRuntimeInstallationId(rs.getString("installation")).setRuntimeManifestSha256(rs.getString("manifest"))
                        .setRuntimeProvisionGeneration(rs.getLong("generation"))));
        lease = new EconomyHostingLeaseEntity().setAgentId(p.agentId()).setLeaseId(p.leaseId()).setBindingId("17").setStatus("PROVISIONING")
                .setLatestIntentId(p.intentId()).setPrincipalType("USER").setPrincipalId("login-sub").setPersonaCode("wuyong");
        when(rent.selectLeaseForUpdate("0", "Client-A", p.leaseId())).thenReturn(lease);
        when(owners.requireOwner(new HostingRentHttp.Actor("login-sub", "0", "Client-A", "Owner-A"))).thenReturn("Owner-A");
        var binding = new AgentPersonaBindingEntity().setId(17L).setAgentId(p.agentId()).setJiacn("Owner-A").setStatus(1).setPersonaCode("wuyong");
        binding.setClientId("Client-A"); binding.setTenantId("0"); when(bindings.findByIdForUpdate(17L)).thenReturn(binding);
        var identity = new AgentIdentityRegistryEntity().setCanonicalAgentId(p.agentId()).setBindingId(17L).setLifecycleStatus("PROVISIONED");
        when(identities.requireRegistrationIdentityInScope("0", "Client-A", "Owner-A", p.agentId())).thenReturn(identity);
        when(identities.requireActiveBinding(identity, null)).thenReturn(binding);
        when(runtime.ensureInstallation(anyString(), anyString(), anyString(), any(), anyLong())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            // Reuse an actual H2 root fence; this test proves transaction/concurrency, not MySQL syntax.
            jdbc.queryForObject("SELECT id FROM installation_lock WHERE id=1 FOR UPDATE", Integer.class);
            jdbc.update("INSERT INTO effects VALUES('installation-ensure')");
            return new AgentRuntimeV1InstallationView(candidate.installationId(), "0", "Client-A", p.agentId(), "1", candidate.manifestSha256(),
                    candidate.enrollmentExpiresAt(), "ACTIVE".equals(base.getStatus()) ? "ACTIVE" : "PENDING", null);
        });
        when(rent.attachRuntimeInstallation(anyString(), anyString(), anyString(), anyString(), anyString())).thenAnswer(call ->
                jdbc.update("UPDATE association SET installation=?,manifest=?,generation=1 WHERE id=1 AND generation=0 AND installation IS NULL",
                        (String)call.getArgument(3), (String)call.getArgument(4)));
        when(rent.advanceRuntimeGeneration(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong())).thenAnswer(call ->
                jdbc.update("UPDATE association SET generation=? WHERE id=1 AND generation=?", (Long)call.getArgument(6), (Long)call.getArgument(5)));
        when(rent.attachRuntimeTarget(anyString(), anyString(), anyString(), anyLong())).thenAnswer(call ->
                jdbc.update("UPDATE free_request SET target=? WHERE id=1 AND target IS NULL", (Long)call.getArgument(3)));
    }
    @AfterEach void close() { jdbc.execute("DROP ALL OBJECTS"); }
    @Test void exactInstallationReplayAttachesOnceAndNeverCreatesReadsOrSendsLegacyKey() {
        service.ensureInstallation(p, candidate); service.ensureInstallation(p, candidate);
        assertEquals(candidate.installationId(), jdbc.queryForObject("SELECT installation FROM association", String.class));
        verify(rent, times(1)).attachRuntimeInstallation(anyString(), anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(keys);
        var order = inOrder(runtime, rent, bindings);
        order.verify(runtime).ensureInstallation(anyString(), anyString(), anyString(), any(), anyLong());
        order.verify(rent).selectIntentForUpdate(anyString(), anyString(), anyString());
        order.verify(rent).selectLeaseForUpdate(anyString(), anyString(), anyString()); order.verify(bindings).findByIdForUpdate(17L);
    }
    @Test void concurrentSameCandidateHasOnlyOneExactDurableAssociation() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            Callable<Boolean> ensure = () -> { gate.await(); service.ensureInstallation(p, candidate); return true; };
            var a = executor.submit(ensure); var b = executor.submit(ensure); gate.countDown();
            assertTrue(a.get(10, TimeUnit.SECONDS)); assertTrue(b.get(10, TimeUnit.SECONDS));
        }
        verify(rent, times(1)).attachRuntimeInstallation(anyString(), anyString(), anyString(), anyString(), anyString()); verifyNoInteractions(keys);
    }
    @Test void failedLinkCasRollsBackInstallationAndScopeImpersonationRollsBackAllEffects() {
        doReturn(0).when(rent).attachRuntimeInstallation(anyString(), anyString(), anyString(), anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> service.ensureInstallation(p, candidate));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM effects", Integer.class));
        assertNull(jdbc.queryForObject("SELECT installation FROM association", String.class));
        var other = new ManagedHostingProvisioner.Preparation("0", "Client-A", "Other", p.agentId(), p.intentId(), p.leaseId(), p.bindingId(), 1000);
        assertThrows(IllegalStateException.class, () -> service.ensureInstallation(other, candidate));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM effects", Integer.class)); verifyNoInteractions(keys);
    }
    @Test void changedInstallationManifestBindingAndOriginalReserveAssociationAreRejected() {
        service.ensureInstallation(p, candidate);
        for (String column : java.util.List.of("installation", "manifest")) {
            String original = jdbc.queryForObject("SELECT " + column + " FROM association", String.class);
            jdbc.update("UPDATE association SET " + column + "='wrong'");
            assertThrows(IllegalStateException.class, () -> service.ensureInstallation(p, candidate));
            jdbc.update("UPDATE association SET " + column + "=?", original);
        }
        lease.setBindingId("18"); assertThrows(IllegalStateException.class, () -> service.ensureInstallation(p, candidate));
        lease.setBindingId("17"); base.setReservedAt(1001L); assertThrows(IllegalStateException.class, () -> service.ensureInstallation(p, candidate));
        verifyNoInteractions(keys);
    }
    private ManagedHostingProvisioner.Preparation free() {
        return new ManagedHostingProvisioner.Preparation(p.tenantId(), p.clientId(), p.ownerJiacn(), p.agentId(), p.intentId(), p.leaseId(),
                p.bindingId(), p.reservedAt(), "hrr-one", 1100, lease.getPaidThrough());
    }
    private ManagedHostingProvisioner.Candidate generation(long generation) {
        return new ManagedHostingProvisioner.Candidate(candidate.installationId(), candidate.manifestSha256(), candidate.enrollmentSecretSha256(),
                candidate.enrollmentExpiresAt(), generation, candidate.hostId());
    }
    private void active() {
        service.ensureInstallation(p, candidate); base.setStatus("ACTIVE"); lease.setStatus("ACTIVE").setPaidThrough(System.currentTimeMillis() + 60000);
        when(rent.selectReprovisionForUpdate("0", "Client-A", "hrr-one")).thenAnswer(call -> {
            Long target = jdbc.queryForObject("SELECT target FROM free_request WHERE id=1 FOR UPDATE", Long.class);
            return new EconomyHostingReprovisionEntity().setRequestId("hrr-one").setIntentId(p.intentId()).setAgentId(p.agentId())
                    .setLeaseId(p.leaseId()).setPrincipalId("login-sub").setStatus("PROVISIONING_UNKNOWN").setRequestedAt(1100L)
                    .setPaidThrough(lease.getPaidThrough()).setRuntimeTargetGeneration(target);
        });
    }
    @Test void freeReprovisionDurablyAllocatesOneNewGenerationAndExactReplayWithoutAnyFunds() {
        active(); service.ensureInstallation(free(), generation(2)); service.ensureInstallation(free(), generation(2));
        assertEquals(2L, jdbc.queryForObject("SELECT generation FROM association", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT target FROM free_request", Long.class));
        verify(rent, times(1)).advanceRuntimeGeneration(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong());
        verify(rent, times(1)).attachRuntimeTarget(anyString(), anyString(), anyString(), anyLong()); verifyNoInteractions(keys);
        assertThrows(IllegalStateException.class, () -> service.ensureInstallation(free(), generation(3)));
    }
    @Test void oldGenerationAndFailedFreeTargetCasCannotAdvanceInitialGeneration() {
        active(); assertThrows(IllegalStateException.class, () -> service.ensureInstallation(free(), generation(1)));
        doReturn(0).when(rent).attachRuntimeTarget(anyString(), anyString(), anyString(), anyLong());
        assertThrows(IllegalStateException.class, () -> service.ensureInstallation(free(), generation(2)));
        assertEquals(1L, jdbc.queryForObject("SELECT generation FROM association", Long.class));
        assertNull(jdbc.queryForObject("SELECT target FROM free_request", Long.class)); verifyNoInteractions(keys);
    }
    @Test void legacyAuditRefundRevokesOnlyExactExistingReferenceAndRequiresTransaction() {
        var failed = new EconomyHostingProvisioningIntentEntity().setId(1L).setIntentId(p.intentId()).setQuotePurpose("INITIAL")
                .setPrincipalType("USER").setTenantId(p.tenantId()).setClientId(p.clientId()).setAgentId(p.agentId())
                .setLeaseId(p.leaseId()).setReservedAt(p.reservedAt()).setStatus("FAILED_NO_EFFECT").setManagedApiKeyId("old-key-id");
        assertThrows(IllegalStateException.class, () -> service.disableForRefund(p, failed));
        when(keys.disableManagedKey("old-key-id", "0", "Client-A", "Owner-A", "hosting:hri-original")).thenReturn(true);
        new TransactionTemplate(manager).executeWithoutResult(status -> service.disableForRefund(p, failed));
        verify(keys).disableManagedKey("old-key-id", "0", "Client-A", "Owner-A", "hosting:hri-original");
        verify(keys, never()).create(any()); verify(keys, never()).get(any());
    }
}
