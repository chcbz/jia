package cn.jia.agent.service.impl;

import cn.jia.agent.dao.impl.PersonalWorkspaceTaskLinkDaoImpl;
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
        var global=new GlobalConfig();global.setBanner(false);
        var bean=new MybatisSqlSessionFactoryBean();bean.setDataSource(ds);bean.setConfiguration(configuration);bean.setGlobalConfig(global);
        var mapper=new SqlSessionTemplate(Objects.requireNonNull(bean.getObject())).getMapper(PersonalWorkspaceTaskFileLinkMapper.class);
        var target=new TaskLinkedMaterialReaderImpl(new PersonalWorkspaceTaskLinkDaoImpl(mapper),storage);
        var proxy=new ProxyFactory(target);proxy.setInterfaces(TaskLinkedMaterialReader.class);
        var interceptor=new TransactionInterceptor();interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());proxy.addAdvice(interceptor);
        reader=(TaskLinkedMaterialReader)proxy.getProxy();
    }
    @AfterEach void cleanup() {
        if(admin!=null&&database!=null&&database.startsWith(namespace))admin.execute("DROP DATABASE IF EXISTS `"+database+"`");
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
