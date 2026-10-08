package cn.jia.agent.service.impl;

import cn.jia.agent.api.AgentTaskRequirementSnapshotController;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskRequirementReadException;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentTaskRequirementSnapshotServiceImplTest {
    private final List<String> lockOrder=new ArrayList<>();
    private final MemoryJdbc jdbc=new MemoryJdbc(lockOrder);
    private final AgentTaskMutationTransaction transactions=mock(AgentTaskMutationTransaction.class);
    private final AgentTaskMetaEntity task=new AgentTaskMetaEntity();
    private AgentTaskRequirementSnapshotServiceImpl service;
    private static final AgentTaskExecutionGrantService.Scope OWNER =
            new AgentTaskExecutionGrantService.Scope("0","client","owner");

    @BeforeEach void before() {
        task.setTenantId("0");task.setClientId("client");task.setOwnerJiacn("owner");
        task.setTaskId("task-1");task.setTaskVersion(0L);task.setCurrentEventVersion(0L);
        when(transactions.executeWithLockedTaskRootInOwnerScope(anyString(),anyString(),
                anyString(),anyString(),any())).thenAnswer(i -> {
                    lockOrder.add("task-root");
                    if (!Objects.equals("0",i.getArgument(0))
                            || !Objects.equals("client",i.getArgument(1))
                            || !Objects.equals("owner",i.getArgument(2))
                            || !Objects.equals("task-1",i.getArgument(3))) {
                        throw new AgentTaskCollaborationException(
                                AgentTaskCollaborationException.Reason.NOT_FOUND,"not found");
                    }
                    return ((AgentTaskMutationTransaction.LockedTaskMutation<?>)i.getArgument(4)).apply(task);
                });
        service=new AgentTaskRequirementSnapshotServiceImpl(jdbc,transactions);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        EsContext context=new EsContext();context.setClientId("client");context.setJiacn("owner");
        EsContextHolder.setContext(context);
    }
    @AfterEach void after() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        SecurityContextHolder.clearContext();
        EsContextHolder.setContext(new EsContext());
    }

    @Test void creationPreservesLongOriginalInsteadOfTruncatedTaskPlan() {
        String title="完整标题".repeat(24), description="原始完整需求🚀".repeat(90);
        var created=service.captureOnCreate(OWNER,"task-1",title,description);
        assertEquals(title,service.read(OWNER,"task-1",1).title());
        assertEquals(description,created.description());
        assertEquals(64,created.sha256().length());
        assertThrows(IllegalStateException.class, () -> service.captureOnCreate(OWNER,"task-1",title,description));
        assertThrows(IllegalStateException.class, () -> service.read(OWNER,"task-1",2));
        assertThrows(IllegalStateException.class, () -> service.read(
                new AgentTaskExecutionGrantService.Scope("0","client","other"),"task-1",1));
    }

    @Test void malformedUnicodeAndByteOverflowFailRatherThanStoreReplacement() {
        assertThrows(IllegalArgumentException.class,
                () -> service.captureOnCreate(OWNER,"task-1","Title","invalid\ud800"));
        assertThrows(IllegalArgumentException.class,
                () -> service.captureOnCreate(OWNER,"task-1","Title","🚀".repeat(4_194_304)));
        assertTrue(jdbc.rows.isEmpty());
    }

    @Test void alteredPersistedDescriptionIsNeverReturned() {
        service.captureOnCreate(OWNER,"task-1","Original title","Original description");
        jdbc.rows.getFirst().description="modified";
        assertThrows(IllegalStateException.class, () -> service.read(OWNER,"task-1",1));
    }

    @Test void ownerReconfirmationIsExplicitIdempotentAndFundedFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> service.reconfirm(OWNER,"task-1",0,
                "confirm-1","Title","Description"));
        jwt("owner","client");
        var initial=service.reconfirm(OWNER,"task-1",0,"confirm-1","Title","Description");
        assertEquals("RECONFIRM",initial.source());
        assertEquals(initial, service.reconfirm(OWNER,"task-1",0,"confirm-1","Title","Description"));
        assertThrows(IllegalStateException.class, () -> service.reconfirm(OWNER,"task-1",0,
                "confirm-1","Other title","Description"));
        var next=service.reconfirm(OWNER,"task-1",0,"confirm-2","Other title","New full body");
        assertEquals(2L,next.revision());
        assertEquals("Description",service.read(OWNER,"task-1",1).description());
        assertThrows(IllegalStateException.class, () -> service.requireCurrent(OWNER,"task-1",1));
        assertEquals(2L,service.requireCurrent(OWNER,"task-1",2).revision());
        jdbc.funded=true;
        assertThrows(IllegalStateException.class, () -> service.reconfirm(OWNER,"task-1",0,
                "confirm-3","Other title","New full body"));
        jwt("another-owner","client");
        assertThrows(IllegalArgumentException.class, () -> service.reconfirm(OWNER,"task-1",0,
                "confirm-4","Other title","New full body"));
    }


    @Test void currentReadLocksRootThenLatestAndReturnsRealRevisionWithoutWrites() {
        String title="完整原文标题".repeat(20), description="不可截断的原始正文🚀".repeat(80);
        service.captureOnCreate(OWNER,"task-1",title,description);
        jwt("owner","client");
        service.reconfirm(OWNER,"task-1",0,"confirm-2",title,description);
        task.setTaskVersion(Long.MAX_VALUE);
        int updatesBefore=jdbc.updateCalls;
        lockOrder.clear();

        AgentTaskRequirementSnapshotService.CurrentSnapshot current=
                service.readCurrent(OWNER,"task-1");

        assertEquals("task-1",current.taskId());
        assertEquals(Long.MAX_VALUE,current.taskVersion());
        assertEquals(2L,current.requirementRevision());
        assertEquals(title,current.title());
        assertEquals(description,current.description());
        assertEquals("RECONFIRM",current.source());
        assertEquals(64,current.sha256().length());
        assertEquals(List.of("task-root","requirement-snapshot"),lockOrder);
        assertEquals(updatesBefore,jdbc.updateCalls,"GET projection must not write");
    }

    @Test void currentReadFailsClosedAcrossMissingForeignCorruptAndUnavailableSources() {
        assertReason(AgentTaskRequirementReadException.Reason.RECONFIRM_REQUIRED,
                () -> service.readCurrent(OWNER,"task-1"));
        assertReason(AgentTaskRequirementReadException.Reason.NOT_FOUND,
                () -> service.readCurrent(
                        new AgentTaskExecutionGrantService.Scope("0","Client","owner"),"task-1"));
        assertThrows(IllegalArgumentException.class, () -> service.readCurrent(
                new AgentTaskExecutionGrantService.Scope("0","client ","owner"),"task-1"));
        assertThrows(IllegalArgumentException.class, () -> service.readCurrent(OWNER,"task-1 "));

        service.captureOnCreate(OWNER,"task-1","Original","Full immutable body");
        jdbc.rows.getFirst().description="tampered";
        assertReason(AgentTaskRequirementReadException.Reason.INTEGRITY_ERROR,
                () -> service.readCurrent(OWNER,"task-1"));

        jdbc.failReads=true;
        assertReason(AgentTaskRequirementReadException.Reason.SOURCE_UNAVAILABLE,
                () -> service.readCurrent(OWNER,"task-1"));
    }

    @Test void controllerUsesOnlyJwtScopeReturnsDecimalStringsNoStoreAndIgnoresSpoofHeaders()
            throws Exception {
        AgentTaskRequirementSnapshotService snapshots=mock(AgentTaskRequirementSnapshotService.class);
        when(snapshots.readCurrent(OWNER,"task-1")).thenReturn(
                new AgentTaskRequirementSnapshotService.CurrentSnapshot("task-1",Long.MAX_VALUE,
                        Long.MAX_VALUE,"Title",null,"a".repeat(64),"CREATE"));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(
                new AgentTaskRequirementSnapshotController(snapshots)).build();

        mvc.perform(get("/agent/tasks/task-1/requirements/current")
                        .principal(jwtToken("owner","client"))
                        .header("X-Owner-Jiacn","foreign")
                        .header("X-Client-Id","foreign")
                        .header("X-Tenant-Id","foreign"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"private, no-store"))
                .andExpect(jsonPath("$.data.taskId").value("task-1"))
                .andExpect(jsonPath("$.data.taskVersion").value("9223372036854775807"))
                .andExpect(jsonPath("$.data.requirementRevision").value("9223372036854775807"))
                .andExpect(jsonPath("$.data.description").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.contentSha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.data.source").value("CREATE"));
        verify(snapshots).readCurrent(OWNER,"task-1");
        verifyNoMoreInteractions(snapshots);
    }

    @Test void controllerMapsAuthParameterAndReadFailuresWithoutLeakingIdentifiers()
            throws Exception {
        AgentTaskRequirementSnapshotService snapshots=mock(AgentTaskRequirementSnapshotService.class);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(
                new AgentTaskRequirementSnapshotController(snapshots)).build();

        mvc.perform(get("/agent/tasks/task-1/requirements/current"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"private, no-store"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/agent/tasks/task-1/requirements/current?owner=foreign")
                        .principal(jwtToken("owner","client")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"private, no-store"))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        AgentTaskRequirementSnapshotController direct=
                new AgentTaskRequirementSnapshotController(snapshots);
        RuntimeException invalidTask=assertThrows(RuntimeException.class, () -> direct.current(
                "task-1 ",new MockHttpServletRequest(),jwtToken("owner","client")));
        assertEquals(400,direct.badRequest(invalidTask).getStatusCode().value());
        assertEquals("private, no-store",direct.badRequest(invalidTask)
                .getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertThrows(RuntimeException.class, () -> direct.current(
                "task-1",new MockHttpServletRequest(),jwtToken("owner ","client")));
        verifyNoInteractions(snapshots);

        for (var entry:List.of(
                new FailureCase(AgentTaskRequirementReadException.Reason.NOT_FOUND,404,"REQUIREMENT_NOT_FOUND"),
                new FailureCase(AgentTaskRequirementReadException.Reason.RECONFIRM_REQUIRED,409,"REQUIREMENT_RECONFIRM_REQUIRED"),
                new FailureCase(AgentTaskRequirementReadException.Reason.INTEGRITY_ERROR,500,"REQUIREMENT_INTEGRITY_ERROR"),
                new FailureCase(AgentTaskRequirementReadException.Reason.SOURCE_UNAVAILABLE,503,"REQUIREMENT_SOURCE_UNAVAILABLE"))) {
            reset(snapshots);
            when(snapshots.readCurrent(OWNER,"task-1")).thenThrow(
                    new AgentTaskRequirementReadException(entry.reason,
                            new DataAccessResourceFailureException("secret sql /private/path task-1")));
            mvc.perform(get("/agent/tasks/task-1/requirements/current")
                            .principal(jwtToken("owner","client")))
                    .andExpect(status().is(entry.status))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"private, no-store"))
                    .andExpect(jsonPath("$.code").value(entry.code))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("secret sql"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("/private/path"))));
        }

        reset(snapshots);
        when(snapshots.readCurrent(OWNER,"task-missing")).thenThrow(
                new AgentTaskRequirementReadException(AgentTaskRequirementReadException.Reason.NOT_FOUND));
        when(snapshots.readCurrent(new AgentTaskExecutionGrantService.Scope("0","client","foreign"),"task-missing"))
                .thenThrow(new AgentTaskRequirementReadException(AgentTaskRequirementReadException.Reason.NOT_FOUND));
        String missing=mvc.perform(get("/agent/tasks/task-missing/requirements/current")
                        .principal(jwtToken("owner","client"))).andReturn().getResponse().getContentAsString();
        String foreign=mvc.perform(get("/agent/tasks/task-missing/requirements/current")
                        .principal(jwtToken("foreign","client"))).andReturn().getResponse().getContentAsString();
        assertEquals(missing,foreign,"missing and foreign roots must be indistinguishable");
        assertFalse(missing.contains("task-missing"));
        assertFalse(missing.contains("foreign"));
    }

    @Test void controllerReadSwitchIsOptInAndDefaultsAbsent() {
        ConditionalOnProperty gate=AgentTaskRequirementSnapshotController.class
                .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(gate);
        assertEquals("agent.task-requirement-snapshot",gate.prefix());
        assertTrue(Arrays.asList(gate.name()).contains("read-enabled"));
        assertEquals("true",gate.havingValue());
        assertFalse(gate.matchIfMissing());
    }

    private static void assertReason(AgentTaskRequirementReadException.Reason reason,
            org.junit.jupiter.api.function.Executable action) {
        assertEquals(reason,assertThrows(AgentTaskRequirementReadException.class,action).reason());
    }

    private static JwtAuthenticationToken jwtToken(String owner,String client) {
        Jwt token=Jwt.withTokenValue("token").header("alg","none")
                .claim("jiacn",owner).claim("client_id",client).build();
        JwtAuthenticationToken authentication=new JwtAuthenticationToken(token);
        authentication.setAuthenticated(true);
        return authentication;
    }

    private record FailureCase(AgentTaskRequirementReadException.Reason reason,int status,String code) { }

    private static void jwt(String owner,String client) {
        Jwt token=Jwt.withTokenValue("token").header("alg","none")
                .claim("jiacn",owner).claim("client_id",client).build();
        var authentication=new JwtAuthenticationToken(token);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** Source-only JDBC fixture; does NOT simulate actual MySQL row locks or schema behavior. */
    private static final class MemoryJdbc extends JdbcTemplate {
        private final List<Row> rows=new ArrayList<>();
        private final List<String> lockOrder;
        private boolean funded;
        private boolean failReads;
        private int updateCalls;
        private MemoryJdbc(List<String> lockOrder) { this.lockOrder=lockOrder; }
        @Override public int update(String sql,Object... args) {
            updateCalls++;
            if (!sql.startsWith("INSERT INTO agent_task_requirement_snapshot")) throw new AssertionError(sql);
            if (rows.stream().anyMatch(r -> r.taskId.equals(args[3]) && r.revision==(long)args[4]))
                throw new IllegalStateException("Duplicate revision");
            rows.add(new Row((String)args[0],(String)args[1],(String)args[2],(String)args[3],
                    (long)args[4],(String)args[5],(long)args[6],(String)args[7],
                    (String)args[8],(String)args[9],(String)args[10]));
            return 1;
        }
        @Override public <T> T queryForObject(String sql,Class<T> type,Object... args) {
            if (sql.contains("FROM agent_task_funding")) return type.cast(funded?1:0);
            if (sql.contains("task_version_at_confirmation")) return type.cast(rows.stream()
                    .filter(r -> Objects.equals(r.confirmationId,args[4]) && r.revision==(long)args[5])
                    .findFirst().orElseThrow().taskVersion);
            throw new AssertionError(sql);
        }
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
            if (failReads) throw new DataAccessResourceFailureException("database unavailable");
            if (sql.contains("ORDER BY revision DESC") && sql.contains("FOR UPDATE"))
                lockOrder.add("requirement-snapshot");
            List<Row> matches=rows.stream().filter(r -> r.tenant.equals(args[0])
                    && r.client.equals(args[1]) && r.owner.equals(args[2])
                    && r.taskId.equals(args[3])).filter(r -> {
                        if (sql.contains("AND confirmation_id=?")) return r.confirmationId.equals(args[4]);
                        if (sql.contains("AND revision=?")) return r.revision==(long)args[4];
                        return true;
                    }).sorted(Comparator.comparingLong((Row r)->r.revision).reversed()).toList();
            if (sql.contains("ORDER BY revision DESC LIMIT 1") && !matches.isEmpty())
                matches=matches.subList(0,1);
            List<T> results=new ArrayList<>();
            for (Row row:matches) {
                ResultSet rs=mock(ResultSet.class);
                try {
                    when(rs.getString(1)).thenReturn(row.tenant);when(rs.getString(2)).thenReturn(row.client);
                    when(rs.getString(3)).thenReturn(row.owner);when(rs.getString(4)).thenReturn(row.taskId);
                    when(rs.getLong(5)).thenReturn(row.revision);when(rs.getString(6)).thenReturn(row.title);
                    when(rs.getString(7)).thenReturn(row.description);when(rs.getString(8)).thenReturn(row.hash);
                    when(rs.getString(9)).thenReturn(row.source);
                    results.add(mapper.mapRow(rs,results.size()));
                } catch (SQLException ex) { throw new IllegalStateException(ex); }
            }
            return results;
        }
        private static final class Row {
            final String tenant,client,owner,taskId,confirmationId,title,hash,source;
            final long revision,taskVersion;
            String description;
            Row(String tenant,String client,String owner,String taskId,long revision,
                    String confirmationId,long taskVersion,String title,String description,
                    String hash,String source) {
                this.tenant=tenant;this.client=client;this.owner=owner;this.taskId=taskId;
                this.revision=revision;this.confirmationId=confirmationId;
                this.taskVersion=taskVersion;this.title=title;this.description=description;
                this.hash=hash;this.source=source;
            }
        }
    }
}
