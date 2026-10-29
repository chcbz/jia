package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentTaskBountyBootstrapOutboxSchemaInitializer;
import cn.jia.agent.config.AgentTaskRequirementSnapshotSchemaInitializer;
import cn.jia.agent.dao.impl.AgentTaskBountyBootstrapOutboxDaoImpl;
import cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapOutboxEntity;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO;
import cn.jia.agent.mapper.AgentTaskBountyBootstrapOutboxMapper;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.skill.SkillManagementMySqlFixture;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Uses the existing worker-owned MySQL lifecycle, never a configured application DB. */
class TaskBootstrapTenantIsolationMySqlTest extends SkillManagementMySqlFixture {
    private String database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private AgentTaskBountyBootstrapOutboxDaoImpl dao;
    private AgentTaskRequirementSnapshotServiceImpl snapshots;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setUp() throws Exception {
        database = "cyf_tenant_bootstrap_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        var source = source(database);
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        script("db/agent-task-bounty-bootstrap-outbox-v1.sql");
        script("db/agent-task-requirement-snapshot-v1.sql");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskBountyBootstrapOutboxMapper.class);
        var global = new GlobalConfig(); global.setBanner(false);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration); factory.setGlobalConfig(global);
        dao = new AgentTaskBountyBootstrapOutboxDaoImpl(new SqlSessionTemplate(
                Objects.requireNonNull(factory.getObject())).getMapper(AgentTaskBountyBootstrapOutboxMapper.class));
        snapshots = new AgentTaskRequirementSnapshotServiceImpl(jdbc, mock(AgentTaskMutationTransaction.class));
    }

    @AfterEach void dropOwnedDatabase() {
        if (database != null && database.matches("cyf_tenant_bootstrap_[0-9a-f]{32}"))
            admin.execute("DROP DATABASE `" + database + "`");
    }

    @Test void processDiscoveryDoesNotClaimOrQuarantineAnotherTenantOrClient() throws Exception {
        seed("1", "client", "gray", "same-action"); // First in claim order, must not consume production capacity.
        seed("1", "other-client", "other", "same-action");
        seed("0", "client", "production", "same-action");
        var production = service(false, null, null);
        var gray = service(true, "1", "client");
        var beforeGray = row("gray"); var beforeOther = row("other");
        var claimed = production.claimNextAvailable("production-worker", 1000);
        assertNotNull(claimed); assertEquals("production", claimed.bootstrapId());
        assertEquals(beforeGray, row("gray")); assertEquals(beforeOther, row("other"));
        assertNull(production.claimNextAvailable("production-worker", 1000));
        var beforeProduction = row("production");
        var grayClaim = gray.claimNextAvailable("gray-worker", 1000);
        assertNotNull(grayClaim); assertEquals("gray", grayClaim.bootstrapId());
        assertEquals(beforeProduction, row("production")); assertEquals(beforeOther, row("other"));
        assertNull(gray.claimNextAvailable("gray-worker", 1000));
        assertThrows(IllegalArgumentException.class, () -> gray.claimNext(scope("0", "client"), "worker", 1001));
        assertThrows(IllegalArgumentException.class, () -> production.claimNext(scope("1", "client"), "worker", 1001));
        assertThrows(IllegalArgumentException.class, () -> gray.claimNext(scope("1", "other-client"), "worker", 1001));
        var command = admitted(grayClaim);
        assertThrows(IllegalArgumentException.class, () -> production.reconcile(scope("1", "client"), command, 1002));
        assertEquals("ADMITTED", gray.reconcile(scope("1", "client"), command, 1002).status());
        assertEquals(beforeProduction, row("production"));
        assertEquals(beforeOther, row("other"));
        // Expired production lease is still invisible to the gray discovery/recovery path.
        assertNull(gray.claimNextAvailable("gray-worker", claimed.leaseUntil() + 1));
        assertEquals(beforeProduction, row("production"));
        var recovered = production.claimNextAvailable("new-production-worker", claimed.leaseUntil() + 1);
        assertNotNull(recovered); assertEquals(claimed.claimAttempt() + 1, recovered.claimAttempt());
        assertThrows(IllegalStateException.class, () -> production.reconcile(scope("0", "client"), admitted(claimed), claimed.leaseUntil()+2));
    }

    @Test void missingGraySnapshotQuarantinesOnlyOwnRowAndRollsBackInvalidScope() throws Exception {
        seed("0", "client", "production", "action");
        seed("1", "client", "gray", "action");
        var before = row("production");
        jdbc.update("DELETE FROM agent_task_requirement_snapshot WHERE tenant_id='1'");
        assertNull(service(true,"1","client").claimNextAvailable("gray-worker",1000));
        assertEquals("DEAD", row("gray").get("status"));
        assertEquals(before,row("production"));
        assertThrows(IllegalArgumentException.class, () -> dao.findClaimableAvailableForUpdate(" 1",null,1000));
        assertThrows(IllegalArgumentException.class, () -> dao.findClaimableAvailableForUpdate("1"," client",1000));
        assertEquals(before,row("production"));
    }

    @Test void immutableSnapshotReadsAreTenantExactAndWritesRemainClosed() throws Exception {
        seed("0", "client", "production", "action"); seed("1", "client", "gray", "action");
        assertEquals("Title 0", snapshots.read(scope("0","client"), "task", 1).title());
        assertEquals("Title 1", snapshots.read(scope("1","client"), "task", 1).title());
        assertThrows(IllegalStateException.class, () -> snapshots.read(scope("1","other"),"task",1));
        assertThrows(IllegalArgumentException.class, () -> snapshots.reconfirm(scope("1","client"),"task",0,"confirmation","title","body"));
        String productionHash = snapshots.read(scope("0","client"),"task",1).sha256();
        jdbc.update("UPDATE agent_task_requirement_snapshot SET content_sha256=? WHERE tenant_id='1'",productionHash);
        assertThrows(IllegalStateException.class, () -> snapshots.read(scope("1","client"),"task",1));
        assertEquals(productionHash,snapshots.read(scope("0","client"),"task",1).sha256());
    }

    @Test void migrationIsRepeatablePreservesProductionAndKeepsSchemaGuards() throws Exception {
        jdbc.execute("ALTER TABLE agent_task_bounty_bootstrap_outbox ADD CONSTRAINT chk_atbbo_tenant CHECK (tenant_id='0')");
        jdbc.execute("ALTER TABLE agent_task_requirement_snapshot DROP CHECK chk_atrs_scope, "
                + "ADD CONSTRAINT chk_atrs_scope CHECK (tenant_id='0' AND owner_jiacn<>'0')");
        seed("0","client","production","action");
        var before = row("production");
        var beforeSnapshot = jdbc.queryForMap("SELECT * FROM agent_task_requirement_snapshot");
        System.out.println("CYF_TASK_SNAPSHOT_CHECKS " + jdbc.queryForList(
                "SELECT constraint_name,check_clause FROM information_schema.check_constraints "
                + "WHERE constraint_schema=DATABASE() AND constraint_name IN ('chk_atrs_scope','chk_atrs_content')"));
        // Old production schema must still pass startup validation before any migration.
        new AgentTaskBountyBootstrapOutboxSchemaInitializer(jdbc).afterPropertiesSet();
        new AgentTaskRequirementSnapshotSchemaInitializer(jdbc).afterPropertiesSet();
        assertEquals(before, row("production"));
        script("db/agent-task-bootstrap-tenant-20261029.sql");
        script("db/agent-task-bootstrap-tenant-20261029.sql");
        assertEquals(before,row("production"));
        assertEquals(beforeSnapshot,jdbc.queryForMap("SELECT * FROM agent_task_requirement_snapshot"));
        new AgentTaskBountyBootstrapOutboxSchemaInitializer(jdbc).afterPropertiesSet();
        new AgentTaskRequirementSnapshotSchemaInitializer(jdbc).afterPropertiesSet();
        seed("1","client","gray","action");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "UPDATE agent_task_requirement_snapshot SET owner_jiacn='0' WHERE tenant_id='1'"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "UPDATE agent_task_bounty_bootstrap_outbox SET attempt_count=-1 WHERE tenant_id='1'"));
        assertEquals(before,row("production"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "UPDATE agent_task_requirement_snapshot SET revision=0 WHERE tenant_id='1'"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "UPDATE agent_task_requirement_snapshot SET content_sha256=? WHERE tenant_id='1'", "G".repeat(64)));
        jdbc.execute("ALTER TABLE agent_task_requirement_snapshot DROP CHECK chk_atrs_content, "
                + "ADD CONSTRAINT chk_atrs_content CHECK (revision>=0)");
        assertThrows(IllegalStateException.class,
                () -> new AgentTaskRequirementSnapshotSchemaInitializer(jdbc).afterPropertiesSet());
    }

    private AgentTaskBountyBootstrapOutboxServiceImpl service(boolean fixed,String tenant,String client) {
        return new AgentTaskBountyBootstrapOutboxServiceImpl(dao,json,transactions,snapshots,
                new FixedTenantAdmissionPolicy("0",fixed,tenant,client));
    }
    private static AgentTaskExecutionGrantService.Scope scope(String tenant,String client) {
        return new AgentTaskExecutionGrantService.Scope(tenant,client,"owner");
    }
    private static AgentTaskBountyBootstrapReconcileDTO admitted(AgentTaskBountyBootstrapClaimDTO claim) {
        return new AgentTaskBountyBootstrapReconcileDTO(claim.bootstrapId(),claim.outboxVersion(),
                claim.leaseOwner(),claim.claimAttempt(),AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED,
                "conversation","request",null);
    }
    private Map<String,Object> row(String id) {
        return jdbc.queryForMap("SELECT * FROM agent_task_bounty_bootstrap_outbox WHERE bootstrap_id=?",id);
    }
    private void script(String path) {
        new ResourceDatabasePopulator(new ClassPathResource(path)).execute(Objects.requireNonNull(jdbc.getDataSource()));
    }
    private void seed(String tenant,String client,String id,String action) throws Exception {
        String referenceHash = AgentTaskBountyBootstrapPayload.referenceHash(json,List.of());
        var row = new AgentTaskBountyBootstrapOutboxEntity().setBootstrapId(id)
                .setOwnerJiacn("owner").setTaskId("task").setSourceBusinessActionId(action)
                .setPayloadHash(AgentTaskBountyBootstrapPayload.payloadHash(tenant,client,"owner","task",action,
                        1,0,"agent","grant",1,"DELIBERATE",referenceHash))
                .setRequirementRevision(1L).setRequirementAnchor(AgentTaskBountyBootstrapPayload.REQUIREMENT_ANCHOR)
                .setAssignmentRevision(0L).setTargetAgentId("agent").setGrantId("grant").setGrantVersion(1L)
                .setPermittedOperation("DELIBERATE").setReferenceSummaryJson("[]").setReferenceSummarySha256(referenceHash)
                .setStatus("PENDING").setAttemptCount(0).setVersion(0L).setCreatedAt(1L);
        row.setTenantId(tenant); row.setClientId(client);
        var digest = MessageDigest.getInstance("SHA-256");
        for (String field : List.of("TASK_REQUIREMENT_SNAPSHOT_V1",tenant,client,"owner","task","1","CREATE","Title "+tenant)) {
            byte[] bytes=field.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
        }
        digest.update((byte)0);
        String hash = HexFormat.of().formatHex(digest.digest());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            dao.insert(row);
            jdbc.update("INSERT INTO agent_task_requirement_snapshot "
                    + "(tenant_id,client_id,owner_jiacn,task_id,revision,confirmation_id,task_version_at_confirmation,title,content_sha256,source,created_at) "
                    + "VALUES (?,?,'owner','task',1,'CREATE',0,?,?,'CREATE',1)",tenant,client,"Title "+tenant,hash);
        });
    }
}
