package cn.jia.agent.service.impl;

import cn.jia.agent.dao.impl.PersonalWorkspaceTaskLinkDaoImpl;
import cn.jia.agent.dao.impl.PersonalWorkspaceConversationLinkDaoImpl;
import cn.jia.agent.mapper.PersonalWorkspaceConversationFileLinkMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import cn.jia.agent.mapper.PersonalWorkspaceTaskFileLinkMapper;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.agent.service.TaskLinkedMaterialReader;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real mapper and Spring transaction proxy, with the existing isolated MySQL fixture contract. */
@EnabledIfEnvironmentVariable(named="CYF_REVIEW_MYSQLD",matches=".+")
class TaskLinkedMaterialReaderMySqlTest {
    @TempDir static Path scratch;
    private static Process initializationProcess, databaseProcess;
    private static String baseUrl;
    private static JdbcTemplate admin;
    JdbcTemplate jdbc;
    String database,namespace;
    DataSourceTransactionManager transactions;
    TaskLinkedMaterialReader reader;
    PersonalWorkspaceTaskLinkDaoImpl taskLinks;
    PersonalWorkspaceConversationLinkDaoImpl conversationLinks;
    final PersonalWorkspaceStorage storage=mock(PersonalWorkspaceStorage.class);
    final PersonalWorkspaceTaskLinkService.Scope scope=new PersonalWorkspaceTaskLinkService.Scope("0","client","owner");
    final TaskLinkedMaterialReader.Selection selection=new TaskLinkedMaterialReader.Selection("file",2,"REFERENCE");
    final TaskLinkedMaterialReader.Metadata metadata=new TaskLinkedMaterialReader.Metadata("image/png","a".repeat(64),3);

