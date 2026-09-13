package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskContextPackDao;
import cn.jia.agent.entity.AgentTaskArtifactAcceptDTO;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeViewDTO;
import cn.jia.agent.entity.AgentTaskContextPackTaskSourceRow;
import cn.jia.agent.entity.AgentTaskWorkspaceDTO;
import cn.jia.agent.service.AgentTaskArtifactOutcomeService;
import cn.jia.agent.service.AgentTaskContextPackConversationSource;
import cn.jia.agent.service.AgentTaskContextPackService;
import cn.jia.agent.service.AgentTaskWorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentTaskContextPackRepeatableReadTest {
    @Test
    void concurrentCommitCannotMixRowsAcrossContextPackSections() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Config.class)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("INSERT INTO f01_snapshot(id, snapshot_value) VALUES (1, 'old')");

            var pack = context.getBean(AgentTaskContextPackService.class)
                    .generate("tenant", "client", "1", "actor", "1");

            assertEquals("old", pack.getTaskDescription().getTitle().getValue());
            assertEquals("old", pack.getAuthoritativeArtifacts()
                    .getItems().get(0).getTitle().getValue());
            assertEquals("new", jdbc.queryForObject(
                    "SELECT snapshot_value FROM f01_snapshot WHERE id=1", String.class));
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        private final AtomicBoolean updated = new AtomicBoolean();

        @Bean(destroyMethod = "shutdown")
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("f01-context-pack-race")
                    .build();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("DROP TABLE IF EXISTS f01_snapshot");
            jdbc.execute("CREATE TABLE f01_snapshot(id INT PRIMARY KEY, snapshot_value VARCHAR(20))");
            return jdbc;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new H2SnapshotTransactionManager(dataSource);
        }

        @Bean
        AgentTaskWorkspaceService workspaceService(JdbcTemplate jdbc) {
            return (tenantId, clientId, taskId, actorAgentId) -> {
                jdbc.queryForObject("SELECT snapshot_value FROM f01_snapshot WHERE id=1", String.class);
                AgentTaskWorkspaceDTO result = new AgentTaskWorkspaceDTO();
                AgentTaskWorkspaceDTO.Task task = new AgentTaskWorkspaceDTO.Task();
                task.setTaskId(taskId);
                task.setVersion("1");
                result.setTask(task);
                result.setMembers(List.of());
                result.setWorkItems(List.of());
                result.setOpenRequests(List.of());
                result.setRecentEvents(List.of());
                result.setCurrentVersion("1");
                return result;
            };
        }

        @Bean
        AgentTaskContextPackDao contextPackDao(
                JdbcTemplate jdbc, DataSource dataSource) {
            return (tenantId, clientId, taskId) -> {
                String value = jdbc.queryForObject(
                        "SELECT snapshot_value FROM f01_snapshot WHERE id=1", String.class);
                if (updated.compareAndSet(false, true)) {
                    updateOutsideTransaction(dataSource);
                }
                AgentTaskContextPackTaskSourceRow row =
                        new AgentTaskContextPackTaskSourceRow();
                row.setTenantId(tenantId);
                row.setClientId(clientId);
                row.setTaskId(taskId);
                row.setPlanId(1L);
                row.setTitle(value);
                row.setDescription(value);
                return row;
            };
        }

        @Bean
        AgentTaskArtifactOutcomeService outcomeService(JdbcTemplate jdbc) {
            return new AgentTaskArtifactOutcomeService() {
                @Override
                public AgentTaskArtifactOutcomeViewDTO accept(
                        String tenantId, String clientId, String taskId,
                        String actorAgentId, AgentTaskArtifactAcceptDTO command) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<AgentTaskArtifactOutcomeViewDTO> listAuthoritativeAccepted(
                        String tenantId, String clientId, String taskId,
                        String actorAgentId, String workItemId, Integer limit) {
                    String value = jdbc.queryForObject(
                            "SELECT snapshot_value FROM f01_snapshot WHERE id=1", String.class);
                    AgentTaskArtifactOutcomeViewDTO artifact =
                            new AgentTaskArtifactOutcomeViewDTO();
                    artifact.setArtifactId("artifact");
                    artifact.setTaskId(taskId);
                    artifact.setProducerAgentId(actorAgentId);
                    artifact.setArtifactType("analysis");
                    artifact.setTitle(value);
                    artifact.setContentHash("a".repeat(64));
                    artifact.setArtifactVersion(1);
                    artifact.setVisibility("task_members");
                    artifact.setCreatedAt(1L);
                    artifact.setOutcomeState("accepted");
                    artifact.setOutcomeVersion(1L);
                    artifact.setDecisionId("decision");
                    artifact.setDecidedByAgentId(actorAgentId);
                    artifact.setDecidedAt(1L);
                    return List.of(artifact);
                }
            };
        }

        @Bean
        AgentTaskContextPackServiceImpl contextPackService(
                AgentTaskWorkspaceService workspaceService,
                AgentTaskArtifactOutcomeService outcomeService,
                AgentTaskContextPackDao contextPackDao,
                Optional<AgentTaskContextPackConversationSource> conversationSource) {
            return new AgentTaskContextPackServiceImpl(workspaceService, outcomeService,
                    contextPackDao, conversationSource);
        }

        private static final class H2SnapshotTransactionManager
                extends DataSourceTransactionManager {
            private H2SnapshotTransactionManager(DataSource dataSource) {
                super(dataSource);
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                super.doBegin(transaction, definition);
                if (definition.getIsolationLevel()
                        == TransactionDefinition.ISOLATION_REPEATABLE_READ) {
                    ConnectionHolder holder = (ConnectionHolder)
                            TransactionSynchronizationManager.getResource(obtainDataSource());
                    if (holder == null) {
                        throw new IllegalStateException("snapshot connection is not bound");
                    }
                    try (Statement statement = holder.getConnection().createStatement()) {
                        statement.execute("SET TRANSACTION ISOLATION LEVEL SNAPSHOT");
                    } catch (SQLException exception) {
                        throw new IllegalStateException("cannot enable H2 snapshot", exception);
                    }
                }
            }
        }

        private static void updateOutsideTransaction(DataSource dataSource) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE f01_snapshot SET snapshot_value='new' WHERE id=1")) {
                connection.setAutoCommit(true);
                assertEquals(1, statement.executeUpdate());
            } catch (Exception exception) {
                throw new IllegalStateException("concurrent fixture update failed", exception);
            }
        }
    }
}
