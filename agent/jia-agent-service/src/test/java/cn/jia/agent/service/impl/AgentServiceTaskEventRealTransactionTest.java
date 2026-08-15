package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.config.AgentSceneFeatureFlags;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskNoteDao;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskNoteDaoImpl;
import cn.jia.agent.entity.AgentTaskCreateDTO;
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
                provider(), provider(), provider(), provider(),
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

        reset(eventWriter);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            service.createTask(createRequest());
            status.setRollbackOnly();
        });
        assertEquals(0, count("agent_task_meta"));
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

        reset(eventWriter);
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
        assertEquals(AgentConstants.TASK_STATUS_ARCHIVED,
                jdbc.queryForObject("SELECT reward_status FROM agent_task_meta", String.class));
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
        jdbc.update("""
                INSERT INTO agent_task_meta
                (task_id,reward_status,collaboration_mode,risk_level,max_agents,
                 review_required,task_version,current_event_version,
                 tenant_id,client_id,create_time,update_time)
                VALUES (?,?,'single','low',1,0,0,0,'juyiting','jia_client',1,1)
                """, taskId, status);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
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
