package cn.jia.chat.service;

import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.agent.service.ControlledImageProviderAuthorityLookup;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Current adapter facts only: this projection never grants authority or invokes a tool. */
@Service
public final class ChatActionCapabilityService {
    public record Scope(String tenantId, String ownerJiacn, String clientId, String targetAgentId) { }
    private final TypedInspectionSessionRegistry inspections;
    private final ObjectProvider<ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup> executions;
    private final ControlledImageProviderAuthorityLookup authorities;
    private final boolean inspectionEnabled;
    private final boolean executionEnabled;
    private final boolean textExecutionEnabled;
    private static final com.fasterxml.jackson.databind.ObjectMapper SKILL_JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private cn.jia.agent.service.InstalledSkillDescriptorLookup installedSkills;
    private cn.jia.agent.service.SkillCapabilityLookup skillRuntimes;
    @org.springframework.beans.factory.annotation.Autowired
    public void setInstalledSkillCapabilities(cn.jia.agent.service.InstalledSkillDescriptorLookup installed,
            cn.jia.agent.service.SkillCapabilityLookup runtimes) {
        installedSkills = Objects.requireNonNull(installed); skillRuntimes = Objects.requireNonNull(runtimes);
    }

    /** Installation/runtime observation, NOT availableActions or a task authorization grant.
     * I16/I18 must separately re-admit the selected skill under the current task/assignment. */
    public record SkillCandidate(String skillKey, String skillVersion, String title,
            String state, String reasonCode, String metadataJson) { }
    public record InstalledCatalog(String catalogRevision, List<SkillCandidate> items) {
        public InstalledCatalog { items = List.copyOf(items); }
    }
    public InstalledCatalog inspectInstalledSkills(Scope scope,
            cn.jia.agent.service.AgentManagedSessionLookup.SessionFence expectedFence) {
        Objects.requireNonNull(scope);
        cn.jia.agent.service.SkillCapabilityLookup.validateFence(expectedFence);
        if(installedSkills==null||skillRuntimes==null)
            throw new cn.jia.agent.service.InstalledSkillDescriptorLookup.SourceUnavailable();
        var key=new cn.jia.agent.service.SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),scope.targetAgentId());
        var descriptors=installedSkills.read(key);
        var runtime=Objects.requireNonNull(skillRuntimes.lookup(key,expectedFence));
        var results=new ArrayList<SkillCandidate>();
        var counts=new java.util.HashMap<List<String>,Integer>();
        for(var descriptor:descriptors.skills())
            counts.merge(List.of(descriptor.skillKey(),descriptor.skillVersion()),1,Integer::sum);
        for(var descriptor:descriptors.skills()) {
            String reason=descriptor.reasonCode();
            String title=descriptor.skillKey(); String metadata=null;
            String runner=null;
            if(descriptor.verified()) {
                try {
                    var manifest=SKILL_JSON.readTree(descriptor.manifestJson());
                    title=manifest.path("title").asText(descriptor.skillKey()); runner=manifest.path("runner").asText();
                    // Allowlist: no entrypoint, installation, host, binding or internal package paths.
                    var publicMetadata=SKILL_JSON.createObjectNode();
                    for(String field:List.of("description","parametersSchema","inputs","outputs","costInfo"))
                        publicMetadata.set(field,manifest.get(field));
                    metadata=publicMetadata.toString();
                } catch(java.io.IOException invalid) {
                    throw new cn.jia.agent.service.InstalledSkillDescriptorLookup.SourceUnavailable(invalid);
                }
            } else if(reason==null) reason="SKILL_INSTALLATION_EVIDENCE_INVALID";
            if(counts.get(List.of(descriptor.skillKey(),descriptor.skillVersion()))!=1)
                reason="SKILL_INSTALLATION_AMBIGUOUS";
            if(reason==null && runtime.state()!=cn.jia.agent.service.SkillCapabilityLookup.State.AVAILABLE)
                reason="SKILL_RUNTIME_"+runtime.state().name();
            if(reason==null) {
                var reports=runtime.catalog().skills().stream().filter(r->descriptor.skillKey().equals(r.skillKey())
                        && descriptor.skillVersion().equals(r.skillVersion())).toList();
                if(reports.size()!=1) reason="SKILL_RUNTIME_NOT_DECLARED";
                else {
                    var report=reports.getFirst();
                    if(!Objects.equals(descriptor.runtimeInstallationId(),runtime.fence().runtimeInstallationId())
                            || !Objects.equals(descriptor.runtimeHostId(),runtime.fence().hostId())
                            || !Objects.equals(descriptor.packageSha256(),report.packageSha256())
                            || !Objects.equals(descriptor.manifestSha256(),report.manifestSha256())
                            || !Objects.equals(runner,report.runner())) reason="SKILL_RUNTIME_BINDING_MISMATCH";
                    else if(report.readiness()!=cn.jia.agent.service.SkillCapabilityLookup.Readiness.READY)
                        reason=report.reasonCode()==null?"SKILL_RUNTIME_UNAVAILABLE":report.reasonCode();
                    else if(report.reasonCode()!=null||report.toolchainDigest()==null
                            || ("provider-operation".equals(runner)&&report.bindingRevision()==null))
                        reason="SKILL_RUNTIME_EVIDENCE_INVALID";
                }
            }
            results.add(new SkillCandidate(descriptor.skillKey(),descriptor.skillVersion(),title,
                    reason==null?"RUNTIME_READY":"UNAVAILABLE",reason,metadata));
        }
        // Fence/live directory is rechecked after the join. No old READY survives a reconnect.
        if(runtime.state()==cn.jia.agent.service.SkillCapabilityLookup.State.AVAILABLE
                && !runtime.equals(skillRuntimes.lookup(key,expectedFence))) {
            results.replaceAll(item->new SkillCandidate(item.skillKey(),item.skillVersion(),item.title(),
                    "UNAVAILABLE","SKILL_RUNTIME_CHANGED",item.metadataJson()));
            return new InstalledCatalog(null,results);
        }
        return new InstalledCatalog(runtime.catalog()==null?null:runtime.catalog().catalogRevision(),results);
    }

    private cn.jia.agent.service.NativeTextFileRuntimeLookup textFiles;
    private cn.jia.agent.service.PersonalWorkspaceExecutionService workspaceExecutions;
    @org.springframework.beans.factory.annotation.Autowired
    public void setTextFileCapabilities(cn.jia.agent.service.NativeTextFileRuntimeLookup lookup,
            cn.jia.agent.service.PersonalWorkspaceExecutionService executions) {
        this.textFiles = lookup; this.workspaceExecutions = executions;
    }

    public ChatActionCapabilityService(TypedInspectionSessionRegistry inspections,
            ObjectProvider<ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup> executions,
            ControlledImageProviderAuthorityLookup authorities,
            @Value("${chat.typed-inspection.enabled:false}") boolean inspectionEnabled,
            @Value("${agent.controlled-image-provider.bridge-enabled:false}") boolean bridgeEnabled,
            @Value("${agent.controlled-image-provider.enabled:false}") boolean providerEnabled,
            @Value("${chat.bounty-execution.enabled:false}") boolean executionEnabled) {
        this.inspections = Objects.requireNonNull(inspections);
        this.executions = Objects.requireNonNull(executions);
        this.authorities = Objects.requireNonNull(authorities);
        this.inspectionEnabled = inspectionEnabled;
        this.executionEnabled = bridgeEnabled && providerEnabled && executionEnabled;
        this.textExecutionEnabled = executionEnabled;
    }

    /** Catalogue entries contain authoritative mediaType/contentMimeType metadata, never bytes. */
    public List<Map<String, Object>> available(Scope scope, List<Map<String, Object>> catalog) {
        Objects.requireNonNull(scope); Objects.requireNonNull(catalog);
        List<Map<String, Object>> actions = new ArrayList<>();
        if (inspectionEnabled && !catalog.isEmpty()) {
            try {
                var ready = inspections.requireSingleReady(new TypedInspectionSessionRegistry.Scope(
                        scope.tenantId(), scope.ownerJiacn(), scope.clientId()), scope.targetAgentId());
                LinkedHashSet<String> media = new LinkedHashSet<>();
                for (var source : catalog) {
                    try {
                        var input = ready.declaration().requireInput((String) source.get("mediaType"),
                                (String) source.get("contentMimeType"));
                        media.add(input.mediaKind());
                    } catch (IllegalStateException unsupportedInput) {
                        // One unsupported file does not hide usable inputs or prevent ordinary chat.
                    }
                }
                if (!media.isEmpty()) actions.add(action("inspect-materials", "INSPECT_INPUTS",
                        "INSPECT_INPUTS", List.copyOf(media), 1, 32));
            } catch (IllegalStateException unavailable) {
                // No exact live inspector: do not fabricate a read capability.
            }
        }
        if (executionEnabled) appendImageAdapter(scope, actions);
        if (textExecutionEnabled && catalog.isEmpty() && textFiles != null && workspaceExecutions != null
                && workspaceExecutions.capabilities().allowedMimeTypes().contains("text/plain")
                && textFiles.ready(new cn.jia.agent.service.NativeTextFileRuntimeLookup.Scope(
                        scope.tenantId(),scope.clientId(),scope.ownerJiacn(),scope.targetAgentId())))
            actions.add(action("generate-text-file", "EXECUTE", "GENERATE_TEXT_FILE", List.of(), 0, 0));
        return List.copyOf(actions);
    }

    private void appendImageAdapter(Scope scope, List<Map<String, Object>> actions) {
        var lookup = executions.getIfUnique();
        if (lookup == null) return;
        var declaration = lookup.current(new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.DeclarationScope(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), scope.targetAgentId()));
        if (declaration == null || declaration.state()
                != ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY) return;
        if (declaration.runtimeInstanceId() == null || declaration.runtimeInstanceId().isBlank()
                || !"CONTROLLED_IMAGE_HTTP_V1".equals(declaration.providerLane())
                || declaration.bindingId() == null || declaration.bindingId().isBlank()
                || declaration.bindingEpoch() == null || declaration.bindingEpoch() < 1
                || declaration.modelId() == null || declaration.modelId().isBlank()
                || !Objects.equals(declaration.maxInputItems(), 16)
                || !Objects.equals(declaration.maxOutboundRequestAttempts(), 1)
                || !Objects.equals(declaration.precallFenceVersion(), 1)) return;
        var policy = authorities.current(new ControlledImageProviderAuthorityLookup.Scope(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn()), scope.targetAgentId(),
                declaration.bindingId(), declaration.bindingEpoch());
        if (policy == null || policy.state() != ControlledImageProviderAuthorityLookup.State.READY
                || !Objects.equals(policy.providerLane(), declaration.providerLane())
                || !Objects.equals(policy.bindingId(), declaration.bindingId())
                || !Objects.equals(policy.bindingEpoch(), Long.toString(declaration.bindingEpoch()))
                || !Objects.equals(policy.modelId(), declaration.modelId())
                || !Objects.equals(policy.maxOutboundRequestAttempts(), declaration.maxOutboundRequestAttempts())) return;
        // Limits are the registered image adapter's wire contract, not a generic product restriction.
        if (declaration.operations().contains("GENERATE_IMAGE")) actions.add(action("generate-image", "EXECUTE",
                "GENERATE_IMAGE", List.of("image"), 0, declaration.maxInputItems()));
        if (declaration.operations().contains("EDIT_IMAGE")) actions.add(action("edit-image", "EXECUTE",
                "EDIT_IMAGE", List.of("image"), 1, 1));
    }

    private static Map<String, Object> action(String id, String kind, String operation,
            List<String> media, int min, int max) {
        return Map.of("actionId", id, "kind", kind, "operation", operation,
                "inputMediaTypes", media, "minSources", min, "maxSources", max);
    }
}
