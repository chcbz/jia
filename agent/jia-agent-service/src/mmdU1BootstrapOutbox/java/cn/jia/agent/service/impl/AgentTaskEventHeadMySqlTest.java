package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskEventDao.ReplayScope;
import cn.jia.agent.entity.AgentTaskEventHead;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.agent.skill.SkillManagementMySqlFixture;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real batched metadata SQL on an owned MySQL fixture, never a production database. */
class AgentTaskEventHeadMySqlTest extends SkillManagementMySqlFixture {
    private String database;
    private JdbcTemplate jdbc;
    private AgentTaskEventMapper mapper;
    private org.apache.ibatis.session.SqlSessionFactory sqlFactory;
    @BeforeEach void setup() throws Exception {
        database="cyf_sse_head_"+UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        var source=source(database); jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE agent_task_meta (tenant_id VARCHAR(50), client_id VARCHAR(50), owner_jiacn VARCHAR(50), task_id VARCHAR(100), current_event_version BIGINT, UNIQUE KEY uk_agent_task_meta_scope (tenant_id,client_id,owner_jiacn,task_id))");
        var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); config.addMapper(AgentTaskEventMapper.class);
        var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        sqlFactory=Objects.requireNonNull(factory.getObject());
        mapper=new SqlSessionTemplate(sqlFactory).getMapper(AgentTaskEventMapper.class);
    }
    @AfterEach void cleanup() {
        if(database!=null && database.matches("cyf_sse_head_[0-9a-f]{32}")) admin.execute("DROP DATABASE `"+database+"`");
    }
    @Test void batchedHeadsAreExactForTenantClientOwnerTaskAndUseExistingScopeIndex() {
        jdbc.update("INSERT INTO agent_task_meta VALUES ('0','client','Owner','café',3),('0','other','Owner','task',8),('1','client','Owner','café',9)");
        var scopes=List.of(new ReplayScope("client","Owner","café"),new ReplayScope("other","Owner","task"));
        assertEquals(Set.of(new AgentTaskEventHead("0","client","Owner","café",3L),
                new AgentTaskEventHead("0","other","Owner","task",8L)),new HashSet<>(mapper.findExactCurrentEventVersions("0",scopes)));
        assertEquals(List.of(new AgentTaskEventHead("1","client","Owner","café",9L)),mapper.findExactCurrentEventVersions("1",scopes));
        for(var wrong:List.of(new ReplayScope("Client","Owner","café"),new ReplayScope("client","owner","café"),
                new ReplayScope("client","Owner","cafe"),new ReplayScope("client","Owner","café "),
                new ReplayScope("client ","Owner","café"))) {
            assertEquals(List.of(),mapper.findExactCurrentEventVersions("0",List.of(wrong)));
        }
        assertEquals(List.of(),mapper.findExactCurrentEventVersions("0 ",scopes));
        String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        jdbc.execute("INSERT INTO agent_task_meta SELECT '0','client','Owner',CONCAT('history-',a.n,b.n,c.n),7 FROM "+digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c");
        jdbc.execute("ANALYZE TABLE agent_task_meta");
        var params=Map.of("tenantId","0","scopes",scopes);
        var bound=sqlFactory.getConfiguration().getMappedStatement(AgentTaskEventMapper.class.getName()+".findExactCurrentEventVersions").getBoundSql(params);
        Object[] values=bound.getParameterMappings().stream().map(p->bound.hasAdditionalParameter(p.getProperty())
                ? bound.getAdditionalParameter(p.getProperty()) : sqlFactory.getConfiguration().newMetaObject(params).getValue(p.getProperty())).toArray();
        var plan=jdbc.queryForList("EXPLAIN "+bound.getSql(),values);
        assertTrue(plan.stream().anyMatch(r->"uk_agent_task_meta_scope".equals(r.get("key"))));
        assertTrue(plan.stream().noneMatch(r->"ALL".equals(r.get("type"))));
    }
}
