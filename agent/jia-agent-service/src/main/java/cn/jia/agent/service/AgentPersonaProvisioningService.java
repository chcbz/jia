package cn.jia.agent.service;

import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.common.AgentHostedProfileState;
import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.agent.entity.AgentPersonaBindResultDTO;
import cn.jia.agent.service.AgentHostedBindingTransaction.Prepared;
import cn.jia.agent.service.AgentHostedBindingTransaction.Scope;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.core.util.StringUtil;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
public class AgentPersonaProvisioningService {
    private final AgentService agentService;
    private final AgentHostedBindingTransaction transactions;
    private final AgentHostedProfilePublisher publisher;

    public AgentPersonaProvisioningService(AgentService agentService,
            AgentHostedBindingTransaction transactions, AgentHostedProfilePublisher publisher) {
        this.agentService = agentService;
        this.transactions = transactions;
        this.publisher = publisher;
    }

    public AgentPersonaBindResultDTO bind(Scope scope, String personaCode, String mode) {
        String normalized = StringUtil.isBlank(mode) ? "local" : mode.strip().toLowerCase();
        if ("local".equals(normalized)) return local(scope, personaCode);
        if (!"server".equals(normalized)) throw new IllegalArgumentException("Unsupported bind mode: " + mode);
        Prepared prepared = transactions.prepareHosted(scope, personaCode);
        return advance(scope, prepared);
    }

    public void unbind(Scope scope, String personaCode) {
        Prepared prepared = transactions.prepareUnbind(scope, personaCode);
        if (prepared == null) return;
        AgentHostedProfileEntity hosted = prepared.hosted();
        long generation = hosted.getGeneration();
        try {
            publisher.publish(hosted, prepared.persona(), generation, generation + 1, false, prepared.apiKey());
            transactions.completeUnbind(scope, hosted.getBindingId(), generation, generation + 1);
        } catch (RuntimeException failure) {
            transactions.markRepair(scope, hosted.getBindingId(), AgentHostedProfileState.SUSPENDING, failure);
            throw failure;
        }
    }

    public AgentPersonaBindResultDTO repair(Scope scope, long bindingId) {
        if (bindingId <= 0) throw new IllegalArgumentException("bindingId must be positive");
        Prepared prepared = transactions.resumeRepair(scope, bindingId);
        AgentHostedProfileEntity hosted = prepared.hosted();
        if (AgentHostedProfileState.SUSPENDED.equals(hosted.getLifecycleState())) {
            return result(prepared, null);
        }
        if (AgentHostedProfileState.SUSPENDING.equals(hosted.getLifecycleState())) {
            long generation = hosted.getGeneration();
            try {
                AgentHostedProfilePublisher.PublishedPaths paths = publisher.publish(hosted, prepared.persona(),
                        generation, generation + 1, false, prepared.apiKey());
                AgentHostedProfileEntity suspended = transactions.completeUnbind(
                        scope, hosted.getBindingId(), generation, generation + 1);
                return result(new Prepared(suspended, prepared.agent(), prepared.persona(), null), paths);
            } catch (RuntimeException failure) {
                transactions.markRepair(scope, hosted.getBindingId(), AgentHostedProfileState.SUSPENDING, failure);
                throw failure;
            }
        }
        return advance(scope, prepared);
    }

    private AgentPersonaBindResultDTO advance(Scope scope, Prepared prepared) {
        AgentHostedProfileEntity hosted = prepared.hosted();
        try {
            while (true) {
                String state = hosted.getLifecycleState();
                long generation = hosted.getGeneration();
                if (AgentHostedProfileState.ACTIVE.equals(state)) return result(prepared, null);
                if (AgentHostedProfileState.PREPARED.equals(state)) {
                    AgentHostedProfilePublisher.PublishedPaths paths = publisher.publish(hosted, prepared.persona(),
                            generation, generation + 1, false, prepared.apiKey());
                    hosted = transactions.transition(scope, hosted.getBindingId(), state, generation,
                            AgentHostedProfileState.STAGED_DISABLED, generation + 1, true);
                    prepared = new Prepared(hosted, prepared.agent(), prepared.persona(), prepared.apiKey());
                    continue;
                }
                if (AgentHostedProfileState.STAGED_DISABLED.equals(state)) {
                    AgentHostedProfilePublisher.PublishedPaths paths = publisher.publish(hosted, prepared.persona(),
                            generation, generation + 1, true, prepared.apiKey());
                    hosted = transactions.transition(scope, hosted.getBindingId(), state, generation,
                            AgentHostedProfileState.FILE_ENABLED, generation + 1, true);
                    prepared = new Prepared(hosted, prepared.agent(), prepared.persona(), prepared.apiKey());
                    continue;
                }
                if (AgentHostedProfileState.FILE_ENABLED.equals(state)) {
                    hosted = transactions.transition(scope, hosted.getBindingId(), state, generation,
                            AgentHostedProfileState.ACTIVE, generation, true);
                    prepared = new Prepared(hosted, prepared.agent(), prepared.persona(), prepared.apiKey());
                    continue;
                }
                throw new AgentBizException(AgentErrorConstants.AGENT_ERROR,
                        "Hosted profile cannot be activated from state " + state);
            }
        } catch (RuntimeException failure) {
            transactions.markRepair(scope, hosted.getBindingId(), hosted.getLifecycleState(), failure);
            throw failure;
        }
    }

    private AgentPersonaBindResultDTO local(Scope scope, String personaCode) {
        var agent = agentService.bindPersona(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), personaCode);
        String profileId = personaCode.strip().toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        AgentPersonaBindResultDTO result = new AgentPersonaBindResultDTO();
        result.setAgent(agent); result.setMode("local"); result.setAgentId(agent.getAgentId());
        result.setProfileId(profileId);
        result.setWorkdir(Path.of("$HOME/cyf-agent-clients").resolve(agent.getAgentId()).toString());
        result.setCodexHome("$HOME/.codex-" + profileId);
        result.setMessage("已入名册；浏览器响应不提供凭证，请通过受信任的带外流程配置专用 API key");
        result.setEnvExample("WS_URL=wss://<service>/ws/agent/channel\nDEFAULT_CODEX_PROFILE=" + profileId
                + "\nCODEX_PROFILES_FILE=$PWD/codex-profiles.conf");
        result.setCommands(List.of("Install codex-ws-agent from the trusted package", "Configure a dedicated key out of band"));
        return result;
    }

    private AgentPersonaBindResultDTO result(Prepared prepared, AgentHostedProfilePublisher.PublishedPaths ignored) {
        AgentHostedProfileEntity h = prepared.hosted();
        AgentPersonaBindResultDTO result = new AgentPersonaBindResultDTO();
        result.setAgent(prepared.agent()); result.setMode("server"); result.setAgentId(prepared.agent().getAgentId());
        result.setProfileId(h.getProfileKey()); result.setBindingId(h.getBindingId());
        result.setHostedState(h.getLifecycleState()); result.setGeneration(h.getGeneration());
        result.setWorkdir("/home/isp/hosts/cyf/agent-clients/" + h.getCanonicalAgentId());
        result.setCodexHome("/home/isp/apps/codex-ws-agent/.codex-hosted-" + h.getProfileKey());
        result.setProfilesFile("/home/isp/apps/codex-ws-agent/codex-profiles.conf");
        result.setServerProfileCreated(true); result.setServerProfileAlreadyExists(false);
        result.setMessage("服务器代管 profile 已按专用 binding 激活；凭证不会返回浏览器");
        result.setCommands(List.of());
        return result;
    }

}
