package cn.jia.agent.hosting;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.mapper.AgentHostingRentBindingMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.entity.*;
import cn.jia.economy.hosting.HostingRentLedgerService;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real H2 commit/rollback/root locks; domain collaborators mocked, no runtime I/O. MySQL catalog tested separately. */
class HostingRentReprovisionTransactionTest {
    private static final String AGENT = "agt_0123456789abcdef0123456789abcdef";
    private static final String KEY = "00000000-0000-0000-0000-000000000001";
    private static final HostingRentHttp.Actor ACTOR = new HostingRentHttp.Actor("login-sub", "Tenant-A", "Client-A");
    private final EconomyHostingRentMapper rent = mock(EconomyHostingRentMapper.class);
    private final HostingRentLedgerService ledger = mock(HostingRentLedgerService.class);
    private final ManagedHostingProvisioner provider = mock(ManagedHostingProvisioner.class);
    private final HostingRentReconciler reconciler = mock(HostingRentReconciler.class);
    private final AgentIdentityService identities = mock(AgentIdentityService.class);
    private JdbcTemplate jdbc;
    private HostingRentApplicationService app;
    private EconomyHostingProvisioningIntentEntity initial;
    private long paidThrough;

    @BeforeEach void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:free_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE initial_root(id INT PRIMARY KEY)"); jdbc.update("INSERT INTO initial_root VALUES(1)");
        jdbc.execute("CREATE TABLE lease_root(id INT PRIMARY KEY, version BIGINT, paid_through BIGINT, status VARCHAR(24))");
        paidThrough = System.currentTimeMillis() + 2592000000L;
        jdbc.update("INSERT INTO lease_root VALUES(1,4,?,'ACTIVE')", paidThrough);
        jdbc.execute("CREATE TABLE free_request(request_id VARCHAR(100) PRIMARY KEY, key_value VARBINARY(36) UNIQUE, hash_value BINARY(32), lease_version BIGINT, paid_through BIGINT, requested_at BIGINT, status VARCHAR(32))");
        initial = new EconomyHostingProvisioningIntentEntity().setIntentId("hri-original").setQuotePurpose("INITIAL")
                .setStatus("ACTIVE").setAgentId(AGENT).setLeaseId("hrl-one").setPrincipalType("USER").setPrincipalId("login-sub");
        when(rent.selectInitialIntent("Tenant-A", "Client-A", "hrl-one")).thenReturn(initial);
        when(rent.selectIntentForUpdate("Tenant-A", "Client-A", "hri-original")).thenAnswer(call -> {
            jdbc.queryForObject("SELECT id FROM initial_root WHERE id=1 FOR UPDATE", Integer.class); return initial;
        });
        when(rent.selectLeaseForUpdate("Tenant-A", "Client-A", "hrl-one")).thenAnswer(call ->
                jdbc.queryForObject("SELECT * FROM lease_root WHERE id=1 FOR UPDATE", (rs, row) -> new EconomyHostingLeaseEntity()
                        .setLeaseId("hrl-one").setAgentId(AGENT).setPersonaCode("wuyong").setBindingId("17")
                        .setPrincipalType("USER").setPrincipalId("login-sub").setStatus(rs.getString("status"))
                        .setVersion(rs.getLong("version")).setPaidThrough(rs.getLong("paid_through"))));
        when(rent.selectReprovisionReplay(anyString(), anyString(), anyString(), any())).thenAnswer(call -> replay(call.getArgument(3), false));
        when(rent.selectReprovisionReplayForUpdate(anyString(), anyString(), anyString(), any())).thenAnswer(call -> replay(call.getArgument(3), true));
        when(rent.selectLiveReprovisionForUpdate(anyString(), anyString(), anyString())).thenAnswer(call ->
                jdbc.queryForObject("SELECT COUNT(*) FROM free_request WHERE status IN ('ACCEPTED','PROVISIONING_UNKNOWN')", Integer.class) == 0
                        ? null : new EconomyHostingReprovisionEntity().setStatus("PROVISIONING_UNKNOWN"));
        when(rent.acceptReprovision(anyString(), anyString(), anyString(), anyLong(), anyLong())).thenAnswer(call ->
                jdbc.update("UPDATE lease_root SET version=version+1 WHERE id=1 AND version=? AND status='ACTIVE' AND paid_through>?",
                        (Long) call.getArgument(3), (Long) call.getArgument(4)));
        when(rent.insertReprovision(any())).thenAnswer(call -> {
            EconomyHostingReprovisionEntity row = call.getArgument(0);
            return jdbc.update("INSERT INTO free_request VALUES(?,?,?,?,?,?,'ACCEPTED')", row.getRequestId(), row.getIdempotencyKey(),
                    row.getRequestHash(), row.getLeaseVersion(), row.getPaidThrough(), row.getRequestedAt());
        });
        var owners = mock(HostingRentOwnerResolver.class); when(owners.requireOwner(ACTOR)).thenReturn("Tenant-A");
        var binding = new AgentPersonaBindingEntity().setId(17L).setStatus(1).setAgentId(AGENT).setPersonaCode("wuyong").setJiacn("Tenant-A");
        binding.setClientId("Client-A");
        var identity = new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT).setBindingId(17L);
        when(identities.requireRegistrationIdentityInScope("Tenant-A", "Client-A", "Tenant-A", AGENT)).thenReturn(identity);
        when(identities.requireActiveBinding(identity, null)).thenReturn(binding);
        var bindings = mock(AgentPersonaBindingDao.class); when(bindings.findByIdForUpdate(17L)).thenReturn(binding);
        ObjectProvider<ManagedHostingProvisioner> providers = mock(ObjectProvider.class); when(providers.getIfAvailable()).thenReturn(provider);
        when(provider.available()).thenReturn(true);
        when(provider.availableFor(anyString(), anyString(), anyString())).thenAnswer(call -> provider.available());
        app = new HostingRentApplicationService(new AgentHostingRentProperties(true, null, null, null),
                new EconomyPreviewGate(new EconomyPreviewProperties(true, List.of(new EconomyPreviewProperties.AllowedScope("Tenant-A", "Client-A")))),
                owners, rent, ledger, mock(AgentHostingRentBindingMapper.class), bindings, mock(AgentRuntimeDao.class), identities,
                providers, reconciler, new DataSourceTransactionManager(dataSource), true);
    }
    @AfterEach void close() { jdbc.execute("DROP ALL OBJECTS"); }
    private EconomyHostingReprovisionEntity replay(byte[] key, boolean lock) {
        var rows = jdbc.query("SELECT * FROM free_request WHERE key_value=?" + (lock ? " FOR UPDATE" : ""), (rs, row) ->
                new EconomyHostingReprovisionEntity().setRequestId(rs.getString("request_id")).setRequestHash(rs.getBytes("hash_value"))
                        .setAgentId(AGENT).setLeaseId("hrl-one").setLeaseVersion(rs.getLong("lease_version"))
                        .setPaidThrough(rs.getLong("paid_through")).setRequestedAt(rs.getLong("requested_at")), key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private Map<String,String> body(String version) { return Map.of("mode", "server", "hostingAction", "REPROVISION",
            "agentId", AGENT, "leaseId", "hrl-one", "expectedLeaseVersion", version); }
    private HostingRentApplicationService.BindView confirm(String key, String version) { return app.bind(ACTOR, "wuyong", key, body(version)); }
    private long version() { return jdbc.queryForObject("SELECT version FROM lease_root", Long.class); }

    @Test void immutableFreeReceiptReplaysAfterLeaseChangesWithoutProviderOrMoney() {
        var receipt = (HostingRentApplicationService.ReprovisionView) confirm(KEY, "4");
        assertEquals("5", receipt.leaseVersion()); assertEquals("0", receipt.amountMicro()); assertEquals("ACCEPTED", receipt.status());
        assertEquals(Long.toString(paidThrough), receipt.paidThrough());
        jdbc.update("UPDATE lease_root SET version=99,status='REFUNDED',paid_through=1");
        when(provider.available()).thenReturn(false);
        assertEquals(receipt, confirm(KEY, "4"));
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(HostingRentApplicationException.class, () -> confirm(KEY, "5")).code());
        verifyNoInteractions(ledger); verify(provider, never()).prepareAndObserve(any()); verify(reconciler, times(1)).wake();
    }
    @Test void twoConcurrentSameKeyConfirmationsAdvanceOnceAndReturnSameReceipt() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            Callable<HostingRentApplicationService.BindView> action = () -> { gate.await(); return confirm(KEY, "4"); };
            var first = executor.submit(action); var second = executor.submit(action); gate.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(5, version()); assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM free_request", Integer.class));
        assertEquals(paidThrough, jdbc.queryForObject("SELECT paid_through FROM lease_root", Long.class)); verifyNoInteractions(ledger);
    }
    @Test void concurrentDifferentKeysAndPendingUnknownCannotCreateAnotherGeneration() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            Callable<Boolean> first = () -> { gate.await(); try { confirm(KEY, "4"); return true; } catch (HostingRentApplicationException e) { return false; } };
            Callable<Boolean> second = () -> { gate.await(); try { confirm(UUID.randomUUID().toString(), "4"); return true; } catch (HostingRentApplicationException e) { return false; } };
            var a = executor.submit(first); var b = executor.submit(second); gate.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
        jdbc.update("UPDATE free_request SET status='PROVISIONING_UNKNOWN'");
        assertEquals("HOSTING_RENT_INTENT_CONFLICT", assertThrows(HostingRentApplicationException.class,
                () -> confirm(UUID.randomUUID().toString(), "5")).code());
        assertEquals(5, version()); verifyNoInteractions(ledger);
    }
    @Test void lateInsertFailureRollsBackVersionAndNeverSignalsOrMovesFunds() {
        doThrow(new IllegalStateException("fixture late insert failure")).when(rent).insertReprovision(any());
        assertThrows(IllegalStateException.class, () -> confirm(KEY, "4"));
        assertEquals(4, version()); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM free_request", Integer.class));
        verifyNoInteractions(ledger, reconciler);
    }
    @Test void expiryAndWrongCanonicalBindingFailBeforeAcceptance() {
        jdbc.update("UPDATE lease_root SET paid_through=1");
        assertEquals("HOSTING_RENT_LEASE_CONFLICT", assertThrows(HostingRentApplicationException.class, () -> confirm(KEY, "4")).code());
        jdbc.update("UPDATE lease_root SET paid_through=?", paidThrough);
        when(identities.requireRegistrationIdentityInScope(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AgentIdentityRegistryEntity().setCanonicalAgentId("wrong-agent").setBindingId(17L));
        assertThrows(HostingRentApplicationException.class, () -> confirm(KEY, "4"));
        assertEquals(4, version()); verifyNoInteractions(ledger, reconciler);
    }
}
