package cn.jia.agent.service.impl;

import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionMapper;
import cn.jia.agent.skill.SkillManagementMySqlFixture;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.annotations.Select;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** Owned MySQL fixture for the real HTTP file-queue mapper. No production data or new DDL. */
class WorkspaceFileQueueMySqlTest extends SkillManagementMySqlFixture {
    private String database;
    private JdbcTemplate jdbc;
    private PersonalWorkspaceExecutionMapper mapper;

    @BeforeEach void setup() throws Exception {
        database="cyf_file_queue_"+UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        var source=source(database);jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE agent_personal_workspace_execution (execution_id VARCHAR(100), tenant_id VARCHAR(50), client_id VARCHAR(50), owner_jiacn VARCHAR(50), target_agent_id VARCHAR(100), execution_mode VARCHAR(24), execution_state VARCHAR(24), created_at BIGINT)");
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PersonalWorkspaceExecutionMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);
        mapper=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(PersonalWorkspaceExecutionMapper.class);
    }
    @AfterEach void cleanup() {
        if(database!=null && database.matches("cyf_file_queue_[0-9a-f]{32}")) admin.execute("DROP DATABASE `"+database+"`");
    }
    private void row(String id,String mode,String state,long created) {
        jdbc.update("INSERT INTO agent_personal_workspace_execution VALUES (?,'0','client','owner','agent',?,?,?)",id,mode,state,created);
    }
    private List<String> ids() {
        return mapper.listQueuedByTarget("0","client","owner","agent",16).stream()
                .map(PersonalWorkspaceExecutionEntity::getExecutionId).toList();
    }

    @Test void conversationHeadCannotStarvePrivateTaskOrHistoricalNullMode() throws Exception {
        for(int i=0;i<16;i++) row("conversation-"+i,"CONVERSATION","QUEUED",i);
        row("private","PRIVATE","QUEUED",100);row("task","TASK","QUEUED",101);
        row("historical-private",null,"QUEUED",102);
        // Reproduce the former real SQL (not a mocked DAO): LIMIT picked only conversations.
        String fixed=String.join("\n",PersonalWorkspaceExecutionMapper.class.getMethod("listQueuedByTarget",
                String.class,String.class,String.class,String.class,int.class).getAnnotation(Select.class).value());
        String former=fixed.replace("AND (execution_mode IN ('PRIVATE','TASK') OR execution_mode IS NULL)","");
        assertNotEquals(fixed,former);
        former=former.replace("#{tenantId}","'0'").replace("#{clientId}","'client'")
                .replace("#{ownerJiacn}","'owner'").replace("#{targetAgentId}","'agent'").replace("#{limit}","16");
        var oldPage=jdbc.queryForList(former);
        assertEquals(16,oldPage.size());
        assertTrue(oldPage.stream().allMatch(r->"CONVERSATION".equals(r.get("execution_mode"))));
        assertEquals(List.of("private","task","historical-private"),ids());
    }

    @Test void filePageRetainsLimitOrderingAndAdvancesAfterAuthoritativeCompletion() {
        for(int i=0;i<20;i++) {
            row("unsupported-"+i,"FUTURE_MODE","QUEUED",0);
            row("terminal-"+i,"PRIVATE","OUTPUT_COMMITTED",0);
            row(String.format("file-%02d",i),i%2==0?"PRIVATE":"TASK","QUEUED",100);
        }
        var first=IntStream.range(0,16).mapToObj(i->String.format("file-%02d",i)).toList();
        assertEquals(first,ids());
        assertEquals(first,ids()); // Discovery is read-only; no claim or execution implied.
        for(String id:first) jdbc.update("UPDATE agent_personal_workspace_execution SET execution_state='OUTPUT_COMMITTED' WHERE execution_id=?",id);
        assertEquals(IntStream.range(16,20).mapToObj(i->String.format("file-%02d",i)).toList(),ids());
        jdbc.update("UPDATE agent_personal_workspace_execution SET execution_state='OUTPUT_COMMITTED' WHERE execution_mode IN ('PRIVATE','TASK')");
        assertEquals(List.of(),ids());
    }

    @Test void filteringBeforeLimitPreservesExactTenantClientOwnerAndAgentScope() {
        row("wanted","PRIVATE","QUEUED",100);
        // General-ci deliberately equates these variants without the mapper's binary predicates.
        var variants=Map.of("tenant_id",List.of("1","0 "),"client_id",List.of("Client","clíent","client "),
                "owner_jiacn",List.of("Owner","ówner","owner "),"target_agent_id",List.of("Agent","ágent","agent "));
        int n=0;
        for(var entry:variants.entrySet()) for(String value:entry.getValue()) for(int i=0;i<16;i++) {
            String id="other-"+(n++);row(id,"PRIVATE","QUEUED",0);
            jdbc.update("UPDATE agent_personal_workspace_execution SET "+entry.getKey()+"=? WHERE execution_id=?",value,id);
        }
        assertEquals(List.of("wanted"),ids());
        assertEquals(List.of(),mapper.listQueuedByTarget("missing","client","owner","agent",16));
    }
}
