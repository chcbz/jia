package cn.jia.agent.skill;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.hosting.HostingRentOwnerResolver;
import cn.jia.economy.entity.skill.SkillAgentVersionEntity;
import cn.jia.economy.mapper.EconomySkillApplicationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.atomic.AtomicReference;
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
        when(mapper.lockVersion("owner","client","agt_1")).thenReturn(state);
        when(mapper.advanceVersion(eq("owner"),eq("client"),eq("agt_1"),any(),anyLong())).thenAnswer(i->{
            assertEquals(state.getVersion(),i.getArgument(4));state.setVersion(state.getVersion()+1);state.setSourceHash(i.getArgument(3));return 1;
        });
        var versions=new SkillAgentVersions(mapper,runtimes,SkillMarketplaceRealTransactionTest.provider(mock(cn.jia.agent.service.AgentService.class)),mock(HostingRentOwnerResolver.class),manager,true);
        row.setStatus("offline");versions.observe(row);row.setStatus("online");versions.observe(row);
        assertEquals(19L,state.getVersion());row.setLastSeenAt(1000L);versions.observe(row);assertEquals(19L,state.getVersion());
        verify(mapper,times(2)).advanceVersion(anyString(),anyString(),anyString(),any(),anyLong());
    }
    @Test void defaultOffDoesNotTouchHistoricalRuntimeOrSchema() {
        var mapper=mock(EconomySkillApplicationMapper.class);var runtimes=mock(AgentRuntimeDao.class);
        var versions=new SkillAgentVersions(mapper,runtimes,SkillMarketplaceRealTransactionTest.provider(mock(cn.jia.agent.service.AgentService.class)),mock(HostingRentOwnerResolver.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class),false);
        versions.observe(runtime());verifyNoInteractions(mapper,runtimes);
    }
    private static AgentRuntimeEntity runtime() {
        var row=new AgentRuntimeEntity().setAgentId("agt_1").setBindingId(7L).setOwnerJiacn("owner").setStatus("online").setTokenHash("generation");
        row.setClientId("client");return row;
    }
}
