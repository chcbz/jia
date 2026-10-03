package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentTaskProviderCostConsentSchemaInitializer;
import cn.jia.agent.config.ControlledImageExecutionSchemaInitializer;
import cn.jia.agent.config.ControlledImageFollowupV3SchemaInitializer;
import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.config.PersonalWorkspaceExecutionSchemaInitializer;
import cn.jia.agent.dao.ControlledImageExecutionSourceV3Dao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.dao.impl.PersonalWorkspaceExecutionDaoImpl;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionInputMapper;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionMapper;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionOutputMapper;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL/MyBatis predicates for v3 result stage, commit, failure and owner isolation. */
@EnabledIfEnvironmentVariable(named="MMD_U1_REFERENCE_MYSQL_URL",matches=".+")
class PersonalWorkspaceControlledImageV3ResultMySqlTest {
    @TempDir Path storageRoot;
    private JdbcTemplate admin,jdbc;
    private DriverManagerDataSource dataSource;
    private String database,namespace;
    private TransactionTemplate transactions;
    private PersonalWorkspaceExecutionDao rows;
    private PersonalWorkspaceExecutionServiceImpl service;
    private final ControlledImageFollowupAuthorityService authority=
            mock(ControlledImageFollowupAuthorityService.class);
    private final WorkspaceConversationAccessService conversation=
            mock(WorkspaceConversationAccessService.class);

