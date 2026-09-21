package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.HallPrivateCaseDao;
import cn.jia.agent.dao.HallRequestDraftDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.entity.HallRequestDraftEntity;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.HallRequestDraftService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HallRequestDraftServiceImplTest {
    private static final HallRequestDraftService.OwnerScope OWNER_A =
            new HallRequestDraftService.OwnerScope("0", "client-a", "owner-a");
    private static final HallRequestDraftService.OwnerScope OWNER_B =
            new HallRequestDraftService.OwnerScope("0", "client-a", "owner-b");
    private static final HallRequestDraftService.OwnerScope CLIENT_B =
            new HallRequestDraftService.OwnerScope("0", "client-b", "owner-a");

    @Test
    void idempotentCommandsUseReadCommittedSoConcurrentWinnerCanBeReplayed()
            throws Exception {
        Transactional create = HallRequestDraftServiceImpl.class.getMethod("create",
                HallRequestDraftService.OwnerScope.class,
                HallRequestDraftService.CreateCommand.class, String.class)
                .getAnnotation(Transactional.class);
        Transactional discard = HallRequestDraftServiceImpl.class.getMethod("discard",
                HallRequestDraftService.OwnerScope.class, String.class, long.class, String.class)
                .getAnnotation(Transactional.class);
        Transactional submit = HallRequestDraftServiceImpl.class.getMethod("submit",
                HallRequestDraftService.OwnerScope.class, String.class, long.class,
                boolean.class, String.class).getAnnotation(Transactional.class);
        assertEquals(Isolation.READ_COMMITTED, create.isolation());
        assertEquals(Isolation.READ_COMMITTED, discard.isolation());
        assertEquals(Isolation.READ_COMMITTED, submit.isolation());
    }

    @Test
    void createIdempotencyIsExactScopeAndPayloadBoundWithoutStartingExecution() {
        Fixture fixture = new Fixture();
        var command = create("first title", "private body");

        var first = fixture.service.create(OWNER_A, command, "same-key");
        var replay = fixture.service.create(OWNER_A, command, "same-key");
        assertEquals(first, replay);
        assertEquals(1, fixture.dao.rows.size());

        assertReason(HallRequestDraftService.Reason.IDEMPOTENCY_CONFLICT,
                () -> fixture.service.create(OWNER_A,
                        create("different title", "private body"), "same-key"));

        var otherOwner = fixture.service.create(OWNER_B, command, "same-key");
        var otherClient = fixture.service.create(CLIENT_B, command, "same-key");
        assertNotEquals(first.draftId(), otherOwner.draftId());
        assertNotEquals(first.draftId(), otherClient.draftId());
        assertEquals(3, fixture.dao.rows.size());

        // Draft creation never calls execution; source ACL collaborators also stay idle for an
        // incomplete source-free draft.
        verifyNoInteractions(fixture.workspace, fixture.tasks, fixture.agents, fixture.conversations);
        assertNull(first.submissionRef());
        assertEquals("EDITING", first.state());
    }

    @Test
    void ownerAndClientIsolationHideDetailListMutationAndDiscard() {
        Fixture fixture = new Fixture();
        var created = fixture.service.create(OWNER_A, create("secret title", "secret body"), "create-a");

        for (var intruder : List.of(OWNER_B, CLIENT_B)) {
            assertTrue(fixture.service.list(intruder, null).items().isEmpty());
            assertReason(HallRequestDraftService.Reason.NOT_FOUND,
                    () -> fixture.service.get(intruder, created.draftId()));
            assertReason(HallRequestDraftService.Reason.NOT_FOUND,
                    () -> fixture.service.replace(intruder, created.draftId(), 1,
                            editable("stolen", "stolen")));
            assertReason(HallRequestDraftService.Reason.NOT_FOUND,
                    () -> fixture.service.discard(intruder, created.draftId(), 1, "discard-x"));
        }

        var unchanged = fixture.service.get(OWNER_A, created.draftId());
        assertEquals("secret title", unchanged.editableFields().title());
        assertEquals("secret body", unchanged.editableFields().instruction());
        assertEquals(1, unchanged.revision());
    }

    @Test
    void concurrentCasAllowsExactlyOneWriterAndPreservesWinner() throws Exception {
        Fixture fixture = new Fixture();
        String draftId = fixture.service.create(OWNER_A, create("v1", "body-v1"), "create-cas").draftId();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> left = executor.submit(() -> replaceAfter(start, fixture, draftId, "left"));
            Future<Object> right = executor.submit(() -> replaceAfter(start, fixture, draftId, "right"));
            start.countDown();
            List<Object> results = List.of(left.get(), right.get());
            long wins = results.stream().filter(HallRequestDraftService.DraftView.class::isInstance).count();
            long stale = results.stream().filter(HallRequestDraftService.Failure.class::isInstance)
                    .map(HallRequestDraftService.Failure.class::cast)
                    .filter(failure -> failure.reason() == HallRequestDraftService.Reason.REVISION_CHANGED)
                    .count();
            assertEquals(1, wins);
            assertEquals(1, stale);
        }
        var stored = fixture.service.get(OWNER_A, draftId);
        assertEquals(2, stored.revision());
        assertTrue(List.of("left", "right").contains(stored.editableFields().title()));
    }

    @Test
    void staleRevisionAndDiscardedStateNeverOverwritePersistedText() {
        Fixture fixture = new Fixture();
        String draftId = fixture.service.create(OWNER_A, create("original", "body"), "create-stale").draftId();
        var v2 = fixture.service.replace(OWNER_A, draftId, 1, editable("winner", "winner body"));
        assertEquals(2, v2.revision());

        assertReason(HallRequestDraftService.Reason.REVISION_CHANGED,
                () -> fixture.service.replace(OWNER_A, draftId, 1, editable("stale", "stale body")));
        assertEquals("winner", fixture.service.get(OWNER_A, draftId).editableFields().title());

        fixture.service.discard(OWNER_A, draftId, 2, "discard-key");
        assertReason(HallRequestDraftService.Reason.STATE_CONFLICT,
                () -> fixture.service.replace(OWNER_A, draftId, 3, editable("after", "after")));
        assertEquals("winner", fixture.service.get(OWNER_A, draftId).editableFields().title());
    }

    @Test
    void discardReplayReturnsOriginalReceiptAndConflictingReuseDoesNotWrite() {
        Fixture fixture = new Fixture();
        String first = fixture.service.create(OWNER_A, create("one", "one"), "create-one").draftId();
        String second = fixture.service.create(OWNER_A, create("two", "two"), "create-two").draftId();

        var discarded = fixture.service.discard(OWNER_A, first, 1, "discard-shared");
        var replay = fixture.service.discard(OWNER_A, first, 1, "discard-shared");
        assertEquals(discarded, replay);
        assertEquals(2, discarded.revision());
        assertEquals("DISCARDED", discarded.state());

        assertReason(HallRequestDraftService.Reason.IDEMPOTENCY_CONFLICT,
                () -> fixture.service.discard(OWNER_A, second, 1, "discard-shared"));
        assertEquals("EDITING", fixture.service.get(OWNER_A, second).state());
        assertEquals(1, fixture.service.get(OWNER_A, second).revision());
    }

    @Test
    void unauthorizedFixedFileVersionFails422BeforeAnyDraftReservation() {
        Fixture fixture = new Fixture();
        var fields = new HallRequestDraftService.EditableFields("title", "body", null,
                PersonalWorkspaceExecutionProperties.DOCX,
                List.of(new HallRequestDraftService.InputSelection("foreign-file", 7)));
        var command = new HallRequestDraftService.CreateCommand("CREATE", "files", null,
                null, null, null, fields, null);

        HallRequestDraftService.Failure failure = assertThrows(HallRequestDraftService.Failure.class,
                () -> fixture.service.create(OWNER_A, command, "foreign-source"));
        assertEquals(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, failure.reason());
        assertEquals(Map.of("field", "inputs", "sourceType", "FILE"), failure.safeDetails());
        assertTrue(fixture.dao.rows.isEmpty());
    }

    @Test
    void unsupportedCaseSourceFailsClosedRatherThanPersistingAnUnverifiedReference() {
        Fixture fixture = new Fixture();
        var command = new HallRequestDraftService.CreateCommand("REVISION", "results", null,
                "case-not-yet-supported", null, null, editable("title", "body"),
                new HallRequestDraftService.SourceOutputRef("exec-1", "output-1", "file-1", 1));
        HallRequestDraftService.Failure failure = assertThrows(HallRequestDraftService.Failure.class,
                () -> fixture.service.create(OWNER_A, command, "unsupported-case"));
        assertEquals(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, failure.reason());
        assertEquals("PRIVATE_CASE", failure.safeDetails().get("sourceType"));
        assertTrue(fixture.dao.rows.isEmpty());
    }

    @Test
    void supportedFixedFileVersionPersistsButReplaceRevalidatesImmutableSourceBeforeCas() {
        Fixture fixture = new Fixture();
        PersonalWorkspaceFileEntity file = new PersonalWorkspaceFileEntity().setState("ACTIVE");
        PersonalWorkspaceVersionEntity version = new PersonalWorkspaceVersionEntity();
        when(fixture.workspace.findFile("0", "client-a", "owner-a", "file-1"))
                .thenReturn(file);
        when(fixture.workspace.findVersion("0", "client-a", "owner-a", "file-1", 7))
                .thenReturn(version);
        var command = new HallRequestDraftService.CreateCommand("CREATE", "files",
                new HallRequestDraftService.SourceRef("FILE", "file-1", 7),
                null, null, null, editable("source title", "source body"), null);

        var created = fixture.service.create(OWNER_A, command, "fixed-file");
        assertEquals(new HallRequestDraftService.SourceRef("FILE", "file-1", 7),
                created.sourceSummary().sourceRef());

        when(fixture.workspace.findFile("0", "client-a", "owner-a", "file-1"))
                .thenReturn(null);
        HallRequestDraftService.Failure failure = assertThrows(HallRequestDraftService.Failure.class,
                () -> fixture.service.replace(OWNER_A, created.draftId(), 1,
                        editable("must-not-save", "must-not-save")));
        assertEquals(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, failure.reason());
        assertEquals(Map.of("field", "sourceRef", "sourceType", "FILE"), failure.safeDetails());
        var unchanged = fixture.service.get(OWNER_A, created.draftId());
        assertEquals(1, unchanged.revision());
        assertEquals("source title", unchanged.editableFields().title());
    }

    @Test
    void conversationValidatorAbsenceIsExplicit422AndDoesNotReserveDraft() {
        Fixture fixture = new Fixture(null);
        var command = new HallRequestDraftService.CreateCommand("CREATE", "conversation",
                new HallRequestDraftService.SourceRef("CONVERSATION", "conversation-1", null),
                null, null, "conversation-1", editable("title", "body"), null);

        HallRequestDraftService.Failure failure = assertThrows(HallRequestDraftService.Failure.class,
                () -> fixture.service.create(OWNER_A, command, "conversation-unavailable"));
        assertEquals(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, failure.reason());
        assertEquals(Map.of("field", "conversationId", "sourceType", "CONVERSATION"),
                failure.safeDetails());
        assertTrue(fixture.dao.rows.isEmpty());
    }

    @Test
    void unknownSourceTypeAndUnownedPrivateOutputFail422WithoutMutation() {
        Fixture fixture = new Fixture();
        var unknown = new HallRequestDraftService.CreateCommand("CREATE", "unknown",
                new HallRequestDraftService.SourceRef("PRIVATE_CASE", "case-1", null),
                null, null, null, editable("title", "body"), null);
        HallRequestDraftService.Failure unsupported = assertThrows(
                HallRequestDraftService.Failure.class,
                () -> fixture.service.create(OWNER_A, unknown, "unknown-source"));
        assertEquals(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, unsupported.reason());
        assertEquals("PRIVATE_CASE", unsupported.safeDetails().get("sourceType"));

        var output = new HallRequestDraftService.SourceOutputRef(
                "execution-1", "output-1", "file-1", 1);
        var revision = new HallRequestDraftService.CreateCommand("REVISION", "result",
                new HallRequestDraftService.SourceRef(
                        "EXECUTION_OUTPUT", "execution-1", null),
                null, null, null, editable("revision", "body"), output);
        HallRequestDraftService.Failure denied = assertThrows(
                HallRequestDraftService.Failure.class,
                () -> fixture.service.create(OWNER_A, revision, "foreign-output"));
        assertEquals(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, denied.reason());
        assertEquals(Map.of("field", "sourceOutputRef", "sourceType", "EXECUTION_OUTPUT"),
                denied.safeDetails());
        assertTrue(fixture.dao.rows.isEmpty());
    }

    @Test
    void stableCursorPaginatesSameTimestampWithoutDuplicatesOrPrivateBodies() {
        Fixture fixture = new Fixture();
        for (int index = 0; index < 25; index++) {
            fixture.service.create(OWNER_A,
                    create("title-" + index, "private-body-" + index), "page-" + index);
        }

        var first = fixture.service.list(OWNER_A, null);
        assertEquals(20, first.items().size());
        assertTrue(first.nextCursor() != null && !first.nextCursor().isBlank());
        var second = fixture.service.list(OWNER_A, first.nextCursor());
        assertEquals(5, second.items().size());
        assertNull(second.nextCursor());
        var ids = new java.util.HashSet<String>();
        first.items().forEach(item -> ids.add(item.draftId()));
        second.items().forEach(item -> ids.add(item.draftId()));
        assertEquals(25, ids.size());
        assertTrue(first.items().stream().noneMatch(
                item -> item.toString().contains("private-body-")));
    }

    @Test
    void recoveryListIsStableAndContainsOnlySummariesNotInstructionOrInputs() {
        Fixture fixture = new Fixture();
        var first = fixture.service.create(OWNER_A, create("one", "secret-one"), "list-1");
        fixture.now.incrementAndGet();
        var second = fixture.service.create(OWNER_A, create("two", "secret-two"), "list-2");
        fixture.service.discard(OWNER_A, first.draftId(), 1, "list-discard");

        var page = fixture.service.list(OWNER_A, null);
        assertEquals(1, page.items().size());
        assertEquals(second.draftId(), page.items().getFirst().draftId());
        assertEquals("two", page.items().getFirst().title());
        assertFalse(page.items().getFirst().toString().contains("secret-two"));
        assertNull(page.nextCursor());
    }

    private static Object replaceAfter(CountDownLatch start, Fixture fixture,
            String draftId, String title) throws InterruptedException {
        start.await();
        try { return fixture.service.replace(OWNER_A, draftId, 1, editable(title, title + " body")); }
        catch (HallRequestDraftService.Failure failure) { return failure; }
    }

    private static HallRequestDraftService.CreateCommand create(String title, String instruction) {
        return new HallRequestDraftService.CreateCommand("CREATE", "map", null,
                null, null, null, editable(title, instruction), null);
    }

    private static HallRequestDraftService.EditableFields editable(String title, String instruction) {
        return new HallRequestDraftService.EditableFields(title, instruction, null,
                PersonalWorkspaceExecutionProperties.DOCX, List.of());
    }

    private static void assertReason(HallRequestDraftService.Reason reason, Runnable action) {
        HallRequestDraftService.Failure failure = assertThrows(
                HallRequestDraftService.Failure.class, action::run);
        assertEquals(reason, failure.reason());
    }

    private static final class Fixture {
        private final FakeDao dao = new FakeDao();
        private final HallPrivateCaseDao cases = mock(HallPrivateCaseDao.class);
        private final PersonalWorkspaceExecutionService executions = mock(PersonalWorkspaceExecutionService.class);
        private final PersonalWorkspaceDao workspace = mock(PersonalWorkspaceDao.class);
        private final AgentTaskMetaDao tasks = mock(AgentTaskMetaDao.class);
        private final AgentService agents = mock(AgentService.class);
        private final WorkspaceConversationAccessService conversations;
        private final AtomicLong now = new AtomicLong(1_790_000_000_000L);
        private final HallRequestDraftServiceImpl service;

        private Fixture() { this(mock(WorkspaceConversationAccessService.class)); }
        private Fixture(WorkspaceConversationAccessService conversations) {
            this.conversations = conversations;
            this.service = new HallRequestDraftServiceImpl(dao, cases, executions,
                    workspace, tasks, agents, conversations, new PersonalWorkspaceExecutionProperties(
                            List.of(PersonalWorkspaceExecutionProperties.DOCX)), now::get);
        }
    }

    private static final class FakeDao implements HallRequestDraftDao {
        private final Map<String, HallRequestDraftEntity> rows = new LinkedHashMap<>();

        @Override public synchronized HallRequestDraftEntity find(String tenant, String client,
                String owner, String draftId) {
            HallRequestDraftEntity row = rows.get(draftId);
            return scoped(row, tenant, client, owner) ? row : null;
        }
        @Override public synchronized HallRequestDraftEntity lock(String tenant, String client,
                String owner, String draftId) {
            return find(tenant, client, owner, draftId);
        }
        @Override public synchronized HallRequestDraftEntity findBySubmitKey(String tenant,
                String client, String owner, String key) {
            return rows.values().stream().filter(row -> scoped(row, tenant, client, owner)
                    && key.equals(row.getSubmitKey())).findFirst().orElse(null);
        }
        @Override public synchronized HallRequestDraftEntity findByCreateKey(String tenant,
                String client, String owner, String key) {
            return rows.values().stream().filter(row -> scoped(row, tenant, client, owner)
                    && key.equals(row.getCreateKey())).findFirst().orElse(null);
        }
        @Override public synchronized HallRequestDraftEntity findByDiscardKey(String tenant,
                String client, String owner, String key) {
            return rows.values().stream().filter(row -> scoped(row, tenant, client, owner)
                    && key.equals(row.getDiscardKey())).findFirst().orElse(null);
        }
        @Override public synchronized List<HallRequestDraftEntity> listEditing(String tenant,
                String client, String owner, Long beforeUpdatedAt, String beforeDraftId, int limit) {
            return rows.values().stream().filter(row -> scoped(row, tenant, client, owner))
                    .filter(row -> "EDITING".equals(row.getState()))
                    .filter(row -> beforeUpdatedAt == null || row.getUpdatedAt() < beforeUpdatedAt
                            || (row.getUpdatedAt().equals(beforeUpdatedAt)
                                && row.getDraftId().compareTo(beforeDraftId) < 0))
                    .sorted(Comparator.comparing(HallRequestDraftEntity::getUpdatedAt).reversed()
                            .thenComparing(HallRequestDraftEntity::getDraftId, Comparator.reverseOrder()))
                    .limit(limit).toList();
        }
        @Override public synchronized void reserveCreate(HallRequestDraftEntity entity) {
            HallRequestDraftEntity prior = findByCreateKey(entity.getTenantId(), entity.getClientId(),
                    entity.getOwnerJiacn(), entity.getCreateKey());
            if (prior == null) rows.put(entity.getDraftId(), entity);
        }
        @Override public synchronized int replaceEditing(String tenant, String client, String owner,
                String draftId, long revision, String title, String instruction, String target,
                String mime, String inputs, long updatedAt) {
            HallRequestDraftEntity row = find(tenant, client, owner, draftId);
            if (row == null || !"EDITING".equals(row.getState()) || row.getRevision() != revision) return 0;
            row.setTitle(title).setInstruction(instruction).setTargetAgentId(target).setOutputMime(mime)
                    .setInputsJson(inputs).setRevision(revision + 1).setUpdatedAt(updatedAt);
            return 1;
        }
        @Override public synchronized int reserveSubmitIntent(String tenant, String client,
                String owner, String draftId, long revision, String key, String hash, long updatedAt) {
            if (findBySubmitKey(tenant, client, owner, key) != null) return 0;
            HallRequestDraftEntity row = find(tenant, client, owner, draftId);
            if (row == null || !"EDITING".equals(row.getState()) || row.getRevision() != revision) return 0;
            row.setSubmitKey(key).setSubmitHash(hash).setUpdatedAt(updatedAt);
            return 1;
        }
        @Override public synchronized int markSubmitted(String tenant, String client, String owner,
                String draftId, long revision, String key, String hash, String caseId,
                String submissionRef, String executionId, long updatedAt) {
            HallRequestDraftEntity row = find(tenant, client, owner, draftId);
            if (row == null || !"EDITING".equals(row.getState()) || row.getRevision() != revision
                    || !key.equals(row.getSubmitKey()) || !hash.equals(row.getSubmitHash())) return 0;
            row.setState("SUBMITTED").setCaseId(caseId).setSubmissionRef(submissionRef)
                    .setSubmittedExecutionId(executionId).setRevision(revision + 1).setUpdatedAt(updatedAt);
            return 1;
        }
        @Override public synchronized int discardEditing(String tenant, String client, String owner,
                String draftId, long revision, String key, String hash, long updatedAt) {
            if (findByDiscardKey(tenant, client, owner, key) != null) return 0;
            HallRequestDraftEntity row = find(tenant, client, owner, draftId);
            if (row == null || !"EDITING".equals(row.getState()) || row.getRevision() != revision) return 0;
            row.setState("DISCARDED").setDiscardKey(key).setDiscardHash(hash)
                    .setRevision(revision + 1).setUpdatedAt(updatedAt);
            return 1;
        }
        @Override public boolean privateCommittedOutputExists(String tenant, String client,
                String owner, String execution, String output, String file, int version) {
            return false;
        }
        @Override public boolean lockPrivateCommittedOutputExists(String tenant, String client,
                String owner, String execution, String output, String file, int version) {
            return false;
        }
        private static boolean scoped(HallRequestDraftEntity row, String tenant,
                String client, String owner) {
            return row != null && tenant.equals(row.getTenantId()) && client.equals(row.getClientId())
                    && owner.equals(row.getOwnerJiacn());
        }
    }
}
