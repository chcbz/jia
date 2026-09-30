package cn.jia.chat.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatSchemaReadinessLifecycleTest {
    private static final Map<String, Set<String>> SOURCE_COLUMNS = Map.of(
            "chat_conversation_asset", Set.of("asset_id", "tenant_id", "owner_jiacn", "client_id",
                    "conversation_id", "conversation_generation", "request_id", "step_id",
                    "execution_id", "run_id", "output_id", "content_mime_type", "sha256",
                    "byte_length", "revision"),
            "chat_interaction_step", Set.of("step_id", "tenant_id", "owner_jiacn", "client_id",
                    "request_id", "request_revision", "conversation_id", "conversation_generation",
                    "task_id", "kind", "state"),
            "chat_step_execution_link", Set.of("tenant_id", "owner_jiacn", "client_id", "step_id",
                    "execution_id", "state"),
            "chat_request", Set.of("tenant_id", "owner_jiacn", "client_id", "request_id",
                    "request_revision", "conversation_id", "conversation_generation", "aggregate_state"),
            "chat_conversation", Set.of("id", "tenant_id", "jiacn", "client_id", "conversation_type",
                    "conversation_scope_type", "conversation_scope_key", "task_id", "deleted_at",
                    "lifecycle_generation"));
    private static final List<String> OPERATION_COLUMNS = List.of("operation_id", "tenant_id",
            "owner_jiacn", "client_id", "conversation_id", "conversation_generation",
            "idempotency_key", "request_sha256", "asset_id", "asset_revision", "state",
            "workspace_operation_id", "file_id", "file_version", "error_code", "message",
            "row_revision", "created_at", "updated_at");
    private static final Map<String, IndexSpec> OPERATION_INDEXES = Map.of(
            "PRIMARY", new IndexSpec(true, List.of("operation_id")),
            "uk_chat_archive_key", new IndexSpec(true,
                    List.of("tenant_id", "owner_jiacn", "client_id", "idempotency_key")),
            "uk_chat_archive_source", new IndexSpec(true,
                    List.of("tenant_id", "owner_jiacn", "client_id", "asset_id", "asset_revision")),
            "uk_chat_archive_workspace", new IndexSpec(true,
                    List.of("tenant_id", "owner_jiacn", "client_id", "workspace_operation_id")),
            "idx_chat_archive_conversation", new IndexSpec(false,
                    List.of("tenant_id", "owner_jiacn", "client_id", "conversation_id",
                            "updated_at", "operation_id")));
    private static final Set<String> OPERATION_CHECKS = Set.of("chk_chat_archive_asset_revision",
            "chk_chat_archive_generation", "chk_chat_archive_key_length",
            "chk_chat_archive_request_sha", "chk_chat_archive_state",
            "chk_chat_archive_row_revision", "chk_chat_archive_saved_receipt");

    @Test
    void legacyInitializingBeanReproducesFailureBeforeApplicationRunnersExecute() {
        LifecycleState state = new LifecycleState();

        Exception failure = assertThrows(Exception.class, () -> runApplication(state, true));

        assertTrue(messageChain(failure).contains("legacy archive observed source schema before runners"),
                messageChain(failure));
        assertEquals(0, state.coreAttempts);
        assertEquals(0, state.deliberationAttempts);
        assertEquals(0, state.coreRunnerCalls);
        assertEquals(0, state.deliberationRunnerCalls);
    }

    @Test
    void realSpringLifecycleMakesSourcesReadyBeforeArchiveAndRunnerPhaseDoesNotRepeat() {
        LifecycleState state = new LifecycleState();

        try (ConfigurableApplicationContext ignored = runApplication(state, false)) {
            assertEquals(1, state.coreAttempts);
            assertEquals(1, state.deliberationAttempts);
            assertEquals(1, state.coreRunnerCalls);
            assertEquals(1, state.deliberationRunnerCalls);
            int deliberationReady = state.events.indexOf("deliberation-ready");
            int archiveDdl = state.events.indexOf("archive-ddl");
            assertTrue(deliberationReady >= 0, state.events.toString());
            for (String table : SOURCE_COLUMNS.keySet()) {
                int sourceValidation = state.events.indexOf("archive-source:" + table);
                assertTrue(sourceValidation > deliberationReady, state.events.toString());
                assertTrue(sourceValidation < archiveDdl, state.events.toString());
            }
        }
    }

    @Test
    void partialFailureIsNotCachedAndRetryDoesNotRepeatCompletedCoreSchema() throws Exception {
        LifecycleState state = new LifecycleState();
        state.failDeliberationAttempts = 1;
        RecordingChatSchemaInitializer core = new RecordingChatSchemaInitializer(state);
        RecordingDeliberationSchemaInitializer deliberation = new RecordingDeliberationSchemaInitializer(state);
        ChatSchemaReadiness readiness = new ChatSchemaReadiness(core, deliberation);

        assertThrows(IllegalStateException.class, readiness::ensureInitialized);
        assertEquals(1, state.coreAttempts);
        assertEquals(1, state.deliberationAttempts);

        readiness.ensureInitialized();
        core.run(null);
        deliberation.run(null);
        readiness.ensureInitialized();

        assertEquals(1, state.coreAttempts);
        assertEquals(2, state.deliberationAttempts);
    }

    @Test
    void missingSourceColumnStillFailsBeforeArchiveDdl() {
        LifecycleState state = new LifecycleState();
        state.sourceColumnDrift = true;

        Exception failure = assertThrows(Exception.class, () -> runApplication(state, false));

        assertTrue(messageChain(failure).contains("Conversation archive source schema is unavailable"),
                messageChain(failure));
        assertEquals(1, state.coreAttempts);
        assertEquals(1, state.deliberationAttempts);
        assertFalse(state.archiveTableCreated, state.events.toString());
    }

    @Test
    void archiveIndexDriftStillFailsClosedAfterSourcesBecomeReady() {
        LifecycleState state = new LifecycleState();
        state.archiveIndexDrift = true;

        Exception failure = assertThrows(Exception.class, () -> runApplication(state, false));

        assertTrue(messageChain(failure).contains("Conversation archive index drift"), messageChain(failure));
        assertEquals(1, state.coreAttempts);
        assertEquals(1, state.deliberationAttempts);
    }

    private ConfigurableApplicationContext runApplication(LifecycleState state, boolean legacy) {
        SpringApplication application = new SpringApplication(TestApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        application.setLogStartupInfo(false);
        application.setDefaultProperties(Map.<String, Object>of(
                "spring.main.banner-mode", "off",
                "chat.conversation-archive.enabled", "true"));
        application.addInitializers(applicationContext -> {
            GenericApplicationContext context = (GenericApplicationContext) applicationContext;
            context.registerBean(LifecycleState.class, () -> state);
            context.registerBean(JdbcTemplate.class, () -> archiveJdbcTemplate(state));
            context.registerBean(ChatSchemaInitializer.class,
                    () -> new RecordingChatSchemaInitializer(state));
            context.registerBean(ChatDeliberationSchemaInitializer.class,
                    () -> new RecordingDeliberationSchemaInitializer(state));
            if (legacy) {
                context.registerBean(LegacyArchiveInitializer.class,
                        () -> new LegacyArchiveInitializer(state));
            } else {
                context.registerBean(ChatSchemaReadiness.class,
                        () -> new ChatSchemaReadiness(context.getBean(ChatSchemaInitializer.class),
                                context.getBean(ChatDeliberationSchemaInitializer.class)));
                context.registerBean(ChatConversationArchiveSchemaInitializer.class,
                        () -> new ChatConversationArchiveSchemaInitializer(
                                context.getBean(JdbcTemplate.class), context.getBean(ChatSchemaReadiness.class)));
            }
        });
        return application.run();
    }

    private JdbcTemplate archiveJdbcTemplate(LifecycleState state) {
        try {
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            DatabaseMetaData metadata = mock(DatabaseMetaData.class);
            Statement statement = mock(Statement.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.getMetaData()).thenReturn(metadata);
            when(connection.createStatement()).thenReturn(statement);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.isClosed()).thenReturn(false);
            when(metadata.getDatabaseProductName()).thenReturn("MySQL");
            when(metadata.getDatabaseMajorVersion()).thenReturn(8);
            when(statement.execute(anyString())).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0, String.class).toLowerCase();
                if (sql.contains("create table if not exists chat_conversation_archive_operation")) {
                    state.archiveTableCreated = true;
                    state.events.add("archive-ddl");
                }
                return false;
            });
            return new ArchiveJdbcTemplate(dataSource, state);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String messageChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }

    @Configuration(proxyBeanMethods = false)
    static class TestApplication { }

    private static final class LifecycleState {
        private final List<String> events = new ArrayList<>();
        private int coreAttempts;
        private int deliberationAttempts;
        private int coreRunnerCalls;
        private int deliberationRunnerCalls;
        private int failDeliberationAttempts;
        private boolean coreReady;
        private boolean deliberationReady;
        private boolean archiveTableCreated;
        private boolean sourceColumnDrift;
        private boolean archiveIndexDrift;
    }

    private static final class RecordingChatSchemaInitializer extends ChatSchemaInitializer {
        private final LifecycleState state;

        private RecordingChatSchemaInitializer(LifecycleState state) {
            super(new JdbcTemplate());
            this.state = state;
        }

        @Override
        public void run(ApplicationArguments args) {
            state.coreRunnerCalls++;
            super.run(args);
        }

        @Override
        void initializeSchema() {
            state.coreAttempts++;
            state.coreReady = true;
            state.events.add("core-ready");
        }
    }

    private static final class RecordingDeliberationSchemaInitializer
            extends ChatDeliberationSchemaInitializer {
        private final LifecycleState state;

        private RecordingDeliberationSchemaInitializer(LifecycleState state) {
            super(new JdbcTemplate());
            this.state = state;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            state.deliberationRunnerCalls++;
            super.run(args);
        }

        @Override
        void initializeSchema() {
            state.deliberationAttempts++;
            if (!state.coreReady) throw new IllegalStateException("deliberation initialized before core chat schema");
            if (state.failDeliberationAttempts > 0) {
                state.failDeliberationAttempts--;
                throw new IllegalStateException("simulated deliberation schema failure");
            }
            state.deliberationReady = true;
            state.events.add("deliberation-ready");
        }
    }

    private static final class LegacyArchiveInitializer implements InitializingBean {
        private final LifecycleState state;

        private LegacyArchiveInitializer(LifecycleState state) {
            this.state = state;
        }

        @Override
        public void afterPropertiesSet() {
            if (!state.coreReady || !state.deliberationReady)
                throw new IllegalStateException("legacy archive observed source schema before runners");
        }
    }

    private static final class ArchiveJdbcTemplate extends JdbcTemplate {
        private final LifecycleState state;

        private ArchiveJdbcTemplate(DataSource dataSource, LifecycleState state) {
            super(dataSource);
            this.state = state;
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            String normalized = sql.toLowerCase();
            if (normalized.contains("from information_schema.columns")) {
                String table = String.valueOf(args[0]);
                state.events.add("archive-source:" + table);
                List<String> columns;
                if (ChatConversationArchiveSchemaInitializer.TABLE.equals(table)) {
                    columns = state.archiveTableCreated ? OPERATION_COLUMNS : List.of();
                } else {
                    columns = state.coreReady && state.deliberationReady
                            ? SOURCE_COLUMNS.getOrDefault(table, Set.of()).stream()
                            .filter(column -> !state.sourceColumnDrift
                                    || !"chat_conversation_asset".equals(table)
                                    || !"sha256".equals(column))
                            .toList()
                            : List.of();
                }
                return columns.stream().map(elementType::cast).toList();
            }
            throw new AssertionError("Unexpected scalar query: " + sql);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            String normalized = sql.toLowerCase();
            if (normalized.contains("from information_schema.tables")) {
                if (!state.archiveTableCreated) return List.of();
                return List.of(row("engine", "InnoDB", "table_collation", "utf8mb4_0900_bin"));
            }
            if (normalized.contains("from information_schema.statistics")) {
                List<Map<String, Object>> rows = new ArrayList<>();
                OPERATION_INDEXES.forEach((name, spec) -> {
                    List<String> columns = spec.columns();
                    if (state.archiveIndexDrift && "uk_chat_archive_source".equals(name))
                        columns = List.of("tenant_id", "owner_jiacn", "client_id", "asset_revision", "asset_id");
                    for (int index = 0; index < columns.size(); index++) {
                        rows.add(row("index_name", name, "non_unique", spec.unique() ? 0 : 1,
                                "seq_in_index", index + 1, "column_name", columns.get(index),
                                "sub_part", null));
                    }
                });
                return rows;
            }
            if (normalized.contains("from information_schema.table_constraints")) {
                return OPERATION_CHECKS.stream()
                        .map(name -> row("constraint_name", name, "enforced", "YES"))
                        .toList();
            }
            throw new AssertionError("Unexpected metadata query: " + sql);
        }

        private static Map<String, Object> row(Object... values) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 0; index < values.length; index += 2)
                row.put(String.valueOf(values[index]), values[index + 1]);
            return row;
        }
    }

    private record IndexSpec(boolean unique, List<String> columns) { }
}