    @BeforeAll static void startOwnedDatabase() throws Exception {
        Path executable = Path.of(System.getenv("CYF_REVIEW_MYSQLD")).toRealPath();
        assertTrue(Files.isExecutable(executable));
        Path data = Files.createDirectory(scratch.resolve("data"));
        Path log = scratch.resolve("mysql.log");
        String user = System.getProperty("user.name");
        try {
            initializationProcess = new ProcessBuilder(executable.toString(), "--no-defaults", "--initialize-insecure",
                    "--user=" + user, "--datadir=" + data, "--basedir=" + executable.getParent().getParent())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertEquals(0, initializationProcess.waitFor(), () -> "Owned MySQL init failed: " + read(log));
            initializationProcess = null;
            int port;
            do { try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); } }
            while (port == 3306 || port == 33060);
            databaseProcess = new ProcessBuilder(executable.toString(), "--no-defaults", "--user=" + user,
                    "--datadir=" + data, "--basedir=" + executable.getParent().getParent(),
                    "--socket=" + scratch.resolve("mysql.sock"), "--pid-file=" + scratch.resolve("mysql.pid"),
                    "--port=" + port, "--bind-address=127.0.0.1", "--mysqlx=OFF", "--skip-log-bin",
                    "--innodb-buffer-pool-size=67108864")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            baseUrl = "jdbc:mysql://127.0.0.1:" + port + "/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            Class.forName("com.mysql.cj.jdbc.Driver");
            while (true) {
                assertTrue(databaseProcess.isAlive(), () -> "Owned MySQL exited: " + read(log));
                try (var connection = DriverManager.getConnection(baseUrl, "root", "")) {
                    assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("8.0.21"));
                    break;
                } catch (SQLException notReady) { Thread.sleep(100); }
            }
            admin = new JdbcTemplate(new DriverManagerDataSource(baseUrl, "root", ""));
            System.out.println("CYF_TASK_MATERIAL_MYSQL owned=true version=" + admin.queryForObject("SELECT VERSION()", String.class));
        } catch (Throwable failure) {
            try { stopOwnedDatabase(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll static void stopOwnedDatabase() throws Exception {
        // Only handles returned by this test's ProcessBuilder; never kill by name, PID file or port.
        try { stopOwnedProcess(databaseProcess); }
        finally {
            databaseProcess = null;
            try { stopOwnedProcess(initializationProcess); }
            finally { initializationProcess = null; admin = null; }
        }
    }
    private static void stopOwnedProcess(Process process) throws InterruptedException {
        if (process == null) return;
        process.destroy();
        try { process.waitFor(); }
        catch (InterruptedException interrupted) {
            process.destroyForcibly();
            try { process.waitFor(); } finally { Thread.currentThread().interrupt(); }
            throw interrupted;
        }
    }
    private static String read(Path file) {
        try { return Files.readString(file); }
        catch (Exception failure) { return failure.getClass().getSimpleName(); }
    }

    @BeforeEach void setup() throws Exception {
        String url=baseUrl,user="root",password="";
        namespace="cyf_material_";database=namespace+UUID.randomUUID().toString().substring(0,8);
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        int query=url.indexOf('?');String base=query<0?url:url.substring(0,query),suffix=query<0?"":url.substring(query);
        var ds=new DriverManagerDataSource(base.substring(0,base.lastIndexOf('/')+1)+database+suffix,user,password);
        jdbc=new JdbcTemplate(ds);transactions=new DataSourceTransactionManager(ds);
        String scopeColumns="tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),file_id VARCHAR(100)";
        jdbc.execute("CREATE TABLE agent_personal_workspace_file ("+scopeColumns+", state VARCHAR(20)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE agent_personal_workspace_file_version ("+scopeColumns+", version INT, content_mime_type VARCHAR(100),content_hash VARCHAR(64),byte_length BIGINT,storage_uri VARCHAR(200)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE agent_personal_workspace_task_file_link ("+scopeColumns+", task_id VARCHAR(100),file_version INT,link_role VARCHAR(20),link_state VARCHAR(20)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO agent_personal_workspace_file VALUES ('0','client','owner','file','ACTIVE')");
        jdbc.update("INSERT INTO agent_personal_workspace_file_version VALUES ('0','client','owner','file',2,'image/png',?,3,'private:v2')",metadata.sha256());
        jdbc.update("INSERT INTO agent_personal_workspace_task_file_link VALUES ('0','client','owner','file','task',2,'REFERENCE','ACTIVE')");
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PersonalWorkspaceTaskFileLinkMapper.class);
        configuration.addMapper(PersonalWorkspaceConversationFileLinkMapper.class);
        var global=new GlobalConfig();global.setBanner(false);
        var bean=new MybatisSqlSessionFactoryBean();bean.setDataSource(ds);bean.setConfiguration(configuration);bean.setGlobalConfig(global);
        var mapper=new SqlSessionTemplate(Objects.requireNonNull(bean.getObject())).getMapper(PersonalWorkspaceTaskFileLinkMapper.class);
        taskLinks = new PersonalWorkspaceTaskLinkDaoImpl(mapper);
        conversationLinks = new PersonalWorkspaceConversationLinkDaoImpl(
                new SqlSessionTemplate(Objects.requireNonNull(bean.getObject()))
                        .getMapper(PersonalWorkspaceConversationFileLinkMapper.class));
        var target=new TaskLinkedMaterialReaderImpl(taskLinks,storage);
        var proxy=new ProxyFactory(target);proxy.setInterfaces(TaskLinkedMaterialReader.class);
        var interceptor=new TransactionInterceptor();interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());proxy.addAdvice(interceptor);
        reader=(TaskLinkedMaterialReader)proxy.getProxy();
    }
    @AfterEach void cleanup() {
        if(admin!=null&&database!=null&&database.startsWith(namespace))admin.execute("DROP DATABASE IF EXISTS `"+database+"`");
    }
    @Test void canonicalMySqlSchemaAllowsIndependentTenantLinksAndOperations() {
        installCanonicalWorkspaceSchema();
        exerciseTenantLinks();
    }

    @Test void existingTenantZeroChecksAreMigratedWithoutChangingProductionRows() {
        installCanonicalWorkspaceSchema();
        String[][] oldChecks = {
                {"task_file_link", "chk_pwtl_single_tenant"},
                {"task_link_operation", "chk_pwtl_operation_tenant"},
                {"conversation_file_link", "chk_pwcl_single_tenant"},
                {"conversation_link_operation", "chk_pwcl_operation_tenant"}
        };
        for (String[] check : oldChecks) {
            jdbc.execute("ALTER TABLE agent_personal_workspace_" + check[0]
                    + " ADD CONSTRAINT " + check[1] + " CHECK (tenant_id = '0')");
        }
        insertLink("task", "0", "prior");
        insertLink("conversation", "0", "prior");
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> insertLink("task", "1", "rejected"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> insertLink("conversation", "1", "rejected"));
        var priorTask = jdbc.queryForMap("SELECT * FROM agent_personal_workspace_task_file_link");
        var priorConversation = jdbc.queryForMap("SELECT * FROM agent_personal_workspace_conversation_file_link");
        script("db/agent-personal-workspace-tenant-links-20261029.sql");
        script("db/agent-personal-workspace-tenant-links-20261029.sql");
        assertEquals(priorTask, jdbc.queryForMap("SELECT * FROM agent_personal_workspace_task_file_link"));
        assertEquals(priorConversation, jdbc.queryForMap("SELECT * FROM agent_personal_workspace_conversation_file_link"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema=DATABASE() AND constraint_name IN "
                + "('chk_pwtl_single_tenant','chk_pwtl_operation_tenant',"
                + "'chk_pwcl_single_tenant','chk_pwcl_operation_tenant')", Integer.class));
        exerciseTenantLinks();
    }

    @Test void realServiceTransactionsKeepTenantLinksAndFailedReservationsSeparate() {
        installCanonicalWorkspaceSchema();
        jdbc.execute("CREATE TABLE agent_task_meta (tenant_id VARCHAR(50),client_id VARCHAR(50),"
                + "owner_jiacn VARCHAR(50),task_id VARCHAR(100)) ENGINE=InnoDB");
        for (String tenant : java.util.List.of("0", "1")) {
            jdbc.update("INSERT INTO agent_task_meta VALUES (?,'client','owner','parent')", tenant);
            seedFile(tenant, "shared");
        }
        seedFile("0", "production-only");
        var taskService = transactional(new PersonalWorkspaceTaskLinkServiceImpl(taskLinks),
                PersonalWorkspaceTaskLinkService.class);
        var access = mock(cn.jia.chat.service.WorkspaceConversationAccessService.class);
        var conversationService = transactional(new PersonalWorkspaceConversationLinkServiceImpl(conversationLinks, access),
                cn.jia.agent.service.PersonalWorkspaceConversationLinkService.class);
        for (String tenant : java.util.List.of("0", "1")) {
            var taskScope = new PersonalWorkspaceTaskLinkService.Scope(tenant, "client", "owner");
            var conversationScope = new cn.jia.agent.service.PersonalWorkspaceConversationLinkService.Scope(tenant, "client", "owner");
            var taskCommand = new PersonalWorkspaceTaskLinkService.CreateCommand("shared", 2, "REFERENCE", "same-key");
            var conversationCommand = new cn.jia.agent.service.PersonalWorkspaceConversationLinkService.CreateCommand("shared", 2, "REFERENCE", "same-key");
            var taskLink = taskService.create(taskScope, "parent", taskCommand);
            var conversationLink = conversationService.create(conversationScope, "41", conversationCommand);
            assertEquals(taskLink, taskService.create(taskScope, "parent", taskCommand));
            assertEquals(conversationLink, conversationService.create(conversationScope, "41", conversationCommand));
            assertEquals(1, taskService.list(taskScope, "parent", null).items().size());
            assertEquals(1, conversationService.list(conversationScope, "41", null).items().size());
            verify(access, atLeastOnce()).requireAccessible(
                    new cn.jia.chat.service.WorkspaceConversationAccessService.Scope(tenant, "client", "owner"), "41");
        }
        var gray = new PersonalWorkspaceTaskLinkService.Scope("1", "client", "owner");
        var grayConversation = new cn.jia.agent.service.PersonalWorkspaceConversationLinkService.Scope("1", "client", "owner");
        assertEquals(PersonalWorkspaceTaskLinkService.Reason.NOT_FOUND, assertThrows(
                PersonalWorkspaceTaskLinkService.Failure.class, () -> taskService.create(gray, "parent",
                        new PersonalWorkspaceTaskLinkService.CreateCommand("production-only", 2, "REFERENCE", "denied-key"))).getReason());
        assertEquals(cn.jia.agent.service.PersonalWorkspaceConversationLinkService.Reason.NOT_FOUND, assertThrows(
                cn.jia.agent.service.PersonalWorkspaceConversationLinkService.Failure.class, () -> conversationService.create(grayConversation, "41",
                        new cn.jia.agent.service.PersonalWorkspaceConversationLinkService.CreateCommand("production-only", 2, "REFERENCE", "denied-key"))).getReason());
        for (String kind : java.util.List.of("task", "conversation")) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_" + kind
                    + "_link_operation WHERE idempotency_key='denied-key'", Integer.class));
        }
        doThrow(new IllegalStateException("unavailable")).when(access).requireAccessible(
                new cn.jia.chat.service.WorkspaceConversationAccessService.Scope("1", "client", "owner"), "42");
        assertThrows(cn.jia.agent.service.PersonalWorkspaceConversationLinkService.Failure.class,
                () -> conversationService.create(grayConversation, "42",
                        new cn.jia.agent.service.PersonalWorkspaceConversationLinkService.CreateCommand("shared", 2, "REFERENCE", "acl-denied")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_conversation_link_operation "
                + "WHERE idempotency_key='acl-denied'", Integer.class));
    }

    private void seedFile(String tenant, String file) {
        jdbc.update("INSERT INTO agent_personal_workspace_file "
                + "(file_id,owner_jiacn,source_kind,display_name,media_family,state,metadata_revision,latest_version,created_at,tenant_id,client_id) "
                + "VALUES (?,'owner','UPLOAD','image.png','IMAGE','ACTIVE',1,2,1,?,'client')", file, tenant);
        jdbc.update("INSERT INTO agent_personal_workspace_file_version "
                + "(file_id,owner_jiacn,version,original_filename,content_mime_type,byte_length,content_hash,storage_uri,created_at,tenant_id,client_id) "
                + "VALUES (?,'owner',2,'image.png','image/png',3,?,'private:v2',1,?,'client')", file, "a".repeat(64), tenant);
    }

    private <T> T transactional(T target, Class<T> contract) {
        var proxy = new ProxyFactory(target);
        proxy.setInterfaces(contract);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        return contract.cast(proxy.getProxy());
    }

    private void installCanonicalWorkspaceSchema() {
        // Replace only this test's minimal projection inside its private random database.
        jdbc.execute("DROP TABLE agent_personal_workspace_task_file_link");
        jdbc.execute("DROP TABLE agent_personal_workspace_file_version");
        jdbc.execute("DROP TABLE agent_personal_workspace_file");
        script("db/agent-personal-workspace-v1.sql");
        script("db/agent-personal-workspace-v1_11-task-links.sql");
        script("db/agent-personal-workspace-v1_13-conversation-links.sql");
    }

    private void script(String path) {
        // ResourceDatabasePopulator retains one connection for SET/PREPARE/EXECUTE.
        new ResourceDatabasePopulator(new ClassPathResource(path))
                .execute(Objects.requireNonNull(jdbc.getDataSource()));
    }

    private void insertLink(String kind, String tenant, String relation) {
        if (!java.util.Set.of("task", "conversation").contains(kind)) throw new IllegalArgumentException();
        jdbc.update("INSERT INTO agent_personal_workspace_" + kind + "_file_link "
                + "(relation_id,owner_jiacn," + kind + "_id,file_id,file_version,link_role,"
                + "link_state,relation_revision,created_at,tenant_id,client_id) "
                + "VALUES (?,'owner','parent',?,2,'REFERENCE','ACTIVE',1,1,?,'client')",
                relation, "file-" + relation, tenant);
    }

    private void exerciseTenantLinks() {
        for (String tenant : java.util.List.of("0", "1")) {
            insertLink("task", tenant, "shared");
            insertLink("conversation", tenant, "shared");
            var task = taskLinks.reserveOperation(tenant, "client", "owner", "CREATE",
                    "same-key", "a".repeat(64), "task-op-" + tenant, 1);
            var conversation = conversationLinks.reserveOperation(tenant, "client", "owner", "CREATE",
                    "same-key", "a".repeat(64), "conversation-op-" + tenant, 1);
            assertEquals("task-op-" + tenant, task.getOperationId());
            assertEquals("conversation-op-" + tenant, conversation.getOperationId());
            assertEquals(tenant, taskLinks.findByRelation(tenant, "client", "owner", "parent", "shared").getTenantId());
            assertEquals(tenant, conversationLinks.findByRelation(tenant, "client", "owner", "parent", "shared").getTenantId());
            assertEquals("task-op-" + tenant, taskLinks.reserveOperation(tenant, "client", "owner", "CREATE",
                    "same-key", "a".repeat(64), "unused", 2).getOperationId());
            assertEquals("conversation-op-" + tenant, conversationLinks.reserveOperation(tenant, "client", "owner", "CREATE",
                    "same-key", "a".repeat(64), "unused", 2).getOperationId());
        }
        insertLink("task", "0", "production-only");
        insertLink("conversation", "0", "production-only");
        assertNull(taskLinks.findByRelation("1", "client", "owner", "parent", "production-only"));
        assertNull(conversationLinks.findByRelation("1", "client", "owner", "parent", "production-only"));
        assertFalse(taskLinks.completeOperation("1", "client", "owner", "task-op-0", "shared", 3));
        assertFalse(conversationLinks.completeOperation("1", "client", "owner", "conversation-op-0", "shared", 3));
        assertTrue(taskLinks.completeOperation("1", "client", "owner", "task-op-1", "shared", 3));
        assertTrue(conversationLinks.completeOperation("1", "client", "owner", "conversation-op-1", "shared", 3));
        assertTrue(taskLinks.changeState("1", "client", "owner", "parent", "shared", "ACTIVE", 1, "DETACHED", 2, 3L));
        assertTrue(conversationLinks.changeState("1", "client", "owner", "parent", "shared", "ACTIVE", 1, "DETACHED", 2, 3L));
        assertFalse(taskLinks.changeState("1", "client", "owner", "parent", "shared", "ACTIVE", 1, "DETACHED", 2, 3L));
        assertFalse(conversationLinks.changeState("1", "client", "owner", "parent", "shared", "ACTIVE", 1, "DETACHED", 2, 3L));
        assertEquals("ACTIVE", taskLinks.findByRelation("0", "client", "owner", "parent", "shared").getLinkState());
        assertEquals("ACTIVE", conversationLinks.findByRelation("0", "client", "owner", "parent", "shared").getLinkState());
        for (String kind : java.util.List.of("task", "conversation")) {
            assertThrows(org.springframework.dao.DataAccessException.class, () ->
                    jdbc.update("UPDATE agent_personal_workspace_" + kind + "_file_link SET file_version=0 WHERE tenant_id='1'"));
            assertThrows(org.springframework.dao.DataAccessException.class, () ->
                    jdbc.update("UPDATE agent_personal_workspace_" + kind + "_link_operation SET request_hash='bad' WHERE tenant_id='1'"));
        }
    }

    @Test void realMapperKeepsExactScopeVersionAndRole() {
        assertEquals(metadata,reader.metadata(scope,"task",selection));
        assertMissing(new PersonalWorkspaceTaskLinkService.Scope("0","client","Owner"),"task",selection);
        assertMissing(new PersonalWorkspaceTaskLinkService.Scope("0","Client","owner"),"task",selection);
        assertMissing(new PersonalWorkspaceTaskLinkService.Scope("1","client","owner"),"task",selection);
        assertMissing(scope,"Task",selection);
        assertMissing(scope,"task",new TaskLinkedMaterialReader.Selection("File",2,"REFERENCE"));
        assertMissing(scope,"task",new TaskLinkedMaterialReader.Selection("file",1,"REFERENCE"));
        assertMissing(scope,"task",new TaskLinkedMaterialReader.Selection("file",2,"INPUT"));
        jdbc.update("INSERT INTO agent_personal_workspace_task_file_link SELECT * FROM agent_personal_workspace_task_file_link");
        assertMissing(scope,"task",selection); // Duplicate facts cannot be hidden with LIMIT 1.
    }
    @Test void proxySuspendsRepeatableReadAndSeesRevocationAfterStorage() {
        when(storage.read(any(),anyString(),anyString(),anyLong(),anyString())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            jdbc.update("UPDATE agent_personal_workspace_task_file_link SET link_state='DETACHED'");
            return new PersonalWorkspaceStorage.StoredContent(new byte[]{1,2,3},metadata.sha256(),3,"image/png");
        });
        var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(status->{
            assertEquals("ACTIVE",jdbc.queryForObject("SELECT link_state FROM agent_personal_workspace_task_file_link",String.class));
            assertEquals(TaskLinkedMaterialReader.Failure.Reason.NOT_FOUND,assertThrows(TaskLinkedMaterialReader.Failure.class,
                    ()->reader.read(scope,"task",selection,metadata)).reason());
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("ACTIVE",jdbc.queryForObject("SELECT link_state FROM agent_personal_workspace_task_file_link",String.class));
        });
        assertEquals("DETACHED",jdbc.queryForObject("SELECT link_state FROM agent_personal_workspace_task_file_link",String.class));
    }
    void assertMissing(PersonalWorkspaceTaskLinkService.Scope s,String task,TaskLinkedMaterialReader.Selection x) {
        assertEquals(TaskLinkedMaterialReader.Failure.Reason.NOT_FOUND,assertThrows(TaskLinkedMaterialReader.Failure.class,
                ()->reader.metadata(s,task,x)).reason());
    }
}
