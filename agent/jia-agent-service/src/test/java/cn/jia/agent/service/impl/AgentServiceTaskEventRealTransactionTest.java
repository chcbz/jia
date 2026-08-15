package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.config.AgentSceneFeatureFlags;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskNoteDao;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskNoteDaoImpl;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskNoteDTO;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskNoteMapper;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.task.service.TaskService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/** Real H2/MyBatis/Spring rollback coverage for C01B-4 AgentService task writes. */
class AgentServiceTaskEventRealTransactionTest {
    private static final String JDBC_URL =
            "jdbc:h2:mem:cyf_c01b4_agent_service;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                    + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000";

    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private AgentTaskEventWriter eventWriter;
    private AgentEventPublisher eventPublisher;
    private TaskService taskService;
    private AgentService service;

    @BeforeEach
    void setUp() throws Exception {
        EsContextHolder.setContext(new EsContext());
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl(JDBC_URL);
        source.setUsername("sa");
        source.setPassword("");
        DataSource dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        createTables();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMetaMapper.class);
        configuration.addMapper(AgentTaskNoteMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = bean.getObject();
        SqlSessionTemplate template = new SqlSessionTemplate(factory);

        AgentTaskMetaDaoImpl taskMetaDaoImpl = new AgentTaskMetaDaoImpl();
        setBaseMapper(taskMetaDaoImpl, template.getMapper(AgentTaskMetaMapper.class));
        AgentTaskMetaDao taskMetaDao = taskMetaDaoImpl;
        AgentTaskNoteDaoImpl taskNoteDaoImpl = new AgentTaskNoteDaoImpl();
        setBaseMapper(taskNoteDaoImpl, template.getMapper(AgentTaskNoteMapper.class));
        AgentTaskNoteDao taskNoteDao = taskNoteDaoImpl;

        transactionManager = new DataSourceTransactionManager(dataSource);
        AgentTaskMutationTransaction mutationTransaction =
                new AgentTaskMutationTransactionImpl(taskMetaDao, transactionManager);
        eventWriter = mock(AgentTaskEventWriter.class);
        eventPublisher = mock(AgentEventPublisher.class);
        taskService = mock(TaskService.class);
        doAnswer(invocation -> {
            assertEquals(1, jdbc.queryForObject(
                            "SELECT COUNT(*) FROM agent_task_meta WHERE task_id <> '42'",
                            Integer.class),
                    "one scoped reserved task root must exist before TaskService.create");
            cn.jia.task.entity.TaskPlanEntity plan = invocation.getArgument(0);
            jdbc.update("INSERT INTO task_plan_fixture(id, name) VALUES (?, ?)",
                    42L, plan.getName());
            jdbc.update("INSERT INTO task_item_fixture(id, plan_id) VALUES (?, ?)",
                    1L, 42L);
            plan.setId(42L);
            return plan;
        }).when(taskService).create(any());
        AgentServiceImpl raw = new AgentServiceImpl(
                mock(cn.jia.agent.dao.AgentRuntimeDao.class),
                mock(cn.jia.agent.service.AgentIdentityService.class),
                mock(cn.jia.agent.dao.AgentPersonaDao.class),
                mock(cn.jia.agent.dao.AgentPersonaBindingDao.class),
                taskMetaDao,
                mock(cn.jia.agent.dao.AgentTaskMemberDao.class),
                mock(AgentLegacyTaskCompatibilityService.class),
                taskNoteDao,
                mock(cn.jia.agent.dao.DialogueTemplateDao.class),
                provider(eventPublisher), provider(taskService), provider(), provider(),
                new AgentSceneFeatureFlags(false, false),
                mutationTransaction, eventWriter);
        service = transactionalProxy(raw);
    }

