package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskArtifactDao;
import cn.jia.agent.dao.AgentTaskArtifactOutcomeDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.impl.AgentTaskArtifactDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskArtifactOutcomeDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskEventDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMemberDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.entity.AgentTaskArtifactAcceptDTO;
import cn.jia.agent.entity.AgentTaskArtifactRefDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.mapper.AgentTaskArtifactMapper;
import cn.jia.agent.mapper.AgentTaskArtifactOutcomeMapper;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.agent.mapper.AgentTaskMemberMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.service.AgentTaskArtifactOutcomeService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskArtifactOutcomeRealTransactionTest {
    private static final String JDBC_URL =
            "jdbc:h2:mem:cyf_f06_outcome;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                    + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000";
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final long NOW = 1_726_000_000_000L;

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private AgentTaskArtifactDao artifactDao;
    private AgentTaskArtifactOutcomeDao outcomeDao;
    private AgentTaskMemberDao memberDao;
    private AgentTaskMetaDao taskDao;
    private AgentTaskMutationTransaction mutationTransaction;
    private AgentTaskEventWriter eventWriter;
    private AgentTaskArtifactOutcomeService service;
    private AtomicLong clock;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(JDBC_URL);
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        createTables();

        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory(dataSource));
        taskDao = new AgentTaskMetaDaoImpl();
        setField(taskDao, "baseMapper", template.getMapper(AgentTaskMetaMapper.class));
        memberDao = new AgentTaskMemberDaoImpl(template.getMapper(AgentTaskMemberMapper.class));
        artifactDao = new AgentTaskArtifactDaoImpl(template.getMapper(AgentTaskArtifactMapper.class));
        outcomeDao = new AgentTaskArtifactOutcomeDaoImpl(
                template.getMapper(AgentTaskArtifactOutcomeMapper.class));
        AgentTaskEventDaoImpl eventDao = new AgentTaskEventDaoImpl();
        setField(eventDao, "baseMapper", template.getMapper(AgentTaskEventMapper.class));
        eventWriter = new AgentTaskEventWriterImpl(eventDao, transactionManager,
                new cn.jia.agent.service.AgentTaskEventAfterCommitPublisher(
                        new cn.jia.agent.service.AgentTaskEventBroker(), transactionManager));
        mutationTransaction = new AgentTaskMutationTransactionImpl(taskDao, transactionManager);
        clock = new AtomicLong(NOW);
        service = new AgentTaskArtifactOutcomeServiceImpl(
                artifactDao, outcomeDao, memberDao, taskDao,
                mutationTransaction, eventWriter, clock::incrementAndGet);
        insertTask();
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void acceptedReplacementIsPersistedAndAuthoritativeReadExcludesSuperseded() {
        insertArtifact("artifact-old", 1, "work-1");
        insertArtifact("artifact-new", 1, "work-1");
        service.accept(TENANT, CLIENT, TASK, ACTOR,
                command("decision-old", ref("artifact-old", 1, 0)));

        var accepted = service.accept(TENANT, CLIENT, TASK, ACTOR,
                command("decision-new", ref("artifact-new", 1, 0),
                        ref("artifact-old", 1, 1)));
        var authoritative = service.listAuthoritativeAccepted(
                TENANT, CLIENT, TASK, ACTOR, "work-1", 100);

        assertEquals("artifact-new", accepted.getArtifactId());
        assertEquals(List.of("artifact-new"),
                authoritative.stream().map(value -> value.getArtifactId()).toList());
        assertEquals(List.of(
                        Map.of("ARTIFACT_ID", "artifact-new", "OUTCOME_STATE", "accepted"),
                        Map.of("ARTIFACT_ID", "artifact-old", "OUTCOME_STATE", "superseded")),
                jdbc.queryForList("SELECT artifact_id, outcome_state "
                        + "FROM agent_task_artifact_outcome ORDER BY artifact_id"));
        assertEquals(List.of("ARTIFACT_ACCEPTED", "ARTIFACT_ACCEPTED", "ARTIFACT_SUPERSEDED"),
                jdbc.queryForList("SELECT event_type FROM agent_task_event ORDER BY event_version",
                        String.class));

        long events = count("SELECT COUNT(*) FROM agent_task_event");
        long outcomes = count("SELECT COUNT(*) FROM agent_task_artifact_outcome");
        long decisions = count("SELECT COUNT(*) FROM agent_task_artifact_outcome_decision");
        var replay = service.accept(TENANT, CLIENT, TASK, ACTOR,
                command("decision-new", ref("artifact-new", 1, 0),
                        ref("artifact-old", 1, 1)));
        var supersededDecisionReplay = service.accept(TENANT, CLIENT, TASK, ACTOR,
                command("decision-old", ref("artifact-old", 1, 0)));
        assertEquals(accepted.getDecisionId(), replay.getDecisionId());
        assertEquals("artifact-old", supersededDecisionReplay.getArtifactId());
        assertEquals("accepted", supersededDecisionReplay.getOutcomeState());
        assertEquals(events, count("SELECT COUNT(*) FROM agent_task_event"));
        assertEquals(outcomes, count("SELECT COUNT(*) FROM agent_task_artifact_outcome"));
        assertEquals(decisions,
                count("SELECT COUNT(*) FROM agent_task_artifact_outcome_decision"));
    }

    @Test
    void authoritativeFilterRunsBeforeLimitSoNewerSupersededRowsCannotHideAccepted() {
        insertArtifact("artifact-authoritative", 1, "work-1");
        jdbc.update("""
                INSERT INTO agent_task_artifact_outcome
                (task_id, artifact_id, artifact_version, outcome_state,
                 decision_id, decision_digest, decided_by_agent_id, decided_at, version,
                 tenant_id, client_id, create_time, update_time)
                VALUES (?, 'artifact-authoritative', 1, 'accepted',
                        'decision-authoritative', ?, ?, 1, 1, ?, ?, 1, 1)
                """, TASK, "a".repeat(64), ACTOR, TENANT, CLIENT);
        for (int index = 0; index < 101; index++) {
            String artifactId = "artifact-superseded-" + String.format("%03d", index);
            insertArtifact(artifactId, 1, "work-1");
            jdbc.update("""
                    INSERT INTO agent_task_artifact_outcome
                    (task_id, artifact_id, artifact_version, outcome_state,
                     superseded_by_artifact_id, superseded_by_artifact_version,
                     decision_id, decision_digest, decided_by_agent_id, decided_at, version,
                     tenant_id, client_id, create_time, update_time)
                    VALUES (?, ?, 1, 'superseded', 'artifact-authoritative', 1,
                            ?, ?, ?, ?, 1, ?, ?, 1, 1)
                    """, TASK, artifactId, "decision-superseded-" + index,
                    "b".repeat(64), ACTOR, 100L + index, TENANT, CLIENT);
        }

        var rows = service.listAuthoritativeAccepted(
                TENANT, CLIENT, TASK, ACTOR, "work-1", 1);

        assertEquals(List.of("artifact-authoritative"),
                rows.stream().map(value -> value.getArtifactId()).toList());
    }

    @Test
    void eventFailureRollsBackAcceptedInsertSupersessionUpdateAndEventVersion() {
        insertArtifact("artifact-old", 1, "work-1");
        insertArtifact("artifact-new", 1, "work-1");
        service.accept(TENANT, CLIENT, TASK, ACTOR,
                command("decision-old", ref("artifact-old", 1, 0)));
        long initialEventVersion = jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta WHERE task_id=?",
                Long.class, TASK);

        AtomicInteger calls = new AtomicInteger();
        AgentTaskEventWriter failOnSecondEvent = command -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("synthetic second event failure");
            }
            return eventWriter.append(command);
        };
        AgentTaskArtifactOutcomeService failing = new AgentTaskArtifactOutcomeServiceImpl(
                artifactDao, outcomeDao, memberDao, taskDao,
                mutationTransaction, failOnSecondEvent, clock::incrementAndGet);

        assertThrows(IllegalStateException.class, () -> failing.accept(
                TENANT, CLIENT, TASK, ACTOR,
                command("decision-rollback", ref("artifact-new", 1, 0),
                        ref("artifact-old", 1, 1))));

        assertEquals(0, count("SELECT COUNT(*) FROM agent_task_artifact_outcome "
                + "WHERE artifact_id='artifact-new'"));
        assertEquals(0, count("SELECT COUNT(*) FROM agent_task_artifact_outcome_decision "
                + "WHERE decision_id='decision-rollback'"));
        assertEquals("accepted", jdbc.queryForObject(
                "SELECT outcome_state FROM agent_task_artifact_outcome "
                        + "WHERE artifact_id='artifact-old'", String.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT version FROM agent_task_artifact_outcome WHERE artifact_id='artifact-old'",
                Long.class));
        assertEquals(initialEventVersion, jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta WHERE task_id=?",
                Long.class, TASK));
        assertEquals(initialEventVersion, count("SELECT COUNT(*) FROM agent_task_event"));
    }

    @Test
    void concurrentConflictingDecisionsProduceExactlyOneReplacement() throws Exception {
        insertArtifact("artifact-old", 1, "work-1");
        insertArtifact("artifact-a", 1, "work-1");
        insertArtifact("artifact-b", 1, "work-1");
        service.accept(TENANT, CLIENT, TASK, ACTOR,
                command("decision-old", ref("artifact-old", 1, 0)));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> first = executor.submit(race(
                    ready, start, "decision-a", "artifact-a"));
            Future<Outcome> second = executor.submit(race(
                    ready, start, "decision-b", "artifact-b"));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Outcome> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertEquals(1, outcomes.stream().filter(Outcome::success).count());
            assertEquals(1, outcomes.stream()
                    .filter(value -> value.reason() == Reason.VERSION_CONFLICT).count());
            assertEquals(1, count("SELECT COUNT(*) FROM agent_task_artifact_outcome "
                    + "WHERE outcome_state='accepted'"));
            assertEquals(1, count("SELECT COUNT(*) FROM agent_task_artifact_outcome "
                    + "WHERE artifact_id IN ('artifact-a','artifact-b')"));
            assertEquals(2, count("SELECT COUNT(*) FROM agent_task_artifact_outcome_decision"));
            assertEquals("superseded", jdbc.queryForObject(
                    "SELECT outcome_state FROM agent_task_artifact_outcome "
                            + "WHERE artifact_id='artifact-old'", String.class));
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<Outcome> race(
            CountDownLatch ready, CountDownLatch start, String decisionId, String artifactId) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("race start timeout");
            }
            try {
                service.accept(TENANT, CLIENT, TASK, ACTOR,
                        command(decisionId, ref(artifactId, 1, 0),
                                ref("artifact-old", 1, 1)));
                return new Outcome(true, null);
            } catch (AgentTaskCollaborationException exception) {
                return new Outcome(false, exception.getReason());
            }
        };
    }

    private SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMetaMapper.class);
        configuration.addMapper(AgentTaskMemberMapper.class);
        configuration.addMapper(AgentTaskArtifactMapper.class);
        configuration.addMapper(AgentTaskArtifactOutcomeMapper.class);
        configuration.addMapper(AgentTaskEventMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        return bean.getObject();
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(100) NOT NULL,
                    reward_status VARCHAR(20) NOT NULL DEFAULT 'running',
                    coordinator_agent_id VARCHAR(100), task_version BIGINT NOT NULL DEFAULT 0,
                    current_event_version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, task_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_member (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(100) NOT NULL,
                    agent_id VARCHAR(100) NOT NULL, member_role VARCHAR(20) NOT NULL,
                    member_status VARCHAR(20) NOT NULL, assignment_source VARCHAR(20),
                    version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_artifact (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, artifact_id VARCHAR(100) NOT NULL,
                    task_id VARCHAR(100) NOT NULL, work_item_id VARCHAR(100),
                    producer_agent_id VARCHAR(100) NOT NULL, artifact_type VARCHAR(30) NOT NULL,
                    title VARCHAR(255) NOT NULL, content CLOB, storage_uri VARCHAR(1000),
                    content_hash VARCHAR(128), artifact_version INT NOT NULL,
                    visibility VARCHAR(20) NOT NULL, metadata_json CLOB, created_at BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, artifact_id, artifact_version)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_artifact_outcome (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(100) NOT NULL,
                    artifact_id VARCHAR(100) NOT NULL, artifact_version INT NOT NULL,
                    outcome_state VARCHAR(20) NOT NULL,
                    superseded_by_artifact_id VARCHAR(100),
                    superseded_by_artifact_version INT, decision_id VARCHAR(100) NOT NULL,
                    decision_digest CHAR(64) NOT NULL, decided_by_agent_id VARCHAR(100) NOT NULL,
                    decided_at BIGINT NOT NULL, version BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, artifact_id, artifact_version)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_artifact_outcome_decision (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(100) NOT NULL,
                    decision_id VARCHAR(100) NOT NULL, decision_digest CHAR(64) NOT NULL,
                    accepted_artifact_id VARCHAR(100) NOT NULL,
                    accepted_artifact_version INT NOT NULL,
                    accepted_outcome_version BIGINT NOT NULL,
                    decided_by_agent_id VARCHAR(100) NOT NULL, decided_at BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, task_id, decision_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(100) NOT NULL,
                    event_version BIGINT NOT NULL, event_id VARCHAR(100) NOT NULL,
                    event_type VARCHAR(64) NOT NULL, actor_type VARCHAR(20) NOT NULL,
                    actor_id VARCHAR(100), aggregate_type VARCHAR(30) NOT NULL,
                    aggregate_id VARCHAR(100) NOT NULL, event_json CLOB NOT NULL,
                    occurred_at BIGINT NOT NULL, tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL, create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, task_id, event_version),
                    UNIQUE (tenant_id, client_id, event_id)
                )""");
    }

    private void insertTask() {
        jdbc.update("INSERT INTO agent_task_meta "
                        + "(task_id, coordinator_agent_id, task_version, current_event_version, "
                        + "tenant_id, client_id, create_time, update_time) "
                        + "VALUES (?, ?, 0, 0, ?, ?, 1, 1)",
                TASK, ACTOR, TENANT, CLIENT);
    }

    private void insertArtifact(String artifactId, int version, String workItemId) {
        jdbc.update("""
                INSERT INTO agent_task_artifact
                (artifact_id, task_id, work_item_id, producer_agent_id, artifact_type, title,
                 content_hash, artifact_version, visibility, created_at,
                 tenant_id, client_id, create_time, update_time)
                VALUES (?, ?, ?, ?, 'analysis', ?, ?, ?, 'task_members', ?, ?, ?, 1, 1)
                """, artifactId, TASK, workItemId, ACTOR, artifactId,
                "a".repeat(64), version, NOW - 100, TENANT, CLIENT);
    }

    private AgentTaskArtifactAcceptDTO command(
            String decisionId, AgentTaskArtifactRefDTO accepted,
            AgentTaskArtifactRefDTO... superseded) {
        AgentTaskArtifactAcceptDTO command = new AgentTaskArtifactAcceptDTO();
        command.setDecisionId(decisionId);
        command.setAcceptedArtifact(accepted);
        command.setSupersededArtifacts(List.of(superseded));
        return command;
    }

    private AgentTaskArtifactRefDTO ref(String artifactId, int version, long outcomeVersion) {
        AgentTaskArtifactRefDTO ref = new AgentTaskArtifactRefDTO();
        ref.setArtifactId(artifactId);
        ref.setArtifactVersion(version);
        ref.setExpectedOutcomeVersion(outcomeVersion);
        return ref;
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getSuperclass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Outcome(boolean success, Reason reason) {
    }
}
