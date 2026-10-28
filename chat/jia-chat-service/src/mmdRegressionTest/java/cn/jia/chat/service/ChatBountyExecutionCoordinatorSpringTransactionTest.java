package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Retirement must neither mutate history nor poison a caller's real Spring transaction. */
class ChatBountyExecutionCoordinatorSpringTransactionTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ChatInteractionStepStore steps=mock(ChatInteractionStepStore.class);
    private final ChatDeliberationDao requests=mock(ChatDeliberationDao.class);
    private final ChatConversationDao conversations=mock(ChatConversationDao.class);
    private final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
    private final AgentTaskRequirementSnapshotService requirements=mock(AgentTaskRequirementSnapshotService.class);
    private final PersonalWorkspaceExecutionService executions=mock(PersonalWorkspaceExecutionService.class);
    private final ChatBountyExecutionCoordinator coordinator=new ChatBountyExecutionCoordinator(jdbc,steps,
            requests,conversations,grants,requirements,executions,new ObjectMapper());
    private final ChatBountyExecutionCoordinator.Pending candidate=
            new ChatBountyExecutionCoordinator.Pending("0","owner","client","req","step");

    private JdbcTemplate evidence;
    private TransactionTemplate transaction;

    @BeforeEach void setUp() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:retired_coordinator_"
                +java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        evidence=new JdbcTemplate(source);
        evidence.execute("CREATE TABLE history(label VARCHAR(40) PRIMARY KEY, state VARCHAR(40))");
        evidence.update("INSERT INTO history VALUES ('intent','WAITING_AUTHORIZATION')");
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @Test void retiredCandidateLeavesHistoryAndSurroundingCommitIntact() {
        transaction.execute(status->{
            assertEquals("RETIRED",coordinator.coordinate(candidate));
            assertTrue(coordinator.pending(100).isEmpty());
            assertFalse(status.isRollbackOnly());
            evidence.update("INSERT INTO history VALUES ('caller','COMMITTED')");
            return null;
        });
        assertEquals("WAITING_AUTHORIZATION",state("intent"));
        assertEquals("COMMITTED",state("caller"));
        verifyNoInteractions(jdbc,steps,requests,conversations,grants,requirements,executions);
    }

    @Test void callerRollbackDoesNotCreateExecutionOrChangeHistoricalIntent() {
        assertThrows(IllegalStateException.class,()->transaction.execute(status->{
            evidence.update("INSERT INTO history VALUES ('caller','PENDING')");
            assertEquals("RETIRED",coordinator.coordinate(candidate));
            throw new IllegalStateException("caller failure");
        }));
        assertEquals(1,evidence.queryForObject("SELECT COUNT(*) FROM history",Integer.class));
        assertEquals("WAITING_AUTHORIZATION",state("intent"));
        verifyNoInteractions(jdbc,steps,requests,conversations,grants,requirements,executions);
    }

    private String state(String key) {
        return evidence.queryForObject("SELECT state FROM history WHERE label=?",String.class,key);
    }
}
