package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskExecutionGrantService.Scope;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentTaskCurrentRequirementTransactionTest {
    static final Scope OWNER=new Scope("0","client","owner");
    JdbcTemplate jdbc; TransactionTemplate tx; AgentTaskRequirementSnapshotServiceImpl service;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:current-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        for (String name:new String[]{"agent-task-requirement-snapshot-v1.sql","agent-task-current-requirement-v1.sql","agent-task-requirement-edit-operation-v1.sql"}) {
            String ddl=new ClassPathResource("db/"+name).getContentAsString(StandardCharsets.UTF_8)
                    .replaceAll("(?i) COLLATE [a-z0-9_]+", "").replaceAll("(?i) CHARACTER SET [a-z0-9_]+", "")
                    .replaceAll("(?i) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
            jdbc.execute(ddl);
        }
        var tasks=mock(AgentTaskMetaDao.class);
        when(tasks.findByTaskIdForUpdateInOwnerScope(anyString(),anyString(),anyString(),anyString())).thenAnswer(i->{
            var r=new AgentTaskMetaEntity().setTaskId(i.getArgument(3)).setOwnerJiacn(i.getArgument(2)).setTaskVersion(0L).setCurrentEventVersion(0L).setRewardStatus("open");
            r.setTenantId(i.getArgument(0));r.setClientId(i.getArgument(1));return r;
        });
        service=new AgentTaskRequirementSnapshotServiceImpl(jdbc,new AgentTaskMutationTransactionImpl(tasks,manager));
    }
    int count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @Test void manyEditsHaveOneCurrentRowAndOnlyBusinessBoundaryFreezes() {
        tx.execute(s->service.captureOnCreate(OWNER,"task","Original","Body"));
        for(int i=1;i<=20;i++){final int revision=i;tx.execute(s->service.updateCurrent(OWNER,"task",revision,"Title "+revision,"Body "+revision));}
        assertEquals(1,count("agent_task_current_requirement"));assertEquals(1,count("agent_task_requirement_snapshot"));
        assertEquals(21,service.readCurrent(OWNER,"task").requirementRevision());
        assertThrows(IllegalStateException.class,()->service.read(OWNER,"task",21));
        var frozen=tx.execute(s->service.freezeCurrent(OWNER,"task",21));
        assertEquals("Title 20",frozen.title());assertEquals(2,count("agent_task_requirement_snapshot"));
        assertEquals(frozen,tx.execute(s->service.freezeCurrent(OWNER,"task",21)));
        assertEquals(2,count("agent_task_requirement_snapshot"));
        assertEquals("Original",service.read(OWNER,"task",1).title());
    }
    @Test void currentReplacementAndBusinessFreezeRollbackTogetherWithCaller() {
        tx.execute(s->service.captureOnCreate(OWNER,"task","Original","Body"));
        assertThrows(IllegalStateException.class,()->tx.execute(s->{service.updateCurrent(OWNER,"task",1,"Changed","Body");service.freezeCurrent(OWNER,"task",2);throw new IllegalStateException("assignment failed");}));
        assertEquals("Original",service.readCurrent(OWNER,"task").title());assertEquals(1,count("agent_task_requirement_snapshot"));
    }
    @Test void backfillIsExactIdempotentAndNeverOverwritesCurrentEdits() throws Exception {
        tx.execute(s->service.captureOnCreate(OWNER,"task","Full original 🚀","Body"));
        var original=service.read(OWNER,"task",1);
        jdbc.update("DELETE FROM agent_task_current_requirement");
        String sql=new ClassPathResource("db/agent-task-current-requirement-backfill-v1.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(sql);jdbc.execute(sql);
        assertEquals(original.sha256(),service.readCurrent(OWNER,"task").sha256());
        tx.execute(s->service.updateCurrent(OWNER,"task",1,"Later","Later body"));jdbc.execute(sql);
        assertEquals("Later",service.readCurrent(OWNER,"task").title());assertEquals(1,count("agent_task_current_requirement"));
        assertEquals(original,service.read(OWNER,"task",1));
    }
    @Test void sameTaskIdInDifferentScopesHasIndependentCurrentState() {
        var other=new Scope("1","client","owner");
        tx.execute(s->service.captureOnCreate(OWNER,"task","Production","Body"));tx.execute(s->service.captureOnCreate(other,"task","Gray","Body"));
        tx.execute(s->service.updateCurrent(other,"task",1,"Gray changed","Body"));
        assertEquals("Production",service.readCurrent(OWNER,"task").title());assertEquals("Gray changed",service.readCurrent(other,"task").title());
    }
}
