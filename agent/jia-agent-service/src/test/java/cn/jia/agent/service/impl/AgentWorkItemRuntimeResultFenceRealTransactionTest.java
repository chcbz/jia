package cn.jia.agent.service.impl;

import cn.jia.agent.api.AgentWorkItemRuntimeResultController;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.*;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import cn.jia.user.security.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real H2/MyBatis/production result, artifact, lease, reassignment and transaction proxies.
 * Runtime DAO seams are explicitly JDBC-backed fixture locks; identity/account authority is
 * synthetic. This is not full filter/client/enrollment/MySQL or online acceptance evidence. */
class AgentWorkItemRuntimeResultFenceRealTransactionTest {
    private static final String TENANT="0", CLIENT="client-a", OWNER="owner-a", TASK="task-1", WORK="work-1";
    private static final String PREVIOUS="agt_"+"a".repeat(32), TARGET="agt_"+"b".repeat(32), COORDINATOR="agt_"+"c".repeat(32);
    private static final String INSTALLATION="rti_"+"1".repeat(32), TOKEN="original-business-lease";
    private static final long NOW=1_800_000_000_000L;
    private final ObjectMapper json=new ObjectMapper();
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private DataSourceTransactionManager manager;
    private AgentTaskMetaDao taskDao;
    private AgentTaskMemberDao memberDao;
    private AgentTaskWorkItemDao workDao;
    private AgentTaskArtifactDao artifactDao;
    private AgentTaskEventDao eventDao;
    private AgentTaskEventWriter writer;
    private AgentTaskMutationTransaction roots;
    private AgentWorkItemLeaseService leases;
    private AgentWorkItemReassignmentService reassignments;
    private AgentTaskArtifactService artifacts;
    private AgentWorkItemResultCommitService results;
    private AgentRuntimeAuthenticationService auth;
    private AgentRuntimeAuthentication principal;
    private AgentWorkItemReassignmentResultDTO receipt;
    private BlockingStorage storage;
    private MockMvc mvc;
    private String path;
    private final List<String> locks=new ArrayList<>();

