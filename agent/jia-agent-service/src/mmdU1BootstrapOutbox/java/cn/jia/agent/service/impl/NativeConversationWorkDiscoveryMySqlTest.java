package cn.jia.agent.service.impl;

import cn.jia.agent.entity.NativeConversationWorkAvailable;
import cn.jia.agent.mapper.NativeConversationWorkMapper;
import cn.jia.agent.skill.SkillManagementMySqlFixture;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MySQL query/record projection; synthetic queue, no production credentials or task payloads. */
class NativeConversationWorkDiscoveryMySqlTest extends SkillManagementMySqlFixture {
    private String database;
    private JdbcTemplate jdbc;
    private NativeConversationWorkMapper mapper;
    private org.apache.ibatis.session.SqlSessionFactory sqlFactory;
    @BeforeEach void setup() throws Exception {
        database="cyf_native_hint_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        var source=source(database);jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE agent_personal_workspace_execution (tenant_id VARCHAR(50),client_id VARCHAR(50),target_agent_id VARCHAR(100),execution_mode VARCHAR(24),execution_state VARCHAR(24),execution_protocol_version INT,conversation_provider_started_at BIGINT)");
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.addMapper(NativeConversationWorkMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);
        sqlFactory=Objects.requireNonNull(factory.getObject());
        mapper=new SqlSessionTemplate(sqlFactory).getMapper(NativeConversationWorkMapper.class);
    }
    @AfterEach void cleanup(){if(database!=null && database.matches("cyf_native_hint_[0-9a-f]{32}"))admin.execute("DROP DATABASE `"+database+"`");}
    private void row(String tenant,String client,String agent,String state,int protocol,Long started){
        jdbc.update("INSERT INTO agent_personal_workspace_execution VALUES (?,?,?,'CONVERSATION',?,?,?)",tenant,client,agent,state,protocol,started);
    }
    @Test void scansOnlyQueuedV5TargetsWithinExactPartitionAndLocalSubscriptions(){
        row("tenant","client","agent","QUEUED",5,null);row("tenant","client","agent","QUEUED",5,null);
        row("tenant","client","remote-node","QUEUED",5,null);row("Tenant","client","agent","QUEUED",5,null);
        row("tenant","Client","agent","QUEUED",5,null);row("tenant","client","Agent","QUEUED",5,null);
        row("tenant","client","terminal","COMPLETED",5,null);row("tenant","client","legacy","QUEUED",4,null);
        row("tenant","client","started","QUEUED",5,100L);
        var wanted=new NativeConversationWorkAvailable("tenant","client","agent");
        var targets=List.of(wanted,new NativeConversationWorkAvailable("tenant","client","terminal"),
                new NativeConversationWorkAvailable("tenant","client","legacy"),new NativeConversationWorkAvailable("tenant","client","started"));
        assertEquals(List.of(wanted),mapper.pendingFor("tenant",targets));
        assertEquals(List.of(),mapper.pendingFor("empty-tenant",targets));
    }

    @Test void sharedScanSeparatesNegotiatedLanesAndIgnoresOtherModesAndUnsubscribedTargets() {
        row("tenant","client","both","QUEUED",5,null);
        for(String mode:List.of("PRIVATE","TASK","FUTURE_MODE"))
            jdbc.update("INSERT INTO agent_personal_workspace_execution VALUES ('tenant','client',?,?, 'QUEUED',1,NULL)",mode.equals("FUTURE_MODE")?"unsupported":"both",mode);
        jdbc.update("INSERT INTO agent_personal_workspace_execution VALUES ('tenant','client','historical',NULL,'QUEUED',1,NULL)");
        var nativeTarget=new NativeConversationWorkAvailable("tenant","client","both");
        var fileTarget=new NativeConversationWorkAvailable("tenant","client","both","WORKSPACE_FILE");
        var historical=new NativeConversationWorkAvailable("tenant","client","historical","WORKSPACE_FILE");
        var unsupported=new NativeConversationWorkAvailable("tenant","client","unsupported","WORKSPACE_FILE");
        assertEquals(Set.of(nativeTarget,fileTarget,historical),new HashSet<>(mapper.pendingFor("tenant",List.of(nativeTarget,fileTarget,historical,unsupported))));
        assertEquals(List.of(fileTarget),mapper.pendingFor("tenant",List.of(fileTarget)));
        assertEquals(List.of(nativeTarget),mapper.pendingFor("tenant",List.of(nativeTarget)));
    }

    @Test void coveringIndexAvoidsScanningTerminalHistoryForCompensation() throws Exception {
        // Owned synthetic fixture; representative cardinality, not a production performance claim.
        jdbc.execute("INSERT INTO agent_personal_workspace_execution SELECT 'tenant','client',CONCAT('history-',a.n,b.n,c.n,d.n),'CONVERSATION','COMPLETED',5,NULL FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d");
        row("tenant","client","agent","QUEUED",5,null);
        var target=new NativeConversationWorkAvailable("tenant","client","agent");
        var params=Map.of("tenantId","tenant","targets",List.of(target));
        var bound=sqlFactory.getConfiguration().getMappedStatement(NativeConversationWorkMapper.class.getName()+".pendingFor").getBoundSql(params);
        Object[] values=bound.getParameterMappings().stream().map(p->bound.hasAdditionalParameter(p.getProperty())
                ? bound.getAdditionalParameter(p.getProperty()) : sqlFactory.getConfiguration().newMetaObject(params).getValue(p.getProperty())).toArray();
        var before=jdbc.queryForList("EXPLAIN "+bound.getSql(),values);
        assertTrue(before.stream().anyMatch(r->"ALL".equals(r.get("type"))));
        String ddl=new org.springframework.core.io.ClassPathResource("db/agent-personal-workspace-native-work-index-v1.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.execute(ddl);jdbc.execute("ANALYZE TABLE agent_personal_workspace_execution");
        var after=jdbc.queryForList("EXPLAIN "+bound.getSql(),values);
        assertTrue(after.stream().anyMatch(r->"idx_pwex_native_work".equals(r.get("key"))));
        assertTrue(after.stream().noneMatch(r->"ALL".equals(r.get("type"))));
        assertEquals(List.of(target),mapper.pendingFor("tenant",List.of(target)));
        var file=new NativeConversationWorkAvailable("tenant","client","agent","WORKSPACE_FILE");
        jdbc.update("INSERT INTO agent_personal_workspace_execution VALUES ('tenant','client','agent','PRIVATE','QUEUED',1,NULL)");
        var mixedParams=Map.of("tenantId","tenant","targets",List.of(target,file));
        var mixed=sqlFactory.getConfiguration().getMappedStatement(NativeConversationWorkMapper.class.getName()+".pendingFor").getBoundSql(mixedParams);
        Object[] mixedValues=mixed.getParameterMappings().stream().map(p->mixed.hasAdditionalParameter(p.getProperty())
                ? mixed.getAdditionalParameter(p.getProperty()) : sqlFactory.getConfiguration().newMetaObject(mixedParams).getValue(p.getProperty())).toArray();
        var mixedPlan=jdbc.queryForList("EXPLAIN "+mixed.getSql(),mixedValues);
        assertTrue(mixedPlan.stream().anyMatch(r->"idx_pwex_native_work".equals(r.get("key"))));
        assertTrue(mixedPlan.stream().noneMatch(r->"ALL".equals(r.get("type"))));
        assertEquals(Set.of(target,file),new HashSet<>(mapper.pendingFor("tenant",List.of(target,file))));
        System.out.println("NATIVE_WORK_QUERY_PLAN syntheticHistory=10000 before="+before+" after="+after+" mixed="+mixedPlan);
    }
}
