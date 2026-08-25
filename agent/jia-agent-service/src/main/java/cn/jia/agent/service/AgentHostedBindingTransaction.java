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
import cn.jia.core.util.StringUtil;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
        AgentPersonaEntity persona = requirePersona(personaCode);
        if (Boolean.TRUE.equals(persona.getSystemAgent())) fail(AgentErrorConstants.PERSONA_NOT_BINDABLE, "System persona cannot be bound");
        AgentPersonaBindingEntity binding = bindingDao.findExactActiveByScopeAndPersonaForUpdate(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), persona.getPersonaCode());
        boolean createdBinding = binding == null;
        AgentRuntimeDTO agent;
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
            agent = existingRuntime(binding, identity.getCanonicalAgentId(), persona);
        }
        AgentHostedProfileEntity hosted = hostedDao.findExactForUpdate(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), binding.getId());
        if (hosted != null) {
            requireExactHosted(scope, binding, hosted);
            return prepared(hosted, agent, persona, requireApiKey(hosted));
        }
        // Existing bindings without a durable hosted row are ambiguous legacy state and are not adopted.
        if (!createdBinding) {
            fail(AgentErrorConstants.AGENT_ERROR, "Legacy binding has no durable hosted profile; explicit migration is required");
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
    public Prepared resumeRepair(Scope scope, long bindingId) {
        AgentHostedProfileEntity hosted = hostedDao.findExactForUpdate(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), bindingId);
        if (hosted == null) fail(AgentErrorConstants.AGENT_FORBIDDEN, "Hosted binding not found");
        if (AgentHostedProfileState.REPAIR_REQUIRED.equals(hosted.getLifecycleState())) {
            String resume = hosted.getResumeState();
            if (resume == null || !AgentHostedProfileState.VALUES.contains(resume)
                    || AgentHostedProfileState.REPAIR_REQUIRED.equals(resume)) {
                fail(AgentErrorConstants.AGENT_ERROR, "Hosted repair checkpoint is invalid");
            }
            require(hostedDao.transition(hosted.getId(), AgentHostedProfileState.REPAIR_REQUIRED,
                    hosted.getGeneration(), resume, hosted.getGeneration(), hosted.getDesiredEnabled()) == 1,
                    "Hosted repair checkpoint changed concurrently");
            hosted.setLifecycleState(resume);
            hosted.setResumeState(null);
        }
        AgentPersonaBindingEntity binding = bindingDao.findByIdForUpdate(bindingId);
        requireExactHosted(scope, binding, hosted);
        AgentRuntimeEntity runtime = runtimeDao.findByAgentId(hosted.getCanonicalAgentId());
        boolean suspensionCheckpoint = AgentHostedProfileState.SUSPENDING.equals(hosted.getLifecycleState())
                || AgentHostedProfileState.SUSPENDED.equals(hosted.getLifecycleState());
        require(runtime != null || suspensionCheckpoint, "Hosted runtime projection is missing");
        AgentRuntimeDTO runtimeDto = runtime == null ? runtimePlaceholder(hosted.getCanonicalAgentId()) : toDto(runtime);
        String apiKey = AgentHostedProfileState.SUSPENDED.equals(hosted.getLifecycleState())
                ? null : requireApiKeyMaterial(hosted);
        return prepared(hosted, runtimeDto, requirePersona(hosted.getPersonaCode()), apiKey);
    }

    @Transactional(rollbackFor = Exception.class)
    public Prepared prepareUnbind(Scope scope, String personaCode) {
        AgentPersonaEntity persona = requirePersona(personaCode);
        AgentPersonaBindingEntity selected = bindingDao.findExactActiveByScopeAndPersonaForUpdate(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), persona.getPersonaCode());
        if (selected == null) fail(AgentErrorConstants.AGENT_FORBIDDEN, "Persona is not bound to current owner");
        AgentPersonaBindingEntity binding = bindingDao.findByIdForUpdate(selected.getId());
        requireExactBinding(scope, binding);
        AgentIdentityRegistryEntity identity = identityService.requireRegistrationIdentityInScope(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), binding.getAgentId());
        AgentHostedProfileEntity hosted = hostedDao.findExactForUpdate(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), binding.getId());
        AgentRuntimeEntity runtime = runtimeDao.findByAgentId(identity.getCanonicalAgentId());
        if (hosted == null) {
            suspendDatabase(scope, binding, identity, runtime, null);
            return null;
        }
        requireExactHosted(scope, binding, hosted);
        if (AgentHostedProfileState.REPAIR_REQUIRED.equals(hosted.getLifecycleState())
                && AgentHostedProfileState.SUSPENDING.equals(hosted.getResumeState())) {
            require(hostedDao.transition(hosted.getId(), AgentHostedProfileState.REPAIR_REQUIRED,
                    hosted.getGeneration(), AgentHostedProfileState.SUSPENDING,
                    hosted.getGeneration(), false) == 1, "Hosted unbind repair changed concurrently");
            hosted.setLifecycleState(AgentHostedProfileState.SUSPENDING);
            hosted.setDesiredEnabled(false);
        } else if (AgentHostedProfileState.ACTIVE.equals(hosted.getLifecycleState())) {
            require(hostedDao.transition(hosted.getId(), AgentHostedProfileState.ACTIVE,
                    hosted.getGeneration(), AgentHostedProfileState.SUSPENDING,
                    hosted.getGeneration(), false) == 1, "Hosted unbind state changed concurrently");
            hosted.setLifecycleState(AgentHostedProfileState.SUSPENDING);
            hosted.setDesiredEnabled(false);
        } else if (!AgentHostedProfileState.SUSPENDING.equals(hosted.getLifecycleState())) {
            fail(AgentErrorConstants.AGENT_ERROR, "Hosted profile cannot unbind from state " + hosted.getLifecycleState());
        }
        return prepared(hosted, runtime == null ? runtimePlaceholder(hosted.getCanonicalAgentId()) : toDto(runtime), persona, requireApiKeyMaterial(hosted));
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentHostedProfileEntity completeUnbind(Scope scope, long bindingId, long expectedGeneration, long fileGeneration) {
        AgentPersonaBindingEntity binding = bindingDao.findByIdForUpdate(bindingId);
        if (binding == null) fail(AgentErrorConstants.AGENT_FORBIDDEN, "Binding not found");
        requireExactBinding(scope, binding);
        AgentIdentityRegistryEntity identity = identityService.requireRegistrationIdentityInScope(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), binding.getAgentId());
        AgentHostedProfileEntity hosted = hostedDao.findExactForUpdate(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), bindingId);
        requireExactHosted(scope, binding, hosted);
        require(AgentHostedProfileState.SUSPENDING.equals(hosted.getLifecycleState())
                && Objects.equals(hosted.getGeneration(), expectedGeneration), "Hosted unbind generation changed");
        OauthApiKeyEntity key = exactKey(hosted);
        if (key.getStatus() == null || key.getStatus() != 0) {
            key.setStatus(0);
            require(requireApiKeyService().update(key) != null, "Dedicated hosted API key disable failed");
        }
        suspendDatabase(scope, binding, identity, runtimeDao.findByAgentIdForUpdate(identity.getCanonicalAgentId()), hosted);
        require(hostedDao.transition(hosted.getId(), AgentHostedProfileState.SUSPENDING,
                expectedGeneration, AgentHostedProfileState.SUSPENDED, fileGeneration, false) == 1,
                "Hosted suspend state changed concurrently");
        hosted.setLifecycleState(AgentHostedProfileState.SUSPENDED);
        hosted.setGeneration(fileGeneration);
        hosted.setDesiredEnabled(false);
        hosted.setResumeState(null);
        return hosted;
    }

    private void suspendDatabase(Scope scope, AgentPersonaBindingEntity binding,
            AgentIdentityRegistryEntity identity, AgentRuntimeEntity runtime, AgentHostedProfileEntity hosted) {
        binding.setStatus(AgentConstants.BINDING_STATUS_SUSPENDED);
        require(bindingDao.updateById(binding) == 1, "Binding suspension failed");
        identityService.suspendForBinding(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), binding.getId());
        if (runtime != null) {
            require(Objects.equals(runtime.getAgentId(), identity.getCanonicalAgentId())
                    && Objects.equals(runtime.getBindingId(), binding.getId())
                    && Objects.equals(runtime.getClientId(), scope.clientId())
                    && Objects.equals(runtime.getOwnerJiacn(), scope.ownerJiacn()), "Runtime scope mismatch during unbind");
            runtime.setStatus(AgentConstants.STATUS_OFFLINE);
            runtime.setLastSeenAt(System.currentTimeMillis());
            require(runtimeDao.updateById(runtime) == 1, "Runtime suspension failed");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentHostedProfileEntity transition(Scope scope, long bindingId, String expectedState,
            long expectedGeneration, String nextState, long nextGeneration, boolean desiredEnabled) {
        AgentHostedProfileEntity hosted = hostedDao.findExactForUpdate(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), bindingId);
        if (hosted == null) fail(AgentErrorConstants.AGENT_FORBIDDEN, "Hosted binding not found");
        require(hostedDao.transition(hosted.getId(), expectedState, expectedGeneration,
                nextState, nextGeneration, desiredEnabled) == 1, "Hosted state/generation conflict");
        hosted.setLifecycleState(nextState);
        hosted.setGeneration(nextGeneration);
        hosted.setDesiredEnabled(desiredEnabled);
        return hosted;
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRepair(Scope scope, long bindingId, String resumeState, RuntimeException failure) {
        AgentHostedProfileEntity hosted = hostedDao.findExactForUpdate(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), bindingId);
        if (hosted != null) {
            String type = failure == null ? "RuntimeException" : failure.getClass().getSimpleName();
            if (type == null || type.isEmpty()) type = "RuntimeException";
            type = type.replaceAll("[^A-Za-z0-9_$]", "_");
            if (type.length() > 160) type = type.substring(0, 160);
            hostedDao.markRepair(hosted.getId(), resumeState, "HOSTED_PROFILE_FAILURE:" + type);
        }
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
    private AgentPersonaEntity requirePersona(String code) {
        AgentPersonaEntity p = personaDao.findByCode(code);
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
    private AgentRuntimeDTO runtimePlaceholder(String canonicalAgentId) {
        AgentRuntimeDTO dto = new AgentRuntimeDTO();
        dto.setAgentId(canonicalAgentId);
        dto.setStatus(AgentConstants.STATUS_OFFLINE);
        return dto;
    }
    private AgentRuntimeDTO toDto(AgentRuntimeEntity r) {
        AgentRuntimeDTO d = new AgentRuntimeDTO();
        d.setAgentId(r.getAgentId()); d.setName(r.getName()); d.setAvatar(r.getAvatar());
        d.setOwnerJiacn(r.getOwnerJiacn()); d.setPersonaCode(r.getPersonaCode());
        d.setPersonaName(r.getPersonaName()); d.setStatus(r.getStatus()); d.setLastSeenAt(r.getLastSeenAt());
        return d;
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
                    || "0".equals(ownerJiacn) || !Objects.equals(tenantId, ownerJiacn)) {
                throw new IllegalArgumentException("exact hosted scope is required");
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
