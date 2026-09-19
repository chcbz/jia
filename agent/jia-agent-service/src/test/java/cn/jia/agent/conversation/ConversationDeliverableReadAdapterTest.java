package cn.jia.agent.conversation;

import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationDeliverableReadAdapterTest {
    private static final ConversationDeliverableReadAdapter.Scope SCOPE =
            new ConversationDeliverableReadAdapter.Scope("0", "client-a", "owner-a");
    private static final String CONVERSATION = "7";

    private WorkspaceConversationAccessService access;
    private RecordingJdbcTemplate jdbc;
    private ConversationDeliverableReadAdapter adapter;

    @BeforeEach
    void setUp() {
        access = mock(WorkspaceConversationAccessService.class);
        jdbc = new RecordingJdbcTemplate();
        adapter = new ConversationDeliverableReadAdapter(access, jdbc);
        when(access.requireAccessible(new WorkspaceConversationAccessService.Scope(
                "0", "client-a", "owner-a"), CONVERSATION))
                .thenAnswer(invocation -> {
                    jdbc.order.add("acl");
                    return new WorkspaceConversationAccessService.ConversationView(
                            CONVERSATION, "private", "agent:a", null,
                            List.of("a"), 1, 1);
                });
    }

    @Test
    void authorizesBeforeExactScopedQueryAndReturnsExplicitEmptyState() {
        ConversationDeliverableReadAdapter.Page page = adapter.list(SCOPE, CONVERSATION, 25);

        assertEquals(List.of("acl", "sql"), jdbc.order);
        assertEquals("EMPTY", page.state());
        assertTrue(page.items().isEmpty());
        assertFalse(page.publicationPending());
        assertNull(page.nextCursor());
        assertEquals(13, jdbc.args.length);
        assertEquals(List.of("0", "client-a", "owner-a", CONVERSATION),
                List.of(jdbc.args[0], jdbc.args[1], jdbc.args[2], jdbc.args[3]));
        assertEquals(26, jdbc.args[12]);
        String sql = jdbc.sql.toLowerCase();
        assertTrue(sql.contains("e.conversation_id=?"));
        for (String forbidden : List.of("storage_uri", "lease_token", "original_filename",
                "instruction", "failure_message", "failure_code")) {
            assertFalse(sql.contains(forbidden), forbidden);
        }
    }

    @Test
    void aclDenialIsOpaqueAndPreventsAnyExecutionQuery() {
        when(access.requireAccessible(new WorkspaceConversationAccessService.Scope(
                "0", "client-a", "owner-a"), CONVERSATION))
                .thenThrow(new IllegalStateException("foreign owner detail"));

        ConversationDeliverableReadAdapter.Failure failure = assertThrows(
                ConversationDeliverableReadAdapter.Failure.class,
                () -> adapter.list(SCOPE, CONVERSATION, 100));

        assertEquals(ConversationDeliverableReadAdapter.Reason.NOT_FOUND, failure.reason());
        assertNull(jdbc.sql);
    }

    @Test
    void committedPrivateOutputReturnsOnlyTrustedImmutableReferences() {
        jdbc.rows = List.of(committed("PRIVATE"));

        ConversationDeliverableReadAdapter.Page page = adapter.list(SCOPE, CONVERSATION, 100);

        assertEquals("AVAILABLE", page.state());
        assertFalse(page.publicationPending());
        assertEquals(1, page.items().size());
        ConversationDeliverableReadAdapter.Item item = page.items().getFirst();
        assertEquals("out-1", item.outputId());
        assertEquals("exec-1", item.executionId());
        assertEquals("file-1", item.fileId());
        assertEquals(1, item.fileVersion());
        assertEquals("a".repeat(64), item.contentHash());
        assertEquals("application/pdf", item.contentMimeType());
        assertEquals(42, item.byteLength());
        assertEquals(1234, item.committedAt());
        assertEquals("WORKSPACE_COMMITTED", item.publicationState());
        assertEquals("NOT_APPLICABLE", item.formalDeliveryState());
        List<String> fields = java.util.Arrays.stream(
                        ConversationDeliverableReadAdapter.Item.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        for (String forbidden : List.of("storageUri", "leaseToken", "originalFilename",
                "content", "body", "instruction", "metadata")) {
            assertFalse(fields.contains(forbidden), forbidden);
        }
    }

    @Test
    void stagedOrUnmappedTaskOutputIsSyncingAndNeverPseudoDelivered() {
        Map<String, Object> staged = base("TASK", "OUTPUT_STAGED", "STAGED");
        staged.put("workspace_file_id", null);
        staged.put("workspace_file_version", null);
        staged.put("committed_at", null);
        jdbc.rows = List.of(staged);
        ConversationDeliverableReadAdapter.Page stagedPage =
                adapter.list(SCOPE, CONVERSATION, 100);
        assertEquals("SYNCING", stagedPage.state());
        assertTrue(stagedPage.items().isEmpty());
        assertTrue(stagedPage.publicationPending());

        Map<String, Object> unmapped = committed("TASK");
        unmapped.put("publication_state", "PENDING");
        jdbc.rows = List.of(unmapped);
        ConversationDeliverableReadAdapter.Page unmappedPage =
                adapter.list(SCOPE, CONVERSATION, 100);
        assertEquals("SYNCING", unmappedPage.state());
        assertTrue(unmappedPage.items().isEmpty());
        assertTrue(unmappedPage.publicationPending());
    }

    @Test
    void publishedTaskOutputRequiresExactArtifactAndFormalDeliveryReferences() {
        Map<String, Object> published = committed("TASK");
        published.put("publication_state", "PUBLISHED");
        published.put("artifact_id", "artifact-1");
        published.put("artifact_version", 1);
        published.put("formal_delivery_id", "delivery-1");
        published.put("formal_delivery_state", "submitted");
        published.put("artifact_content_hash", "a".repeat(64));
        jdbc.rows = List.of(published);

        ConversationDeliverableReadAdapter.Page page = adapter.list(SCOPE, CONVERSATION, 100);

        assertEquals("AVAILABLE", page.state());
        assertFalse(page.publicationPending());
        var item = page.items().getFirst();
        assertEquals("PUBLISHED", item.publicationState());
        assertEquals("submitted", item.formalDeliveryState());
        assertEquals("artifact-1", item.artifactId());
        assertEquals(1, item.artifactVersion());
    }

    @Test
    void corruptCommittedReferenceFailsClosed() {
        Map<String, Object> corrupt = committed("PRIVATE");
        corrupt.put("content_hash", "not-a-hash");
        jdbc.rows = List.of(corrupt);

        ConversationDeliverableReadAdapter.Failure failure = assertThrows(
                ConversationDeliverableReadAdapter.Failure.class,
                () -> adapter.list(SCOPE, CONVERSATION, 100));
        assertEquals(ConversationDeliverableReadAdapter.Reason.CORRUPT_STATE, failure.reason());
    }

    @Test
    void invalidCallerScopeFailsBeforeAclAndSql() {
        ConversationDeliverableReadAdapter.Failure failure = assertThrows(
                ConversationDeliverableReadAdapter.Failure.class,
                () -> adapter.list(new ConversationDeliverableReadAdapter.Scope(
                        "0", " client-a", "owner-a"), CONVERSATION, 100));
        assertEquals(ConversationDeliverableReadAdapter.Reason.BAD_REQUEST, failure.reason());
        verify(access, never()).requireAccessible(
                new WorkspaceConversationAccessService.Scope("0", " client-a", "owner-a"),
                CONVERSATION);
        assertNull(jdbc.sql);
    }

    private static Map<String, Object> committed(String mode) {
        return base(mode, "OUTPUT_COMMITTED", "COMMITTED");
    }

    private static Map<String, Object> base(String mode, String executionState, String outputState) {
        Map<String, Object> row = new HashMap<>();
        row.put("execution_id", "exec-1");
        row.put("execution_mode", mode);
        row.put("execution_state", executionState);
        row.put("output_id", "out-1");
        row.put("output_state", outputState);
        row.put("workspace_file_id", "file-1");
        row.put("workspace_file_version", 1);
        row.put("content_hash", "a".repeat(64));
        row.put("content_mime_type", "application/pdf");
        row.put("byte_length", 42L);
        row.put("committed_at", 1234L);
        return row;
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> order = new ArrayList<>();
        private List<Map<String, Object>> rows = List.of();
        private String sql;
        private Object[] args;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            order.add("sql");
            this.sql = sql;
            this.args = args;
            return rows;
        }
    }
}
