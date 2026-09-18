package cn.jia.agent.service.impl;

import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.entity.PersonalWorkspaceTaskFileLinkEntity;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
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

class PersonalWorkspaceTaskLinkServiceImplTest {
    private static final PersonalWorkspaceTaskLinkService.Scope OWNER =
            new PersonalWorkspaceTaskLinkService.Scope("0", "browser", "owner-a");

    @Test
    void exactVersionDuplicateSelectionAndIdempotencyStayStable() {
        FakeDao dao = new FakeDao();
        PersonalWorkspaceTaskLinkService service = new PersonalWorkspaceTaskLinkServiceImpl(dao);
        var command = new PersonalWorkspaceTaskLinkService.CreateCommand(
                "file-a", 1, "INPUT", "create-key-1");

        var first = service.create(OWNER, "task-a", command);
        var replay = service.create(OWNER, "task-a", command);
        var duplicateSelection = service.create(OWNER, "task-a",
                new PersonalWorkspaceTaskLinkService.CreateCommand(
                        "file-a", 1, "INPUT", "create-key-2"));

        assertEquals(first, replay);
        assertEquals(first, duplicateSelection);
        assertEquals(1, dao.links.size());
        assertEquals(1, first.version());
        assertEquals(List.of("task.lock", "operation.reserve", "file.lock.active",
                "version.find", "selection.lock", "link.insert", "file.impact.bump",
                "operation.complete"), dao.events.subList(0, 8));
        assertFalse(dao.events.subList(8, 11).contains("file.lock.active"),
                "same-key replay must return the committed relation without another write path");
    }

    @Test
    void sameKeyDifferentRequestConflictsBeforeAnyForeignFileLookup() {
        FakeDao dao = new FakeDao();
        PersonalWorkspaceTaskLinkService service = new PersonalWorkspaceTaskLinkServiceImpl(dao);
        service.create(OWNER, "task-a", new PersonalWorkspaceTaskLinkService.CreateCommand(
                "file-a", 1, "REFERENCE", "same-key"));
        dao.events.clear();

        assertReason(PersonalWorkspaceTaskLinkService.Reason.IDEMPOTENCY_CONFLICT,
                () -> service.create(OWNER, "task-a",
                        new PersonalWorkspaceTaskLinkService.CreateCommand(
                                "file-b", 1, "REFERENCE", "same-key")));
        assertEquals(List.of("task.lock", "operation.reserve"), dao.events);
    }

    @Test
    void taskFileAndVersionChecksFailClosedAndOutputCannotBeForged() {
        FakeDao foreignTaskDao = new FakeDao();
        PersonalWorkspaceTaskLinkService foreignTaskService =
                new PersonalWorkspaceTaskLinkServiceImpl(foreignTaskDao);
        foreignTaskDao.taskOwned = false;
        assertReason(PersonalWorkspaceTaskLinkService.Reason.NOT_FOUND,
                () -> foreignTaskService.create(OWNER, "foreign-task",
                        command("file-a", 1, "INPUT", "k1")));
        assertFalse(foreignTaskDao.events.contains("operation.reserve"));

        FakeDao inactiveFileDao = new FakeDao();
        PersonalWorkspaceTaskLinkService inactiveFileService =
                new PersonalWorkspaceTaskLinkServiceImpl(inactiveFileDao);
        inactiveFileDao.activeFile = false;
        assertReason(PersonalWorkspaceTaskLinkService.Reason.NOT_FOUND,
                () -> inactiveFileService.create(OWNER, "task-a",
                        command("file-a", 1, "INPUT", "k2")));

        FakeDao missingVersionDao = new FakeDao();
        PersonalWorkspaceTaskLinkService missingVersionService =
                new PersonalWorkspaceTaskLinkServiceImpl(missingVersionDao);
        missingVersionDao.versionExists = false;
        assertReason(PersonalWorkspaceTaskLinkService.Reason.NOT_FOUND,
                () -> missingVersionService.create(OWNER, "task-a",
                        command("file-a", 2, "INPUT", "k3")));

        assertReason(PersonalWorkspaceTaskLinkService.Reason.OUTPUT_MANAGED_BY_EXECUTION,
                () -> missingVersionService.create(OWNER, "task-a",
                        command("file-a", 1, "OUTPUT", "k4")));
    }

    @Test
    void detachPreservesSnapshotConceptAndOutputIsNeverUserDeleted() {
        FakeDao dao = new FakeDao();
        PersonalWorkspaceTaskLinkService service = new PersonalWorkspaceTaskLinkServiceImpl(dao);
        var linked = service.create(OWNER, "task-a", command("file-a", 1, "INPUT", "create"));
        String activeEtag = PersonalWorkspaceTaskLinkServiceImpl.etag(linked);

        var detached = service.detach(OWNER, "task-a", linked.relationId(),
                activeEtag, "delete-key");
        assertTrue(detached.executionSnapshotsPreserved());
        assertEquals("DETACHED", detached.link().state());
        assertEquals(linked.relationRevision() + 1, detached.link().relationRevision());
        assertEquals("file-a", detached.link().fileId());
        assertNotEquals(activeEtag, PersonalWorkspaceTaskLinkServiceImpl.etag(detached.link()));

        var replay = service.detach(OWNER, "task-a", linked.relationId(),
                activeEtag, "delete-key");
        assertEquals(detached, replay);

        PersonalWorkspaceTaskFileLinkEntity output = dao.seed("rel-output", "file-output", 1,
                "OUTPUT", "ACTIVE", 1L);
        assertReason(PersonalWorkspaceTaskLinkService.Reason.OUTPUT_MANAGED_BY_EXECUTION,
                () -> service.detach(OWNER, "task-a", output.getRelationId(),
                        "\"rel-output:1\"", "delete-output"));
        assertEquals("ACTIVE", output.getLinkState());
    }

