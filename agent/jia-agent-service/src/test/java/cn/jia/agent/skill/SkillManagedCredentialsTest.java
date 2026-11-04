package cn.jia.agent.skill;
import cn.jia.economy.service.skill.*;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.economy.config.*;
import cn.jia.economy.entity.*;
import cn.jia.agent.entity.skill.SkillManagedCredentialEntity;
import cn.jia.economy.mapper.*;
import cn.jia.agent.mapper.skill.*;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class SkillManagedCredentialsTest {
    private final SkillTradeService trade=mock(SkillTradeService.class);
    @Test void hostedAdoptionRequiresFreshUniqueScopedKeyAndCanonicalBindingWithoutRotation() {
        var a=new HostingRentHttp.Actor("sub","0","client","tenant");var mapper=mock(SkillCredentialMapper.class);
        var roots=mock(SkillInstallationMapper.class);var rent=mock(SkillHostingCredentialLookup.class);var versions=mock(SkillAgentVersions.class);
        var runtimes=mock(AgentRuntimeDao.class);var keys=mock(ApiKeyService.class);
        when(runtimes.findByAgentIdForUpdate("agent")).thenReturn(new AgentRuntimeEntity().setAgentId("agent").setBindingId(7L));
        var intent=new SkillHostingCredential().setIntentId("intent").setLeaseId("lease")
                .setPrincipalType("USER").setPrincipalId("sub").setManagedApiKeyId("key");
        when(rent.candidates("0","client","agent")).thenReturn(List.of(intent));
        var key=new OauthApiKeyEntity();key.setId("key");key.setTenantId("0");key.setJiacn("tenant");key.setClientId("client");key.setStatus(1);key.setKeyName("hosting:intent");
        when(keys.get("key")).thenReturn(key);when(rent.referenceCount("key")).thenReturn(1);
        when(rent.lockLease("0","client","lease")).thenReturn(new SkillHostingLease().setAgentId("agent").setBindingId("7").setStatus("ACTIVE"));
        var state=new AtomicReference<SkillManagedCredentialEntity>();when(mapper.active("0","client","agent")).thenAnswer(i->state.get());
        when(mapper.insert(anyString(),eq("key"),eq("agent"),eq(7L),eq("0"),eq("client"),anyLong())).thenAnswer(i->{
            var row=new SkillManagedCredentialEntity();row.setApiKeyId("key");row.setBindingId(7L);state.set(row);return 1;
        });
        var service=new SkillManagedCredentials(mapper,roots,rent,versions,runtimes,SkillMarketplaceRealTransactionTest.provider(keys),mock(org.springframework.transaction.PlatformTransactionManager.class),trade);
        assertEquals("key",service.requireCurrent(a,"agent"));verify(keys,never()).create(any());verify(keys,never()).update(any());
        state.set(null);when(rent.referenceCount("key")).thenReturn(2);
        assertEquals("SKILL_AGENT_CREDENTIAL_UNPROVEN",assertThrows(SkillMarketplaceException.class,()->service.requireCurrent(a,"agent")).code());
        when(rent.referenceCount("key")).thenReturn(1);key.setKeyName("owner-shared");
        assertThrows(SkillMarketplaceException.class,()->service.requireCurrent(a,"agent"));key.setKeyName("hosting:intent");intent.setPrincipalId("tenant");
        assertThrows(SkillMarketplaceException.class,()->service.requireCurrent(a,"agent"));
        verify(mapper,times(1)).insert(anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyLong());
    }
}
