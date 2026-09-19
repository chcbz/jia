package cn.jia.agent.service.impl;

import cn.jia.agent.dao.PersonalWorkspaceConversationLinkDao;
import cn.jia.agent.entity.PersonalWorkspaceConversationFileLinkEntity;
import cn.jia.agent.service.PersonalWorkspaceConversationLinkService;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonalWorkspaceConversationLinkServiceImplTest {
    private static final PersonalWorkspaceConversationLinkService.Scope OWNER =
            new PersonalWorkspaceConversationLinkService.Scope("0", "browser", "owner-a");

    @Test
    void exactVersionDuplicateSelectionAndIdempotencyStayStable() {
        FakeDao dao = new FakeDao();
        PersonalWorkspaceConversationLinkService service = new PersonalWorkspaceConversationLinkServiceImpl(dao, new FakeConversations(dao));
        var command = new PersonalWorkspaceConversationLinkService.CreateCommand(
                "file-a", 1, "INPUT", "create-key-1");

        var first = service.create(OWNER, "conversation-a", command);
        var replay = service.create(OWNER, "conversation-a", command);
        var duplicateSelection = service.create(OWNER, "conversation-a",
                new PersonalWorkspaceConversationLinkService.CreateCommand(
                        "file-a", 1, "INPUT", "create-key-2"));

        assertEquals(first, replay);
        assertEquals(first, duplicateSelection);
        assertEquals(1, dao.links.size());
        assertEquals(1, first.version());
        assertEquals(List.of("operation.reserve", "file.lock.active",
                "version.find", "selection.lock", "link.insert", "file.impact.bump",
                "operation.complete"), dao.events.subList(0, 7));
        assertFalse(dao.events.subList(7, 9).contains("file.lock.active"),
                "same-key replay must return the committed relation without another write path");
    }

    @Test
    void sameKeyDifferentRequestConflictsBeforeAnyForeignFileLookup() {
        FakeDao dao = new FakeDao();
        PersonalWorkspaceConversationLinkService service = new PersonalWorkspaceConversationLinkServiceImpl(dao, new FakeConversations(dao));
        service.create(OWNER, "conversation-a", new PersonalWorkspaceConversationLinkService.CreateCommand(
                "file-a", 1, "REFERENCE", "same-key"));
        dao.events.clear();

        assertReason(PersonalWorkspaceConversationLinkService.Reason.IDEMPOTENCY_CONFLICT,
                () -> service.create(OWNER, "conversation-a",
                        new PersonalWorkspaceConversationLinkService.CreateCommand(
                                "file-b", 1, "REFERENCE", "same-key")));
        assertEquals(List.of("operation.reserve"), dao.events);
    }

    @Test
    void conversationAndFileVersionChecksFailClosedAndOutputCannotBeForged() {
        FakeDao foreignTaskDao = new FakeDao();
        PersonalWorkspaceConversationLinkService foreignTaskService =
                new PersonalWorkspaceConversationLinkServiceImpl(foreignTaskDao, new FakeConversations(foreignTaskDao));
        foreignTaskDao.conversationAccessible = false;
        assertReason(PersonalWorkspaceConversationLinkService.Reason.NOT_FOUND,
                () -> foreignTaskService.create(OWNER, "foreign-conversation",
                        command("file-a", 1, "INPUT", "k1")));
        assertFalse(foreignTaskDao.events.contains("operation.reserve"));

        FakeDao inactiveFileDao = new FakeDao();
        PersonalWorkspaceConversationLinkService inactiveFileService =
                new PersonalWorkspaceConversationLinkServiceImpl(inactiveFileDao, new FakeConversations(inactiveFileDao));
        inactiveFileDao.activeFile = false;
        assertReason(PersonalWorkspaceConversationLinkService.Reason.NOT_FOUND,
                () -> inactiveFileService.create(OWNER, "conversation-a",
                        command("file-a", 1, "INPUT", "k2")));

        FakeDao missingVersionDao = new FakeDao();
        PersonalWorkspaceConversationLinkService missingVersionService =
                new PersonalWorkspaceConversationLinkServiceImpl(missingVersionDao, new FakeConversations(missingVersionDao));
        missingVersionDao.versionExists = false;
        assertReason(PersonalWorkspaceConversationLinkService.Reason.NOT_FOUND,
                () -> missingVersionService.create(OWNER, "conversation-a",
                        command("file-a", 2, "INPUT", "k3")));

        assertReason(PersonalWorkspaceConversationLinkService.Reason.OUTPUT_MANAGED_BY_EXECUTION,
                () -> missingVersionService.create(OWNER, "conversation-a",
                        command("file-a", 1, "OUTPUT", "k4")));
    }

    @Test
    void detachPreservesSnapshotConceptAndOutputIsNeverUserDeleted() {
        FakeDao dao = new FakeDao();
        PersonalWorkspaceConversationLinkService service = new PersonalWorkspaceConversationLinkServiceImpl(dao, new FakeConversations(dao));
        var linked = service.create(OWNER, "conversation-a", command("file-a", 1, "INPUT", "create"));
        String activeEtag = PersonalWorkspaceConversationLinkServiceImpl.etag(linked);

        var detached = service.detach(OWNER, "conversation-a", linked.relationId(),
                activeEtag, "delete-key");
        assertTrue(detached.executionSnapshotsPreserved());
        assertEquals("DETACHED", detached.link().state());
        assertEquals(linked.relationRevision() + 1, detached.link().relationRevision());
        assertEquals("file-a", detached.link().fileId());
        assertNotEquals(activeEtag, PersonalWorkspaceConversationLinkServiceImpl.etag(detached.link()));

        var replay = service.detach(OWNER, "conversation-a", linked.relationId(),
                activeEtag, "delete-key");
        assertEquals(detached, replay);

        PersonalWorkspaceConversationFileLinkEntity output = dao.seed("rel-output", "file-output", 1,
                "OUTPUT", "ACTIVE", 1L);
        assertReason(PersonalWorkspaceConversationLinkService.Reason.OUTPUT_MANAGED_BY_EXECUTION,
                () -> service.detach(OWNER, "conversation-a", output.getRelationId(),
                        "\"rel-output:1\"", "delete-output"));
        assertEquals("ACTIVE", output.getLinkState());
    }

    @Test
    void writeMethodsAreAtomicAndReadIsReadOnly() throws Exception {
        Method list = PersonalWorkspaceConversationLinkServiceImpl.class.getMethod("list",
                PersonalWorkspaceConversationLinkService.Scope.class, String.class, String.class);
        Method create = PersonalWorkspaceConversationLinkServiceImpl.class.getMethod("create",
                PersonalWorkspaceConversationLinkService.Scope.class, String.class,
                PersonalWorkspaceConversationLinkService.CreateCommand.class);
        Method detach = PersonalWorkspaceConversationLinkServiceImpl.class.getMethod("detach",
                PersonalWorkspaceConversationLinkService.Scope.class, String.class, String.class,
                String.class, String.class);
        assertTrue(list.getAnnotation(Transactional.class).readOnly());
        assertFalse(create.getAnnotation(Transactional.class).readOnly());
        assertFalse(detach.getAnnotation(Transactional.class).readOnly());
        assertEquals(List.of(Exception.class), List.of(create.getAnnotation(Transactional.class).rollbackFor()));
        assertEquals(List.of(Exception.class), List.of(detach.getAnnotation(Transactional.class).rollbackFor()));
    }

    private static PersonalWorkspaceConversationLinkService.CreateCommand command(
            String fileId, int version, String role, String key) {
        return new PersonalWorkspaceConversationLinkService.CreateCommand(fileId, version, role, key);
    }

    private static void assertReason(PersonalWorkspaceConversationLinkService.Reason reason,
            Executable executable) {
        PersonalWorkspaceConversationLinkService.Failure failure = assertThrows(
                PersonalWorkspaceConversationLinkService.Failure.class, executable);
        assertEquals(reason, failure.getReason());
    }

    private static final class FakeConversations implements WorkspaceConversationAccessService {
        private final FakeDao dao;
        private FakeConversations(FakeDao dao) { this.dao = dao; }
        @Override public ConversationView requireAccessible(Scope scope, String conversationId) {
            if (!dao.conversationAccessible || !"0".equals(scope.tenantId())
                    || !"browser".equals(scope.clientId()) || !"owner-a".equals(scope.ownerJiacn())
                    || !"conversation-a".equals(conversationId)) {
                throw new IllegalStateException("unavailable");
            }
            return new ConversationView(conversationId, "private", "agent:agent-a", null,
                    List.of("agent-a"), 1L, 1L);
        }
    }

    private static final class FakeDao implements PersonalWorkspaceConversationLinkDao {
        private boolean conversationAccessible = true;
        private boolean activeFile = true;
        private boolean fileExists = true;
        private boolean versionExists = true;
        private final List<String> events = new ArrayList<>();
        private final Map<String, PersonalWorkspaceConversationFileLinkEntity> links = new LinkedHashMap<>();
        private final Map<String, OperationRow> operations = new LinkedHashMap<>();

        @Override public boolean lockFile(String t, String c, String o, String file, boolean active) {
            events.add(active ? "file.lock.active" : "file.lock");
            return fileExists && (!active || activeFile);
        }
        @Override public boolean versionExists(String t, String c, String o, String file, int version) {
            events.add("version.find"); return versionExists;
        }
        @Override public PersonalWorkspaceConversationFileLinkEntity findByRelation(
                String t, String c, String o, String conversation, String relation) {
            events.add("relation.find"); return links.get(relation);
        }
        @Override public PersonalWorkspaceConversationFileLinkEntity lockByRelation(
                String t, String c, String o, String conversation, String relation) {
            events.add("relation.lock"); return links.get(relation);
        }
        @Override public PersonalWorkspaceConversationFileLinkEntity lockBySelection(
                String t, String c, String o, String conversation, String file, int version, String role) {
            events.add("selection.lock");
            return links.values().stream().filter(link -> file.equals(link.getFileId())
                    && version == link.getFileVersion() && role.equals(link.getLinkRole()))
                    .findFirst().orElse(null);
        }
        @Override public List<PersonalWorkspaceConversationFileLinkEntity> list(
                String t, String c, String o, String conversation, Long at, String relation, int limit) {
            events.add("links.list"); return links.values().stream().limit(limit).toList();
        }
        @Override public boolean bumpFileImpactRevision(String t, String c, String o, String file) {
            events.add("file.impact.bump"); return true;
        }
        @Override public void insert(PersonalWorkspaceConversationFileLinkEntity link) {
            events.add("link.insert"); links.put(link.getRelationId(), link);
        }
        @Override public boolean changeState(String t, String c, String o, String conversation,
                String relation, String expected, long expectedRevision, String next,
                long nextRevision, Long detachedAt) {
            events.add("link.update");
            PersonalWorkspaceConversationFileLinkEntity link = links.get(relation);
            if (link == null || !expected.equals(link.getLinkState())
                    || expectedRevision != link.getRelationRevision()) return false;
            link.setLinkState(next).setRelationRevision(nextRevision).setDetachedAt(detachedAt);
            return true;
        }
        @Override public OperationRow reserveOperation(String t, String c, String o,
                String type, String key, String hash, String proposed, long now) {
            events.add("operation.reserve");
            return operations.computeIfAbsent(type + ':' + key, ignored -> {
                OperationRow row = new OperationRow();
                row.setOperationId(proposed); row.setOperationType(type);
                row.setIdempotencyKey(key); row.setRequestHash(hash);
                row.setOperationState("PROCESSING"); return row;
            });
        }
        @Override public boolean completeOperation(String t, String c, String o,
                String operationId, String relationId, long completedAt) {
            events.add("operation.complete");
            OperationRow row = operations.values().stream()
                    .filter(value -> operationId.equals(value.getOperationId()))
                    .findFirst().orElse(null);
            if (row == null || !"PROCESSING".equals(row.getOperationState())) return false;
            row.setOperationState("COMMITTED"); row.setRelationId(relationId); return true;
        }

        private PersonalWorkspaceConversationFileLinkEntity seed(String relation, String file, int version,
                String role, String state, long revision) {
            PersonalWorkspaceConversationFileLinkEntity link = new PersonalWorkspaceConversationFileLinkEntity()
                    .setRelationId(relation).setOwnerJiacn("owner-a").setConversationId("conversation-a")
                    .setFileId(file).setFileVersion(version).setLinkRole(role)
                    .setLinkState(state).setRelationRevision(revision).setCreatedAt(1L);
            link.setTenantId("0"); link.setClientId("browser");
            links.put(relation, link); return link;
        }
    }
}
