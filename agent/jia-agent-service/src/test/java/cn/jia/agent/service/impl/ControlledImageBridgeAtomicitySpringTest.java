package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;


import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring REQUIRED rollback evidence; no Provider, websocket or external database is used. */
class ControlledImageBridgeAtomicitySpringTest {
    private JdbcTemplate jdbc;
    private AgentTaskMutationTransaction transactions;

    @AfterEach void clearRuntimePrincipal() { SecurityContextHolder.clearContext(); }

    @BeforeEach void setUp() {
        DriverManagerDataSource source=new DriverManagerDataSource(
                "jdbc:h2:mem:controlled_bridge_atomicity;MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc=new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS bridge_atomic_evidence");
        jdbc.execute("CREATE TABLE bridge_atomic_evidence(label VARCHAR(40) PRIMARY KEY)");
        AgentTaskMetaDao roots=mock(AgentTaskMetaDao.class);
        when(roots.findByTaskIdForUpdateInOwnerScope("0","client","owner","task"))
                .thenReturn(root());
        transactions=new AgentTaskMutationTransactionImpl(roots,new DataSourceTransactionManager(source));
    }

    @Test void taskRootTransactionRollsBackOnFailure() {
        assertThrows(IllegalStateException.class, () -> transactions.executeWithLockedTaskRootInOwnerScope(
                "0","client","owner","task", root -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    jdbc.update("INSERT INTO bridge_atomic_evidence(label) VALUES ('mutation')");
                    throw new IllegalStateException("rollback fixture");
                }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM bridge_atomic_evidence",Integer.class));
    }



    private static AgentTaskMetaEntity root() {
        AgentTaskMetaEntity row=new AgentTaskMetaEntity().setTaskId("task").setTaskVersion(7L)
                .setCurrentEventVersion(7L).setAssignedAgentId("agent");
        row.setTenantId("0");row.setClientId("client");row.setOwnerJiacn("owner");return row;
    }
}
