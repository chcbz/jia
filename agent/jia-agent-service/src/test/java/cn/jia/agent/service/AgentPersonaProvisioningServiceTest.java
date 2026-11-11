package cn.jia.agent.service;
import cn.jia.agent.entity.AgentRuntimeDTO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AgentPersonaProvisioningServiceTest {
    private static final String AGENT_ID = "agt_0123456789abcdef0123456789abcdef";
    @Test
    void localBindProjectsTrustedScopeIntoRuntimeSetupWithoutLegacyCredentials() {
        AgentService agentService = mock(AgentService.class);
        AgentRuntimeDTO runtime = new AgentRuntimeDTO();
        runtime.setAgentId(AGENT_ID);
        AgentOwnerScope nonDefaultClientScope = new AgentOwnerScope("1", "cyf-local", "owner-local");
        when(agentService.bindPersona("1", "cyf-local", "owner-local", "wuyong"))
                .thenReturn(runtime);

        var result = new AgentPersonaProvisioningService(agentService)
                .bind(nonDefaultClientScope, "wuyong", "local");

        assertEquals("local", result.getMode());
        assertEquals(AGENT_ID, result.getAgentId());
        assertEquals("wuyong", result.getProfileId());
        assertNull(result.getWorkdir());
        assertNull(result.getEnvExample());
        assertNull(result.getProfileExample());
        assertTrue(result.getMessage().contains("不会创建 installation"));
        assertFalse(result.getMessage().toLowerCase().contains("api key"));
        var setup = result.getRuntimeSetup();
        assertNotNull(setup);
        assertEquals("v1", setup.getRuntimeProtocolVersion());
        assertEquals("1", setup.getTenantId());
        assertEquals("cyf-local", setup.getClientId());
        assertEquals(AGENT_ID, setup.getCanonicalAgentId());
        assertEquals("1", setup.getManifestVersion());
        assertEquals("/agent/runtime/v1/installations", setup.getInstallationCreatePath());
        assertEquals("https://gitee.com/chcbz/isp-install/blob/develop/docs/cyf-agent-runtime-v1-self-host.md",
                setup.getGuideUrl());
        assertFalse(result.getCommands().toString().toLowerCase().contains("api key"));
        assertFalse(result.getCommands().toString().contains("codex-ws-agent"));
        verify(agentService).bindPersona("1", "cyf-local", "owner-local", "wuyong");
    }

    @Test void managedBindHasNoAlternateLegacyExecutionPath() {
        AgentService agentService = mock(AgentService.class);
        var service = new AgentPersonaProvisioningService(agentService);
        for (String tenant : java.util.List.of("0", "1")) {
            assertThrows(IllegalArgumentException.class,
                () -> service.bind(new AgentOwnerScope(tenant, "client", "owner"), "wuyong", "server"));
        }
        verifyNoInteractions(agentService);
    }
}
