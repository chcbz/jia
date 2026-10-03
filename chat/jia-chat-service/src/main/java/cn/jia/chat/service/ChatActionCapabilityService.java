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
                "EDIT_IMAGE", List.of("image"), 1, declaration.maxInputItems()));
    }

    private static Map<String, Object> action(String id, String kind, String operation,
            List<String> media, int min, int max) {
        return Map.of("actionId", id, "kind", kind, "operation", operation,
                "inputMediaTypes", media, "minSources", min, "maxSources", max);
    }
}
