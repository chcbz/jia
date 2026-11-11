package cn.jia.agent.service;

import cn.jia.agent.entity.AgentPersonaBindResultDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.core.util.StringUtil;
import org.springframework.stereotype.Service;
import java.util.List;

/** Self-host installation setup; managed provisioning belongs exclusively to the rent lifecycle. */
@Service
public class AgentPersonaProvisioningService {
    private final AgentService agentService;

    public AgentPersonaProvisioningService(AgentService agentService) {
        this.agentService = agentService;
    }

    public AgentPersonaBindResultDTO bind(AgentOwnerScope scope, String personaCode, String mode) {
        String normalized = StringUtil.isBlank(mode) ? "local" : mode.strip().toLowerCase(java.util.Locale.ROOT);
        if (!"local".equals(normalized)) throw new IllegalArgumentException("Unsupported bind mode: " + mode);
        return local(scope, personaCode);
    }

    private AgentPersonaBindResultDTO local(AgentOwnerScope scope, String personaCode) {
        AgentRuntimeDTO agent = agentService.bindPersona(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), personaCode);
        String profileId = personaCode.strip().toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        AgentPersonaBindResultDTO result = new AgentPersonaBindResultDTO();
        result.setAgent(agent);
        result.setMode("local");
        result.setAgentId(agent.getAgentId());
        result.setProfileId(profileId);
        result.setMessage("本地绑定仅提供 Runtime v1 自托管准备信息；不会创建 installation 或返回凭证。");
        result.setEnvExample(null);
        result.setProfileExample(null);
        AgentPersonaBindResultDTO.RuntimeSetup runtimeSetup =
                new AgentPersonaBindResultDTO.RuntimeSetup();
        runtimeSetup.setRuntimeProtocolVersion("v1");
        runtimeSetup.setTenantId(scope.tenantId());
        runtimeSetup.setClientId(scope.clientId());
        runtimeSetup.setCanonicalAgentId(agent.getAgentId());
        runtimeSetup.setManifestVersion("1");
        runtimeSetup.setInstallationCreatePath("/agent/runtime/v1/installations");
        runtimeSetup.setGuideUrl("https://gitee.com/chcbz/isp-install/blob/develop/docs/cyf-agent-runtime-v1-self-host.md");
        result.setRuntimeSetup(runtimeSetup);
        result.setCommands(List.of("Prepare Runtime v1 enrollment materials on the target host",
                "Review the self-hosting guide and explicitly register the installation in the web console"));
        return result;
    }

}
