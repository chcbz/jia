package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentTaskProviderCostConsentSchemaInitializer;
import cn.jia.agent.config.ControlledImageBridgeSchemaInitializer;
import cn.jia.agent.config.ControlledImageExecutionSchemaInitializer;
import cn.jia.agent.config.ControlledImageProviderProperties;
import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Isolated MySQL 8 DDL, uniqueness and concurrent reserve/consume evidence; no Provider is called. */
@EnabledIfEnvironmentVariable(named="MMD_U1_REFERENCE_MYSQL_URL",matches=".+")
class ControlledImageBridgeMySqlTest {
    private JdbcTemplate admin,jdbc;
    private DriverManagerDataSource source;
    private String database,namespace;

    @BeforeEach void setUp() {
        String url=required("MMD_U1_REFERENCE_MYSQL_URL");
        String user=required("MMD_U1_REFERENCE_MYSQL_USER");
        String password=System.getenv("MMD_U1_REFERENCE_MYSQL_PASSWORD");
        String prefix=required("MMD_U1_REFERENCE_MYSQL_DATABASE_PREFIX");
        if(password==null||!url.startsWith("jdbc:mysql://")||!prefix.matches("[A-Za-z0-9_]{1,20}")
                ||!"true".equals(required("MMD_U1_REFERENCE_MYSQL_ISOLATED_FIXTURE")))
            throw new IllegalStateException("isolated MySQL acknowledgement required");
        namespace=prefix+"_ci_bridge_";
        database=namespace+UUID.randomUUID().toString().substring(0,8);
        admin=new JdbcTemplate(ds(url,user,password));
        assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("8."));
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        source=ds(databaseUrl(url,database),user,password);jdbc=new JdbcTemplate(source);
        createExecutionBase();
        new AgentTaskProviderCostConsentSchemaInitializer(jdbc).afterPropertiesSet();
        new ControlledImageBridgeSchemaInitializer(jdbc).afterPropertiesSet();
        new ControlledImageExecutionSchemaInitializer(jdbc).afterPropertiesSet();
    }

    @AfterEach void tearDown() {
        if(admin==null||database==null)return;
        if(!database.startsWith(namespace))throw new IllegalStateException("unowned database");
        admin.execute("DROP DATABASE IF EXISTS `"+database+"`");
    }

    @Test void actualMySqlJsonRoundTripPreservesNonemptyAssignmentReadInputs() throws Exception {
        String original = "[{\"fileId\":\"reference-v2\",\"version\":2,\"purpose\":\"REFERENCE\","
                + "\"contentMimeType\":\"image/png\",\"byteLength\":1863523,\"contentHash\":\""
                + "a".repeat(64) + "\"}]";
        String stored = jdbc.queryForObject("SELECT CAST(? AS JSON)", String.class, original);
        assertNotEquals(original, stored, "real MySQL changes JSON formatting/key order");
        var reader = new AgentTaskDeliberationOperationReadServiceImpl(
                mock(AgentTaskExecutionGrantDao.class), mock(AgentTaskBountyBootstrapOutboxDao.class),
                mock(ControlledImageBridgeOperationDao.class), mock(AgentTaskRequirementSnapshotService.class),
                mock(AgentTaskMutationTransaction.class), new tools.jackson.databind.ObjectMapper());
        var inputs = AgentTaskDeliberationOperationReadServiceImpl.class
                .getDeclaredMethod("readInputs", String.class);
        inputs.setAccessible(true);
        @SuppressWarnings("unchecked")
        var actual = (List<AgentTaskDeliberationOperationReadService.InputSummary>) inputs.invoke(reader, stored);
        assertEquals(2, actual.getFirst().version());
        assertEquals(1863523, actual.getFirst().byteLength());
        assertEquals("a".repeat(64), actual.getFirst().contentHash());
        var operations = AgentTaskDeliberationOperationReadServiceImpl.class
                .getDeclaredMethod("readOperations", String.class);
        operations.setAccessible(true);
        String rawOperations = jdbc.queryForObject("SELECT CAST(? AS JSON)", String.class,
                "[\"EDIT_IMAGE\",\"GENERATE_IMAGE\"]");
        assertEquals(List.of("EDIT_IMAGE", "GENERATE_IMAGE"), operations.invoke(reader, rawOperations));
    }

    @Test void actualMySqlNormalizesExactChecksAndRejectsSameNamedWeakDefinitions() {
        assertDoesNotThrow(()->new ControlledImageBridgeSchemaInitializer(jdbc).afterPropertiesSet());
        assertDoesNotThrow(()->new ControlledImageExecutionSchemaInitializer(jdbc).afterPropertiesSet());
        jdbc.execute("ALTER TABLE agent_controlled_image_bridge_operation "
                +"DROP CHECK chk_acibo_locator, ADD CONSTRAINT chk_acibo_locator CHECK (1=1)");
        assertThrows(IllegalStateException.class,
                ()->new ControlledImageBridgeSchemaInitializer(jdbc).afterPropertiesSet());
        jdbc.execute("ALTER TABLE agent_controlled_image_bridge_operation "
                +"DROP CHECK chk_acibo_locator, ADD CONSTRAINT chk_acibo_locator "
                +"CHECK (authority_locator=CONCAT('mmd-ci-v1:',consent_id))");
        for(String weakened:java.util.List.of(
                "((execution_protocol_version=2 AND operation_grant_id IS NULL) OR (execution_protocol_version=3 AND operation_grant_id REGEXP '^wrong_[0-9a-f]{32}$'))",
                "(((execution_protocol_version=2 AND operation_grant_id IS NULL) OR (execution_protocol_version=3 AND operation_grant_id REGEXP '^opgrant_[0-9a-f]{32}$')) OR TRUE)")) {
            jdbc.execute("ALTER TABLE agent_controlled_image_bridge_operation DROP CHECK chk_acibo_protocol, ADD CONSTRAINT chk_acibo_protocol CHECK ("+weakened+")");
            assertThrows(IllegalStateException.class,
                    ()->new ControlledImageBridgeSchemaInitializer(jdbc).afterPropertiesSet());
        }
        jdbc.execute("ALTER TABLE agent_controlled_image_bridge_operation DROP CHECK chk_acibo_protocol, ADD CONSTRAINT chk_acibo_protocol CHECK ((execution_protocol_version=2 AND operation_grant_id IS NULL) OR (execution_protocol_version=3 AND operation_grant_id REGEXP '^opgrant_[0-9a-f]{32}$')) NOT ENFORCED");
        assertThrows(IllegalStateException.class,
                ()->new ControlledImageBridgeSchemaInitializer(jdbc).afterPropertiesSet());
        jdbc.execute("ALTER TABLE agent_controlled_image_bridge_operation DROP CHECK chk_acibo_protocol, ADD CONSTRAINT chk_acibo_protocol CHECK ((execution_protocol_version=2 AND operation_grant_id IS NULL) OR (execution_protocol_version=3 AND operation_grant_id REGEXP '^opgrant_[0-9a-f]{32}$'))");
        jdbc.execute("ALTER TABLE agent_personal_workspace_execution "
                +"DROP CHECK chk_pwex_controlled_consent, "
                +"ADD CONSTRAINT chk_pwex_controlled_consent CHECK (1=1)");
        assertThrows(IllegalStateException.class,
                ()->new ControlledImageExecutionSchemaInitializer(jdbc).afterPropertiesSet());
    }

    @Test void legacyBridgeCatalogUpgradesOncePreservesV2RowsAndIsIdempotent() {
        jdbc.execute("DROP TABLE agent_controlled_image_bridge_operation");
        createLegacyBridge();
        jdbc.update("""
                INSERT INTO agent_controlled_image_bridge_operation
                  (owner_jiacn,task_id,assignment_idempotency_key,wrapper_digest,consent_id,
                   expected_consent_version,grant_id,grant_version,assignment_revision,
                   authority_locator,created_at,tenant_id,client_id,create_time,update_time)
                VALUES ('owner','task','legacy-key',?,'consent_1234567890abcdef1234567890abcdef',
                        1,'grant',1,7,'mmd-ci-v1:consent_1234567890abcdef1234567890abcdef',
                        1,'0','client',1,1)
                ""","a".repeat(64));

        var initializer=new ControlledImageBridgeSchemaInitializer(jdbc);
        assertDoesNotThrow(initializer::afterPropertiesSet);
        assertDoesNotThrow(initializer::afterPropertiesSet);

        assertEquals(2,jdbc.queryForObject("SELECT execution_protocol_version FROM agent_controlled_image_bridge_operation WHERE assignment_idempotency_key='legacy-key'",Integer.class));
        assertNull(jdbc.queryForObject("SELECT operation_grant_id FROM agent_controlled_image_bridge_operation WHERE assignment_idempotency_key='legacy-key'",String.class));
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='agent_controlled_image_bridge_operation' AND index_name='uk_acibo_scope_operation_grant'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='agent_controlled_image_bridge_operation' AND constraint_name='chk_acibo_protocol' AND constraint_type='CHECK' AND enforced='YES'",Integer.class));
    }

    @Test void newTaskVersionZeroIssuesConsentAndCommitsProtocol3Bridge() {
        AgentTaskMetaEntity root=new AgentTaskMetaEntity().setTaskId("task-zero")
                .setTaskVersion(0L).setCurrentEventVersion(0L).setRewardStatus("open");
        root.setTenantId("0");root.setClientId("client");root.setOwnerJiacn("owner");
        AgentTaskMetaDao roots=mock(AgentTaskMetaDao.class);
        when(roots.findByTaskIdForUpdateInOwnerScope("0","client","owner","task-zero"))
                .thenReturn(root);
        AgentTaskMutationTransaction transactions=new AgentTaskMutationTransactionImpl(
                roots,new DataSourceTransactionManager(source));

        Map<String,AgentTaskExecutionGrantEntity> grantsByAction=new LinkedHashMap<>();
        Map<String,AgentTaskExecutionGrantEntity> grantsById=new LinkedHashMap<>();
        AgentTaskExecutionGrantDao grantRows=mock(AgentTaskExecutionGrantDao.class);
        when(grantRows.findByActionForUpdate(anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(invocation->grantsByAction.get(invocation.getArgument(3)));
        when(grantRows.findByGrantForUpdate(anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(invocation->grantsById.get(invocation.getArgument(4)));
        when(grantRows.findByGrant(anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(invocation->grantsById.get(invocation.getArgument(4)));
        when(grantRows.findActiveByTask(anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(invocation->grantsById.values().stream()
                        .filter(row->invocation.getArgument(3).equals(row.getTaskId())
                                &&"ACTIVE".equals(row.getState())).findFirst().orElse(null));
        when(grantRows.latestAssignmentEventJson("0","client","owner","task-zero"))
                .thenReturn("{\"resultVersion\":1}");
        doAnswer(invocation->{
            AgentTaskExecutionGrantEntity row=invocation.getArgument(0);
            grantsByAction.put(row.getSourceBusinessActionId(),row);grantsById.put(row.getGrantId(),row);
            return null;
        }).when(grantRows).insert(any());
        when(grantRows.supersedeActiveForTask(anyString(),anyString(),anyString(),anyString(),anyLong()))
                .thenReturn(0);
        when(grantRows.setCostAuthorizationRef(eq("0"),eq("client"),eq("owner"),eq("task-zero"),
                anyString(),eq(1L),anyString())).thenAnswer(invocation->{
                    AgentTaskExecutionGrantEntity row=grantsById.get(invocation.getArgument(4));
                    if(row==null||row.getCostAuthorizationRef()!=null)return false;
                    row.setCostAuthorizationRef(invocation.getArgument(6));return true;
                });

        Map<String,AgentTaskBountyBootstrapOutboxEntity> bootstrapByAction=new LinkedHashMap<>();
        AgentTaskBountyBootstrapOutboxDao bootstrapRows=mock(AgentTaskBountyBootstrapOutboxDao.class);
        when(bootstrapRows.findByActionForUpdate(anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(invocation->bootstrapByAction.get(invocation.getArgument(3)));
        doAnswer(invocation->{
            AgentTaskBountyBootstrapOutboxEntity row=invocation.getArgument(0);
            bootstrapByAction.put(row.getSourceBusinessActionId(),row);return null;
        }).when(bootstrapRows).insert(any());

        AgentLegacyTaskCompatibilityService legacy=mock(AgentLegacyTaskCompatibilityService.class);
        when(legacy.resolveAgentId("0","client","owner","agent-zero")).thenReturn("agent-zero");
        when(legacy.assignResolvedVersionedWithLockedTask(eq("0"),eq("client"),eq("owner"),
                eq("task-zero"),eq(List.of("agent-zero")),eq(false),eq(0L),any(),same(root)))
                .thenAnswer(invocation->{
                    AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator validator=
                            invocation.getArgument(7);
                    validator.validate(root,List.of("agent-zero"));
                    root.setAssignedAgentId("agent-zero").setTaskVersion(1L).setCurrentEventVersion(1L);
                    return new AgentLegacyTaskCompatibilityService.AssignOutcome(
                            List.of("agent-zero"),true,"event-zero",1L);
                });
        AgentIdentityService identities=mock(AgentIdentityService.class);
        when(identities.lockActiveCanonicalAgentIdsInScope(
                "0","client","owner",List.of("agent-zero"))).thenReturn(List.of("agent-zero"));
        AgentTaskRequirementSnapshotService requirements=mock(AgentTaskRequirementSnapshotService.class);
        var requirementSnapshot = new AgentTaskRequirementSnapshotService.Snapshot(
                "0","client","owner","task-zero",1L,
                "Draw a bird","Generate one image","a".repeat(64),"CREATE");
        when(requirements.requireCurrent(any(),eq("task-zero"),eq(1L)))
                .thenReturn(requirementSnapshot);
        when(requirements.read(any(),eq("task-zero"),eq(1L)))
                .thenReturn(requirementSnapshot);
        var json=new tools.jackson.databind.ObjectMapper();
        var grants=new AgentTaskExecutionGrantServiceImpl(grantRows,bootstrapRows,
                mock(PersonalWorkspaceTaskLinkDao.class),mock(PersonalWorkspaceDao.class),legacy,identities,
                transactions,json,requirements);

        AgentTaskProviderCostConsentDao consentRows=mysqlConsentDao();
        ControlledImageProviderOperatorPolicy policies=operatorPolicy();
        var consents=new AgentTaskProviderCostConsentServiceImpl(
                consentRows,grants,transactions,policies,json);
        AgentTaskAssignDTO assignment=zeroVersionAssignment();
        AgentTaskProviderCostConsentIssueDTO issue=new AgentTaskProviderCostConsentIssueDTO();
        issue.setSchemaVersion(1);issue.setAssignmentIdempotencyKey("assignment-zero");
        issue.setAssignment(assignment);issue.setProviderBinding(
                new AgentTaskProviderCostConsentIssueDTO.ProviderBinding("binding-zero","1"));
        issue.setAcknowledgement("UNPRICED_EXTERNAL_ACCOUNT_ONE_IMAGE_REQUEST_ATTEMPT");
        var consent=consents.issue(new AgentTaskProviderCostConsentService.Scope("0","client","owner"),
                "task-zero","consent-zero",issue).receipt();
        assertEquals("ISSUED",consent.state());assertEquals("1",consent.version());
        assertEquals(0L,root.getTaskVersion(),"consent preview must not consume task CAS");

        @SuppressWarnings("unchecked") ObjectProvider<ControlledImageExecutionSessionLookup> sessions=
                mock(ObjectProvider.class);
        ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup declarationLookup=scope->
                new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.Declaration(
                        ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY,
                        "runtime-zero",List.of("GENERATE_IMAGE","EDIT_IMAGE"),
                        "CONTROLLED_IMAGE_HTTP_V1","binding-zero",1L,"model-zero",16,1,1);
        @SuppressWarnings("unchecked")
        ObjectProvider<ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup> declarations=
                mock(ObjectProvider.class);
        when(declarations.getIfUnique()).thenReturn(declarationLookup);
        var authority=new ControlledImageGrantAuthority(
                grantRows,consentRows,policies,sessions,declarations);
        grants.setControlledAuthority(authority);
        ControlledImageBridgeOperationDao operations=mysqlBridgeOperationDao();
        var bridge=new ControlledImagePointAndStartServiceImpl(operations,grantRows,consentRows,
                grants,consents,authority,transactions,json);

        var result=bridge.submit(new AgentTaskExecutionGrantService.Scope("0","client","owner"),
                "task-zero","assignment-zero",new ControlledImagePointAndStartDTO.Request(
                        1,assignment,new ControlledImagePointAndStartDTO.ProviderConsent(
                                consent.consentId(),consent.version())));

        assertFalse(result.replay());assertEquals("BOUND",result.receipt().providerConsent().state());
        assertEquals(1L,root.getTaskVersion());assertEquals("agent-zero",root.getAssignedAgentId());
        assertEquals("3:1:0",jdbc.queryForObject("""
                SELECT CONCAT(execution_protocol_version,':',assignment_revision,':',expected_consent_version-1)
                  FROM agent_controlled_image_bridge_operation
                 WHERE task_id='task-zero' AND assignment_idempotency_key='assignment-zero'
                """,String.class));
        assertEquals("BOUND:2:0:1",jdbc.queryForObject("""
                SELECT CONCAT(state,':',version,':',task_version,':',bound_assignment_revision)
                  FROM agent_task_provider_cost_consent
                 WHERE task_id='task-zero' AND consent_id=?
                """,String.class,consent.consentId()));
        var readService = new AgentTaskDeliberationOperationReadServiceImpl(
                grantRows,bootstrapRows,operations,requirements,transactions,json);
        var projected = readService.read(new AgentTaskExecutionGrantService.Scope(
                "0","client","owner"),"task-zero","assignment-zero");
        assertEquals(1,projected.assignmentRevision());
        assertEquals("ACTIVE",projected.grantState());
        assertEquals("GENERATE_IMAGE",projected.initialOperation());
        assertTrue(projected.currentAssignment());
    }

    @Test void twoExecutionRunsCanReserveOnlyOneConsent() throws Exception {
        insertConsent("consent_1234567890abcdef1234567890abcdef","BOUND",2,null,null);
        assertThrows(DataAccessException.class,()->jdbc.update(
                "UPDATE agent_task_provider_cost_consent SET request_digest=? WHERE consent_id=?",
                "A"+"a".repeat(63),"consent_1234567890abcdef1234567890abcdef"));
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->reserveAttempt(start,"execution-a","run-a"));
            var second=executor.submit(()->reserveAttempt(start,"execution-b","run-b"));
            start.countDown();
            assertEquals(1,first.get(10,TimeUnit.SECONDS)+second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_personal_workspace_execution WHERE controlled_consent_id=?",
                Integer.class,"consent_1234567890abcdef1234567890abcdef"));
        assertEquals("RESERVED:3",jdbc.queryForObject(
                "SELECT CONCAT(state,':',version) FROM agent_task_provider_cost_consent WHERE consent_id=?",
                String.class,"consent_1234567890abcdef1234567890abcdef"));
        assertThrows(DataAccessException.class,()->insertExecution("execution-c","run-c",
                "consent_1234567890abcdef1234567890abcdef"));
    }

    @Test void concurrentProviderStartCommitsExactlyOneConsumeAndMarker() throws Exception {
        String consent="consent_abcdefabcdefabcdefabcdefabcdefab";
        insertConsent(consent,"RESERVED",3,"execution-a","run-a");
        insertExecution("execution-a","run-a",consent);
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->startAttempt(start,consent));
            var second=executor.submit(()->startAttempt(start,consent));
            start.countDown();
            assertEquals(1,first.get(10,TimeUnit.SECONDS)+second.get(10,TimeUnit.SECONDS));
        }
        assertEquals("CONSUMED:4",jdbc.queryForObject(
                "SELECT CONCAT(state,':',version) FROM agent_task_provider_cost_consent WHERE consent_id=?",
                String.class,consent));
        assertEquals(1,jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_personal_workspace_execution "
                        +"WHERE controlled_consent_id=? AND conversation_provider_started_at IS NOT NULL "
                        +"AND conversation_provider_lease_version=1",Integer.class,consent));
        assertEquals(0,startAttempt(new CountDownLatch(0),consent));
    }

    private int reserveAttempt(CountDownLatch start,String execution,String run) {
        await(start);
        try {
            return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{
                insertExecution(execution,run,"consent_1234567890abcdef1234567890abcdef");
                int changed=jdbc.update("""
                        UPDATE agent_task_provider_cost_consent
                           SET state='RESERVED',version=version+1,reserved_execution_id=?,reserved_run_id=?,update_time=2
                         WHERE consent_id=? AND state='BOUND' AND version=2
                        """,execution,run,"consent_1234567890abcdef1234567890abcdef");
                if(changed!=1)throw new IllegalStateException("reserve lost after execution insert");
                return 1;
            });
        } catch (DataAccessException|IllegalStateException lost) { return 0; }
    }

    private int startAttempt(CountDownLatch start,String consent) {
        await(start);
        return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{
            int consumed=jdbc.update("""
                    UPDATE agent_task_provider_cost_consent
                       SET state='CONSUMED',version=version+1,consumed_lease_id='pwe_lease_fixture',
                           consumed_at=2,update_time=2
                     WHERE consent_id=? AND state='RESERVED' AND version=3
                    """,consent);
            if(consumed==0)return 0;
            int marker=jdbc.update("""
                    UPDATE agent_personal_workspace_execution
                       SET conversation_provider_started_at=2,conversation_provider_lease_version=1,
                           update_time=2
                     WHERE tenant_id='0' AND client_id='client' AND owner_jiacn='owner'
                       AND task_id='task' AND run_id='run-a' AND execution_id='execution-a'
                       AND controlled_consent_id=? AND execution_mode='CONVERSATION'
                       AND execution_state='QUEUED' AND permitted_operation='GENERATE_IMAGE'
                       AND output_content_mime_type='image/png' AND conversation_lease_version=1
                       AND conversation_provider_started_at IS NULL
                       AND conversation_provider_lease_version IS NULL
                       AND CAST(controlled_consent_id AS BINARY)=CAST(? AS BINARY)
                       AND OCTET_LENGTH(controlled_consent_id)=OCTET_LENGTH(?)
                    """,consent,consent,consent);
            if(marker!=1)throw new IllegalStateException("provider marker CAS failed");
            return 1;
        });
    }

    private void createLegacyBridge() {
        jdbc.execute("""
                CREATE TABLE agent_controlled_image_bridge_operation (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                  task_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                  assignment_idempotency_key VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                  wrapper_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                  consent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                  expected_consent_version BIGINT NOT NULL,
                  grant_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                  grant_version BIGINT NOT NULL,
                  assignment_revision BIGINT NOT NULL,
                  authority_locator VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                  created_at BIGINT NOT NULL,
                  tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                  client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                  create_time BIGINT DEFAULT NULL,
                  update_time BIGINT DEFAULT NULL,
                  PRIMARY KEY(id),
                  UNIQUE KEY uk_acibo_scope_key(tenant_id,client_id,owner_jiacn,task_id,assignment_idempotency_key),
                  UNIQUE KEY uk_acibo_scope_consent(tenant_id,client_id,owner_jiacn,task_id,consent_id),
                  UNIQUE KEY uk_acibo_scope_grant(tenant_id,client_id,owner_jiacn,task_id,grant_id),
                  CONSTRAINT chk_acibo_scope CHECK(tenant_id='0' AND owner_jiacn<>'0'),
                  CONSTRAINT chk_acibo_hash CHECK(wrapper_digest REGEXP '^[0-9a-f]{64}$'),
                  CONSTRAINT chk_acibo_consent CHECK(consent_id REGEXP '^consent_[0-9a-f]{32}$'),
                  CONSTRAINT chk_acibo_locator CHECK(authority_locator=CONCAT('mmd-ci-v1:',consent_id)),
                  CONSTRAINT chk_acibo_versions CHECK(expected_consent_version>0 AND grant_version>0 AND assignment_revision>=0),
                  CONSTRAINT chk_acibo_time CHECK(created_at>0)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
    }

    private void createExecutionBase() {
        jdbc.execute("""
                CREATE TABLE agent_personal_workspace_execution (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  execution_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                  owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                  task_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                  run_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                  execution_mode VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
                  execution_state VARCHAR(30) COLLATE utf8mb4_0900_bin NOT NULL,
                  permitted_operation VARCHAR(40) COLLATE utf8mb4_0900_bin DEFAULT NULL,
                  output_content_mime_type VARCHAR(127) COLLATE utf8mb4_0900_bin NOT NULL,
                  conversation_lease_version BIGINT DEFAULT NULL,
                  conversation_provider_started_at BIGINT DEFAULT NULL,
                  conversation_provider_lease_version BIGINT DEFAULT NULL,
                  tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                  client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                  update_time BIGINT DEFAULT NULL,
                  PRIMARY KEY(id),
                  UNIQUE KEY uk_pwex_fixture_execution(tenant_id,client_id,owner_jiacn,execution_id),
                  UNIQUE KEY uk_pwex_fixture_run(tenant_id,client_id,owner_jiacn,task_id,run_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
    }

    private void insertExecution(String execution,String run,String consent) {
        jdbc.update("""
                INSERT INTO agent_personal_workspace_execution
                  (execution_id,owner_jiacn,task_id,run_id,execution_mode,execution_state,
                   permitted_operation,output_content_mime_type,conversation_lease_version,
                   conversation_provider_started_at,conversation_provider_lease_version,
                   controlled_consent_id,tenant_id,client_id,update_time)
                VALUES (?,'owner','task',?,'CONVERSATION','QUEUED','GENERATE_IMAGE','image/png',1,
                        NULL,NULL,?,'0','client',1)
                """,execution,run,consent);
    }

    private void insertConsent(String consent,String state,long version,String execution,String run) {
        boolean reserved=execution!=null;
        jdbc.update("""
                INSERT INTO agent_task_provider_cost_consent
                  (consent_id,owner_jiacn,task_id,target_agent_id,idempotency_key,request_digest,
                   assignment_idempotency_key,assignment_base_hash,task_version,requirement_revision,
                   requirement_sha256,input_snapshot_digest,input_snapshot_json,provider_lane,binding_id,
                   binding_epoch,model_id,custody,operator_issuer,operator_policy_revision,pricing_mode,
                   max_outbound_request_attempts,expires_at,state,version,bound_grant_id,bound_grant_version,
                   bound_assignment_revision,reserved_execution_id,reserved_run_id,consumed_lease_id,consumed_at,
                   revoke_idempotency_key,revoke_request_digest,revoked_at,created_at,tenant_id,client_id,
                   create_time,update_time)
                VALUES (?,'owner','task','agent',?,?,'assignment-key',?,6,3,?,?,JSON_ARRAY(),
                        'CONTROLLED_IMAGE_HTTP_V1','binding',1,'model','OPERATOR_TEMPLATE','operator',
                        'policy-r1','UNPRICED_EXTERNAL_ACCOUNT',1,9000000000000,?,?,
                        'grant',1,7,?,?,NULL,NULL,NULL,NULL,NULL,1,'0','client',1,1)
                """,consent,"issue-"+consent.substring(8,16),"a".repeat(64),"b".repeat(64),
                "c".repeat(64),"d".repeat(64),state,version,
                reserved?execution:null,reserved?run:null);
    }

    private AgentTaskProviderCostConsentDao mysqlConsentDao() {
        AgentTaskProviderCostConsentDao rows=mock(AgentTaskProviderCostConsentDao.class);
        when(rows.findByIdempotencyKeyForUpdate(anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(invocation->consentBy("idempotency_key",invocation.getArgument(4),true));
        when(rows.findByConsentForUpdate(anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(invocation->consentBy("consent_id",invocation.getArgument(4),true));
        when(rows.findByConsent(anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(invocation->consentBy("consent_id",invocation.getArgument(4),false));
        doAnswer(invocation->{
            AgentTaskProviderCostConsentEntity row=invocation.getArgument(0);
            int inserted=jdbc.update("""
                    INSERT INTO agent_task_provider_cost_consent
                      (consent_id,owner_jiacn,task_id,target_agent_id,idempotency_key,request_digest,
                       assignment_idempotency_key,assignment_base_hash,task_version,requirement_revision,
                       requirement_sha256,input_snapshot_digest,input_snapshot_json,provider_lane,binding_id,
                       binding_epoch,model_id,custody,operator_issuer,operator_policy_revision,pricing_mode,
                       max_outbound_request_attempts,expires_at,state,version,created_at,tenant_id,client_id,
                       create_time,update_time)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS JSON),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL)
                    """,row.getConsentId(),row.getOwnerJiacn(),row.getTaskId(),row.getTargetAgentId(),
                    row.getIdempotencyKey(),row.getRequestDigest(),row.getAssignmentIdempotencyKey(),
                    row.getAssignmentBaseHash(),row.getTaskVersion(),row.getRequirementRevision(),
                    row.getRequirementSha256(),row.getInputSnapshotDigest(),row.getInputSnapshotJson(),
                    row.getProviderLane(),row.getBindingId(),row.getBindingEpoch(),row.getModelId(),
                    row.getCustody(),row.getOperatorIssuer(),row.getOperatorPolicyRevision(),
                    row.getPricingMode(),row.getMaxOutboundRequestAttempts(),row.getExpiresAt(),
                    row.getState(),row.getVersion(),row.getCreatedAt(),row.getTenantId(),row.getClientId());
            assertEquals(1,inserted);return null;
        }).when(rows).insert(any());
        when(rows.bind(any(),anyLong())).thenAnswer(invocation->{
            AgentTaskProviderCostConsentEntity row=invocation.getArgument(0);
            long version=invocation.getArgument(1);
            return jdbc.update("""
                    UPDATE agent_task_provider_cost_consent
                       SET state='BOUND',version=version+1,bound_grant_id=?,bound_grant_version=?,
                           bound_assignment_revision=?,update_time=?
                     WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND consent_id=?
                       AND state='ISSUED' AND version=?
                    """,row.getBoundGrantId(),row.getBoundGrantVersion(),row.getBoundAssignmentRevision(),
                    row.getUpdateTime(),row.getTenantId(),row.getClientId(),row.getOwnerJiacn(),
                    row.getTaskId(),row.getConsentId(),version)==1;
        });
        return rows;
    }

    private AgentTaskProviderCostConsentEntity consentBy(String column,Object value,boolean lock) {
        if(!List.of("idempotency_key","consent_id").contains(column))throw new IllegalArgumentException(column);
        List<AgentTaskProviderCostConsentEntity> rows=jdbc.query(
                "SELECT * FROM agent_task_provider_cost_consent WHERE tenant_id='0' AND client_id='client' "
                        +"AND owner_jiacn='owner' AND task_id='task-zero' AND "+column+"=? LIMIT 1"
                        +(lock?" FOR UPDATE":""),(result,index)->{
                    AgentTaskProviderCostConsentEntity row=new AgentTaskProviderCostConsentEntity()
                            .setConsentId(result.getString("consent_id"))
                            .setOwnerJiacn(result.getString("owner_jiacn"))
                            .setTaskId(result.getString("task_id"))
                            .setTargetAgentId(result.getString("target_agent_id"))
                            .setIdempotencyKey(result.getString("idempotency_key"))
                            .setRequestDigest(result.getString("request_digest"))
                            .setAssignmentIdempotencyKey(result.getString("assignment_idempotency_key"))
                            .setAssignmentBaseHash(result.getString("assignment_base_hash"))
                            .setTaskVersion(result.getLong("task_version"))
                            .setRequirementRevision(result.getLong("requirement_revision"))
                            .setRequirementSha256(result.getString("requirement_sha256"))
                            .setInputSnapshotDigest(result.getString("input_snapshot_digest"))
                            .setInputSnapshotJson(result.getString("input_snapshot_json"))
                            .setProviderLane(result.getString("provider_lane"))
                            .setBindingId(result.getString("binding_id"))
                            .setBindingEpoch(result.getLong("binding_epoch"))
                            .setModelId(result.getString("model_id"))
                            .setCustody(result.getString("custody"))
                            .setOperatorIssuer(result.getString("operator_issuer"))
                            .setOperatorPolicyRevision(result.getString("operator_policy_revision"))
                            .setPricingMode(result.getString("pricing_mode"))
                            .setMaxOutboundRequestAttempts(result.getInt("max_outbound_request_attempts"))
                            .setExpiresAt(result.getLong("expires_at"))
                            .setState(result.getString("state")).setVersion(result.getLong("version"))
                            .setBoundGrantId(result.getString("bound_grant_id"))
                            .setCreatedAt(result.getLong("created_at"));
                    long grantVersion=result.getLong("bound_grant_version");
                    if(!result.wasNull())row.setBoundGrantVersion(grantVersion);
                    long assignmentRevision=result.getLong("bound_assignment_revision");
                    if(!result.wasNull())row.setBoundAssignmentRevision(assignmentRevision);
                    row.setTenantId(result.getString("tenant_id"));row.setClientId(result.getString("client_id"));
                    return row;
                },value);
        return rows.isEmpty()?null:rows.getFirst();
    }

    private ControlledImageBridgeOperationDao mysqlBridgeOperationDao() {
        ControlledImageBridgeOperationDao rows=mock(ControlledImageBridgeOperationDao.class);
        when(rows.lock(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(null);
        when(rows.find(anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(invocation->bridgeBy(invocation.getArgument(0),invocation.getArgument(1),
                        invocation.getArgument(2),invocation.getArgument(3),invocation.getArgument(4)));
        doAnswer(invocation->{
            ControlledImageBridgeOperationEntity row=invocation.getArgument(0);
            assertEquals(1,jdbc.update("""
                    INSERT INTO agent_controlled_image_bridge_operation
                      (owner_jiacn,task_id,assignment_idempotency_key,wrapper_digest,consent_id,
                       expected_consent_version,grant_id,grant_version,assignment_revision,authority_locator,
                       execution_protocol_version,operation_grant_id,created_at,tenant_id,client_id,
                       create_time,update_time)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL)
                    """,row.getOwnerJiacn(),row.getTaskId(),row.getAssignmentIdempotencyKey(),
                    row.getWrapperDigest(),row.getConsentId(),row.getExpectedConsentVersion(),
                    row.getGrantId(),row.getGrantVersion(),row.getAssignmentRevision(),
                    row.getAuthorityLocator(),row.getExecutionProtocolVersion(),row.getOperationGrantId(),
                    row.getCreatedAt(),row.getTenantId(),row.getClientId()));
            return null;
        }).when(rows).insert(any());
        return rows;
    }

    private ControlledImageBridgeOperationEntity bridgeBy(String tenant,String client,String owner,
            String task,String key) {
        List<ControlledImageBridgeOperationEntity> rows=jdbc.query("""
                SELECT * FROM agent_controlled_image_bridge_operation
                 WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=?
                   AND assignment_idempotency_key=?
                """,(result,index)->{
            ControlledImageBridgeOperationEntity row=new ControlledImageBridgeOperationEntity()
                    .setOwnerJiacn(result.getString("owner_jiacn"))
                    .setTaskId(result.getString("task_id"))
                    .setAssignmentIdempotencyKey(result.getString("assignment_idempotency_key"))
                    .setWrapperDigest(result.getString("wrapper_digest"))
                    .setConsentId(result.getString("consent_id"))
                    .setExpectedConsentVersion(result.getLong("expected_consent_version"))
                    .setGrantId(result.getString("grant_id"))
                    .setGrantVersion(result.getLong("grant_version"))
                    .setAssignmentRevision(result.getLong("assignment_revision"))
                    .setAuthorityLocator(result.getString("authority_locator"))
                    .setExecutionProtocolVersion(result.getInt("execution_protocol_version"))
                    .setOperationGrantId(result.getString("operation_grant_id"))
                    .setCreatedAt(result.getLong("created_at"));
            row.setTenantId(result.getString("tenant_id"));
            row.setClientId(result.getString("client_id"));
            return row;
        },tenant,client,owner,task,key);
        return rows.isEmpty()?null:rows.getFirst();
    }

    private static AgentTaskAssignDTO zeroVersionAssignment() {
        AgentTaskAssignDTO assignment=new AgentTaskAssignDTO();
        assignment.setWorkflowVersion(2);assignment.setBusinessAction("assign_and_start");
        assignment.setExpectedTaskVersion(0L);assignment.setRequirementRevision(1L);
        assignment.setAgentId("agent-zero");assignment.setRequestedOperations(List.of("GENERATE_IMAGE"));
        assignment.setInitialOperation("GENERATE_IMAGE");assignment.setInputRefs(List.of());
        return assignment;
    }

    private static ControlledImageProviderOperatorPolicy operatorPolicy() {
        NativeProviderCredentialBindingLookup lookup=scope->new NativeProviderCredentialBindingLookup.Snapshot(
                NativeProviderCredentialBindingLookup.State.READY,1,"CONTROLLED_IMAGE_HTTP_V1",
                "binding-zero",1L,"model-zero",16,1,1);
        @SuppressWarnings("unchecked") ObjectProvider<NativeProviderCredentialBindingLookup> provider=
                mock(ObjectProvider.class);
        when(provider.getIfUnique()).thenReturn(lookup);
        ControlledImageProviderProperties properties=new ControlledImageProviderProperties();
        properties.setEnabled(true);
        ControlledImageProviderProperties.OperatorPolicy policy=new ControlledImageProviderProperties.OperatorPolicy();
        policy.setTenantId("0");policy.setClientId("client");policy.setOwnerJiacn("owner");
        policy.setTargetAgentId("agent-zero");policy.setProviderLane("CONTROLLED_IMAGE_HTTP_V1");
        policy.setBindingId("binding-zero");policy.setBindingEpoch(1L);policy.setModelId("model-zero");
        policy.setCustody("OPERATOR_TEMPLATE");policy.setIssuer("operator-zero");
        policy.setPolicyRevision("policy-zero");policy.setExpiresAt(System.currentTimeMillis()+600_000L);
        policy.setAllowUnpricedExternalAccount(true);policy.setMaxOutboundRequestAttempts(1);
        properties.setOperatorPolicies(List.of(policy));
        return new ControlledImageProviderOperatorPolicy(properties,provider);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10,TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);
        }
    }
    private static DriverManagerDataSource ds(String url,String user,String password) {
        DriverManagerDataSource value=new DriverManagerDataSource();
        value.setDriverClassName("com.mysql.cj.jdbc.Driver");value.setUrl(url);
        value.setUsername(user);value.setPassword(password);return value;
    }
    private static String databaseUrl(String base,String database) {
        int query=base.indexOf('?');String suffix=query<0?"":base.substring(query);
        String plain=query<0?base:base.substring(0,query);
        return plain.substring(0,plain.lastIndexOf('/')+1)+database+suffix;
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null||value.isBlank())throw new IllegalStateException(name+" required");
        return value;
    }
}
