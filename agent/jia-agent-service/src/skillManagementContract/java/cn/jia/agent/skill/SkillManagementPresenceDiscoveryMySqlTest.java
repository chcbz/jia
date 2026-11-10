package cn.jia.agent.skill;

import cn.jia.agent.dao.impl.AgentRuntimeDaoImpl;
import cn.jia.agent.mapper.AgentRuntimeMapper;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SkillManagementPresenceDiscoveryMySqlTest extends SkillManagementMySqlFixture {
    @Test void presenceDiscoveryUsesFixedTenantAndOptionalClientWithoutHttpContext() throws Exception {
        String database="cyf_presence_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        try {
            var ds=source(database);
            var jdbc=new JdbcTemplate(ds);
            var declarations=new ArrayList<String>();
            var resources=getClass().getClassLoader().getResources("db/schema.sql");
            while(resources.hasMoreElements()) try(var in=resources.nextElement().openStream()) {
                String ddl=new String(in.readAllBytes(),StandardCharsets.UTF_8);
                int start=ddl.indexOf("CREATE TABLE IF NOT EXISTS agent_runtime (");
                if(start>=0) declarations.add(ddl.substring(start,ddl.indexOf(';',start)+1));
            }
            assertEquals(1,declarations.size());jdbc.execute(declarations.getFirst());
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("db/agent-runtime-session-fence-v1.sql")).execute(ds);
            for (var row : List.of(new String[]{"local","blue","client"},new String[]{"foreign","red","client"},
                    new String[]{"other-client","blue","other"},new String[]{"case-alias","BLUE","client"}))
                jdbc.update("INSERT INTO agent_runtime(agent_id,name,owner_jiacn,status,last_seen_at,tenant_id,client_id) VALUES (?,?,'owner','online',1,?,?)",
                        row[0],row[0],row[1],row[2]);
            var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(AgentRuntimeMapper.class);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(configuration);
            var sql=new SqlSessionTemplate(java.util.Objects.requireNonNull(factory.getObject()));
            var dao=new AgentRuntimeDaoImpl();
            ReflectionTestUtils.setField(dao,"baseMapper",sql.getMapper(AgentRuntimeMapper.class));
            ReflectionTestUtils.setField(dao,"partition",new FixedTenantAdmissionPolicy("blue","client"));
            assertEquals(List.of("local"),dao.findHeartbeatTimedOut(100).stream().map(r->r.getAgentId()).toList());
            assertEquals(List.of("local"),dao.findByAgentIds(Set.of("local","foreign","other-client","case-alias"))
                    .stream().map(r->r.getAgentId()).toList());
            assertTrue(dao.findByAgentIds(Set.of()).isEmpty());
            ReflectionTestUtils.setField(dao,"partition",new FixedTenantAdmissionPolicy("blue",null));
            assertEquals(Set.of("local","other-client"),new java.util.HashSet<>(dao.findHeartbeatTimedOut(100)
                    .stream().map(r->r.getAgentId()).toList()));
        } finally { admin.execute("DROP DATABASE `"+database+"`"); }
    }
}
