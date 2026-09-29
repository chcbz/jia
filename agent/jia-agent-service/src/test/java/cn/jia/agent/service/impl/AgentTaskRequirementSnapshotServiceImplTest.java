package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTaskRequirementSnapshotServiceImplTest {
    private final MemoryJdbc jdbc=new MemoryJdbc();
    private final AgentTaskMutationTransaction transactions=mock(AgentTaskMutationTransaction.class);
    private final AgentTaskMetaEntity task=new AgentTaskMetaEntity();
    private AgentTaskRequirementSnapshotServiceImpl service;
    private static final AgentTaskExecutionGrantService.Scope OWNER =
            new AgentTaskExecutionGrantService.Scope("0","client","owner");

    @BeforeEach void before() {
        task.setTaskVersion(0L);
        when(transactions.executeWithLockedTaskRootInOwnerScope(anyString(),anyString(),
                anyString(),anyString(),any())).thenAnswer(i ->
                ((AgentTaskMutationTransaction.LockedTaskMutation<?>)i.getArgument(4)).apply(task));
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
        private boolean funded;
        @Override public int update(String sql,Object... args) {
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
