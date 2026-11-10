package cn.jia.agent.service;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.common.AgentHostedProfileState;
import cn.jia.agent.dao.AgentHostedProfileDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class AgentHostedBindingTransaction {
    private final AgentRuntimeDao runtimeDao;
    private final AgentIdentityService identityService;
    private final AgentPersonaDao personaDao;
    private final AgentPersonaBindingDao bindingDao;
    private final AgentHostedProfileDao hostedDao;
    private final ObjectProvider<ApiKeyService> apiKeyProvider;

    public AgentHostedBindingTransaction(AgentRuntimeDao runtimeDao, AgentIdentityService identityService,
            AgentPersonaDao personaDao, AgentPersonaBindingDao bindingDao,
            AgentHostedProfileDao hostedDao, ObjectProvider<ApiKeyService> apiKeyProvider) {
        this.runtimeDao = runtimeDao;
        this.identityService = identityService;
        this.personaDao = personaDao;
        this.bindingDao = bindingDao;
        this.hostedDao = hostedDao;
        this.apiKeyProvider = apiKeyProvider;
    }

    @Transactional(rollbackFor = Exception.class)
    public Prepared prepareHosted(Scope scope, String personaCode) {
        scope.requireLegacyHostedTenant();
        AgentPersonaEntity persona = requirePersona(scope, personaCode);
        if (Boolean.TRUE.equals(persona.getSystemAgent())) fail(AgentErrorConstants.PERSONA_NOT_BINDABLE, "System persona cannot be bound");
        AgentPersonaBindingEntity binding = bindingDao.findActiveByTenantClientAndPersonaForUpdate(
                scope.tenantId(), scope.clientId(), persona.getPersonaCode());
        if (binding != null && !Objects.equals(scope.ownerJiacn(), binding.getJiacn())) {
            fail(AgentErrorConstants.PERSONA_BOUND, "Persona is already bound");
        }
        boolean createdBinding = binding == null;
        AgentRuntimeDTO agent;
        AgentHostedProfileEntity hosted;
        if (createdBinding) {
            binding = new AgentPersonaBindingEntity();
            binding.setTenantId(scope.tenantId());
            binding.setClientId(scope.clientId());
            binding.setJiacn(scope.ownerJiacn());
            binding.setPersonaCode(persona.getPersonaCode());
            binding.setAgentId("agt_" + UUID.randomUUID().toString().replace("-", ""));
            binding.setBoundAt(System.currentTimeMillis());
            binding.setStatus(AgentConstants.BINDING_STATUS_ACTIVE);
            require(bindingDao.insert(binding) == 1 && binding.getId() != null, "Hosted binding insert failed");
            AgentIdentityRegistryEntity identity = identityService.provisionOpaqueIdentity(binding,
                    "PWA-HOSTED-P0 binding: " + persona.getPersonaCode());
            agent = createRuntime(binding, identity.getCanonicalAgentId(), persona);
        } else {
            requireExactBinding(scope, binding);
            AgentIdentityRegistryEntity identity = identityService.requireRegistrationIdentityInScope(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), binding.getAgentId());
            identityService.requireActiveBinding(identity, null);
            hosted = hostedDao.findExactForUpdate(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), binding.getId());
            if (hosted == null) {
                fail(AgentErrorConstants.AGENT_ERROR,
                        "Legacy binding has no durable hosted profile; explicit migration is required");
            }
            requireExactHosted(scope, binding, hosted);
            String checkpoint = requireEffectiveCheckpoint(hosted);
            AgentHostedGeneration.requireAdvanceable(checkpoint, hosted.getGeneration());
            agent = existingRuntime(binding, identity.getCanonicalAgentId(), persona);
            return prepared(hosted, agent, persona, requireApiKey(hosted));
        }
        hosted = hostedDao.findExactForUpdate(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), binding.getId());
        if (hosted != null) {
            requireExactHosted(scope, binding, hosted);
            return prepared(hosted, agent, persona, requireApiKey(hosted));
        }
        ApiKeyService apiKeys = requireApiKeyService();
        OauthApiKeyEntity key = new OauthApiKeyEntity();
        key.setClientId(scope.clientId());
        key.setJiacn(scope.ownerJiacn());
        key.setApiKey("cdx_" + UUID.randomUUID().toString().replace("-", ""));
        String profileKey = scopeDigest(scope) + binding.getId();
        key.setKeyName("hosted-binding-" + binding.getId());
        key.setStatus(1);
        key.setDescription("Dedicated hosted Agent key for profile " + profileKey);
        key = apiKeys.create(key);
        require(key != null && !StringUtil.isBlank(key.getId()) && !StringUtil.isBlank(key.getApiKey()),
                "Dedicated hosted API key creation failed");
        hosted = new AgentHostedProfileEntity();
        hosted.setTenantId(scope.tenantId());
        hosted.setClientId(scope.clientId());
        hosted.setOwnerJiacn(scope.ownerJiacn());
        hosted.setBindingId(binding.getId());
        hosted.setCanonicalAgentId(agent.getAgentId());
        hosted.setPersonaCode(persona.getPersonaCode());
        hosted.setProfileKey(profileKey);
        hosted.setApiKeyId(key.getId());
        hosted.setLifecycleState(AgentHostedProfileState.PREPARED);
        hosted.setGeneration(0L);
        hosted.setDesiredEnabled(true);
        require(hostedDao.insert(hosted) == 1, "Hosted durable row insert failed");
        return prepared(hosted, agent, persona, key.getApiKey());
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentHostedProfileEntity transition(Scope scope, long bindingId, String expectedState,
            long expectedGeneration, String nextState, long nextGeneration, boolean desiredEnabled) {
        scope.requireLegacyHostedTenant();
        AgentHostedGeneration.requireTransition(expectedState, expectedGeneration,
                nextState, nextGeneration);
        AgentPersonaBindingEntity binding = bindingDao.findByIdForUpdate(bindingId);
        if (binding == null) fail(AgentErrorConstants.AGENT_FORBIDDEN, "Hosted binding not found");
        requireExactBinding(scope, binding);
        AgentHostedProfileEntity hosted = hostedDao.findExactForUpdate(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), bindingId);
        if (hosted == null) fail(AgentErrorConstants.AGENT_FORBIDDEN, "Hosted binding not found");
        requireExactHosted(scope, binding, hosted);
        require(hostedDao.transition(hosted.getId(), expectedState, expectedGeneration,
                nextState, nextGeneration, desiredEnabled) == 1, "Hosted state/generation conflict");
        hosted.setLifecycleState(nextState);
        hosted.setResumeState(null);
        hosted.setGeneration(nextGeneration);
        hosted.setDesiredEnabled(desiredEnabled);
        hosted.setLastError(null);
        return hosted;
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRepair(Scope scope, long bindingId, String expectedState,
            String expectedResumeState, long expectedGeneration, String resumeState,
            RuntimeException failure) {
        scope.requireLegacyHostedTenant();
        AgentHostedGeneration.requireAdvanceable(resumeState, expectedGeneration);
        AgentPersonaBindingEntity binding = bindingDao.findByIdForUpdate(bindingId);
        if (binding == null) return;
        requireExactBinding(scope, binding);
        AgentHostedProfileEntity hosted = hostedDao.findExactForUpdate(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), bindingId);
        if (hosted == null) return;
        requireExactHosted(scope, binding, hosted);
        String type = failure == null ? "RuntimeException" : failure.getClass().getSimpleName();
        if (type == null || type.isEmpty()) type = "RuntimeException";
        type = type.replaceAll("[^A-Za-z0-9_$]", "_");
        if (type.length() > 160) type = type.substring(0, 160);
        hostedDao.markRepair(hosted.getId(), expectedState, expectedResumeState,
                expectedGeneration, resumeState, "HOSTED_PROFILE_FAILURE:" + type);
    }

    private String requireEffectiveCheckpoint(AgentHostedProfileEntity hosted) {
        String checkpoint = AgentHostedProfileState.REPAIR_REQUIRED.equals(hosted.getLifecycleState())
                ? hosted.getResumeState() : hosted.getLifecycleState();
        if (checkpoint == null || !AgentHostedProfileState.VALUES.contains(checkpoint)
                || AgentHostedProfileState.REPAIR_REQUIRED.equals(checkpoint)) {
            fail(AgentErrorConstants.AGENT_ERROR, "Hosted lifecycle checkpoint is invalid");
        }
        return checkpoint;
    }

    private Prepared prepared(AgentHostedProfileEntity h, AgentRuntimeDTO a, AgentPersonaEntity p, String key) {
        return new Prepared(h, a, p, key);
    }
    private OauthApiKeyEntity exactKey(AgentHostedProfileEntity hosted) {
        OauthApiKeyEntity key = requireApiKeyService().get(hosted.getApiKeyId());
        require(key != null && Objects.equals(key.getId(), hosted.getApiKeyId())
                && Objects.equals(key.getClientId(), hosted.getClientId())
                && Objects.equals(key.getJiacn(), hosted.getOwnerJiacn()), "Hosted API key scope mismatch");
        return key;
    }
    private String requireApiKey(AgentHostedProfileEntity hosted) {
        OauthApiKeyEntity key = exactKey(hosted);
        require((key.getStatus() == null || key.getStatus() == 1) && !StringUtil.isBlank(key.getApiKey()),
                "Dedicated hosted API key is disabled or missing");
        return key.getApiKey();
    }
    private String requireApiKeyMaterial(AgentHostedProfileEntity hosted) {
        OauthApiKeyEntity key = exactKey(hosted);
        require(!StringUtil.isBlank(key.getApiKey()), "Dedicated hosted API key material is missing");
        return key.getApiKey();
    }
    private ApiKeyService requireApiKeyService() {
        ApiKeyService service = apiKeyProvider.getIfAvailable();
        if (service == null) fail(AgentErrorConstants.AGENT_ERROR, "ApiKeyService is unavailable");
        return service;
    }
    private AgentPersonaEntity requirePersona(Scope scope, String code) {
        AgentPersonaEntity p = personaDao.findCatalogPersona(scope.tenantId(), scope.clientId(), code);
        if (p == null) {
            p = cn.jia.agent.common.WaterMarginPersonaCatalog.publicPersona(code);
        }
        if (p == null) fail(AgentErrorConstants.AGENT_NOT_FOUND, "Persona not found");
        return p;
    }
    private void requireExactBinding(Scope s, AgentPersonaBindingEntity b) {
        require(Objects.equals(s.tenantId(), b.getTenantId()) && Objects.equals(s.clientId(), b.getClientId())
                && Objects.equals(s.ownerJiacn(), b.getJiacn()), "Binding owner scope mismatch");
    }
    private void requireExactHosted(Scope s, AgentPersonaBindingEntity b, AgentHostedProfileEntity h) {
        require(b != null && Objects.equals(b.getId(), h.getBindingId())
                && Objects.equals(b.getAgentId(), h.getCanonicalAgentId())
                && Objects.equals(b.getPersonaCode(), h.getPersonaCode())
                && Objects.equals(s.tenantId(), h.getTenantId())
                && Objects.equals(s.clientId(), h.getClientId())
                && Objects.equals(s.ownerJiacn(), h.getOwnerJiacn())
                && Objects.equals(scopeDigest(s) + b.getId(), h.getProfileKey()), "Hosted durable identity mismatch");
    }
    private AgentRuntimeDTO existingRuntime(AgentPersonaBindingEntity b, String canonical, AgentPersonaEntity p) {
        AgentRuntimeEntity runtime = runtimeDao.findByAgentId(canonical);
        return runtime == null ? createRuntime(b, canonical, p) : toDto(runtime);
    }
    private AgentRuntimeDTO createRuntime(AgentPersonaBindingEntity b, String canonical, AgentPersonaEntity p) {
        AgentRuntimeEntity r = new AgentRuntimeEntity();
        r.setAgentId(canonical); r.setName(p.getName()); r.setAvatar(p.getAvatar());
        r.setPersonaCode(p.getPersonaCode()); r.setPersonaName(p.getName());
        r.setOwnerJiacn(b.getJiacn()); r.setBindingId(b.getId()); r.setClientId(b.getClientId());
        r.setAbilities(p.getAbilities()); r.setStatus(AgentConstants.STATUS_OFFLINE);
        r.setLastSeenAt(System.currentTimeMillis());
        require(runtimeDao.insert(r) == 1, "Hosted runtime insert failed");
        return toDto(r);
    }
    private AgentRuntimeDTO toDto(AgentRuntimeEntity runtime) {
        AgentRuntimeDTO dto = new AgentRuntimeDTO();
        dto.setAgentId(runtime.getAgentId());
        dto.setName(runtime.getName());
        dto.setAvatar(runtime.getAvatar());
        dto.setOwnerJiacn(runtime.getOwnerJiacn());
        dto.setPersonaCode(runtime.getPersonaCode());
        dto.setPersonaName(runtime.getPersonaName());
        dto.setAbilities(parseAbilities(runtime.getAbilities()));
        dto.setStatus(runtime.getStatus());
        dto.setEndpoint(runtime.getEndpoint());
        dto.setCurrentTaskId(runtime.getCurrentTaskId());
        dto.setCurrentTaskTitle(runtime.getCurrentTaskTitle());
        dto.setLastSeenAt(runtime.getLastSeenAt());
        dto.setErrorMessage(runtime.getErrorMessage());
        return dto;
    }

    private List<String> parseAbilities(String value) {
        if (StringUtil.isBlank(value) || "[]".equals(value.trim())) return Collections.emptyList();
        List<String> parsed = JsonUtil.jsonToList(value, String.class);
        if (!parsed.isEmpty()) return parsed;
        return Arrays.stream(value.split(",")).map(String::trim)
                .filter(item -> !item.isEmpty()).toList();
    }
    static String scopeDigest(Scope s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((s.tenantId()+"\0"+s.clientId()+"\0"+s.ownerJiacn())
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private void require(boolean ok, String message) { if (!ok) fail(AgentErrorConstants.AGENT_ERROR, message); }
    private void fail(String code, String message) { throw new AgentBizException(code, message); }

    public record Scope(String tenantId, String clientId, String ownerJiacn) {
        public Scope {
            if (!validExact(tenantId) || !validExact(clientId) || !validExact(ownerJiacn)
                    || "0".equals(ownerJiacn)) {
                throw new IllegalArgumentException("exact hosted scope is required");
            }
        }
        public void requireLegacyHostedTenant() {
            if (!"0".equals(tenantId)) {
                throw new IllegalArgumentException("hosted profile scope requires tenant 0");
            }
        }
        private static boolean validExact(String value) {
            if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > 50
                    || isPadding(value.codePointAt(0)) || isPadding(value.codePointBefore(value.length()))
                    || value.codePoints().anyMatch(Character::isISOControl)) return false;
            for (int index = 0; index < value.length(); index++) {
                char unit = value.charAt(index);
                if (Character.isHighSurrogate(unit)) {
                    if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
                } else if (Character.isLowSurrogate(unit)) return false;
            }
            return true;
        }
        private static boolean isPadding(int codePoint) {
            return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
        }
    }
    public record Prepared(AgentHostedProfileEntity hosted, AgentRuntimeDTO agent,
            AgentPersonaEntity persona, String apiKey) { }
}
