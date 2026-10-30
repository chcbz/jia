package cn.jia.agent.skill;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.hosting.HostingRentOwnerResolver;
import cn.jia.economy.entity.skill.SkillAgentVersionEntity;
import cn.jia.economy.mapper.EconomySkillApplicationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class SkillAgentVersionsTest {
    @Test void heartbeatDoesNotChangeTokenButRegistrationOwnershipAndStatusDo() {
        var r=runtime();var original=SkillAgentVersions.sourceHash(r);
        r.setLastSeenAt(999L);assertArrayEquals(original,SkillAgentVersions.sourceHash(r));
        r.setStatus("offline");assertFalse(java.util.Arrays.equals(original,SkillAgentVersions.sourceHash(r)));
        r=runtime();r.setTokenHash("new-generation");assertFalse(java.util.Arrays.equals(original,SkillAgentVersions.sourceHash(r)));
        r=runtime();r.setBindingId(9L);assertFalse(java.util.Arrays.equals(original,SkillAgentVersions.sourceHash(r)));
        r=runtime();r.setOwnerJiacn("other");assertFalse(java.util.Arrays.equals(original,SkillAgentVersions.sourceHash(r)));
    }
    @Test void onlineOfflineOnlineAbaAdvancesDurableCasTwice() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:skill_versions;MODE=MYSQL","sa","");
        var manager=new DataSourceTransactionManager(ds);var mapper=mock(EconomySkillApplicationMapper.class);
        var runtimes=mock(AgentRuntimeDao.class);var row=runtime();
        var state=new SkillAgentVersionEntity();state.setVersion(17L);state.setSourceHash(SkillAgentVersions.sourceHash(row));
        when(runtimes.findByAgentIdForUpdate("agt_1")).thenReturn(row);
        when(mapper.lockVersion("tenant-blue","client","agt_1")).thenReturn(state);
        when(mapper.advanceVersion(eq("tenant-blue"),eq("client"),eq("agt_1"),any(),anyLong())).thenAnswer(i->{
            assertEquals(state.getVersion(),i.getArgument(4));state.setVersion(state.getVersion()+1);state.setSourceHash(i.getArgument(3));return 1;
        });
        var versions=new SkillAgentVersions(mapper,runtimes,SkillMarketplaceRealTransactionTest.provider(mock(cn.jia.agent.service.AgentService.class)),mock(HostingRentOwnerResolver.class),manager);
        row.setStatus("offline");versions.observe(row);row.setStatus("online");versions.observe(row);
        assertEquals(19L,state.getVersion());row.setLastSeenAt(1000L);versions.observe(row);assertEquals(19L,state.getVersion());
        verify(mapper,times(2)).advanceVersion(anyString(),anyString(),anyString(),any(),anyLong());
    }
    @Test void unboundRuntimeDoesNotTouchSkillState() {
        var mapper=mock(EconomySkillApplicationMapper.class);var runtimes=mock(AgentRuntimeDao.class);
        var versions=new SkillAgentVersions(mapper,runtimes,SkillMarketplaceRealTransactionTest.provider(mock(cn.jia.agent.service.AgentService.class)),mock(HostingRentOwnerResolver.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        versions.observe(new AgentRuntimeEntity().setAgentId("unbound"));versions.observe(null);verifyNoInteractions(mapper,runtimes);
    }
    @Test void readAndConsumeUsePartitionNotOwnerWithoutExtraLookup() {
        var mapper=mock(EconomySkillApplicationMapper.class);
        var runtimes=mock(AgentRuntimeDao.class);var agents=mock(AgentService.class);
        var owners=mock(HostingRentOwnerResolver.class);
        var actor=new HostingRentHttp.Actor("user", "tenant-blue", "client", "owner");
        var row=runtime();var dto=new AgentRuntimeDTO();dto.setAgentId("agt_1");dto.setSystemAgent(false);
        when(owners.requireOwner(actor)).thenReturn("owner");
        when(agents.requireApiKeyOwnedAgent("client","owner","agt_1")).thenReturn(dto);
        when(runtimes.findInScope("tenant-blue","client","agt_1")).thenReturn(row);
        var state=new SkillAgentVersionEntity();state.setVersion(7L);state.setSourceHash(SkillAgentVersions.sourceHash(row));
        when(mapper.findVersion("tenant-blue","client","agt_1")).thenReturn(state);
        when(mapper.lockVersion("tenant-blue","client","agt_1")).thenReturn(state);
        when(mapper.advanceVersion("tenant-blue","client","agt_1",state.getSourceHash(),7L)).thenReturn(1);
        var versions=new SkillAgentVersions(mapper,runtimes,SkillMarketplaceRealTransactionTest.provider(agents),owners,
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        assertEquals(7L,versions.readOwned(actor,"agt_1"));
        versions.consume(actor,"agt_1",7L);
        verify(mapper).findVersion("tenant-blue","client","agt_1");
        verify(mapper).lockVersion("tenant-blue","client","agt_1");
        verify(mapper).advanceVersion("tenant-blue","client","agt_1",state.getSourceHash(),7L);
        verifyNoMoreInteractions(mapper);
        verify(runtimes).findInScope("tenant-blue","client","agt_1");verifyNoMoreInteractions(runtimes);
    }
    @Test void realSqlKeepsVersionRootsSeparateForSameOwnerClientAndAgent() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:skill_partition_"+UUID.randomUUID()+";MODE=MYSQL","sa","");
        // Keep this private fixture alive without DB_CLOSE_DELAY or a persistent test database.
        try(var connection=ds.getConnection()) {
            var jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE economy_skill_agent_version (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    +"tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, agent_id VARCHAR(100) NOT NULL, "
                    +"source_hash VARBINARY(32) NOT NULL, version BIGINT NOT NULL, UNIQUE(tenant_id,client_id,agent_id))");
            var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);
            config.addMapper(EconomySkillApplicationMapper.class);
            var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
            var mapper=new SqlSessionTemplate(java.util.Objects.requireNonNull(factory.getObject())).getMapper(EconomySkillApplicationMapper.class);
            var runtimes=mock(AgentRuntimeDao.class);var manager=new DataSourceTransactionManager(ds);
            var versions=new SkillAgentVersions(mapper,runtimes,SkillMarketplaceRealTransactionTest.provider(mock(AgentService.class)),
                    mock(HostingRentOwnerResolver.class),manager);
            var blue=runtime();var green=runtime();green.setTenantId("tenant-green");
            when(runtimes.findByAgentIdForUpdate("agt_1")).thenReturn(blue);versions.observe(blue);
            when(runtimes.findByAgentIdForUpdate("agt_1")).thenReturn(green);versions.observe(green);
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM economy_skill_agent_version",Integer.class));
            assertNull(mapper.findVersion("owner","client","agt_1"));
            blue.setStatus("offline");when(runtimes.findByAgentIdForUpdate("agt_1")).thenReturn(blue);versions.observe(blue);
            assertEquals(2L,mapper.findVersion("tenant-blue","client","agt_1").getVersion());
            assertEquals(1L,mapper.findVersion("tenant-green","client","agt_1").getVersion());
            var actor=new HostingRentHttp.Actor("user","tenant-blue","client","owner");
            new TransactionTemplate(manager).executeWithoutResult(status->versions.consume(actor,"agt_1",2L));
            assertEquals(3L,mapper.findVersion("tenant-blue","client","agt_1").getVersion());
            assertEquals(1L,mapper.findVersion("tenant-green","client","agt_1").getVersion());
            assertThrows(SkillMarketplaceException.class,()->new TransactionTemplate(manager)
                    .executeWithoutResult(status->versions.consume(actor,"agt_1",2L)));
        }
    }
    private static AgentRuntimeEntity runtime() {
        var row=new AgentRuntimeEntity().setAgentId("agt_1").setBindingId(7L).setOwnerJiacn("owner").setStatus("online").setTokenHash("generation");
        row.setTenantId("tenant-blue");row.setClientId("client");return row;
    }
}
