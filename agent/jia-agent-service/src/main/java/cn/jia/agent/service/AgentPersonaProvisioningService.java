package cn.jia.agent.service;

import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.common.AgentHostedProfileState;
import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.agent.entity.AgentPersonaBindResultDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.service.AgentHostedBindingTransaction.Prepared;
import cn.jia.agent.service.AgentHostedBindingTransaction.Scope;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.core.util.StringUtil;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

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
        String expectedState = hosted.getLifecycleState();
        String expectedResumeState = hosted.getResumeState();
        long generation = AgentHostedGeneration.requireNonNegative(hosted.getGeneration());
        long nextGeneration = AgentHostedGeneration.successor(generation);
        try {
            publisher.publish(hosted, prepared.persona(), generation, nextGeneration,
                    false, prepared.apiKey());
            transactions.completeUnbind(scope, hosted.getBindingId(), generation, nextGeneration);
        } catch (RuntimeException failure) {
            markRepair(scope, hosted, expectedState, expectedResumeState, generation,
                    AgentHostedProfileState.SUSPENDING, failure);
            throw failure;
        }
    }

    public AgentPersonaBindResultDTO repair(Scope scope, long bindingId) {
        if (bindingId <= 0) throw new IllegalArgumentException("bindingId must be positive");
        Prepared prepared = transactions.resumeRepair(scope, bindingId);
        AgentHostedProfileEntity hosted = prepared.hosted();
        if (AgentHostedProfileState.SUSPENDED.equals(hosted.getLifecycleState())) {
            return result(prepared, publisher.inspectExisting(hosted, hosted.getGeneration()));
        }
        if (AgentHostedProfileState.SUSPENDING.equals(hosted.getLifecycleState())) {
            String expectedState = hosted.getLifecycleState();
            String expectedResumeState = hosted.getResumeState();
            long generation = AgentHostedGeneration.requireNonNegative(hosted.getGeneration());
            long nextGeneration = AgentHostedGeneration.successor(generation);
            try {
                AgentHostedProfilePublisher.PublishedPaths paths = publisher.publish(hosted, prepared.persona(),
                        generation, nextGeneration, false, prepared.apiKey());
                AgentHostedProfileEntity suspended = transactions.completeUnbind(
                        scope, hosted.getBindingId(), generation, nextGeneration);
                return result(new Prepared(suspended, prepared.agent(), prepared.persona(), null), paths);
            } catch (RuntimeException failure) {
                markRepair(scope, hosted, expectedState, expectedResumeState, generation,
                        AgentHostedProfileState.SUSPENDING, failure);
                throw failure;
            }
        }
        return advance(scope, prepared);
    }

    private AgentPersonaBindResultDTO advance(Scope scope, Prepared prepared) {
        AgentHostedProfileEntity hosted = prepared.hosted();
        AgentHostedProfilePublisher.PublishedPaths published = null;
        while (true) {
            String state = hosted.getLifecycleState();
            String resumeState = hosted.getResumeState();
            long generation = AgentHostedGeneration.requireNonNegative(hosted.getGeneration());
            if (AgentHostedProfileState.ACTIVE.equals(state)) {
                AgentHostedProfilePublisher.PublishedPaths paths = published == null
                        ? publisher.inspectExisting(hosted, generation) : published;
                return result(prepared, paths);
            }
            if (AgentHostedProfileState.PREPARED.equals(state)) {
                long nextGeneration = AgentHostedGeneration.successor(generation);
                try {
                    published = merge(published, publisher.publish(hosted, prepared.persona(),
                            generation, nextGeneration, false, prepared.apiKey()));
                    hosted = transactions.transition(scope, hosted.getBindingId(), state, generation,
                            AgentHostedProfileState.STAGED_DISABLED, nextGeneration, true);
                    prepared = new Prepared(hosted, prepared.agent(), prepared.persona(), prepared.apiKey());
                    continue;
                } catch (RuntimeException failure) {
                    markRepair(scope, hosted, state, resumeState, generation, state, failure);
                    throw failure;
                }
            }
            if (AgentHostedProfileState.STAGED_DISABLED.equals(state)) {
                long nextGeneration = AgentHostedGeneration.successor(generation);
                try {
                    published = merge(published, publisher.publish(hosted, prepared.persona(),
                            generation, nextGeneration, true, prepared.apiKey()));
                    hosted = transactions.transition(scope, hosted.getBindingId(), state, generation,
                            AgentHostedProfileState.FILE_ENABLED, nextGeneration, true);
                    prepared = new Prepared(hosted, prepared.agent(), prepared.persona(), prepared.apiKey());
                    continue;
                } catch (RuntimeException failure) {
                    markRepair(scope, hosted, state, resumeState, generation, state, failure);
                    throw failure;
                }
            }
            if (AgentHostedProfileState.FILE_ENABLED.equals(state)) {
                try {
                    hosted = transactions.transition(scope, hosted.getBindingId(), state, generation,
                            AgentHostedProfileState.ACTIVE, generation, true);
                    prepared = new Prepared(hosted, prepared.agent(), prepared.persona(), prepared.apiKey());
                    continue;
                } catch (RuntimeException failure) {
                    markRepair(scope, hosted, state, resumeState, generation, state, failure);
                    throw failure;
                }
            }
            throw new AgentBizException(AgentErrorConstants.AGENT_ERROR,
                    "Hosted profile cannot be activated from state " + state);
        }
    }

    private void markRepair(Scope scope, AgentHostedProfileEntity hosted,
            String expectedState, String expectedResumeState, long expectedGeneration,
            String resumeState, RuntimeException failure) {
        try {
            transactions.markRepair(scope, hosted.getBindingId(), expectedState,
                    expectedResumeState, expectedGeneration, resumeState, failure);
        } catch (RuntimeException repairFailure) {
            failure.addSuppressed(repairFailure);
        }
    }

    private AgentHostedProfilePublisher.PublishedPaths merge(
            AgentHostedProfilePublisher.PublishedPaths existing,
            AgentHostedProfilePublisher.PublishedPaths next) {
        if (existing == null) return next;
        if (!Objects.equals(existing.workdir(), next.workdir())
                || !Objects.equals(existing.codexHome(), next.codexHome())
                || !Objects.equals(existing.profilesFile(), next.profilesFile())) {
            throw new AgentBizException(AgentErrorConstants.AGENT_ERROR,
                    "Hosted profile publication paths changed during activation");
        }
        boolean created = existing.created() || next.created();
        return new AgentHostedProfilePublisher.PublishedPaths(existing.workdir(), existing.codexHome(),
                existing.profilesFile(), created, !created && existing.alreadyExists() && next.alreadyExists());
    }

    private AgentPersonaBindResultDTO local(Scope scope, String personaCode) {
        AgentRuntimeDTO agent = agentService.bindPersona(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), personaCode);
        String profileId = personaCode.strip().toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        String agentName = StringUtil.isBlank(agent.getName()) ? personaCode : agent.getName();
        String personaName = !StringUtil.isBlank(agent.getTitle()) ? agent.getTitle()
                : !StringUtil.isBlank(agent.getPersonaName()) ? agent.getPersonaName() : agentName;
        String workdir = Path.of("/home/isp/apps/codex-ws-agent").toAbsolutePath().normalize().toString();
        AgentPersonaBindResultDTO result = new AgentPersonaBindResultDTO();
        result.setAgent(agent);
        result.setMode("local");
        result.setAgentId(agent.getAgentId());
        result.setProfileId(profileId);
        result.setWorkdir(workdir);
        result.setMessage("已入名册；浏览器响应不提供凭证，请通过受信任的带外流程配置专用 API key");
        result.setEnvExample("WS_URL=wss://<service>/ws/agent/channel\nOPENCLAW_API_KEY=<key>"
                + "\nDEFAULT_CODEX_PROFILE=" + profileId
                + "\nCODEX_PROFILES_FILE=$PWD/codex-profiles.conf");
        result.setProfileExample("""
                [agent.%s]
                agentId=%s
                codexWorkdir=%s
                agentName=%s
                personaName=%s
                isDefault=true
                """.formatted(profileId, agent.getAgentId(), workdir,
                agentName, personaName).stripTrailing());
        result.setCommands(List.of("Install codex-ws-agent from the trusted package",
                "Configure a dedicated key out of band"));
        return result;
    }

    private AgentPersonaBindResultDTO result(Prepared prepared,
            AgentHostedProfilePublisher.PublishedPaths paths) {
        if (paths == null) {
            throw new AgentBizException(AgentErrorConstants.AGENT_ERROR,
                    "Hosted profile publication metadata is unavailable");
        }
        AgentHostedProfileEntity hosted = prepared.hosted();
        AgentPersonaBindResultDTO result = new AgentPersonaBindResultDTO();
        result.setAgent(prepared.agent());
        result.setMode("server");
        result.setAgentId(prepared.agent().getAgentId());
        result.setProfileId(hosted.getProfileKey());
        result.setBindingId(Long.toString(hosted.getBindingId()));
        result.setHostedState(hosted.getLifecycleState());
        result.setGeneration(Long.toString(hosted.getGeneration()));
        result.setWorkdir(paths.workdir());
        result.setCodexHome(paths.codexHome());
        result.setProfilesFile(paths.profilesFile());
        result.setServerProfileCreated(paths.created());
        result.setServerProfileAlreadyExists(paths.alreadyExists());
        result.setMessage("服务器代管 profile 已按专用 binding 激活；凭证不会返回浏览器");
        result.setCommands(List.of());
        return result;
    }
}