    @AfterEach
    void tearDown() {
        EsContextHolder.setContext(new EsContext());
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void createEventFailureAndOuterRollbackRemoveTaskRoot() {
        doAnswer(invocation -> {
            AgentTaskEventWriteCommand command = invocation.getArgument(0);
            if (TaskEventType.TASK_CREATED.equals(command.getEventType())) {
                throw new IllegalStateException("forced create event failure");
            }
            return null;
        }).when(eventWriter).append(any());
        assertThrows(IllegalStateException.class, () -> service.createTask(createRequest()));
        assertEquals(0, count("agent_task_meta"));
        assertEquals(0, count("task_plan_fixture"));
        assertEquals(0, count("task_item_fixture"));
        verify(eventPublisher, never()).publishTaskEvent(any(), any());

        reset(eventWriter, eventPublisher);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            service.createTask(createRequest());
            status.setRollbackOnly();
        });
        assertEquals(0, count("agent_task_meta"));
        assertEquals(0, count("task_plan_fixture"));
        assertEquals(0, count("task_item_fixture"));
        verify(eventPublisher, never()).publishTaskEvent(any(), any());
    }

    @Test
    void numericPlanIdCollisionRollsBackReservationPlanItemAndLeavesExistingEventUntouched() {
        insertTask("42", AgentConstants.TASK_STATUS_OPEN, 9L, 7L);
        jdbc.update("""
                INSERT INTO agent_task_event
                (task_id,event_version,event_id,event_type,event_json,tenant_id,client_id)
                VALUES ('42',7,'evt-existing','TASK_CREATED','{}','juyiting','jia_client')
                """);

        assertThrows(IllegalStateException.class, () -> service.createTask(createRequest()));

        assertEquals(1, count("agent_task_meta"));
        assertEquals(9L, jdbc.queryForObject(
                "SELECT task_version FROM agent_task_meta WHERE task_id='42'", Long.class));
        assertEquals(7L, jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta WHERE task_id='42'", Long.class));
        assertEquals(1, count("agent_task_event"));
        assertEquals("evt-existing", jdbc.queryForObject(
                "SELECT event_id FROM agent_task_event", String.class));
        assertEquals(0, count("task_plan_fixture"));
        assertEquals(0, count("task_item_fixture"));
        verify(eventWriter, never()).append(any());
        verify(eventPublisher, never()).publishTaskEvent(any(), any());
    }

    @Test
    void successfulCreateRekeysReservedRootAndPublishesOnlyAfterCommit() {
        AgentTaskDTO created = service.createTask(createRequest());

        assertEquals("42", created.getId());
        assertEquals(List.of("42"), jdbc.queryForList(
                "SELECT task_id FROM agent_task_meta", String.class));
        assertEquals(1, count("task_plan_fixture"));
        assertEquals(1, count("task_item_fixture"));
        verify(eventPublisher).publishTaskEvent("task_created", created);
    }

    @Test
    void archiveAndNoteEventFailuresRollBackBusinessRows() {
        insertTask("task-1", AgentConstants.TASK_STATUS_COMPLETED);
        doAnswer(invocation -> {
            AgentTaskEventWriteCommand command = invocation.getArgument(0);
            if (TaskEventType.TASK_ARCHIVED.equals(command.getEventType())) {
                throw new IllegalStateException("forced archive event failure");
            }
            return null;
        }).when(eventWriter).append(any());
        assertThrows(IllegalStateException.class, () -> service.archiveTask("task-1"));
        assertEquals(AgentConstants.TASK_STATUS_COMPLETED,
                jdbc.queryForObject("SELECT reward_status FROM agent_task_meta", String.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT task_version FROM agent_task_meta", Long.class));
        verify(eventPublisher, never()).publishTaskEvent(any(), any());

        reset(eventWriter, eventPublisher);
        doAnswer(invocation -> {
            AgentTaskEventWriteCommand command = invocation.getArgument(0);
            if (TaskEventType.PROGRESS_REPORTED.equals(command.getEventType())) {
                throw new IllegalStateException("forced note event failure");
            }
            return null;
        }).when(eventWriter).append(any());
        AgentTaskNoteDTO note = new AgentTaskNoteDTO();
        note.setNoteType("summary");
        note.setContent("must not survive append failure");
        assertThrows(IllegalStateException.class, () -> service.addTaskNote("task-1", note));
        assertEquals(0, count("agent_task_note"));
    }

    @Test
    void archivedNoOpAllocatesNoEventAndDoesNotRewriteRoot() {
        insertTask("task-1", AgentConstants.TASK_STATUS_ARCHIVED);
        service.archiveTask("task-1");
        verify(eventWriter, never()).append(any());
        verify(eventPublisher, never()).publishTaskEvent(any(), any());
        assertEquals(AgentConstants.TASK_STATUS_ARCHIVED,
                jdbc.queryForObject("SELECT reward_status FROM agent_task_meta", String.class));
    }

    @Test
    void invalidArchiveStateLeavesVersionAndPublicationsUntouched() {
        insertTask("task-1", AgentConstants.TASK_STATUS_RUNNING);

        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> service.archiveTask("task-1"));

        assertEquals(AgentConstants.TASK_STATUS_RUNNING,
                jdbc.queryForObject("SELECT reward_status FROM agent_task_meta", String.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT task_version FROM agent_task_meta", Long.class));
        verify(eventWriter, never()).append(any());
        verify(eventPublisher, never()).publishTaskEvent(any(), any());
    }

    @Test
    void successfulArchiveUsesCasVersionAndPublishesAfterCommit() {
        insertTask("task-1", AgentConstants.TASK_STATUS_FAILED);

        AgentTaskDTO archived = service.archiveTask("task-1");

        assertEquals(AgentConstants.TASK_STATUS_ARCHIVED, archived.getStatus());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT task_version FROM agent_task_meta", Long.class));
        ArgumentCaptor<AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        String payload = event.getValue().getEventJson();
        org.junit.jupiter.api.Assertions.assertTrue(payload.contains("\"expectedVersion\":0"));
        org.junit.jupiter.api.Assertions.assertTrue(payload.contains("\"resultVersion\":1"));
        verify(eventPublisher).publishTaskEvent("task_archived", archived);
    }

    @Test
    void outerRollbackRemovesArchiveAndNoteBusinessMutations() {
        insertTask("task-1", AgentConstants.TASK_STATUS_COMPLETED);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            service.archiveTask("task-1");
            status.setRollbackOnly();
        });
        assertEquals(AgentConstants.TASK_STATUS_COMPLETED,
                jdbc.queryForObject("SELECT reward_status FROM agent_task_meta", String.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT task_version FROM agent_task_meta", Long.class));
        verify(eventPublisher, never()).publishTaskEvent(any(), any());

        AgentTaskNoteDTO note = new AgentTaskNoteDTO();
        note.setNoteType("summary");
        note.setContent("outer rollback note");
        outer.executeWithoutResult(status -> {
            service.addTaskNote("task-1", note);
            status.setRollbackOnly();
        });
        assertEquals(0, count("agent_task_note"));
    }

    private AgentTaskCreateDTO createRequest() {
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle("C01B-4 transaction test");
        request.setDescription("bounded");
        return request;
    }

    private void insertTask(String taskId, String status) {
        insertTask(taskId, status, 0L, 0L);
    }

    private void insertTask(
            String taskId, String status, long taskVersion, long eventVersion) {
        jdbc.update("""
                INSERT INTO agent_task_meta
                (task_id,reward_status,collaboration_mode,risk_level,max_agents,
                 review_required,task_version,current_event_version,
                 tenant_id,client_id,create_time,update_time)
                VALUES (?,?,'single','low',1,0,?,?,'juyiting','jia_client',1,1)
                """, taskId, status, taskVersion, eventVersion);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private AgentService transactionalProxy(AgentServiceImpl target) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(AgentService.class);
        factory.addAdvice(interceptor);
        return (AgentService) factory.getProxy();
    }

    private void setBaseMapper(Object dao, Object mapper) throws Exception {
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(dao, mapper);
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id VARCHAR(100) NOT NULL,
                    reward_status VARCHAR(20) NOT NULL,
                    assigned_agent_id VARCHAR(100),
                    required_abilities TEXT,
                    reward INT,
                    assigned_at BIGINT,
                    started_at BIGINT,
                    completed_at BIGINT,
                    failure_reason VARCHAR(1000),
                    collaboration_mode VARCHAR(20) NOT NULL,
                    risk_level VARCHAR(20) NOT NULL,
                    max_agents INT NOT NULL,
                    coordinator_agent_id VARCHAR(100),
                    review_required TINYINT NOT NULL,
                    task_version BIGINT NOT NULL,
                    current_event_version BIGINT NOT NULL,
                    create_time BIGINT,
                    update_time BIGINT,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE (tenant_id, client_id, task_id)
                )""");
        jdbc.execute("""
                CREATE TABLE task_plan_fixture (
                    id BIGINT NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    PRIMARY KEY (id)
                )""");
        jdbc.execute("""
                CREATE TABLE task_item_fixture (
                    id BIGINT NOT NULL,
                    plan_id BIGINT NOT NULL,
                    PRIMARY KEY (id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_event (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id VARCHAR(100) NOT NULL,
                    event_version BIGINT NOT NULL,
                    event_id VARCHAR(100) NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    event_json CLOB NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE (tenant_id, client_id, task_id, event_version),
                    UNIQUE (tenant_id, client_id, event_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_note (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id VARCHAR(100) NOT NULL,
                    author_id VARCHAR(100),
                    author_type VARCHAR(20),
                    note_type VARCHAR(20),
                    content TEXT,
                    created_at BIGINT,
                    create_time BIGINT,
                    update_time BIGINT,
                    tenant_id VARCHAR(50),
                    client_id VARCHAR(50),
                    PRIMARY KEY (id)
                )""");
    }
}