    @Test
    void writeMethodsAreAtomicAndReadIsReadOnly() throws Exception {
        Method list = PersonalWorkspaceTaskLinkServiceImpl.class.getMethod("list",
                PersonalWorkspaceTaskLinkService.Scope.class, String.class, String.class);
        Method create = PersonalWorkspaceTaskLinkServiceImpl.class.getMethod("create",
                PersonalWorkspaceTaskLinkService.Scope.class, String.class,
                PersonalWorkspaceTaskLinkService.CreateCommand.class);
        Method detach = PersonalWorkspaceTaskLinkServiceImpl.class.getMethod("detach",
                PersonalWorkspaceTaskLinkService.Scope.class, String.class, String.class,
                String.class, String.class);
        assertTrue(list.getAnnotation(Transactional.class).readOnly());
        assertFalse(create.getAnnotation(Transactional.class).readOnly());
        assertFalse(detach.getAnnotation(Transactional.class).readOnly());
        assertEquals(List.of(Exception.class), List.of(create.getAnnotation(Transactional.class).rollbackFor()));
        assertEquals(List.of(Exception.class), List.of(detach.getAnnotation(Transactional.class).rollbackFor()));
    }

    private static PersonalWorkspaceTaskLinkService.CreateCommand command(
            String fileId, int version, String role, String key) {
        return new PersonalWorkspaceTaskLinkService.CreateCommand(fileId, version, role, key);
    }

    private static void assertReason(PersonalWorkspaceTaskLinkService.Reason reason,
            Executable executable) {
        PersonalWorkspaceTaskLinkService.Failure failure = assertThrows(
                PersonalWorkspaceTaskLinkService.Failure.class, executable);
        assertEquals(reason, failure.getReason());
    }

    private static final class FakeDao implements PersonalWorkspaceTaskLinkDao {
        private boolean taskOwned = true;
        private boolean activeFile = true;
        private boolean fileExists = true;
        private boolean versionExists = true;
        private final List<String> events = new ArrayList<>();
        private final Map<String, PersonalWorkspaceTaskFileLinkEntity> links = new LinkedHashMap<>();
        private final Map<String, OperationRow> operations = new LinkedHashMap<>();

        @Override public boolean taskExists(String t, String c, String o, String task) {
            events.add("task.find"); return taskOwned;
        }
        @Override public boolean lockTask(String t, String c, String o, String task) {
            events.add("task.lock"); return taskOwned;
        }
        @Override public boolean lockFile(String t, String c, String o, String file, boolean active) {
            events.add(active ? "file.lock.active" : "file.lock");
            return fileExists && (!active || activeFile);
        }
        @Override public boolean versionExists(String t, String c, String o, String file, int version) {
            events.add("version.find"); return versionExists;
        }
        @Override public PersonalWorkspaceTaskFileLinkEntity findByRelation(
                String t, String c, String o, String task, String relation) {
            events.add("relation.find"); return links.get(relation);
        }
        @Override public PersonalWorkspaceTaskFileLinkEntity lockByRelation(
                String t, String c, String o, String task, String relation) {
            events.add("relation.lock"); return links.get(relation);
        }
        @Override public PersonalWorkspaceTaskFileLinkEntity lockBySelection(
                String t, String c, String o, String task, String file, int version, String role) {
            events.add("selection.lock");
            return links.values().stream().filter(link -> file.equals(link.getFileId())
                    && version == link.getFileVersion() && role.equals(link.getLinkRole()))
                    .findFirst().orElse(null);
        }
        @Override public List<PersonalWorkspaceTaskFileLinkEntity> list(
                String t, String c, String o, String task, Long at, String relation, int limit) {
            events.add("links.list"); return links.values().stream().limit(limit).toList();
        }
        @Override public boolean bumpFileImpactRevision(String t, String c, String o, String file) {
            events.add("file.impact.bump"); return true;
        }
        @Override public void insert(PersonalWorkspaceTaskFileLinkEntity link) {
            events.add("link.insert"); links.put(link.getRelationId(), link);
        }
        @Override public boolean changeState(String t, String c, String o, String task,
                String relation, String expected, long expectedRevision, String next,
                long nextRevision, Long detachedAt) {
            events.add("link.update");
            PersonalWorkspaceTaskFileLinkEntity link = links.get(relation);
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

        private PersonalWorkspaceTaskFileLinkEntity seed(String relation, String file, int version,
                String role, String state, long revision) {
            PersonalWorkspaceTaskFileLinkEntity link = new PersonalWorkspaceTaskFileLinkEntity()
                    .setRelationId(relation).setOwnerJiacn("owner-a").setTaskId("task-a")
                    .setFileId(file).setFileVersion(version).setLinkRole(role)
                    .setLinkState(state).setRelationRevision(revision).setCreatedAt(1L);
            link.setTenantId("0"); link.setClientId("browser");
            links.put(relation, link); return link;
        }
    }
}
