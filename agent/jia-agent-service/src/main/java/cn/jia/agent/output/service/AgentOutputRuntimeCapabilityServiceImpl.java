package cn.jia.agent.output.service;

import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.output.AgentOutputRuntimeCapabilityService;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.core.util.JsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class AgentOutputRuntimeCapabilityServiceImpl
        implements AgentOutputRuntimeCapabilityService {
    private final AgentRuntimeDao runtimeDao;
    private final AgentIdentityService identityService;
    private final boolean enabled;

    public AgentOutputRuntimeCapabilityServiceImpl(
            AgentRuntimeDao runtimeDao,
            AgentIdentityService identityService,
            @Value("${agent.output-delivery.enabled:false}") boolean enabled) {
        this.runtimeDao = runtimeDao;
        this.identityService = identityService;
        this.enabled = enabled;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceAfterRegistration(
            String tenantId, String clientId, String agentId,
            String runtimeInstanceId, String registrationToken,
            List<String> outputCapabilities) {
        if (!enabled) return;
        requireExact(runtimeInstanceId, "runtimeInstanceId", 400);
        requireExact(registrationToken, "registrationToken", 200);
        AgentRuntimeEntity runtime = requireCurrentRuntime(tenantId, clientId, agentId);
        List<String> normalized = normalize(outputCapabilities);
        String storedRuntimeId = outputCapabilities == null ? null : runtimeInstanceId;
        String json = outputCapabilities == null ? null : JsonUtil.toJson(normalized);
        int updated = runtimeDao.replaceOutputCapabilities(
                tenantId, clientId, tenantId, agentId, runtime.getBindingId(),
                registrationToken, storedRuntimeId, json, System.currentTimeMillis());
        if (updated != 1) {
            throw denied("Registration generation no longer owns the Agent runtime");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshPresence(
            String tenantId, String clientId, String agentId,
            String runtimeInstanceId, List<String> outputCapabilities) {
        if (!enabled) return;
        requireExact(runtimeInstanceId, "runtimeInstanceId", 400);
        AgentRuntimeEntity runtime = requireCurrentRuntime(tenantId, clientId, agentId);
        int updated = runtimeDao.refreshOutputCapabilities(
                tenantId, clientId, tenantId, agentId, runtime.getBindingId(),
                runtimeInstanceId, JsonUtil.toJson(normalize(outputCapabilities)),
                System.currentTimeMillis());
        if (updated != 1) {
            throw denied("Presence came from an old or unregistered runtime");
        }
    }

    private AgentRuntimeEntity requireCurrentRuntime(
            String tenantId, String clientId, String agentId) {
        requireExact(tenantId, "tenantId", 200);
        requireExact(clientId, "clientId", 200);
        requireExact(agentId, "agentId", 400);
        if (!List.of(agentId).equals(identityService.lockActiveCanonicalAgentIdsInScope(
                tenantId, clientId, tenantId, List.of(agentId)))) {
            throw denied("Agent binding is unavailable");
        }
        AgentRuntimeEntity runtime = runtimeDao.findExactOutputRuntime(
                tenantId, clientId, tenantId, agentId, true);
        if (runtime == null || runtime.getBindingId() == null
                || !tenantId.equals(runtime.getTenantId())
                || !clientId.equals(runtime.getClientId())
                || !tenantId.equals(runtime.getOwnerJiacn())
                || !agentId.equals(runtime.getAgentId())) {
            throw denied("Agent runtime is outside the authenticated scope");
        }
        identityService.requireActiveIdentityForBinding(
                tenantId, clientId, tenantId, runtime.getBindingId(), agentId);
        return runtime;
    }

    private List<String> normalize(List<String> capabilities) {
        List<String> raw = capabilities == null ? List.of() : capabilities;
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String capability : raw) {
            requireExact(capability, "outputCapability", 100);
            if (!OutputConstants.R1_REGISTERED_CAPABILITIES.contains(capability)
                    || !unique.add(capability)) {
                throw denied("Output capability is unsupported or duplicated");
            }
        }
        List<String> result = new ArrayList<>(unique);
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    private void requireExact(String value, String field, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw denied(field + " is invalid");
        }
    }

    private OutputAuthorizationException denied(String message) {
        return new OutputAuthorizationException("OUTPUT_RUNTIME_FORBIDDEN", message);
    }
}
