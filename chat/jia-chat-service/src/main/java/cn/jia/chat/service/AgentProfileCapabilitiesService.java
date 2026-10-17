package cn.jia.chat.service;

import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.hosting.HostingRentOwnerResolver;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentManagedSessionLookup.SessionFence;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.InstalledSkillDescriptorLookup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Private read-only profile. Installation and readiness do not confer task authorization. */
@Service
public final class AgentProfileCapabilitiesService {
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;
    private final AgentService agents;
    private final HostingRentOwnerResolver owners;
    private final AgentRuntimeAuthenticationService runtimes;
    private final ChatActionCapabilityService capabilities;
    private final Clock clock;

    @Autowired
    public AgentProfileCapabilitiesService(AgentService agents, HostingRentOwnerResolver owners,
            AgentRuntimeAuthenticationService runtimes, ChatActionCapabilityService capabilities) {
        this(agents, owners, runtimes, capabilities, Clock.systemUTC());
    }
    AgentProfileCapabilitiesService(AgentService agents, HostingRentOwnerResolver owners,
            AgentRuntimeAuthenticationService runtimes, ChatActionCapabilityService capabilities, Clock clock) {
        this.agents = agents; this.owners = owners; this.runtimes = runtimes;
        this.capabilities = capabilities; this.clock = clock;
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record ProfileCapabilities(String agentId, long observedAt, List<String> abilities,
            Long lastSeenAt, SkillDirectory skills) {
        public ProfileCapabilities { abilities = List.copyOf(abilities); }
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record SkillDirectory(String status, String catalogRevision, String reasonCode, List<SkillItem> items) {
        public SkillDirectory { items = List.copyOf(items); }
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record SkillItem(String skillKey, String skillVersion, String title, boolean installationVerified,
            String runtimeState, String reasonCode) { }

    public ProfileCapabilities read(HostingRentHttp.Actor actor, String agentId) {
        String owner = owners.requireOwner(actor);
        AgentRuntimeDTO agent = requireOwned(actor, owner, agentId);
        var scope = new ChatActionCapabilityService.Scope(actor.tenantId(), owner, actor.clientId(), agentId);
        AgentRuntimeAuthenticationService.Proof proof;
        try {
            proof = runtimes.currentRegisteredProof(actor.tenantId(), actor.clientId(), agentId);
        } catch (IllegalArgumentException missingOrAmbiguousProof) {
            proof = null;
        }
        SessionFence fence = null;
        if (proof != null) {
            var actual = proof.scope();
            if (!Objects.equals(actual.tenantId(), actor.tenantId())
                    || !Objects.equals(actual.clientId(), actor.clientId())
                    || !Objects.equals(actual.ownerJiacn(), owner) || !Objects.equals(actual.agentId(), agentId)) {
                throw new IllegalStateException("AGENT_PROFILE_PROOF_INTEGRITY");
            }
            fence = new SessionFence(proof.installationId(), proof.hostId(), actual.runtimeInstanceId(), proof.sessionGeneration());
        }
        SkillDirectory directory;
        try {
            var catalog = fence == null ? capabilities.inspectInstalledSkillsWithoutRuntime(scope)
                    : capabilities.inspectInstalledSkills(scope, fence);
            directory = project(catalog);
        } catch (InstalledSkillDescriptorLookup.SourceUnavailable unavailable) {
            directory = new SkillDirectory("UNAVAILABLE", null, "SKILL_DESCRIPTOR_SOURCE_UNAVAILABLE", List.of());
        }
        // Re-resolve persisted login ownership and the exact canonical binding after all observations.
        if (!owner.equals(owners.requireOwner(actor))) throw new cn.jia.agent.hosting.HostingRentApplicationException(404, "AGENT_PROFILE_NOT_FOUND");
        requireOwned(actor, owner, agentId);
        if (agent.getAbilities() == null) throw new IllegalStateException("AGENT_PROFILE_ABILITIES_INVALID");
        for (String ability : agent.getAbilities()) if (ability == null || ability.isBlank())
            throw new IllegalStateException("AGENT_PROFILE_ABILITIES_INVALID");
        long observedAt = safeTime(clock.millis());
        Long lastSeenAt = agent.getLastSeenAt() == null ? null : safeTime(agent.getLastSeenAt());
        return new ProfileCapabilities(agentId, observedAt, agent.getAbilities(), lastSeenAt, directory);
    }

    private AgentRuntimeDTO requireOwned(HostingRentHttp.Actor actor, String owner, String agentId) {
        var agent = agents.requireApiKeyOwnedAgent(actor.clientId(), owner, agentId);
        if (agent == null || !agentId.equals(agent.getAgentId()) || Boolean.TRUE.equals(agent.getSystemAgent())
                || !owner.equals(agent.getOwnerJiacn())) {
            throw new cn.jia.agent.hosting.HostingRentApplicationException(404, "AGENT_PROFILE_NOT_FOUND");
        }
        return agent;
    }
    private static SkillDirectory project(ChatActionCapabilityService.InstalledCatalog catalog) {
        String revision = catalog.catalogRevision();
        if (revision != null && !revision.matches("0|[1-9][0-9]*"))
            throw new IllegalStateException("AGENT_PROFILE_REVISION_INVALID");
        var grouped = new LinkedHashMap<List<String>, List<ChatActionCapabilityService.SkillCandidate>>();
        for (var item : catalog.items()) {
            if (!text(item.skillKey()) || !text(item.skillVersion())) throw new IllegalStateException("AGENT_PROFILE_SKILL_INVALID");
            grouped.computeIfAbsent(List.of(item.skillKey(), item.skillVersion()), key -> new ArrayList<>()).add(item);
        }
        var items = new ArrayList<SkillItem>();
        for (var group : grouped.values()) {
            var first = group.getFirst();
            if (group.size() != 1) {
                items.add(new SkillItem(first.skillKey(), first.skillVersion(), first.skillKey(), false,
                        "UNAVAILABLE", "SKILL_INSTALLATION_AMBIGUOUS"));
                continue;
            }
            boolean ready = "RUNTIME_READY".equals(first.state());
            if ((!ready && !"UNAVAILABLE".equals(first.state()))
                    || (ready && (!first.installationVerified() || first.reasonCode() != null))
                    || (!ready && (first.reasonCode() == null || !first.reasonCode().matches("[A-Z][A-Z0-9_]{0,63}"))))
                throw new IllegalStateException("AGENT_PROFILE_SKILL_INVALID");
            items.add(new SkillItem(first.skillKey(), first.skillVersion(), text(first.title()) ? first.title() : first.skillKey(),
                    first.installationVerified(), first.state(), first.reasonCode()));
        }
        items.sort(Comparator.comparing(SkillItem::skillKey).thenComparing(SkillItem::skillVersion));
        return new SkillDirectory("AVAILABLE", revision, null, items);
    }
    private static boolean text(String text) { return text != null && !text.isBlank(); }
    private static long safeTime(long value) {
        if (value < 0 || value > MAX_SAFE_INTEGER) throw new IllegalStateException("AGENT_PROFILE_TIME_INVALID");
        return value;
    }
}
