package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskWorkspaceRows.ArtifactRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.EventRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.MemberRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.TaskRow;
import cn.jia.agent.dao.impl.AgentTaskWorkspaceDaoImpl;
import cn.jia.agent.mapper.AgentTaskWorkspaceMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real H2/MyBatis evidence for C04 exact predicates, ordering and fixed sentinels. */
class AgentTaskWorkspaceMapperRealDatabaseTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private JdbcTemplate jdbc;
    private AgentTaskWorkspaceMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:c04_workspace;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        source.setUsername("sa");
        source.setPassword("");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        createTables();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskWorkspaceMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = bean.getObject();
        mapper = new SqlSessionTemplate(factory).getMapper(AgentTaskWorkspaceMapper.class);
    }

    @Test
    void daoRejectsUnicodeSpacePaddingForEveryScopeDimensionBeforeIssuingSql() {
        AgentTaskWorkspaceDao dao = new AgentTaskWorkspaceDaoImpl(mapper);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> dao.findTask("\u00a0" + TENANT, CLIENT, TASK));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> dao.findTask(TENANT, CLIENT + "\u2007", TASK));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> dao.findTask(TENANT, CLIENT, "\u202f" + TASK));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> dao.findActorMember(TENANT, CLIENT, TASK, ACTOR + "\u2007"));
    }

    @Test
    void daoUsesCodePointLimitsAndRejectsUnpairedSurrogates() {
        String supplementary = new String(Character.toChars(0x1f642));
        String boundaryTask = "t" + supplementary.repeat(99);
        AgentTaskWorkspaceMapper acceptingMapper = mock(AgentTaskWorkspaceMapper.class);
        TaskRow row = new TaskRow();
        row.setTaskId(boundaryTask);
        when(acceptingMapper.findTask(TENANT, CLIENT, boundaryTask)).thenReturn(row);
        AgentTaskWorkspaceDao dao = new AgentTaskWorkspaceDaoImpl(acceptingMapper);
        assertEquals(boundaryTask, dao.findTask(TENANT, CLIENT, boundaryTask).getTaskId());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> dao.findTask(TENANT, CLIENT, "t" + supplementary.repeat(100)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> dao.findTask(TENANT, CLIENT, "task-\udc00"));
    }

    @Test
    void byteExactPredicatesRejectCaseInsensitiveOwnerClientTaskAndActorMatches() {
        insertTask("Tenant-A", "Client-A", "Task-1", 0L);
        jdbc.update("INSERT INTO agent_task_member "
                        + "(task_id,agent_id,member_role,member_status,assignment_source,version,"
                        + "tenant_id,client_id) VALUES (?,?,?,?,?,?,?,?)",
                "Task-1", "Agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "worker", "accepted",
                "manual", 0L, "Tenant-A", "Client-A");

        assertNull(mapper.findTask(TENANT, "Client-A", "Task-1"));
        assertNull(mapper.findTask("Tenant-A", CLIENT, "Task-1"));
        assertNull(mapper.findTask("Tenant-A", "Client-A", TASK));
        assertEquals("Task-1", mapper.findTask(
                "Tenant-A", "Client-A", "Task-1").getTaskId());
        assertNull(mapper.findActorMember("Tenant-A", "Client-A", "Task-1", ACTOR));
        assertEquals("Agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                mapper.findActorMember("Tenant-A", "Client-A", "Task-1",
                        "Agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").getAgentId());
    }

    @Test
    void memberQueryUses500SentinelAndDeterministicOrder() {
        insertTask(TENANT, CLIENT, TASK, 0L);
        List<Object[]> batch = new ArrayList<>();
        batch.add(memberArgs(ACTOR, "worker", 0));
        for (int i = 0; i < 505; i++) {
            batch.add(memberArgs(String.format("agent-%03d", i),
                    i % 2 == 0 ? "reviewer" : "observer", i + 1));
        }
        jdbc.batchUpdate("INSERT INTO agent_task_member "
                + "(task_id,agent_id,member_role,member_status,assignment_source,version,"
                + "tenant_id,client_id) VALUES (?,?,?,?,?,?,?,?)", batch);

        List<MemberRow> rows = mapper.findMembers(TENANT, CLIENT, TASK);
        assertEquals(500, rows.size());
        assertEquals("observer", rows.get(0).getMemberRole());
        assertTrue(rows.get(0).getAgentId().compareTo(rows.get(1).getAgentId()) < 0);
        assertEquals(ACTOR, mapper.findActorMember(TENANT, CLIENT, TASK, ACTOR).getAgentId());
        assertNull(mapper.findActorMember(TENANT, CLIENT, TASK, ACTOR.toUpperCase()));
        assertNull(mapper.findActorMember("Tenant-A", CLIENT, TASK, ACTOR));
    }

    @Test
    void artifactOrderingUsesCanonicalBytesWhenCollationTreatsIdsAsEqual() {
        insertTask(TENANT, CLIENT, TASK, 0L);
        insertArtifact("artifact-a", 1, "other", "task_members", 1000L);
        insertArtifact("Artifact-a", 1, "other", "task_members", 1000L);
        List<ArtifactRow> rows = mapper.findVisibleArtifacts(
                TENANT, CLIENT, TASK, ACTOR, false, false);
        assertEquals(List.of("Artifact-a", "artifact-a"),
                rows.stream().map(ArtifactRow::getArtifactId).toList());
        // H2 VARCHAR_IGNORECASE cannot faithfully model a third all-uppercase lookup
        // when both case-colliding rows exist. Exact original spellings remain deterministic;
        // the service independently cross-validates the returned row identity fail-closed.
        assertEquals("Artifact-a", mapper.findArtifactVersion(
                TENANT, CLIENT, TASK, "Artifact-a", 1).getArtifactId());
        assertEquals("artifact-a", mapper.findArtifactVersion(
                TENANT, CLIENT, TASK, "artifact-a", 1).getArtifactId());
    }

    @Test
    void artifactAclIsAppliedBefore101LimitAndEventSuffixIsDescending101() {
        insertTask(TENANT, CLIENT, TASK, 105L);
        insertArtifact("case-collision", 1, ACTOR.toUpperCase(), "private", 20_000L);
        for (int i = 0; i < 105; i++) {
            insertArtifact("hidden-" + i, 1, "other", "private", 10_000L + i);
        }
        for (int i = 0; i < 101; i++) {
            insertArtifact("visible-" + i, 1, "other", "task_members", 1_000L + i);
        }
        List<ArtifactRow> visible = mapper.findVisibleArtifacts(
                TENANT, CLIENT, TASK, ACTOR, false, false);
        assertEquals(101, visible.size());
        assertTrue(visible.stream().allMatch(row -> row.getArtifactId().startsWith("visible-")));
        assertTrue(visible.stream().noneMatch(row -> "case-collision".equals(row.getArtifactId())));
        assertEquals("visible-100", visible.get(0).getArtifactId());

        for (long version = 1; version <= 105; version++) {
            jdbc.update("INSERT INTO agent_task_event "
                            + "(task_id,event_version,event_type,actor_type,actor_id,aggregate_type,"
                            + "aggregate_id,event_json,occurred_at,tenant_id,client_id) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    TASK, version, "TASK_STARTED", "system", null, "task", TASK,
                    "{\"taskId\":\"task-1\"}", 1000L + version, TENANT, CLIENT);
        }
        List<EventRow> events = mapper.findLatestEvents(TENANT, CLIENT, TASK);
        assertEquals(101, events.size());
        assertEquals(105L, events.get(0).getEventVersion());
        assertEquals(5L, events.get(100).getEventVersion());
    }

    private Object[] memberArgs(String agentId, String role, long version) {
        return new Object[] {TASK, agentId, role, "accepted", "manual", version, TENANT, CLIENT};
    }

    private void insertTask(String tenant, String client, String taskId, long currentVersion) {
        jdbc.update("INSERT INTO agent_task_meta "
                        + "(task_id,reward_status,collaboration_mode,risk_level,max_agents,"
                        + "review_required,task_version,current_event_version,tenant_id,client_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                taskId, "running", "team", "low", 5, false, 0L, currentVersion,
                tenant, client);
    }

    private void insertArtifact(String id, int version, String producer,
            String visibility, long createdAt) {
        jdbc.update("INSERT INTO agent_task_artifact "
                        + "(artifact_id,task_id,producer_agent_id,artifact_type,title,content_hash,"
                        + "artifact_version,visibility,created_at,tenant_id,client_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, TASK, producer, "document", "title", "a".repeat(64),
                version, visibility, createdAt, TENANT, CLIENT);
    }

    private void createTables() {
        jdbc.execute("CREATE TABLE agent_task_meta (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "task_id VARCHAR_IGNORECASE(100),reward_status VARCHAR(20),"
                + "assigned_agent_id VARCHAR(100),required_abilities CLOB,reward INT,"
                + "assigned_at BIGINT,started_at BIGINT,completed_at BIGINT,"
                + "collaboration_mode VARCHAR(20),risk_level VARCHAR(20),max_agents INT,"
                + "coordinator_agent_id VARCHAR(100),review_required BOOLEAN,"
                + "task_version BIGINT,current_event_version BIGINT,"
                + "tenant_id VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50))");
        jdbc.execute("CREATE TABLE agent_task_member (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "task_id VARCHAR_IGNORECASE(100),agent_id VARCHAR_IGNORECASE(100),"
                + "member_role VARCHAR(20),member_status VARCHAR(20),assignment_source VARCHAR(20),"
                + "joined_at BIGINT,accepted_at BIGINT,started_at BIGINT,completed_at BIGINT,"
                + "last_heartbeat_at BIGINT,version BIGINT,tenant_id VARCHAR_IGNORECASE(50),"
                + "client_id VARCHAR_IGNORECASE(50))");
        jdbc.execute("CREATE TABLE agent_task_work_item (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "work_item_id VARCHAR(100),task_id VARCHAR_IGNORECASE(100),title VARCHAR(255),"
                + "description CLOB,work_type VARCHAR(30),required_abilities CLOB,"
                + "assignee_agent_id VARCHAR(100),status VARCHAR(20),priority INT,"
                + "required_item BOOLEAN,dependency_json CLOB,lease_until BIGINT,attempt_count INT,"
                + "max_attempts INT,result_artifact_id VARCHAR(100),submitted_at BIGINT,"
                + "completed_at BIGINT,version BIGINT,create_time BIGINT,"
                + "tenant_id VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50))");
        jdbc.execute("CREATE TABLE agent_task_request (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "request_id VARCHAR(100),task_id VARCHAR_IGNORECASE(100),work_item_id VARCHAR(100),"
                + "requester_agent_id VARCHAR(100),target_type VARCHAR(20),target_id VARCHAR(100),"
                + "request_type VARCHAR(30),status VARCHAR(20),priority INT,title VARCHAR(255),"
                + "description CLOB,due_at BIGINT,acknowledged_at BIGINT,version BIGINT,"
                + "create_time BIGINT,tenant_id VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50))");
        jdbc.execute("CREATE TABLE agent_task_artifact (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "artifact_id VARCHAR_IGNORECASE(100),task_id VARCHAR_IGNORECASE(100),"
                + "work_item_id VARCHAR(100),producer_agent_id VARCHAR_IGNORECASE(100),artifact_type VARCHAR(30),"
                + "title VARCHAR(255),content_hash VARCHAR(128),artifact_version INT,"
                + "visibility VARCHAR(20),created_at BIGINT,tenant_id VARCHAR_IGNORECASE(50),"
                + "client_id VARCHAR_IGNORECASE(50))");
        jdbc.execute("CREATE TABLE agent_task_event (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "task_id VARCHAR_IGNORECASE(100),event_version BIGINT,event_type VARCHAR(64),"
                + "actor_type VARCHAR(20),actor_id VARCHAR(100),aggregate_type VARCHAR(30),"
                + "aggregate_id VARCHAR(100),event_json CLOB,occurred_at BIGINT,"
                + "tenant_id VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50))");
    }
}