    @BeforeEach void setUp() throws Exception {
        dataSource=new DriverManagerDataSource("jdbc:h2:mem:ur02_e05_result_"+UUID.randomUUID()+";MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver"); jdbc=new JdbcTemplate(dataSource);manager=new DataSourceTransactionManager(dataSource); createTables();
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        for(var mapper:List.of(AgentTaskMetaMapper.class,AgentTaskMemberMapper.class,AgentTaskWorkItemMapper.class,
                AgentTaskArtifactMapper.class,AgentTaskEventMapper.class,AgentWorkItemReassignmentMapper.class)) configuration.addMapper(mapper);
        var global=new GlobalConfig();global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(dataSource);factory.setConfiguration(configuration);factory.setGlobalConfig(global);
        var template=new SqlSessionTemplate(factory.getObject());
        taskDao=new AgentTaskMetaDaoImpl();org.springframework.test.util.ReflectionTestUtils.setField(taskDao,"baseMapper",template.getMapper(AgentTaskMetaMapper.class));
        memberDao=new AgentTaskMemberDaoImpl(template.getMapper(AgentTaskMemberMapper.class));workDao=new AgentTaskWorkItemDaoImpl(template.getMapper(AgentTaskWorkItemMapper.class));
        artifactDao=new AgentTaskArtifactDaoImpl(template.getMapper(AgentTaskArtifactMapper.class));
        eventDao=new AgentTaskEventDaoImpl();org.springframework.test.util.ReflectionTestUtils.setField(eventDao,"baseMapper",template.getMapper(AgentTaskEventMapper.class));
        writer=new AgentTaskEventWriterImpl(eventDao,manager,new AgentTaskEventAfterCommitPublisher(new AgentTaskEventBroker(),manager));
        roots=spy(new AgentTaskMutationTransactionImpl(taskDao,manager));
        doAnswer(inv->{locks.add("task-root");return inv.callRealMethod();}).when(roots)
                .executeWithLockedTaskRootInOwnerScope(anyString(),anyString(),anyString(),anyString(),any());
        var identities=mock(AgentIdentityService.class);
        when(identities.requireCanonicalAgentIdInScope(anyString(),anyString(),anyString(),anyString())).thenAnswer(inv->inv.getArgument(3));
        when(identities.requirePersistedCanonicalAgentIdInScope(anyString(),anyString(),anyString(),anyString())).thenAnswer(inv->inv.getArgument(3));
        when(identities.lockActiveCanonicalAgentIdsInScope(anyString(),anyString(),anyString(),any())).thenAnswer(inv->inv.getArgument(3));
        var agents=mock(AgentService.class);
        when(agents.requireApiKeyOwnedAgentForUpdate(anyString(),anyString(),anyString())).thenAnswer(inv->{var row=new AgentRuntimeDTO();row.setAgentId(inv.getArgument(2));row.setStatus("online");return row;});
        // Explicit identity authority on the raw B04 service (proxy construction must not hide its mandatory setter).
        var rawLease=new AgentWorkItemLeaseServiceImpl(memberDao,workDao,roots,writer,()->NOW,()->TOKEN,900_000);rawLease.setIdentityService(identities);leases=proxy(rawLease,AgentWorkItemLeaseService.class);
        var transport=new AgentCommandTransportWriter() {
            @Override public AgentCommandTransportWriteResult write(AgentCommandDraft draft){throw new UnsupportedOperationException();}
            @Override public AgentCommandTransportWriteResult writeAuthorizedHall(AgentCommandDraft draft,String actor){
                insertDelivery(draft);return new AgentCommandTransportWriteResult(jdbc.queryForObject("SELECT id FROM agent_command_delivery WHERE command_id=?",Long.class,draft.commandId()),draft.commandId(),"message-e05","outbox-e05",false);
            }
        };
        reassignments=proxy(new AgentWorkItemReassignmentServiceImpl(new AgentWorkItemReassignmentDaoImpl(template.getMapper(AgentWorkItemReassignmentMapper.class)),
                memberDao,workDao,roots,identities,agents,writer,transport,leases,()->NOW,()->TOKEN,300_000),AgentWorkItemReassignmentService.class);
        storage=new BlockingStorage();
        artifacts=proxy(new AgentTaskCollaborationServiceImpl(taskDao,memberDao,workDao,mock(AgentTaskRequestDao.class),artifactDao,storage,roots,writer,()->NOW),AgentTaskArtifactService.class);
        results=freshResults();
        insertFixture();
        var request=new AgentWorkItemReassignmentRequestDTO();request.setExpectedTaskVersion(7L);request.setExpectedWorkItemVersion(4L);
        request.setExpectedPreviousAgentId(PREVIOUS);request.setTargetAgentId(TARGET);request.setSourceCommandId(sourceCommandId());request.setReason("lease_expired");
        receipt=reassignments.reassign(TENANT,CLIENT,OWNER,"operator-a",COORDINATOR,TASK,WORK,"e05-result-original",request);
        var leaseRequest=new AgentWorkItemReassignmentLeaseRequestDTO();leaseRequest.setCommandId(receipt.getCommandId());leaseRequest.setExpectedWorkItemVersion(5L);
        var started=reassignments.startLease(TENANT,CLIENT,OWNER,TARGET,TASK,WORK,receipt.getReassignmentId(),leaseRequest);assertEquals(6L,started.getWorkItemVersion());
        auth=runtimeAuthority();principal=principal(7,"boot-fixture");
        path="/internal/agent/tasks/"+TASK+"/work-items/"+WORK+"/reassignments/"+receipt.getReassignmentId()+"/commands/"+receipt.getCommandId()+"/result-commit";
        mvc=controller(results);locks.clear();
    }
    @AfterEach void tearDown(){if(jdbc!=null)jdbc.execute("DROP ALL OBJECTS");}
    private MockMvc controller(AgentWorkItemResultCommitService resultService) {
        return MockMvcBuilders.standaloneSetup(new AgentWorkItemRuntimeResultController(resultService,reassignments,auth))
                .setControllerAdvice(new cn.jia.core.security.SensitiveResponseBodyAdvice(new cn.jia.core.security.SensitiveResponseProperties())).build();
    }
    private AgentWorkItemResultCommitService freshResults() {
        var raw=new AgentWorkItemResultCommitServiceImpl(leases,artifacts,workDao,roots,writer,()->NOW);raw.setRuntimeResultRecovery(reassignments,eventDao);
        return proxy(raw,AgentWorkItemResultCommitService.class);
    }
    private String sourceCommandId(){return AgentCommandCanonicalCodec.hallCommandId(TENANT,CLIENT,OWNER,TASK,PREVIOUS,"source-intent",AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE);}
    private AgentWorkItemResultCommitDTO command() {
        var artifact=new AgentTaskArtifactPublishDTO();artifact.setArtifactId("original-result-artifact");artifact.setWorkItemId(WORK);artifact.setProducerAgentId(TARGET);
        artifact.setArtifactType("summary");artifact.setTitle("Original E05 result");artifact.setContentBytes("immutable result".getBytes(StandardCharsets.UTF_8));artifact.setContentMimeType("text/plain");
        artifact.setArtifactVersion(1);artifact.setExpectedPreviousVersion(0);artifact.setVisibility("task_members");artifact.setMetadata(Map.of());
        var command=new AgentWorkItemResultCommitDTO();command.setWorkItemId(WORK);command.setProducerAgentId(TARGET);command.setLeaseToken(TOKEN);command.setExpectedWorkItemVersion(6L);command.setArtifact(artifact);return command;
    }
    private String body(){return json.writeValueAsString(command());}
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private void assertNoResultMutation(int originalEvents) {
        assertEquals(0,count("agent_task_artifact"));assertEquals(originalEvents,count("agent_task_event"));
        assertEquals("running",jdbc.queryForObject("SELECT status FROM agent_task_work_item",String.class));
        assertNull(jdbc.queryForObject("SELECT result_artifact_id FROM agent_task_work_item",String.class));
        assertEquals((long)count("agent_task_event"),jdbc.queryForObject("SELECT current_event_version FROM agent_task_meta",Long.class));
    }
    @Test void committedResultReceiptAndReadbacksUseActualRowsEventsAndConfirmedVersion() throws Exception {
        var result=mvc.perform(post(path).principal(principal).contentType("application/json").content(body())).andExpect(status().isOk()).andReturn();
        var wire=json.readTree(result.getResponse().getContentAsString());assertEquals("submitted",wire.path("status").asText());assertEquals(7L,wire.path("workItemVersion").longValue());assertFalse(wire.has("data"));
        assertEquals(1,count("agent_task_artifact"));assertEquals(4,count("agent_task_event"));assertEquals(1,storage.stores.get());
        assertNull(jdbc.queryForObject("SELECT lease_token FROM agent_task_work_item",String.class));assertNull(jdbc.queryForObject("SELECT lease_until FROM agent_task_work_item",Long.class));
        var saved=jdbc.queryForObject("SELECT event_json FROM agent_task_event WHERE event_type='WORK_ITEM_SUBMITTED'",String.class);
        assertFalse(saved.contains(TOKEN));assertTrue(saved.contains(receipt.getCommandId()));assertTrue(saved.contains(receipt.getReassignmentId()));
        var replay=mvc.perform(post(path).principal(principal).contentType("application/json").content(body())).andExpect(status().isOk()).andReturn();
        assertEquals(wire,json.readTree(replay.getResponse().getContentAsString()));assertEquals(1,storage.stores.get());assertEquals(4,count("agent_task_event"));assertEquals(1,count("agent_task_artifact"));
        var read=controller(freshResults()).perform(get(path).principal(principal)).andExpect(status().isOk()).andReturn();
        assertEquals(wire,json.readTree(read.getResponse().getContentAsString()));assertEquals(1,storage.stores.get());
        assertEquals("installation",locks.get(0));assertEquals("runtime",locks.get(1));assertEquals("task-root",locks.get(2));
    }
    @Test void originalLeaseReadbackIsReadonlyAndOldPostVersionStillConflicts() throws Exception {
        int events=count("agent_task_event");String leasePath=path.replace("/result-commit","/lease");
        mvc.perform(get(leasePath).principal(principal)).andExpect(status().isOk()).andExpect(jsonPath("$.workItemVersion").value(6)).andExpect(jsonPath("$.leaseToken").value(TOKEN));
        assertEquals(6L,jdbc.queryForObject("SELECT version FROM agent_task_work_item",Long.class));assertEquals(events,count("agent_task_event"));assertEquals(0,storage.stores.get());
        var old=new AgentWorkItemReassignmentLeaseRequestDTO();old.setCommandId(receipt.getCommandId());old.setExpectedWorkItemVersion(5L);
        var failure=assertThrows(cn.jia.agent.exception.AgentWorkItemReassignmentException.class,()->reassignments.readLease(TENANT,CLIENT,OWNER,TARGET,TASK,WORK,receipt.getReassignmentId(),old));
        assertEquals(cn.jia.agent.exception.AgentWorkItemReassignmentException.Reason.VERSION_CONFLICT,failure.getReason());
    }
    @Test void persistentRuntimeRotationDuringStorageRejectsFinalCommitWithoutRowsOrEvents() throws Exception {
        int events=count("agent_task_event");storage.afterStore=()->jdbc.update("UPDATE ur02_runtime_generation_fence SET generation=8,boot='replacement' WHERE agent_id=?",TARGET);
        mvc.perform(post(path).principal(principal).contentType("application/json").content(body())).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AGENT_RUNTIME_UNAUTHENTICATED"));
        assertNoResultMutation(events);assertEquals(1,storage.stores.get());assertEquals(8L,jdbc.queryForObject("SELECT generation FROM ur02_runtime_generation_fence",Long.class));
    }
    @Test void revocationDuringStorageDeniesFinalCommitWithoutDeletingOnlyMaterial() throws Exception {
        int events=count("agent_task_event");storage.afterStore=()->jdbc.update("UPDATE ur02_runtime_installation_fence SET status='REVOKED'");
        mvc.perform(post(path).principal(principal).contentType("application/json").content(body())).andExpect(status().isUnauthorized());assertNoResultMutation(events);assertEquals(1,storage.material.size());
    }
    @Test void heartbeatDuringStorageWinsItsCasAndResultCannotPredictNewVersion() throws Exception {
        int events=count("agent_task_event");storage.afterStore=()->{var heart=new AgentWorkItemLeaseCommandDTO();heart.setAgentId(TARGET);heart.setLeaseToken(TOKEN);heart.setExpectedVersion(6L);heart.setLeaseDurationMillis(400_000L);leases.heartbeat(TENANT,CLIENT,OWNER,TASK,WORK,heart);};
        mvc.perform(post(path).principal(principal).contentType("application/json").content(body())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ITEM_RESULT_STALE"));
        assertNoResultMutation(events+1);assertEquals(7L,jdbc.queryForObject("SELECT version FROM agent_task_work_item",Long.class));assertEquals(1,storage.stores.get());
    }
    @Test void leaseReplacementDuringPreparationCannotBorrowOriginalCommandSource() throws Exception {
        int events=count("agent_task_event");storage.afterStore=()->jdbc.update("UPDATE agent_task_work_item SET lease_token='replacement-lease',version=7");
        mvc.perform(post(path).principal(principal).contentType("application/json").content(body())).andExpect(status().isConflict());assertNoResultMutation(events);
    }
    @Test void sourceCanonicalHashCorruptionDuringStorageFailsBeforeBusinessWrites() throws Exception {
        int events=count("agent_task_event");storage.afterStore=()->jdbc.update("UPDATE agent_command_delivery SET command_payload_hash=? WHERE command_id=?",new byte[32],receipt.getCommandId());
        mvc.perform(post(path).principal(principal).contentType("application/json").content(body())).andExpect(status().isConflict());assertNoResultMutation(events);
    }
    @Test void aclChangedDuringStorageIsRecheckedRatherThanTrustedFromPreparedObject() throws Exception {
        int events=count("agent_task_event");storage.afterStore=()->jdbc.update("UPDATE agent_task_member SET member_role='observer' WHERE agent_id=?",TARGET);
        mvc.perform(post(path).principal(principal).contentType("application/json").content(body())).andExpect(status().isForbidden());assertNoResultMutation(events);
    }
    @Test void lostResponseRecoveryWithNewBootReadsOriginalResultButRetiredBootCannot() throws Exception {
        var original=mvc.perform(post(path).principal(principal).contentType("application/json").content(body())).andExpect(status().isOk()).andReturn();
        jdbc.update("UPDATE ur02_runtime_generation_fence SET generation=8,boot='replacement'");
        mvc.perform(get(path).principal(principal)).andExpect(status().isUnauthorized());
        var restored=controller(freshResults()).perform(get(path).principal(principal(8,"replacement"))).andExpect(status().isOk()).andReturn();
        assertEquals(json.readTree(original.getResponse().getContentAsString()),json.readTree(restored.getResponse().getContentAsString()));assertEquals(1,storage.stores.get());assertEquals(4,count("agent_task_event"));
    }
    @Test void changedMaterialMissingProofOrAdvancedStateNeverMasqueradesAsOriginalSuccess() throws Exception {
        mvc.perform(post(path).principal(principal).contentType("application/json").content(body())).andExpect(status().isOk());
        var changed=command();changed.getArtifact().setTitle("different result");
        mvc.perform(post(path).principal(principal).contentType("application/json").content(json.writeValueAsString(changed))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ITEM_RESULT_RECOVERY_REQUIRED"));
        var originalProof=jdbc.queryForObject("SELECT event_json FROM agent_task_event WHERE event_type='WORK_ITEM_SUBMITTED'",String.class);
        jdbc.update("UPDATE agent_task_event SET event_json='{}' WHERE event_type='WORK_ITEM_SUBMITTED'");
        mvc.perform(get(path).principal(principal)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ITEM_RESULT_RECOVERY_REQUIRED"));
        jdbc.update("UPDATE agent_task_event SET event_json=? WHERE event_type='WORK_ITEM_SUBMITTED'",originalProof);
        jdbc.update("UPDATE agent_task_work_item SET status='completed',version=8,completed_at=?",NOW+1);
        mvc.perform(get(path).principal(principal)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ITEM_RESULT_RECOVERY_REQUIRED"));
        assertEquals(1,storage.stores.get());assertEquals(1,count("agent_task_artifact"));assertEquals(4,count("agent_task_event"));
    }
    @Test void preparationRejectsEnclosingTransactionAndResultCasFailureRollsBackArtifactAndEvents() throws Exception {
        var transaction=new TransactionTemplate(manager);
        assertThrows(RuntimeException.class,()->transaction.execute(status->results.prepareRuntimeResult(TENANT,CLIENT,OWNER,TASK,WORK,TARGET,receipt.getReassignmentId(),receipt.getCommandId(),command())));
        assertEquals(0,storage.stores.get());
        var losing=spy(workDao);doReturn(0).when(losing).updateActiveLeaseByVersion(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong(),anyLong(),any());
        var raw=new AgentWorkItemResultCommitServiceImpl(leases,artifacts,losing,roots,writer,()->NOW);raw.setRuntimeResultRecovery(reassignments,eventDao);
        int events=count("agent_task_event");controller(proxy(raw,AgentWorkItemResultCommitService.class)).perform(post(path).principal(principal).contentType("application/json").content(body())).andExpect(status().isConflict());
        assertNoResultMutation(events);assertEquals(1,storage.material.size());
    }
    @Test void preparedPublicationIsOpaqueIssuerScopedAndFreezesOriginalBody() {
        var mutable=command();
        var prepared=results.prepareRuntimeResult(TENANT,CLIENT,OWNER,TASK,WORK,TARGET,receipt.getReassignmentId(),receipt.getCommandId(),mutable);
        mutable.setLeaseToken("forged");mutable.getArtifact().setArtifactId("different-result");mutable.getArtifact().setContentBytes(new byte[]{0});
        var forbidden=assertThrows(cn.jia.agent.exception.AgentTaskCollaborationException.class,()->auth.withNativeFence(principal,
                ()->results.commitPreparedRuntimeResult(TENANT,CLIENT,OWNER,TASK,WORK,TARGET,receipt.getReassignmentId(),"foreign-command",prepared)));
        assertEquals(cn.jia.agent.exception.AgentTaskCollaborationException.Reason.FORBIDDEN,forbidden.getReason());
        assertEquals(0,count("agent_task_artifact"));
        var result=auth.withNativeFence(principal,()->results.commitPreparedRuntimeResult(TENANT,CLIENT,OWNER,TASK,WORK,TARGET,receipt.getReassignmentId(),receipt.getCommandId(),prepared));
        assertEquals("original-result-artifact",result.getArtifact().getArtifactId());assertEquals("submitted",result.getStatus());assertEquals(1,storage.stores.get());
    }
    @Test void unrelatedArtifactVersionConflictCannotImpersonateOriginalBusinessReceipt() throws Exception {
        int events=count("agent_task_event");
        storage.afterStore=()->{
            var competing=command().getArtifact();competing.setContentBytes(null);competing.setContentMimeType(null);competing.setContent("unrelated existing artifact");
            artifacts.publish(TENANT,CLIENT,OWNER,TASK,TARGET,competing);
        };
        mvc.perform(post(path).principal(principal).contentType("application/json").content(body())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ITEM_RESULT_STALE"));
        assertEquals(1,count("agent_task_artifact"));assertEquals(events+1,count("agent_task_event"));
        assertEquals("running",jdbc.queryForObject("SELECT status FROM agent_task_work_item",String.class));assertNull(jdbc.queryForObject("SELECT result_artifact_id FROM agent_task_work_item",String.class));
        mvc.perform(get(path).principal(principal)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WORK_ITEM_RESULT_NOT_FOUND"));
        assertEquals(1,storage.material.size());
    }
    @Test void sourceWrongScopeAndUnknownCommandHideExistenceAndNeverPrepareStorage() throws Exception {
        int events=count("agent_task_event");
        mvc.perform(post(path.replace(receipt.getCommandId(),"unknown-command")).principal(principal).contentType("application/json").content(body())).andExpect(status().isNotFound());
        var foreign=new AgentRuntimeAuthentication(new AgentRuntimeAuthentication.Scope(TENANT,"foreign-client",OWNER,TARGET,"boot-fixture"));
        foreign.setDetails(new AgentRuntimeAuthenticationService.Proof(foreign.getPrincipal(),INSTALLATION,"host-fixture",7,"a".repeat(64),7,2));
        mvc.perform(get(path).principal(foreign)).andExpect(status().isUnauthorized());assertNoResultMutation(events);assertEquals(0,storage.stores.get());
    }
    @Test void storageIsUnfencedWhileBlockedAndIndependentOriginalRootReadCanProceed() throws Exception {
        storage.entered=new CountDownLatch(1);storage.release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<Integer> commit=pool.submit(()->mvc.perform(post(path).principal(principal).contentType("application/json").content(body())).andReturn().getResponse().getStatus());
            assertTrue(storage.entered.await(5,TimeUnit.SECONDS));
            new TransactionTemplate(manager).execute(status->{jdbc.queryForObject("SELECT generation FROM ur02_runtime_generation_fence WHERE agent_id=? FOR UPDATE",Long.class,TARGET);jdbc.queryForObject("SELECT status FROM ur02_runtime_installation_fence FOR UPDATE",String.class);jdbc.queryForObject("SELECT task_version FROM agent_task_meta FOR UPDATE",Long.class);return null;});
            assertEquals(0,count("agent_task_artifact"));storage.release.countDown();assertEquals(200,commit.get(10,TimeUnit.SECONDS));assertEquals(1,count("agent_task_artifact"));
        } finally {storage.release.countDown();pool.shutdownNow();}
    }

    private AgentRuntimeAuthenticationService runtimeAuthority() {
        jdbc.update("INSERT INTO ur02_runtime_installation_fence VALUES (?,'ACTIVE')",INSTALLATION);jdbc.update("INSERT INTO ur02_runtime_generation_fence VALUES (?,7,'boot-fixture')",TARGET);
        var installationDao=mock(AgentRuntimeV1InstallationDao.class);var runtimeDao=mock(AgentRuntimeDao.class);
        doAnswer(inv->{locks.add("installation");return installation(true);}).when(installationDao).lock(INSTALLATION);
        doAnswer(inv->installation(false)).when(installationDao).findInScope(TENANT,CLIENT,INSTALLATION);
        doAnswer(inv->{locks.add("runtime");return runtime(true);}).when(runtimeDao).lockInScope(TENANT,CLIENT,TARGET);
        doAnswer(inv->runtime(false)).when(runtimeDao).findInScope(TENANT,CLIENT,TARGET);
        var identities=mock(AgentIdentityService.class);var identity=new AgentIdentityRegistryEntity().setCanonicalAgentId(TARGET).setBindingId(3L).setOwnerJiacn(OWNER);
        when(identities.requireRegistrationIdentityInScope(TENANT,CLIENT,OWNER,TARGET)).thenReturn(identity);when(identities.requireActiveBinding(identity,null)).thenReturn(new AgentPersonaBindingEntity().setId(3L));
        var accounts=mock(AccountSecurityService.class);when(accounts.findUniqueByExactJiacn(OWNER)).thenReturn(Optional.of(new AccountSecuritySnapshot(7,OWNER,AccountState.ACTIVE,2)));
        return classProxy(new AgentRuntimeAuthenticationService(runtimeDao,installationDao,mock(AgentIdentityRegistryDao.class),identities,accounts,mock(AgentTaskEventsGate.class)));
    }
    private AgentRuntimeV1InstallationEntity installation(boolean lock) {
        String status=jdbc.queryForObject("SELECT status FROM ur02_runtime_installation_fence WHERE installation_id=?"+(lock?" FOR UPDATE":""),String.class,INSTALLATION);
        var row=new AgentRuntimeV1InstallationEntity().setInstallationId(INSTALLATION).setStatus(status).setCanonicalAgentId(TARGET);row.setTenantId(TENANT);row.setClientId(CLIENT);return row;
    }
    private AgentRuntimeEntity runtime(boolean lock) {
        var values=jdbc.queryForMap("SELECT generation,boot FROM ur02_runtime_generation_fence WHERE agent_id=?"+(lock?" FOR UPDATE":""),TARGET);
        var row=new AgentRuntimeEntity().setAgentId(TARGET).setOwnerJiacn(OWNER).setBindingId(3L).setStatus("online").setRuntimeInstallationId(INSTALLATION).setRuntimeHostId("host-fixture")
                .setRuntimeInstanceId((String)values.get("BOOT")).setRuntimeSessionGeneration(((Number)values.get("GENERATION")).longValue()).setTokenHash("urs1:"+"a".repeat(64)+":7:2");row.setTenantId(TENANT);row.setClientId(CLIENT);return row;
    }
    private AgentRuntimeAuthentication principal(long generation,String boot) {
        var scope=new AgentRuntimeAuthentication.Scope(TENANT,CLIENT,OWNER,TARGET,boot);var result=new AgentRuntimeAuthentication(scope);
        result.setDetails(new AgentRuntimeAuthenticationService.Proof(scope,INSTALLATION,"host-fixture",generation,"a".repeat(64),7,2));return result;
    }
    @SuppressWarnings("unchecked") private <T> T proxy(T raw,Class<T> type) {
        var factory=new ProxyFactory(raw);factory.setInterfaces(type);factory.addAdvice(advice());return (T)factory.getProxy();
    }
    @SuppressWarnings("unchecked") private <T> T classProxy(T raw) {var factory=new ProxyFactory(raw);factory.setProxyTargetClass(true);factory.addAdvice(advice());return (T)factory.getProxy();}
    private TransactionInterceptor advice(){var result=new TransactionInterceptor();result.setTransactionManager(manager);result.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());return result;}
    private void insertFixture() {
        jdbc.update("INSERT INTO agent_task_meta (task_id,owner_jiacn,reward_status,coordinator_agent_id,task_version,current_event_version,risk_level,tenant_id,client_id,create_time,update_time) VALUES (?,?, 'running',?,7,0,'low',?,?,1,1)",TASK,OWNER,COORDINATOR,TENANT,CLIENT);
        for(String agent:List.of(PREVIOUS,TARGET,COORDINATOR)) jdbc.update("INSERT INTO agent_task_member (task_id,owner_jiacn,agent_id,member_role,member_status,tenant_id,client_id,create_time,update_time) VALUES (?,?,?,?,'working',?,?,1,1)",TASK,OWNER,agent,agent.equals(COORDINATOR)?"coordinator":"worker",TENANT,CLIENT);
        jdbc.update("INSERT INTO agent_task_work_item (task_id,work_item_id,owner_jiacn,title,description,work_type,assignee_agent_id,status,lease_token,lease_until,attempt_count,max_attempts,version,tenant_id,client_id,create_time,update_time) VALUES (?,?,?,'Work One','Original E05 task','implementation',?,'claimed','expired-business-lease',?,1,3,4,?,?,1,1)",TASK,WORK,OWNER,PREVIOUS,NOW-1,TENANT,CLIENT);
        long issued=NOW-600_000;insertDelivery(new AgentCommandDraft(1,sourceCommandId(),TASK,"source-intent",TENANT,CLIENT,OWNER,TASK,WORK,PREVIOUS,AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,issued,issued+AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS,"source-intent",new AgentHallCommandPayload("work_item_execute","Execute original work item","juyiting",null,null,null,"autonomous",false,null)));
    }
    private void insertDelivery(AgentCommandDraft draft) {
        var bytes=AgentCommandCanonicalCodec.businessBytes(draft);jdbc.update("INSERT INTO agent_command_delivery (command_id,task_id,work_item_id,target_agent_id,command_type,command_payload,command_payload_hash,status,attempt_count,version,tenant_id,client_id,owner_jiacn,create_time,update_time) VALUES (?,?,?,?,?,?,?,'PENDING',1,0,?,?,?,?,?)",draft.commandId(),draft.taskId(),draft.workItemId(),draft.targetAgentId(),draft.commandType(),bytes,AgentCommandCanonicalCodec.sha256(bytes),draft.tenantId(),draft.clientId(),draft.ownerJiacn(),draft.issuedAt(),draft.issuedAt());
    }
    private final class BlockingStorage implements AgentTaskArtifactStorage {
        final AtomicInteger stores=new AtomicInteger();final Map<String,byte[]> material=new HashMap<>();Runnable afterStore=()->{};CountDownLatch entered,release;
        @Override public StoredObject store(Scope scope,byte[] content,String mime) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"no external storage I/O under a DB transaction/fence");
            String hash=cn.jia.agent.common.TaskEventPayload.ContentDigest.fromUtf8(new String(content,StandardCharsets.UTF_8)).sha256();String uri=uri(scope,hash);material.put(uri,content.clone());stores.incrementAndGet();
            afterStore.run();if(entered!=null){entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}
            return new StoredObject(uri,hash,content.length,mime,true);
        }
        @Override public StoredContent read(Scope scope,String uri,String hash,long length,String mime){throw new AssertionError("Result commit/readback must not fetch external storage bytes under a fence");}
        @Override public boolean owns(String uri){return uri.startsWith("test-managed:");}
        @Override public boolean matches(Scope scope,String uri,String hash){return uri.equals(uri(scope,hash));}
        private String uri(Scope scope,String hash){return "test-managed:"+scope.tenantId()+"/"+scope.clientId()+"/"+scope.ownerJiacn()+"/"+scope.taskId()+"/"+hash;}
    }
    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    owner_jiacn VARCHAR(50) NOT NULL,
                    task_id VARCHAR(100) NOT NULL,
                    reward_status VARCHAR(20) NOT NULL DEFAULT 'open',
                    assigned_agent_id VARCHAR(100),
                    required_abilities TEXT,
                    reward INT, assigned_at BIGINT, started_at BIGINT, completed_at BIGINT,
                    failure_reason VARCHAR(1000), collaboration_mode VARCHAR(20) DEFAULT 'single',
                    risk_level VARCHAR(20) DEFAULT 'low', max_agents INT DEFAULT 1,
                    coordinator_agent_id VARCHAR(100), review_required TINYINT DEFAULT 0,
                    task_version BIGINT NOT NULL DEFAULT 0,
                    current_event_version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT,
                    update_time BIGINT,
                    UNIQUE (tenant_id, client_id, task_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    owner_jiacn VARCHAR(50) NOT NULL,
                    task_id VARCHAR(100) NOT NULL, event_version BIGINT NOT NULL,
                    event_id VARCHAR(100) NOT NULL, event_type VARCHAR(64) NOT NULL,
                    actor_type VARCHAR(20) NOT NULL, actor_id VARCHAR(100),
                    aggregate_type VARCHAR(30) NOT NULL, aggregate_id VARCHAR(100) NOT NULL,
                    event_json CLOB NOT NULL, occurred_at BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, task_id, event_version),
                    UNIQUE (tenant_id, client_id, event_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_member (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    owner_jiacn VARCHAR(50) NOT NULL,
                    task_id VARCHAR(100) NOT NULL,
                    agent_id VARCHAR(100) NOT NULL,
                    member_role VARCHAR(20) NOT NULL,
                    member_status VARCHAR(20) NOT NULL,
                    assignment_source VARCHAR(20) NOT NULL DEFAULT 'manual',
                    joined_at BIGINT, accepted_at BIGINT, started_at BIGINT, completed_at BIGINT,
                    last_heartbeat_at BIGINT, failure_reason VARCHAR(1000),
                    version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, task_id, agent_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_work_item (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    owner_jiacn VARCHAR(50) NOT NULL,
                    work_item_id VARCHAR(100) NOT NULL,
                    task_id VARCHAR(100) NOT NULL,
                    title VARCHAR(255) NOT NULL DEFAULT '', description TEXT,
                    work_type VARCHAR(30) NOT NULL DEFAULT 'implementation', required_abilities TEXT,
                    assignee_agent_id VARCHAR(100), status VARCHAR(20) NOT NULL DEFAULT 'ready',
                    priority INT NOT NULL DEFAULT 0, required_item TINYINT NOT NULL DEFAULT 1,
                    dependency_json TEXT, lease_token VARCHAR(100), lease_until BIGINT,
                    attempt_count INT NOT NULL DEFAULT 0, max_attempts INT NOT NULL DEFAULT 3,
                    result_artifact_id VARCHAR(100), submitted_at BIGINT, completed_at BIGINT,
                    version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, work_item_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_request (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    owner_jiacn VARCHAR(50) NOT NULL,
                    request_id VARCHAR(100) NOT NULL, task_id VARCHAR(100) NOT NULL,
                    work_item_id VARCHAR(100), requester_agent_id VARCHAR(100) NOT NULL,
                    target_type VARCHAR(20) NOT NULL, target_id VARCHAR(100) NOT NULL,
                    request_type VARCHAR(30) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'open',
                    priority INT NOT NULL DEFAULT 0, title VARCHAR(255) NOT NULL, description TEXT NOT NULL,
                    response_json CLOB, due_at BIGINT, acknowledged_at BIGINT, resolved_at BIGINT,
                    version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, request_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_artifact (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    owner_jiacn VARCHAR(50) NOT NULL,
                    artifact_id VARCHAR(100) NOT NULL, task_id VARCHAR(100) NOT NULL,
                    work_item_id VARCHAR(100), producer_agent_id VARCHAR(100) NOT NULL,
                    artifact_type VARCHAR(30) NOT NULL, title VARCHAR(255) NOT NULL,
                    content CLOB, storage_uri VARCHAR(1000), content_hash VARCHAR(128),
                    artifact_version INT NOT NULL, visibility VARCHAR(20) NOT NULL,
                    metadata_json CLOB, created_at BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, artifact_id, artifact_version)
                )""");

        jdbc.execute("""
                CREATE TABLE agent_command_delivery (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_jiacn VARCHAR(50) NOT NULL, command_id VARCHAR(100) NOT NULL,
                    task_id VARCHAR(100), work_item_id VARCHAR(100), target_agent_id VARCHAR(100),
                    command_type VARCHAR(64), command_payload BLOB, command_payload_hash BINARY(32),
                    status VARCHAR(30), attempt_count INT, next_retry_at BIGINT,
                    lease_owner VARCHAR(100), lease_until BIGINT, active_message_id VARCHAR(100),
                    active_attempt INT, expires_at BIGINT, last_error VARCHAR(255), version BIGINT,
                    replay_parent_message_id VARCHAR(100), replay_requester_id VARCHAR(100),
                    replay_approver_id VARCHAR(100), replay_reason VARCHAR(2000),
                    tenant_id VARCHAR(50), client_id VARCHAR(50), create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id,client_id,owner_jiacn,command_id))
                """);
        jdbc.execute("""
                CREATE TABLE agent_work_item_reassignment (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_jiacn VARCHAR(50) NOT NULL, reassignment_id VARCHAR(100) NOT NULL,
                    request_sha256 CHAR(64) NOT NULL, task_id VARCHAR(100) NOT NULL,
                    work_item_id VARCHAR(100) NOT NULL, operator_subject VARCHAR(100) NOT NULL,
                    coordinator_agent_id VARCHAR(100) NOT NULL, previous_agent_id VARCHAR(100) NOT NULL,
                    target_agent_id VARCHAR(100) NOT NULL, source_command_id VARCHAR(100) NOT NULL,
                    command_id VARCHAR(100) NOT NULL, message_id VARCHAR(100) NOT NULL,
                    outbox_event_id VARCHAR(100) NOT NULL, expected_work_item_version BIGINT NOT NULL,
                    result_work_item_version BIGINT NOT NULL, task_version BIGINT NOT NULL,
                    lease_fence_sha256 CHAR(64) NOT NULL, previous_lease_until BIGINT NOT NULL,
                    lease_until BIGINT NOT NULL, attempt_count INT NOT NULL, max_attempts INT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
                    UNIQUE (tenant_id,client_id,owner_jiacn,reassignment_id),
                    UNIQUE (tenant_id,client_id,owner_jiacn,command_id))
                """);
        jdbc.execute("CREATE TABLE ur02_runtime_installation_fence (installation_id VARCHAR(100) PRIMARY KEY, status VARCHAR(20) NOT NULL)");
        jdbc.execute("CREATE TABLE ur02_runtime_generation_fence (agent_id VARCHAR(100) PRIMARY KEY, generation BIGINT NOT NULL, boot VARCHAR(100) NOT NULL)");
    }

}
