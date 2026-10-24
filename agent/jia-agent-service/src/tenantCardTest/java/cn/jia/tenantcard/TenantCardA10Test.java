package cn.jia.tenantcard;

import cn.jia.agent.api.PersonalWorkspaceController;
import cn.jia.agent.api.PersonalWorkspaceConversationFileLinkController;
import cn.jia.agent.api.PersonalWorkspaceTaskFileLinkController;
import cn.jia.agent.dao.PersonalWorkspaceConversationLinkDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.dao.impl.PersonalWorkspaceExecutionDaoImpl;
import cn.jia.agent.entity.PersonalWorkspaceConversationFileLinkEntity;
import cn.jia.agent.entity.PersonalWorkspaceTaskFileLinkEntity;
import cn.jia.agent.service.PersonalWorkspaceConversationLinkService;
import cn.jia.agent.service.PersonalWorkspaceService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.agent.service.impl.FileSystemPersonalWorkspaceStorage;
import cn.jia.agent.service.impl.PersonalWorkspaceConversationLinkServiceImpl;
import cn.jia.agent.service.impl.PersonalWorkspaceTaskLinkServiceImpl;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionInputMapper;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionMapper;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionOutputMapper;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.ibatis.annotations.Select;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantCardA10Test {
    private static final String TENANT = "1";
    private static final String CLIENT = "client-1";
    private static final String OWNER = "owner-1";
    private static final BusinessScope AUTHORITY = new BusinessScope(
            TenantId.of(TENANT), DataDomainId.of("cyf"), OWNER, CLIENT);

    @AfterEach
    void clear() {
        TrustedTenantContext.clear();
    }

    @Test
    void tenantOneControllersUseExactTrustedScopeAndAllowBasicJwtWithoutDomain() {
        PersonalWorkspaceService files = mock(PersonalWorkspaceService.class);
        PersonalWorkspaceTaskLinkService tasks = mock(PersonalWorkspaceTaskLinkService.class);
        PersonalWorkspaceConversationLinkService conversations =
                mock(PersonalWorkspaceConversationLinkService.class);
        var authentication = authentication(TENANT, CLIENT, OWNER, false);

        TrustedTenantContext.withScope(AUTHORITY, () -> {
            new PersonalWorkspaceController(files).list(null, null, null, null, authentication);
            new PersonalWorkspaceTaskFileLinkController(tasks).list("task-1", null, authentication);
            new PersonalWorkspaceConversationFileLinkController(conversations)
                    .list("conversation-1", null, authentication);
            return null;
        });

        verify(files).list(new PersonalWorkspaceService.Scope(TENANT, CLIENT, OWNER),
                new PersonalWorkspaceService.ListQuery(null, null, null, null));
        verify(tasks).list(new PersonalWorkspaceTaskLinkService.Scope(TENANT, CLIENT, OWNER),
                "task-1", null);
        verify(conversations).list(
                new PersonalWorkspaceConversationLinkService.Scope(TENANT, CLIENT, OWNER),
                "conversation-1", null);
    }

    @Test
    void controllersRejectMissingContextAndAnyTenantClientOwnerMismatchBeforeService() {
        PersonalWorkspaceTaskLinkService service = mock(PersonalWorkspaceTaskLinkService.class);
        PersonalWorkspaceTaskFileLinkController controller =
                new PersonalWorkspaceTaskFileLinkController(service);
        assertThrows(RuntimeException.class,
                () -> controller.list("task-1", null, authentication(TENANT, CLIENT, OWNER, true)));
        assertThrows(RuntimeException.class, () -> TrustedTenantContext.withScope(AUTHORITY,
                () -> controller.list("task-1", null,
                        authentication("0", CLIENT, OWNER, true))));
        assertThrows(RuntimeException.class, () -> TrustedTenantContext.withScope(AUTHORITY,
                () -> controller.list("task-1", null,
                        authentication(TENANT, "foreign-client", OWNER, true))));
        assertThrows(RuntimeException.class, () -> TrustedTenantContext.withScope(AUTHORITY,
                () -> controller.list("task-1", null,
                        authentication(TENANT, CLIENT, "foreign-owner", true))));
        verifyNoInteractions(service);
    }

    @Test
    void filesystemKeepsTenantZeroAndTenantOneInIndependentNamespaces(@TempDir Path directory) {
        PersonalWorkspaceStorage storage = new FileSystemPersonalWorkspaceStorage(
                directory.resolve("workspace"), 4096, Set.of("text/plain"));
        byte[] content = "same bytes".getBytes(StandardCharsets.UTF_8);
        var tenantZero = storage.store(new PersonalWorkspaceStorage.Scope("0", CLIENT, OWNER),
                content, "text/plain");
        var tenantOne = storage.store(new PersonalWorkspaceStorage.Scope(TENANT, CLIENT, OWNER),
                content, "text/plain");

        assertNotEquals(tenantZero.storageUri(), tenantOne.storageUri());
        assertArrayEquals(content, storage.read(new PersonalWorkspaceStorage.Scope(
                TENANT, CLIENT, OWNER), tenantOne.storageUri(), tenantOne.sha256(),
                tenantOne.byteLength(), tenantOne.mimeType()).content());
        assertThrows(RuntimeException.class, () -> storage.read(new PersonalWorkspaceStorage.Scope(
                "0", CLIENT, OWNER), tenantOne.storageUri(), tenantOne.sha256(),
                tenantOne.byteLength(), tenantOne.mimeType()));
        assertThrows(RuntimeException.class, () -> storage.store(
                new PersonalWorkspaceStorage.Scope("01", CLIENT, OWNER), content, "text/plain"));
    }

    @Test
    void tenantOneTaskReferenceCreatePersistsExactScope() {
        PersonalWorkspaceTaskLinkDao dao = mock(PersonalWorkspaceTaskLinkDao.class);
        when(dao.lockTask(TENANT, CLIENT, OWNER, "task-1")).thenReturn(true);
        when(dao.lockFile(TENANT, CLIENT, OWNER, "file-1", true)).thenReturn(true);
        when(dao.versionExists(TENANT, CLIENT, OWNER, "file-1", 1)).thenReturn(true);
        when(dao.bumpFileImpactRevision(TENANT, CLIENT, OWNER, "file-1")).thenReturn(true);
        when(dao.completeOperation(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong())).thenReturn(true);
        when(dao.reserveOperation(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            PersonalWorkspaceTaskLinkDao.OperationRow row =
                    new PersonalWorkspaceTaskLinkDao.OperationRow();
            row.setOperationId(invocation.getArgument(6));
            row.setRequestHash(invocation.getArgument(5));
            row.setOperationState("PROCESSING");
            row.setTenantId(TENANT); row.setClientId(CLIENT); row.setOwnerJiacn(OWNER);
            return row;
        });
        doAnswer(invocation -> {
            PersonalWorkspaceTaskFileLinkEntity link = invocation.getArgument(0);
            assertEquals(TENANT, link.getTenantId());
            assertEquals(CLIENT, link.getClientId());
            assertEquals(OWNER, link.getOwnerJiacn());
            return null;
        }).when(dao).insert(any(PersonalWorkspaceTaskFileLinkEntity.class));
        PersonalWorkspaceTaskLinkService service = new PersonalWorkspaceTaskLinkServiceImpl(dao);

        var view = service.create(new PersonalWorkspaceTaskLinkService.Scope(
                        TENANT, CLIENT, OWNER), "task-1",
                new PersonalWorkspaceTaskLinkService.CreateCommand(
                        "file-1", 1, "REFERENCE", "key-1"));

        assertEquals("task-1", view.taskId());
        assertEquals("file-1", view.fileId());
        verify(dao).insert(any(PersonalWorkspaceTaskFileLinkEntity.class));
    }

    @Test
    void tenantOneConversationReferenceRequiresConversationAccessAndPersistsExactScope() {
        PersonalWorkspaceConversationLinkDao dao = mock(PersonalWorkspaceConversationLinkDao.class);
        WorkspaceConversationAccessService access = mock(WorkspaceConversationAccessService.class);
        when(dao.lockFile(TENANT, CLIENT, OWNER, "file-1", true)).thenReturn(true);
        when(dao.versionExists(TENANT, CLIENT, OWNER, "file-1", 1)).thenReturn(true);
        when(dao.bumpFileImpactRevision(TENANT, CLIENT, OWNER, "file-1")).thenReturn(true);
        when(dao.completeOperation(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong())).thenReturn(true);
        when(dao.reserveOperation(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            PersonalWorkspaceConversationLinkDao.OperationRow row =
                    new PersonalWorkspaceConversationLinkDao.OperationRow();
            row.setOperationId(invocation.getArgument(6));
            row.setRequestHash(invocation.getArgument(5));
            row.setOperationState("PROCESSING");
            row.setTenantId(TENANT); row.setClientId(CLIENT); row.setOwnerJiacn(OWNER);
            return row;
        });
        doAnswer(invocation -> {
            PersonalWorkspaceConversationFileLinkEntity link = invocation.getArgument(0);
            assertEquals(TENANT, link.getTenantId());
            assertEquals(CLIENT, link.getClientId());
            assertEquals(OWNER, link.getOwnerJiacn());
            return null;
        }).when(dao).insert(any(PersonalWorkspaceConversationFileLinkEntity.class));
        PersonalWorkspaceConversationLinkService service =
                new PersonalWorkspaceConversationLinkServiceImpl(dao, access);

        var view = service.create(new PersonalWorkspaceConversationLinkService.Scope(
                        TENANT, CLIENT, OWNER), "conversation-1",
                new PersonalWorkspaceConversationLinkService.CreateCommand(
                        "file-1", 1, "INPUT", "key-1"));

        assertEquals("conversation-1", view.conversationId());
        verify(access).requireAccessible(new WorkspaceConversationAccessService.Scope(
                TENANT, CLIENT, OWNER), "conversation-1");
        verify(dao).insert(any(PersonalWorkspaceConversationFileLinkEntity.class));
    }

    @Test
    void tenantOneUsageMayReadExecutionImpactWithoutOpeningExecutionWrites() {
        PersonalWorkspaceExecutionMapper executions = mock(PersonalWorkspaceExecutionMapper.class);
        PersonalWorkspaceExecutionInputMapper inputs = mock(PersonalWorkspaceExecutionInputMapper.class);
        PersonalWorkspaceExecutionDaoImpl dao = new PersonalWorkspaceExecutionDaoImpl(
                executions, inputs, mock(PersonalWorkspaceExecutionOutputMapper.class));
        when(inputs.listActiveExecutionIdsByFile(TENANT, CLIENT, OWNER, "file-1", 100))
                .thenReturn(List.of("execution-1"));

        assertEquals(List.of("execution-1"), dao.listActiveExecutionIdsByFile(
                TENANT, CLIENT, OWNER, "file-1", 100));
        assertThrows(IllegalArgumentException.class,
                () -> dao.find(TENANT, CLIENT, OWNER, "execution-1"));
        verifyNoInteractions(executions);
    }

    @Test
    void activeExecutionSqlRejectsCollationAliasesAndParentChildScopeDrift() throws Exception {
        var method = PersonalWorkspaceExecutionInputMapper.class.getMethod(
                "listActiveExecutionIdsByFile", String.class, String.class, String.class,
                String.class, int.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value())
                // H2 BINARY means BINARY(1), which truncates values; VARBINARY models
                // MySQL CAST(value AS BINARY) byte comparison without that truncation.
                .replace(" AS BINARY", " AS VARBINARY")
                .replace("#{tenantId}", "'1'")
                .replace("#{clientId}", "'client-1'")
                .replace("#{ownerJiacn}", "'owner-1'")
                .replace("#{fileId}", "'file-1'")
                .replace("#{limit}", "100");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:a10_execution_exact;MODE=MySQL;DB_CLOSE_DELAY=-1");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agent_personal_workspace_execution ("
                    + "execution_id VARCHAR_IGNORECASE(100), tenant_id VARCHAR_IGNORECASE(50), "
                    + "client_id VARCHAR_IGNORECASE(50), owner_jiacn VARCHAR_IGNORECASE(50), "
                    + "execution_state VARCHAR_IGNORECASE(24))");
            statement.execute("CREATE TABLE agent_personal_workspace_execution_input ("
                    + "execution_id VARCHAR_IGNORECASE(100), tenant_id VARCHAR_IGNORECASE(50), "
                    + "client_id VARCHAR_IGNORECASE(50), owner_jiacn VARCHAR_IGNORECASE(50), "
                    + "file_id VARCHAR_IGNORECASE(100), grant_state VARCHAR_IGNORECASE(24))");
            insertExecution(connection, "execution-ok", "1", "client-1", "owner-1");
            insertInput(connection, "execution-ok", "1", "client-1", "owner-1", "file-1");
            insertExecution(connection, "execution-client", "1", "CLIENT-1", "owner-1");
            insertInput(connection, "execution-client", "1", "CLIENT-1", "owner-1", "file-1");
            insertExecution(connection, "execution-owner", "1", "client-1", "OWNER-1");
            insertInput(connection, "execution-owner", "1", "client-1", "OWNER-1", "file-1");
            insertExecution(connection, "execution-file", "1", "client-1", "owner-1");
            insertInput(connection, "execution-file", "1", "client-1", "owner-1", "FILE-1");
            insertExecution(connection, "execution-join-client", "1", "CLIENT-1", "owner-1");
            insertInput(connection, "execution-join-client", "1", "client-1", "owner-1", "file-1");
            insertExecution(connection, "execution-join-owner", "1", "client-1", "OWNER-1");
            insertInput(connection, "execution-join-owner", "1", "client-1", "owner-1", "file-1");
            insertExecution(connection, "execution-join-id", "1", "client-1", "owner-1");
            insertInput(connection, "EXECUTION-JOIN-ID", "1", "client-1", "owner-1", "file-1");
            List<String> found = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery(sql)) {
                while (rows.next()) found.add(rows.getString(1));
            }
            assertEquals(List.of("execution-ok"), found);
        }
    }

    private static void insertExecution(Connection connection, String executionId, String tenant,
            String client, String owner) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO agent_personal_workspace_execution VALUES (?,?,?,?, 'QUEUED')")) {
            insert.setString(1, executionId); insert.setString(2, tenant);
            insert.setString(3, client); insert.setString(4, owner); insert.executeUpdate();
        }
    }

    private static void insertInput(Connection connection, String executionId, String tenant,
            String client, String owner, String fileId) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO agent_personal_workspace_execution_input VALUES (?,?,?,?,?, 'ACTIVE')")) {
            insert.setString(1, executionId); insert.setString(2, tenant);
            insert.setString(3, client); insert.setString(4, owner);
            insert.setString(5, fileId); insert.executeUpdate();
        }
    }

    private static JwtAuthenticationToken authentication(String tenant, String client,
            String owner, boolean includeDomain) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none")
                .claim("tenant_id", tenant).claim("client_id", client).claim("jiacn", owner)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (includeDomain) builder.claim("data_domain_id", "cyf");
        return new JwtAuthenticationToken(builder.build(), List.of());
    }
}