    @BeforeEach void setUp() throws Exception {
        String url=required("MMD_U1_REFERENCE_MYSQL_URL");
        String user=required("MMD_U1_REFERENCE_MYSQL_USER");
        String password=System.getenv("MMD_U1_REFERENCE_MYSQL_PASSWORD");
        String prefix=required("MMD_U1_REFERENCE_MYSQL_DATABASE_PREFIX");
        if(password==null||!url.startsWith("jdbc:mysql://")||!prefix.matches("[A-Za-z0-9_]{1,20}")
                ||!"true".equals(required("MMD_U1_REFERENCE_MYSQL_ISOLATED_FIXTURE")))
            throw new IllegalStateException("isolated MySQL acknowledgement required");
        namespace=prefix+"_v3_result_";
        database=namespace+UUID.randomUUID().toString().substring(0,8);
        admin=new JdbcTemplate(ds(url,user,password));
        assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("8."));
        assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,database));
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        dataSource=ds(databaseUrl(url,database),user,password);
        jdbc=new JdbcTemplate(dataSource);
        new PersonalWorkspaceExecutionSchemaInitializer(jdbc).afterPropertiesSet();
        new AgentTaskProviderCostConsentSchemaInitializer(jdbc).afterPropertiesSet();
        new ControlledImageExecutionSchemaInitializer(jdbc).afterPropertiesSet();
        new ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet();
        rows=executionDao(dataSource);
        transactions=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        configureService();
    }

    @AfterEach void tearDown() {
        if(admin==null||database==null)return;
        if(!database.startsWith(namespace))throw new IllegalStateException("unowned database");
        admin.execute("DROP DATABASE IF EXISTS `"+database+"`");
        assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,database));
    }

    @Test void consumedV3ResultPersistsBytesCommitAndFailureWithoutCrossScopeMutation() throws Exception {
        PersonalWorkspaceExecutionEntity commit=persist("exec-result","run-result","42");
        PersonalWorkspaceExecutionEntity failure=persist("exec-failure","run-failure","42");
        byte[] bytes=png();String hash=sha(bytes);
        var runtime=new PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","agent","runtime");
        var fence=new PersonalWorkspaceExecutionService.ConversationFence(1,"lease-token");

        var staged=inTx(() -> service.stageConversationOutput(runtime,"task","run-result",fence,
                "output_1","bird.png","image/png",bytes));
        String manifest="pwe_m_"+sha("task\nrun-result\noutput_1\n"+hash+"\n"+bytes.length+"\n");
        var committed=inTx(() -> service.commitConversationOutput(runtime,"task","run-result",fence,
                manifest,List.of(new PersonalWorkspaceExecutionService.OutputDeclaration(
                        "output_1",hash,bytes.length))));
        var failed=inTx(() -> service.failConversation(runtime,"task","run-failure",fence,
                "AGENT_DELIVERY_FAILED"));

        assertEquals(hash,staged.sha256());
        assertEquals("COMMITTED",committed.state());
        assertEquals("FAILED",failed.state());
        var persistedCommit=inTx(() -> rows.find("0","client","owner",commit.getExecutionId()));
        var persistedOutput=inTx(() -> rows.lockOutput("0","client","owner",commit.getExecutionId(),"output_1"));
        var persistedFailure=inTx(() -> rows.find("0","client","owner",failure.getExecutionId()));
        assertEquals("OUTPUT_COMMITTED",persistedCommit.getExecutionState());
        assertNotNull(persistedOutput);assertEquals("COMMITTED",persistedOutput.getOutputState());
        assertEquals(hash,persistedOutput.getContentHash());assertEquals((long)bytes.length,persistedOutput.getByteLength());
        assertEquals("FAILED",persistedFailure.getExecutionState());
        assertEquals("AGENT_DELIVERY_FAILED",persistedFailure.getFailureCode());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_execution "
                +"WHERE owner_jiacn='owner' AND conversation_provider_started_at=10 "
                +"AND conversation_provider_lease_version=1",Integer.class));

        var foreignOwner=new PersonalWorkspaceExecutionService.RuntimeScope(
                "0","client","other-owner","agent","runtime");
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,
                () -> inTx(() -> service.stageConversationOutput(foreignOwner,"task","run-result",fence,
                        "output_1","bird.png","image/png",bytes))).getReason());
        var wrongAgent=new PersonalWorkspaceExecutionService.RuntimeScope(
                "0","client","owner","other-agent","runtime");
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,
                () -> inTx(() -> service.failConversation(wrongAgent,"task","run-result",fence,
                        "AGENT_DELIVERY_FAILED"))).getReason());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_execution_output",
                Integer.class));
        assertEquals("OUTPUT_COMMITTED",rows.find("0","client","owner","exec-result").getExecutionState());
    }

    @Test void currentConversationAclAndRuntimeFenceStillBlockRealDaoWrites() throws Exception {
        persist("exec-denied","run-denied","denied-conversation");
        when(conversation.requireAccessible(any(),eq("denied-conversation"))).thenReturn(
                new WorkspaceConversationAccessService.ConversationView("denied-conversation","bounty",
                        "task:task","task",List.of("agent"),1,1));
        byte[] bytes=png();
        var runtime=new PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","agent","runtime");
        var fence=new PersonalWorkspaceExecutionService.ConversationFence(1,"lease-token");

        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,
                () -> inTx(() -> service.failConversation(runtime,"task","run-denied",
                        new PersonalWorkspaceExecutionService.ConversationFence(2,"lease-token"),
                        "AGENT_DELIVERY_FAILED"))).getReason());
        when(conversation.requireAccessible(any(),eq("denied-conversation")))
                .thenThrow(new IllegalStateException("revoked"));
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,
                () -> inTx(() -> service.stageConversationOutput(runtime,"task","run-denied",fence,
                        "output_1","bird.png","image/png",bytes))).getReason());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_execution_output",
                Integer.class));
        assertEquals("QUEUED",rows.find("0","client","owner","exec-denied").getExecutionState());
    }

    private void configureService() {
        PersonalWorkspaceStorage storage=new FileSystemPersonalWorkspaceStorage(
                storageRoot.toAbsolutePath(),1_000_000,Set.of("image/png"));
        service=new PersonalWorkspaceExecutionServiceImpl(rows,mock(cn.jia.agent.dao.PersonalWorkspaceDao.class),
                mock(cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao.class),
                mock(cn.jia.agent.dao.AgentRuntimeDao.class),storage,mock(PersonalWorkspaceWriteService.class),
                new PersonalWorkspaceExecutionProperties(List.of("image/png")));
        AgentTaskMutationTransaction taskTransactions=mock(AgentTaskMutationTransaction.class);
        when(taskTransactions.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),
                eq("task"),any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            AgentTaskMutationTransaction.LockedTaskMutation<Object> mutation=invocation.getArgument(4);
            var root=new AgentTaskMetaEntity().setTaskId("task").setAssignedAgentId("agent").setTaskVersion(1L);
            root.setTenantId("0");root.setClientId("client");root.setOwnerJiacn("owner");
            return mutation.apply(root);
        });
        service.setConversationAdmission(mock(cn.jia.agent.service.AgentTaskExecutionGrantService.class),
                taskTransactions);
        service.setTaskExecutionDependencies(conversation,mock(cn.jia.agent.dao.AgentTaskWorkItemDao.class),
                mock(cn.jia.agent.service.AgentWorkItemLeaseService.class));
        service.setControlledImageFollowupV3(authority,mock(ControlledImageExecutionSourceV3Dao.class));
        ReflectionTestUtils.setField(service,"conversationExecutionEnabled",true);
        when(conversation.requireAccessible(any(),eq("42"))).thenReturn(
                new WorkspaceConversationAccessService.ConversationView("42","bounty","task:task",
                        "task",List.of("agent"),1,1));
        when(authority.runtimeAuthority(any(),eq("task"),anyString(),eq("RESULT")))
                .thenAnswer(invocation -> {
                    String run=invocation.getArgument(2);
                    var execution=rows.findByTaskRun("0","client","owner","task",run);
                    if(execution==null)return null;
                    return new ControlledImageFollowupAuthorityService.RuntimeAuthority(
                            execution.getExecutionId(),execution.getPermittedOperation(),
                            execution.getRuntimeInputSnapshotDigest(),
                            new ControlledImageFollowupAuthorityService.ProviderExecution(
                                    "CONTROLLED_IMAGE_HTTP_V1",execution.getControlledConsentId(),
                                    "binding","1","model",16,1,1));
                });
    }

    private PersonalWorkspaceExecutionEntity persist(String executionId,String runId,String conversationId) {
        String authoritySuffix=sha(executionId).substring(0,32);
        var row=new PersonalWorkspaceExecutionEntity().setExecutionId(executionId).setOwnerJiacn("owner")
                .setTaskId("task").setRunId(runId).setExecutionMode("CONVERSATION")
                .setTaskGrantId("grant").setTaskGrantVersion(1L).setAssignmentRevision(1L)
                .setPermittedOperation("GENERATE_IMAGE")
                .setControlledConsentId("consent_"+authoritySuffix)
                .setExecutionProtocolVersion(3)
                .setOperationGrantId("opgrant_"+authoritySuffix)
                .setRuntimeInputSnapshotDigest("7".repeat(64))
                .setConversationLeaseToken("lease-token").setConversationLeaseRuntimeId("runtime")
                .setConversationLeaseVersion(1L).setConversationLeaseExpiresAt(Long.MAX_VALUE)
                .setConversationProviderStartedAt(10L).setConversationProviderLeaseVersion(1L)
                .setConversationId(conversationId).setTargetAgentId("agent").setInstruction("draw")
                .setOutputContentMimeType("image/png").setExecutionState("QUEUED").setGrantRevision(1L)
                .setIdempotencyKey("idem-"+executionId).setRequestHash("8".repeat(64)).setCreatedAt(1L);
        row.setTenantId("0");row.setClientId("client");
        return inTx(() -> { rows.insert(row);return row; });
    }

    private <T> T inTx(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    private static PersonalWorkspaceExecutionDao executionDao(DriverManagerDataSource source) throws Exception {
        MybatisConfiguration configuration=new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PersonalWorkspaceExecutionMapper.class);
        configuration.addMapper(PersonalWorkspaceExecutionInputMapper.class);
        configuration.addMapper(PersonalWorkspaceExecutionOutputMapper.class);
        GlobalConfig global=new GlobalConfig();global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean=new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);bean.setConfiguration(configuration);bean.setGlobalConfig(global);
        SqlSessionFactory factory=Objects.requireNonNull(bean.getObject());
        SqlSessionTemplate session=new SqlSessionTemplate(factory);
        return new PersonalWorkspaceExecutionDaoImpl(session.getMapper(PersonalWorkspaceExecutionMapper.class),
                session.getMapper(PersonalWorkspaceExecutionInputMapper.class),
                session.getMapper(PersonalWorkspaceExecutionOutputMapper.class));
    }

    private static byte[] png() throws Exception {
        var stream=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB),"png",stream);
        return stream.toByteArray();
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static String sha(String value) {
        return sha(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static DriverManagerDataSource ds(String url,String user,String password) {
        DriverManagerDataSource value=new DriverManagerDataSource();
        value.setDriverClassName("com.mysql.cj.jdbc.Driver");value.setUrl(url);
        value.setUsername(user);value.setPassword(password);return value;
    }
    private static String databaseUrl(String base,String database) {
        int query=base.indexOf('?');String suffix=query<0?"":base.substring(query);
        String plain=query<0?base:base.substring(0,query);
        return plain.substring(0,plain.lastIndexOf('/')+1)+database+suffix;
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null||value.isBlank())throw new IllegalStateException(name+" required");
        return value;
    }
}
