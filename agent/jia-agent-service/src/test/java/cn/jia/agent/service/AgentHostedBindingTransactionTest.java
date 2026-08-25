package cn.jia.agent.service;

import cn.jia.agent.dao.*;
import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.agent.service.AgentHostedBindingTransaction.Scope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentHostedBindingTransactionTest {
    @Test
    @SuppressWarnings("unchecked")
    void disabledFileCrashWindowCompletesExactDatabaseSuspensionWithAlreadyDisabledKey() {
        Scope scope = new Scope("owner-a", "client-a", "owner-a");
        AgentRuntimeDao runtimeDao = mock(AgentRuntimeDao.class);
        AgentIdentityService identityService = mock(AgentIdentityService.class);
        AgentPersonaBindingDao bindingDao = mock(AgentPersonaBindingDao.class);
        AgentHostedProfileDao hostedDao = mock(AgentHostedProfileDao.class);
        ApiKeyService apiKeys = mock(ApiKeyService.class);
        ObjectProvider<ApiKeyService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(apiKeys);

        AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity();
        binding.setId(12L); binding.setTenantId("owner-a"); binding.setClientId("client-a");
        binding.setJiacn("owner-a"); binding.setPersonaCode("wuyong");
        binding.setAgentId("agt_0123456789abcdef0123456789abcdef");
        binding.setStatus(AgentConstants.BINDING_STATUS_ACTIVE);
        AgentIdentityRegistryEntity identity = new AgentIdentityRegistryEntity();
        identity.setCanonicalAgentId(binding.getAgentId());
        AgentHostedProfileEntity hosted = new AgentHostedProfileEntity();
        hosted.setId(5L); hosted.setBindingId(12L); hosted.setTenantId("owner-a");
        hosted.setClientId("client-a"); hosted.setOwnerJiacn("owner-a");
        hosted.setCanonicalAgentId(binding.getAgentId()); hosted.setPersonaCode("wuyong");
        hosted.setProfileKey(AgentHostedBindingTransaction.scopeDigest(scope) + 12L);
        hosted.setApiKeyId("key-12"); hosted.setLifecycleState("SUSPENDING");
        hosted.setGeneration(4L); hosted.setDesiredEnabled(false);
        AgentRuntimeEntity runtime = new AgentRuntimeEntity();
        runtime.setAgentId(binding.getAgentId()); runtime.setBindingId(12L);
        runtime.setClientId("client-a"); runtime.setOwnerJiacn("owner-a");
        runtime.setStatus(AgentConstants.STATUS_ONLINE);
        OauthApiKeyEntity key = new OauthApiKeyEntity();
        key.setId("key-12"); key.setClientId("client-a"); key.setJiacn("owner-a");
        key.setApiKey("cdx_0123456789abcdef0123456789abcdef"); key.setStatus(0);

        when(bindingDao.findByIdForUpdate(12L)).thenReturn(binding);
        when(identityService.requireRegistrationIdentityInScope(
                "owner-a", "client-a", "owner-a", binding.getAgentId())).thenReturn(identity);
        when(hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L)).thenReturn(hosted);
        when(apiKeys.get("key-12")).thenReturn(key);
        when(bindingDao.updateById(binding)).thenReturn(1);
        when(runtimeDao.findByAgentIdForUpdate(binding.getAgentId())).thenReturn(runtime);
        when(runtimeDao.updateById(runtime)).thenReturn(1);
        when(hostedDao.transition(5L, "SUSPENDING", 4L, "SUSPENDED", 5L, false)).thenReturn(1);

        AgentHostedBindingTransaction transaction = new AgentHostedBindingTransaction(
                runtimeDao, identityService, mock(AgentPersonaDao.class), bindingDao,
                hostedDao, provider);
        AgentHostedProfileEntity result = transaction.completeUnbind(scope, 12L, 4L, 5L);

        assertEquals("SUSPENDED", result.getLifecycleState());
        assertEquals(AgentConstants.BINDING_STATUS_SUSPENDED, binding.getStatus());
        assertEquals(AgentConstants.STATUS_OFFLINE, runtime.getStatus());
        verify(apiKeys, never()).update(any());
        verify(identityService).suspendForBinding("owner-a", "client-a", "owner-a", 12L);
        verify(hostedDao).transition(5L, "SUSPENDING", 4L, "SUSPENDED", 5L, false);
    }

    @Test
    void repairPersistsOnlyFixedFailureCategoryNeverMessageOrPath() {
        AgentHostedProfileDao hostedDao = mock(AgentHostedProfileDao.class);
        AgentHostedProfileEntity hosted = new AgentHostedProfileEntity();
        hosted.setId(9L);
        when(hostedDao.findExactForUpdate("owner-a", "client-a", "owner-a", 12L)).thenReturn(hosted);
        AgentHostedBindingTransaction transaction = new AgentHostedBindingTransaction(
                mock(AgentRuntimeDao.class), mock(AgentIdentityService.class), mock(AgentPersonaDao.class),
                mock(AgentPersonaBindingDao.class), hostedDao, mock(ObjectProvider.class));

        transaction.markRepair(new Scope("owner-a", "client-a", "owner-a"), 12L, "SUSPENDING",
                new IllegalStateException("cdx_secret at /home/isp/private/auth.json"));

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(hostedDao).markRepair(eq(9L), eq("SUSPENDING"), error.capture());
        assertEquals("HOSTED_PROFILE_FAILURE:IllegalStateException", error.getValue());
        assertFalse(error.getValue().contains("cdx_secret"));
        assertFalse(error.getValue().contains("/home/isp"));
    }
}
