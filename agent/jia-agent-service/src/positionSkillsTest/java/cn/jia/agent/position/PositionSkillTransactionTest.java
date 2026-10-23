package cn.jia.agent.position;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.PositionAuthority.*;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import cn.jia.economy.entity.skill.SkillProductVersionEntity;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static cn.jia.agent.position.PositionSkillState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual production SQL/store/transaction service against an explicitly supplied isolated MySQL.
 * Identity and package catalog are test doubles; this does not claim OAuth or Runtime E2E. */
class PositionSkillTransactionTest {
    private JdbcTemplate admin,jdbc;
    private String database;
    private PositionSkillService service;
    private Scope scope=new Scope("0","test-client","owner-a");
    @BeforeEach void setup()throws Exception {
        String base=System.getenv("CYF_POSITION_TEST_JDBC");
        assertNotNull(base,"CYF_POSITION_TEST_JDBC must explicitly identify an isolated loopback test server");
        assertTrue(base.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql(?:\\?.*)?"));
        String user=System.getenv("CYF_POSITION_TEST_USER"),pass=System.getenv("CYF_POSITION_TEST_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(base,user,pass));
        database="aps_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE DATABASE "+database);
        var ds=new DriverManagerDataSource(base.replace("/mysql","/"+database),user,pass);jdbc=new JdbcTemplate(ds);
        String ddl=new ClassPathResource("db/agent-position-skills.sql").getContentAsString(StandardCharsets.UTF_8);
        for(String sql:ddl.replaceAll("(?m)^--.*$", "").split(";"))if(!sql.isBlank())jdbc.execute(sql);
        var identities=mock(AgentIdentityService.class);
        var identity=new AgentIdentityRegistryEntity().setCanonicalAgentId("agent1").setBindingId(1L);
        when(identities.requireRegistrationIdentityInScope("0","test-client","owner-a","agent1")).thenReturn(identity);
        when(identities.lockActiveCanonicalAgentIdsInScope(anyString(),anyString(),anyString(),anyList())).thenAnswer(i->i.getArgument(3));
        var packages=mock(EconomySkillMarketplaceMapper.class);
        when(packages.selectApprovedProductVersion("0","test-client","v1")).thenReturn(new SkillProductVersionEntity().setReviewStatus("APPROVED").setSkillKey("maintain"));
        service=new PositionSkillService(new PositionSkillStore(jdbc),identities,packages,new DataSourceTransactionManager(ds));
        grant("MANAGE_POSITIONS","positions");grant("ARCHIVE_WRITE","archive:public");
        service.defineSkill(scope,"key1",new Skill("maintain",SkillType.POSITION,"v1"));
        service.configure(scope,"key2",new Position("editor","Editor",2,"archive:public",false,List.of("maintain"),0),0);
    }
    private void grant(String p,String r){jdbc.update("INSERT INTO agent_position_authority VALUES(?,?,?,?,?)",scope.tenantId(),scope.clientId(),scope.ownerJiacn(),p,r);}
    @AfterEach void cleanup(){if(admin!=null && database!=null && database.matches("aps_[a-f0-9]{32}"))admin.execute("DROP DATABASE "+database);}
    @Test void operationsAndStateCommitTogetherAndReplaySurvivesNewRead(){
        var a=service.appoint(scope,"appoint1","editor","agent1");
        var b=service.appoint(scope,"appoint1","editor","agent1");assertEquals(a.resultId(),b.resultId());assertEquals(a.revision(),b.revision());
        service.resign(scope,"resign1",a.resultId());
        var recovered=service.recovery(scope,"appoint1");assertFalse(recovered.state().appointments().get(a.resultId()).active());
        assertEquals(a.operationId(),recovered.operationId());
        assertThrows(PositionSkillException.class,()->service.resign(scope,"appoint1",a.resultId()));
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM agent_position_operation",Integer.class));
    }
    @Test void ordinaryOwnerCannotGrantPublicWriteOrPublishAndScopeCannotLeak(){
        assertThrows(PositionSkillException.class,()->service.appoint(new Scope("0","test-client","owner-b"),"no-rights","editor","agent1"));
        assertEquals(0,service.read(new Scope("0","other-client","owner-a")).state().appointments().size());
        assertThrows(PositionSkillException.class,()->service.configure(scope,"publish",new Position("publisher","P",1,"archive:public",true,List.of("maintain"),0),0));
        assertFalse(service.read(scope).state().positions().containsKey("publisher"));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM agent_position_operation",Integer.class));
    }
    @Test void businessCommitRollsBackAndRevocationFencesLaterWrites(){
        var a=service.appoint(scope,"appoint1","editor","agent1");
        var g=new Grant(a.resultId(),1,1,"agent1",1,"book",false);
        jdbc.execute("CREATE TABLE test_publication(id INT PRIMARY KEY)");
        assertThrows(IllegalStateException.class,()->service.withAuthority(scope,g,()->{jdbc.update("INSERT INTO test_publication VALUES(1)");throw new IllegalStateException();}));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM test_publication",Integer.class));
        service.withAuthority(scope,g,()->jdbc.update("INSERT INTO test_publication VALUES(1)"));
        service.resign(scope,"resign1",a.resultId());
        assertThrows(PositionSkillException.class,()->service.withAuthority(scope,g,()->jdbc.update("INSERT INTO test_publication VALUES(2)")));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM test_publication",Integer.class));
    }
    @Test void publicationAndResignationSerializeAtDatabaseRoot()throws Exception {
        var a=service.appoint(scope,"appoint1","editor","agent1");
        var g=new Grant(a.resultId(),1,1,"agent1",1,"book",false);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var revokeStarted=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var publishing=pool.submit(()->service.withAuthority(scope,g,()->{entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return "committed";}));
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            var revoking=pool.submit(()->{revokeStarted.countDown();return service.resign(scope,"resign1",a.resultId());});
            assertTrue(revokeStarted.await(10,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->revoking.get(200,TimeUnit.MILLISECONDS));
            release.countDown();assertEquals("committed",publishing.get(10,TimeUnit.SECONDS));revoking.get(10,TimeUnit.SECONDS);
            assertThrows(PositionSkillException.class,()->service.withAuthority(scope,g,()->"forbidden"));
        }finally{release.countDown();}
    }
}
