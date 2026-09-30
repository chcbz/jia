package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskCreationOperationDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.entity.AgentTaskCreationOperationEntity;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCreationOperationService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskCreationOperationServiceImplTest {
    private static final AgentTaskCreationOperationService.Scope SCOPE =
            new AgentTaskCreationOperationService.Scope("0", "client-a", "owner-a");

    private AgentTaskCreationOperationDao operations;
    private AgentService agents;
    private AgentTaskRequirementSnapshotService requirements;
    private PersonalWorkspaceTaskLinkService links;
    private PersonalWorkspaceTaskLinkDao linkRows;
    private PersonalWorkspaceDao workspace;
    private AgentTaskCreationOperationServiceImpl service;
    private TransactionTemplate transactions;
    private AtomicReference<AgentTaskCreationOperationEntity> stored;

    @BeforeEach
    void setUp() {
        authenticate("owner-a", "client-a");
        operations = mock(AgentTaskCreationOperationDao.class);
        agents = mock(AgentService.class);
        requirements = mock(AgentTaskRequirementSnapshotService.class);
        links = mock(PersonalWorkspaceTaskLinkService.class);
        linkRows = mock(PersonalWorkspaceTaskLinkDao.class);
        workspace = mock(PersonalWorkspaceDao.class);
        service = new AgentTaskCreationOperationServiceImpl(operations, agents, requirements,
                links, linkRows, workspace, JsonMapper.builder().build());
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:atco_unit;MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", "");
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        stored = new AtomicReference<>();
        when(operations.reserveAndLock(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            AgentTaskCreationOperationEntity current = stored.get();
            if (current != null) return current;
            AgentTaskCreationOperationEntity row = new AgentTaskCreationOperationEntity()
                    .setOperationId(invocation.getArgument(5))
                    .setOwnerJiacn(invocation.getArgument(2))
                    .setIdempotencyKey(invocation.getArgument(3))
                    .setRequestHash(invocation.getArgument(4))
                    .setOperationState("PROCESSING")
                    .setInputRefsJson(invocation.getArgument(6))
                    .setCreatedAt(invocation.getArgument(7));
            row.setTenantId(invocation.getArgument(0));
            row.setClientId(invocation.getArgument(1));
            stored.set(row);
            return row;
        });
        when(operations.complete(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(1L), anyLong())).thenReturn(true);
        when(linkRows.lockTask("0", "client-a", "owner-a", "task-1")).thenReturn(true);
        when(linkRows.taskExists("0", "client-a", "owner-a", "task-1")).thenReturn(true);
        AgentTaskDTO created = task("task-1", "完整🚀标题", "");
        when(agents.createTask(any())).thenReturn(created);
        when(agents.getTask("task-1")).thenReturn(created);
        when(requirements.read(any(AgentTaskExecutionGrantService.Scope.class), eq("task-1"),
                eq(1L))).thenReturn(new AgentTaskRequirementSnapshotService.Snapshot(
                        "0", "client-a", "owner-a", "task-1", 1,
                        "完整🚀标题", "", "a".repeat(64), "CREATE"));
        when(workspace.lockFile(eq("0"), eq("client-a"), eq("owner-a"), anyString()))
                .thenAnswer(invocation -> file(invocation.getArgument(3), "ACTIVE"));
        when(workspace.findVersion(eq("0"), eq("client-a"), eq("owner-a"),
                anyString(), anyInt())).thenAnswer(invocation -> version(
                        invocation.getArgument(3), invocation.getArgument(4), "image/png"));
        when(links.create(any(), eq("task-1"), any())).thenAnswer(invocation -> {
            PersonalWorkspaceTaskLinkService.CreateCommand command = invocation.getArgument(2);
            return new PersonalWorkspaceTaskLinkService.LinkView("rel-" + command.fileId(),
                    "task-1", command.fileId(), command.version(), command.role(),
                    "ACTIVE", 1, 1);
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        EsContextHolder.clearContext();
    }

    @Test
    void canonicalRefsUnicodeNullSemanticsReplayAndConflictHaveNoSecondWrites() {
        var command = command("完整🚀标题", true, "", true, null, true, null,
                List.of(ref("file-b", 2), ref("file-a", 3)));
        AgentTaskCreationOperationService.Result first = inTransaction(
                () -> service.create(SCOPE, "same-key", command));

        assertFalse(first.replay());
        assertEquals(1, first.receipt().requirementRevision());
        assertEquals("COMMITTED", first.receipt().state());
        assertEquals(first.receipt().taskId(), first.receipt().task().getId());
        assertEquals(List.of(ref("file-a", 3), ref("file-b", 2)),
                first.receipt().inputRefs());
        ArgumentCaptor<cn.jia.agent.entity.AgentTaskCreateDTO> task =
                ArgumentCaptor.forClass(cn.jia.agent.entity.AgentTaskCreateDTO.class);
        verify(agents).createTask(task.capture());
        assertEquals("", task.getValue().getDescription());
        assertNull(task.getValue().getRequiredAbilities());
        assertNull(task.getValue().getReward());
        ArgumentCaptor<PersonalWorkspaceTaskLinkService.CreateCommand> link =
                ArgumentCaptor.forClass(PersonalWorkspaceTaskLinkService.CreateCommand.class);
        verify(links, times(2)).create(any(), eq("task-1"), link.capture());
        assertEquals(List.of("file-a", "file-b"), link.getAllValues().stream()
                .map(PersonalWorkspaceTaskLinkService.CreateCommand::fileId).toList());
        assertTrue(link.getAllValues().stream().allMatch(value -> "REFERENCE".equals(value.role())));

        AgentTaskCreationOperationService.Result replay = inTransaction(
                () -> service.create(SCOPE, "same-key", command));
        assertTrue(replay.replay());
        assertEquals(first.receipt().operationId(), replay.receipt().operationId());
        assertEquals(first.receipt().inputRefs(), replay.receipt().inputRefs());
        verify(agents, times(1)).createTask(any());
        verify(links, times(2)).create(any(), anyString(), any());

        AgentTaskCreationOperationService.Failure conflict = assertThrows(
                AgentTaskCreationOperationService.Failure.class, () -> inTransaction(
                        () -> service.create(SCOPE, "same-key", command(
                                "different", true, "", true, null, true, null,
                                command.inputRefs()))));
        assertEquals(AgentTaskCreationOperationService.Reason.IDEMPOTENCY_CONFLICT,
                conflict.reason());
        verify(agents, times(1)).createTask(any());
    }

    @Test
    void wrongMimeMissingForeignTrashedAndDuplicateReferencesFailClosed() {
        when(workspace.findVersion("0", "client-a", "owner-a", "bad-mime", 1))
                .thenReturn(version("bad-mime", 1, "image/gif"));
        AgentTaskCreationOperationService.Failure mime = assertThrows(
                AgentTaskCreationOperationService.Failure.class, () -> inTransaction(
                        () -> service.create(SCOPE, "mime-key", command("完整🚀标题", true, "",
                                true, List.of(), true, null, List.of(ref("bad-mime", 1))))));
        assertEquals(AgentTaskCreationOperationService.Reason.NOT_FOUND, mime.reason());

        stored.set(null);
        when(workspace.lockFile("0", "client-a", "owner-a", "trashed"))
                .thenReturn(file("trashed", "TRASHED"));
        AgentTaskCreationOperationService.Failure trashed = assertThrows(
                AgentTaskCreationOperationService.Failure.class, () -> inTransaction(
                        () -> service.create(SCOPE, "trash-key", command("完整🚀标题", true, "",
                                true, List.of(), true, null, List.of(ref("trashed", 1))))));
        assertEquals(AgentTaskCreationOperationService.Reason.NOT_FOUND, trashed.reason());

        stored.set(null);
        var duplicate = command("完整🚀标题", true, "", true, List.of(), true, null,
                List.of(ref("file-a", 1), ref("file-a", 1)));
        AgentTaskCreationOperationService.Failure duplicateFailure = assertThrows(
                AgentTaskCreationOperationService.Failure.class,
                () -> inTransaction(() -> service.create(SCOPE, "duplicate-key", duplicate)));
        assertEquals(AgentTaskCreationOperationService.Reason.BAD_REQUEST,
                duplicateFailure.reason());
    }

    @Test
    void exactSnapshotAndIdentityDriftAreRejectedBeforeReceiptOrMutation() {
        when(requirements.read(any(), eq("task-1"), eq(1L))).thenReturn(
                new AgentTaskRequirementSnapshotService.Snapshot("0", "client-a", "owner-a",
                        "task-1", 1, "wrong", "", "a".repeat(64), "CREATE"));
        AgentTaskCreationOperationService.Failure snapshot = assertThrows(
                AgentTaskCreationOperationService.Failure.class, () -> inTransaction(
                        () -> service.create(SCOPE, "snapshot-key", command("完整🚀标题", true,
                                "", true, List.of(), true, null, List.of()))));
        assertEquals(AgentTaskCreationOperationService.Reason.UNAVAILABLE, snapshot.reason());
        verify(links, never()).create(any(), anyString(), any());

        authenticate("owner-b", "client-a");
        AgentTaskCreationOperationService.Failure scope = assertThrows(
                AgentTaskCreationOperationService.Failure.class, () -> inTransaction(
                        () -> service.create(SCOPE, "scope-key", command("title", true, null,
                                true, new ArrayList<>(), true, null, List.of()))));
        assertEquals(AgentTaskCreationOperationService.Reason.FORBIDDEN, scope.reason());
    }

    @Test
    void malformedDirectAbilityElementIsBadRequestNotInfrastructureFailure() {
        List<String> abilities = new ArrayList<>();
        abilities.add(null);
        var command = command("title", true, null, true, abilities, true, null, List.of());
        AgentTaskCreationOperationService.Failure failure = assertThrows(
                AgentTaskCreationOperationService.Failure.class,
                () -> inTransaction(() -> service.create(SCOPE, "ability-key", command)));
        assertEquals(AgentTaskCreationOperationService.Reason.BAD_REQUEST, failure.reason());
        verify(operations, never()).reserveAndLock(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> work) {
        return transactions.execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception impossible) {
                throw new AssertionError(impossible);
            }
        });
    }

    private static AgentTaskCreationOperationService.CreateCommand command(String title,
            boolean descriptionPresent, String description, boolean abilitiesPresent,
            List<String> abilities, boolean rewardPresent, Integer reward,
            List<AgentTaskCreationOperationService.InputReference> refs) {
        return new AgentTaskCreationOperationService.CreateCommand(title, descriptionPresent,
                description, abilitiesPresent, abilities, rewardPresent, reward, refs);
    }

    private static AgentTaskCreationOperationService.InputReference ref(String fileId, int version) {
        return new AgentTaskCreationOperationService.InputReference(fileId, version, "REFERENCE");
    }

    private static AgentTaskDTO task(String id, String title, String description) {
        AgentTaskDTO task = new AgentTaskDTO();
        task.setId(id);
        task.setTenantId("0");
        task.setClientId("client-a");
        task.setTitle(title);
        task.setDescription(description);
        task.setRequiredAbilities(List.of());
        return task;
    }

    private static PersonalWorkspaceFileEntity file(String id, String state) {
        PersonalWorkspaceFileEntity file = new PersonalWorkspaceFileEntity()
                .setFileId(id).setOwnerJiacn("owner-a").setState(state);
        file.setTenantId("0");
        file.setClientId("client-a");
        return file;
    }

    private static PersonalWorkspaceVersionEntity version(String id, int number, String mime) {
        PersonalWorkspaceVersionEntity version = new PersonalWorkspaceVersionEntity()
                .setFileId(id).setOwnerJiacn("owner-a").setVersion(number)
                .setContentMimeType(mime);
        version.setTenantId("0");
        version.setClientId("client-a");
        return version;
    }

    private static void authenticate(String owner, String client) {
        Jwt jwt = Jwt.withTokenValue("fixture").header("alg", "none")
                .claim("tenant_id", "0").claim("jiacn", owner).claim("client_id", client)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));
        EsContext context = new EsContext();
        context.setTenantId("0");
        context.setClientId(client);
        context.setJiacn(owner);
        EsContextHolder.setContext(context);
    }
}
