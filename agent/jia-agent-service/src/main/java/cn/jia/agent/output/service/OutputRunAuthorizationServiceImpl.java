package cn.jia.agent.output.service;

import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputRunRequest;
import cn.jia.agent.output.OutputSourceAccessMode;
import cn.jia.agent.output.OutputSourceAuthorization;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.dao.OutputAccessTicketDao;
import cn.jia.agent.output.dao.OutputRunBindingDao;
import cn.jia.agent.output.dao.OutputSourceBindingDao;
import cn.jia.agent.output.dto.OutputAuthReceiptDTO;
import cn.jia.agent.output.dto.OutputContextDTO;
import cn.jia.agent.output.dto.OutputSourceDTO;
import cn.jia.agent.output.entity.OutputAccessTicketEntity;
import cn.jia.agent.output.entity.OutputRunBindingEntity;
import cn.jia.agent.output.entity.OutputSourceBindingEntity;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.impl.AgentServiceImpl;
import cn.jia.core.util.JsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class OutputRunAuthorizationServiceImpl implements OutputRunAuthorizationService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OutputSourceAuthorizerRegistry sourceRegistry;
    private final OutputSourceBindingDao sourceDao;
    private final OutputRunBindingDao runDao;
    private final OutputAccessTicketDao ticketDao;
    private final AgentRuntimeDao runtimeDao;
    private final AgentIdentityService identityService;
    private final boolean enabled;

    public OutputRunAuthorizationServiceImpl(
            OutputSourceAuthorizerRegistry sourceRegistry,
            OutputSourceBindingDao sourceDao,
            OutputRunBindingDao runDao,
            OutputAccessTicketDao ticketDao,
            AgentRuntimeDao runtimeDao,
            AgentIdentityService identityService,
            @Value("${agent.output-delivery.enabled:false}") boolean enabled) {
        this.sourceRegistry = sourceRegistry;
        this.sourceDao = sourceDao;
        this.runDao = runDao;
        this.ticketDao = ticketDao;
        this.runtimeDao = runtimeDao;
        this.identityService = identityService;
        this.enabled = enabled;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<OutputContextDTO> createOrRecoverRun(OutputRunRequest request) {
        if (!enabled) return Optional.empty();
        validateRequest(request);
        return Optional.ofNullable(createOrRecoverRuns(
                List.of(request), List.of(request.producerAgentId()))
                .get(request.producerAgentId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, OutputContextDTO> createOrRecoverRuns(
            List<OutputRunRequest> requests, List<String> identityAgentIds) {
        if (!enabled) return Map.of();
        BatchRequest batch = validateBatch(requests, identityAgentIds);
        long now = System.currentTimeMillis();

        Map<OutputRunRequest, OutputSourceAuthorization> authorizations = new LinkedHashMap<>();
        for (OutputRunRequest request : batch.requests()) {
            authorizations.put(request, sourceRegistry.lockAndAuthorize(
                    request.tenantId(), request.clientId(), request.sourceType(),
                    request.sourceId(), request.producerAgentId()));
        }

        Map<SourceKey, OutputSourceBindingEntity> sources = new LinkedHashMap<>();
        for (OutputRunRequest request : batch.requests()) {
            SourceKey key = new SourceKey(request.tenantId(), request.clientId(),
                    request.sourceType(), request.sourceId());
            sources.computeIfAbsent(key,
                    ignored -> lockOrCreateSource(authorizations.get(request), now));
        }

        Map<OutputRunRequest, OutputRunBindingEntity> priorRuns = new LinkedHashMap<>();
        for (OutputRunRequest request : batch.requests()) {
            OutputRunBindingEntity prior = runDao.findExactByOrigin(
                    request.tenantId(), request.clientId(), request.sourceType(), request.sourceId(),
                    request.producerAgentId(), request.originType(), request.originId());
            if (prior != null && (!OutputConstants.RUN_ACTIVE.equals(prior.getState())
                    || prior.getRecoveryUntil() == null || prior.getRecoveryUntil() < now)) {
                throw new OutputAuthorizationException("OUTPUT_RUN_REDISPATCH_REQUIRED",
                        "The original dispatch run is terminal, revoked, or outside recovery");
            }
            priorRuns.put(request, prior);
        }

        Map<String, AgentRuntimeEntity> runtimes = lockCurrentRuntimes(
                batch.tenantId(), batch.clientId(), batch.identityAgentIds());
        Map<String, OutputContextDTO> contexts = new LinkedHashMap<>();
        for (OutputRunRequest request : batch.requests()) {
            AgentRuntimeEntity runtime = runtimes.get(request.producerAgentId());
            boolean freshCapability = supportsCapabilitySnapshot(
                    runtime, null, System.currentTimeMillis(), true,
                    request.policyVersion() == 1);
            OutputRunBindingEntity prior = priorRuns.get(request);
            if (!freshCapability) {
                if (prior != null || request.policyVersion() > 0) {
                    throw denied("Agent output capability snapshot is unavailable for this dispatch");
                }
                continue;
            }
            OutputSourceBindingEntity source = sources.get(new SourceKey(
                    request.tenantId(), request.clientId(), request.sourceType(), request.sourceId()));
            if (prior != null) {
                requireRunRecoveryCurrent(prior, System.currentTimeMillis());
                requireRunMatches(prior, source, runtime, request);
                contexts.put(request.producerAgentId(), toContext(prior, runtime));
                continue;
            }
            long createdAt = System.currentTimeMillis();
            OutputRunBindingEntity run = new OutputRunBindingEntity();
            run.setTenantId(request.tenantId());
            run.setClientId(request.clientId());
            run.setRunId(UUID.randomUUID().toString().replace("-", ""));
            run.setSourceType(request.sourceType());
            run.setSourceId(request.sourceId());
            run.setProducerAgentId(request.producerAgentId());
            run.setBindingId(Long.toString(runtime.getBindingId()));
            run.setOriginalRuntimeId(runtime.getOutputCapabilitiesRuntimeId());
            run.setOriginType(request.originType());
            run.setOriginId(request.originId());
            run.setState(OutputConstants.RUN_ACTIVE);
            run.setPolicyVersion(request.policyVersion());
            run.setRecoveryUntil(Math.addExact(createdAt, OutputConstants.RUN_RECOVERY_MILLIS));
            run.setMaxBytes(OutputConstants.DEFAULT_MAX_RUN_BYTES);
            run.setMaxFiles(OutputConstants.DEFAULT_MAX_FILES);
            run.setWorkItemId(request.workItemId());
            run.setCreatedAt(createdAt);
            run.setUpdatedAt(createdAt);
            run.setRowVersion(0L);
            if (runDao.insert(run) != 1) throw denied("Output run could not be persisted");
            contexts.put(request.producerAgentId(), toContext(run, runtime));
        }
        return Map.copyOf(contexts);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String requireFreshDispatchRuntime(
            String tenantId, String clientId, String producerAgentId, String runId) {
        if (!enabled) throw denied("Output delivery is disabled");
        requireExact(tenantId, "tenantId", 200);
        requireExact(clientId, "clientId", 200);
        requireExact(producerAgentId, "producerAgentId", 400);
        requireRunId(runId);
        OutputRunBindingEntity projected = runDao.findExactByRun(
                tenantId, clientId, runId, false);
        if (projected == null || !producerAgentId.equals(projected.getProducerAgentId())) {
            throw denied("Output run is unavailable");
        }
        sourceRegistry.lockAndAuthorize(tenantId, clientId, projected.getSourceType(),
                projected.getSourceId(), producerAgentId, OutputSourceAccessMode.MUTATION);
        OutputSourceBindingEntity source = requireActiveSourceBinding(projected, true);
        OutputRunBindingEntity run = runDao.findExactByRun(tenantId, clientId, runId, true);
        long now = System.currentTimeMillis();
        requireSameRunRoute(run, projected);
        requireSameSource(run, source);
        requireRunRecoveryCurrent(run, now);
        if (!OutputConstants.RUN_ACTIVE.equals(run.getState())) {
            throw denied("Output run is unavailable for dispatch");
        }
        AgentRuntimeEntity runtime = lockCurrentRuntimes(
                tenantId, clientId, List.of(producerAgentId)).get(producerAgentId);
        if (!Objects.equals(Long.toString(runtime.getBindingId()), run.getBindingId())) {
            throw denied("Output binding has changed");
        }
        now = System.currentTimeMillis();
        requireRunRecoveryCurrent(run, now);
        if (!supportsCapabilitySnapshot(runtime, null, now, true,
                Objects.equals(run.getPolicyVersion(), 1))) {
            throw new OutputAuthorizationException("OUTPUT_DISPATCH_RUNTIME_UNAVAILABLE",
                    "The current output runtime is unavailable for dispatch");
        }
        return runtime.getOutputCapabilitiesRuntimeId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutputAuthReceiptDTO issueTicket(
            String tenantId, String clientId, String producerAgentId,
            String runtimeInstanceId, String messageId, String runId) {
        if (!enabled) throw denied("Output delivery is disabled");
        requireExact(tenantId, "tenantId", 200);
        requireExact(clientId, "clientId", 200);
        requireExact(producerAgentId, "producerAgentId", 400);
        requireExact(runtimeInstanceId, "runtimeInstanceId", 400);
        requireExact(messageId, "messageId", 100);
        requireRunId(runId);
        long now = System.currentTimeMillis();
        OutputRunBindingEntity projected = runDao.findExactByRun(
                tenantId, clientId, runId, false);
        if (projected == null) throw denied("Output run is unavailable");
        OutputSourceAccessMode sourceAccessMode = isTerminal(projected.getState())
                ? OutputSourceAccessMode.RECEIPT_READ : OutputSourceAccessMode.MUTATION;
        OutputSourceAuthorization sourceAuthorization = sourceRegistry.lockAndAuthorize(
                tenantId, clientId, projected.getSourceType(), projected.getSourceId(),
                producerAgentId, sourceAccessMode);
        OutputSourceBindingEntity source = sourceDao.findExact(
                tenantId, clientId, projected.getSourceType(), projected.getSourceId(), true);
        if (source == null || !OutputConstants.OWNERSHIP_ACTIVE.equals(source.getOwnershipState())
                || !Objects.equals(tenantId, source.getOwnerJiacn())) {
            throw denied("Output source is unavailable");
        }
        OutputRunBindingEntity run = runDao.findExactByRun(
                tenantId, clientId, runId, true);
        now = System.currentTimeMillis();
        requireSameRunRoute(run, projected);
        requireSameSource(run, source);
        AgentRuntimeEntity runtime = requireCurrentRuntime(
                tenantId, clientId, producerAgentId, runtimeInstanceId);
        if (!Objects.equals(Long.toString(runtime.getBindingId()), run.getBindingId())) {
            throw denied("Output binding has changed");
        }
        long rateWindowNow = System.currentTimeMillis();
        if (ticketDao.lockRecentHashesForBinding(
                tenantId, clientId, run.getBindingId(), rateWindowNow - 60_000L).size()
                >= OutputConstants.AUTH_REQUESTS_PER_MINUTE) {
            throw new OutputAuthorizationException("OUTPUT_AUTH_RATE_LIMITED",
                    "Output ticket request rate exceeded");
        }
        now = System.currentTimeMillis();
        requireRunRecoveryCurrent(run, now);
        requireCapabilitySnapshot(runtime, runtimeInstanceId, now, false, false);
        List<String> ticketOperations = requireTicketableRun(
                run, sourceAuthorization, producerAgentId, runtime, now);

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String bearer = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        OutputAccessTicketEntity ticket = new OutputAccessTicketEntity();
        ticket.setTenantId(tenantId);
        ticket.setClientId(clientId);
        ticket.setTicketHash(sha256(bearer));
        ticket.setRunId(runId);
        ticket.setBindingId(run.getBindingId());
        ticket.setIssuedRuntimeId(runtimeInstanceId);
        ticket.setOperationsJson(JsonUtil.toJson(ticketOperations));
        ticket.setExpiresAt(Math.addExact(now, OutputConstants.TICKET_TTL_MILLIS));
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        ticket.setRowVersion(0L);
        if (ticketDao.insert(ticket) != 1) throw denied("Output ticket could not be persisted");
        return new OutputAuthReceiptDTO(messageId, runId, bearer,
                Long.toString(ticket.getExpiresAt()), ticketOperations);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutputTicketAuthorization authorizeTicket(
            String rawBearer, String requiredOperation, boolean receiptReplay) {
        if (!enabled) throw denied("Output delivery is disabled");
        if (rawBearer == null || rawBearer.length() < 40 || rawBearer.length() > 100) {
            throw unauthorized("Output ticket is invalid");
        }
        requireExact(requiredOperation, "requiredOperation", 32);
        byte[] ticketHash = sha256(rawBearer);
        long now = System.currentTimeMillis();
        OutputAccessTicketEntity projectedTicket = ticketDao.findByHash(ticketHash, false);
        List<String> projectedOperations = requireUsableTicket(
                projectedTicket, requiredOperation, now);
        OutputRunBindingEntity projectedRun = runDao.findExactByRun(
                projectedTicket.getTenantId(), projectedTicket.getClientId(),
                projectedTicket.getRunId(), false);
        requireTicketRunRoute(projectedTicket, projectedRun);
        OutputSourceAccessMode sourceAccessMode = receiptReplay
                && OutputConstants.OP_STATUS.equals(requiredOperation)
                && isTerminal(projectedRun.getState())
                ? OutputSourceAccessMode.RECEIPT_READ : OutputSourceAccessMode.MUTATION;
        OutputSourceAuthorization sourceAuthorization = sourceRegistry.lockAndAuthorize(
                projectedTicket.getTenantId(), projectedTicket.getClientId(),
                projectedRun.getSourceType(), projectedRun.getSourceId(),
                projectedRun.getProducerAgentId(), sourceAccessMode);
        OutputSourceBindingEntity source = requireActiveSourceBinding(projectedRun, true);
        OutputRunBindingEntity run = runDao.findExactByRun(
                projectedTicket.getTenantId(), projectedTicket.getClientId(),
                projectedTicket.getRunId(), true);
        now = System.currentTimeMillis();
        requireSameRunRoute(run, projectedRun);
        requireSameSource(run, source);
        requireAuthorizedRun(run, projectedTicket, sourceAuthorization,
                requiredOperation, receiptReplay, now);
        AgentRuntimeEntity runtime = requireActiveBinding(
                projectedTicket.getTenantId(), projectedTicket.getClientId(),
                run.getProducerAgentId());
        if (!Objects.equals(Long.toString(runtime.getBindingId()), run.getBindingId())) {
            throw denied("Output binding has changed");
        }
        OutputAccessTicketEntity ticket = ticketDao.findByHash(ticketHash, true);
        now = System.currentTimeMillis();
        requireSameTicketRoute(ticket, projectedTicket, projectedOperations);
        List<String> operations = requireUsableTicket(ticket, requiredOperation, now);
        requireAuthorizedRun(run, ticket, sourceAuthorization,
                requiredOperation, receiptReplay, now);
        boolean currentRuntimeTicket = Objects.equals(
                runtime.getOutputCapabilitiesRuntimeId(), ticket.getIssuedRuntimeId())
                && Objects.equals(run.getOriginalRuntimeId(), ticket.getIssuedRuntimeId());
        if (OutputConstants.OP_LEASE.equals(requiredOperation)
                || OutputConstants.OP_SUBMIT.equals(requiredOperation)) {
            if (!Objects.equals(run.getPolicyVersion(), 1)
                    || !OutputConstants.SOURCE_TASK.equals(run.getSourceType())
                    || run.getWorkItemId() == null || !currentRuntimeTicket
                    || !supportsCapabilitySnapshot(runtime, ticket.getIssuedRuntimeId(),
                            now, true, true)) {
                throw denied("Output ticket does not authorize the current work runtime");
            }
        }
        return new OutputTicketAuthorization(
                ticket.getTenantId(), ticket.getClientId(), run.getRunId(),
                run.getSourceType(), run.getSourceId(), run.getProducerAgentId(),
                run.getBindingId(), ticket.getIssuedRuntimeId(), currentRuntimeTicket,
                operations, ticket.getExpiresAt(), run.getState(),
                run.getPolicyVersion(), run.getWorkItemId(), run.getRecoveryUntil());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean authorizePersistedMutation(
            String tenantId, String clientId, String runId, String bindingId) {
        try {
            if (!enabled) throw denied("Output delivery is disabled");
            requireExact(tenantId, "tenantId", 200);
            requireExact(clientId, "clientId", 200);
            requireRunId(runId);
            requireExact(bindingId, "bindingId", 400);
            OutputRunBindingEntity projected = runDao.findExactByRun(
                    tenantId, clientId, runId, false);
            if (projected == null || !Objects.equals(bindingId, projected.getBindingId())) {
                throw denied("Output run is unavailable");
            }
            OutputSourceAuthorization sourceAuthorization = sourceRegistry.lockAndAuthorize(
                    tenantId, clientId, projected.getSourceType(), projected.getSourceId(),
                    projected.getProducerAgentId(), OutputSourceAccessMode.MUTATION);
            OutputSourceBindingEntity source = requireActiveSourceBinding(projected, true);
            OutputRunBindingEntity run = runDao.findExactByRun(tenantId, clientId, runId, true);
            long now = System.currentTimeMillis();
            requireSameRunRoute(run, projected);
            requireSameSource(run, source);
            if (!Objects.equals(bindingId, run.getBindingId())
                    || !OutputConstants.RUN_ACTIVE.equals(run.getState())
                    || run.getRecoveryUntil() == null || run.getRecoveryUntil() < now
                    || !sourceAuthorization.writable()) {
                throw denied("Output run no longer authorizes this mutation");
            }
            AgentRuntimeEntity runtime = lockCurrentRuntimeForPersistedAuthorization(
                    tenantId, clientId, run.getProducerAgentId(), bindingId);
            if (runtime == null) {
                throw denied("Output binding has changed");
            }
            if (run.getRecoveryUntil() == null
                    || run.getRecoveryUntil() < System.currentTimeMillis()) {
                throw denied("Output run recovery expired while authorization was locking");
            }
            return true;
        } catch (OutputAuthorizationException denied) {
            return false;
        }
    }

    private OutputSourceBindingEntity lockOrCreateSource(
            OutputSourceAuthorization authorization, long now) {
        OutputSourceBindingEntity existing = sourceDao.findExact(
                authorization.tenantId(), authorization.clientId(),
                authorization.sourceType(), authorization.sourceId(), true);
        if (existing == null) {
            OutputSourceBindingEntity created = new OutputSourceBindingEntity();
            created.setTenantId(authorization.tenantId());
            created.setClientId(authorization.clientId());
            created.setSourceType(authorization.sourceType());
            created.setSourceId(authorization.sourceId());
            created.setOwnerJiacn(authorization.ownerJiacn());
            created.setOwnershipState(OutputConstants.OWNERSHIP_ACTIVE);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            created.setRowVersion(0L);
            if (sourceDao.insert(created) != 1) throw denied("Output source could not be persisted");
            existing = sourceDao.findExact(authorization.tenantId(), authorization.clientId(),
                    authorization.sourceType(), authorization.sourceId(), true);
        }
        if (existing == null
                || !Objects.equals(authorization.ownerJiacn(), existing.getOwnerJiacn())
                || !OutputConstants.OWNERSHIP_ACTIVE.equals(existing.getOwnershipState())) {
            throw denied("Output source owner conflicts with persisted ownership");
        }
        return existing;
    }

    private AgentRuntimeEntity requireCurrentRuntime(
            String tenantId, String clientId, String agentId,
            String expectedRuntimeId) {
        AgentRuntimeEntity runtime = lockCurrentRuntimes(
                tenantId, clientId, List.of(agentId)).get(agentId);
        requireCapabilitySnapshot(runtime, expectedRuntimeId,
                System.currentTimeMillis(), false, false);
        return runtime;
    }

    private void requireCapabilitySnapshot(
            AgentRuntimeEntity runtime, String expectedRuntimeId, long now,
            boolean requireDispatchFreshness, boolean requireDeliveryCapability) {
        if (!supportsCapabilitySnapshot(
                runtime, expectedRuntimeId, now, requireDispatchFreshness,
                requireDeliveryCapability)) {
            throw denied("Agent output capability snapshot is absent, stale, or from another runtime");
        }
    }

    private boolean supportsCapabilitySnapshot(
            AgentRuntimeEntity runtime, String expectedRuntimeId, long now,
            boolean requireDispatchFreshness, boolean requireDeliveryCapability) {
        if (runtime == null
                || runtime.getOutputCapabilitiesJson() == null
                || runtime.getOutputCapabilitiesRuntimeId() == null
                || runtime.getOutputCapabilitiesUpdatedAt() == null
                || runtime.getOutputCapabilitiesUpdatedAt() > now
                || (requireDispatchFreshness && now - runtime.getOutputCapabilitiesUpdatedAt()
                        > OutputConstants.CAPABILITY_FRESHNESS_MILLIS)
                || (expectedRuntimeId != null
                        && !expectedRuntimeId.equals(runtime.getOutputCapabilitiesRuntimeId()))) {
            return false;
        }
        List<String> capabilities = JsonUtil.jsonToList(
                runtime.getOutputCapabilitiesJson(), String.class);
        return capabilities.contains(OutputConstants.CAPABILITY_HTTP_V1)
                && (!requireDeliveryCapability
                        || capabilities.contains(OutputConstants.CAPABILITY_DELIVERY_HTTP_V1));
    }

    private Map<String, AgentRuntimeEntity> lockCurrentRuntimes(
            String tenantId, String clientId, List<String> agentIds) {
        try {
            Map<String, AgentRuntimeEntity> previews = new LinkedHashMap<>();
            for (String agentId : agentIds) {
                AgentRuntimeEntity preview = runtimeDao.findExactOutputRuntime(
                        tenantId, clientId, tenantId, agentId, false);
                if (preview == null || preview.getBindingId() == null) {
                    throw denied("Agent runtime is outside the output scope");
                }
                previews.put(agentId, preview);
            }
            if (!agentIds.equals(identityService.lockActiveCanonicalAgentIdsInScope(
                    tenantId, clientId, tenantId, agentIds))) {
                throw denied("Agent binding is unavailable");
            }
            Map<String, AgentRuntimeEntity> runtimes = new LinkedHashMap<>();
            for (String agentId : agentIds) {
                AgentRuntimeEntity runtime = runtimeDao.findExactOutputRuntime(
                        tenantId, clientId, tenantId, agentId, true);
                if (runtime == null || runtime.getBindingId() == null
                        || !Objects.equals(tenantId, runtime.getTenantId())
                        || !Objects.equals(clientId, runtime.getClientId())
                        || !Objects.equals(tenantId, runtime.getOwnerJiacn())
                        || !Objects.equals(previews.get(agentId).getBindingId(), runtime.getBindingId())) {
                    throw denied("Agent runtime changed while output authorization was locking");
                }
                identityService.requireActiveIdentityForBinding(
                        tenantId, clientId, tenantId, runtime.getBindingId(), agentId);
                runtimes.put(agentId, runtime);
            }
            return runtimes;
        } catch (AgentServiceImpl.AgentBizException identityDenied) {
            if (AgentErrorConstants.AGENT_FORBIDDEN.equals(identityDenied.getCode())) {
                throw denied("Agent binding is unavailable");
            }
            throw identityDenied;
        }
    }

    private AgentRuntimeEntity requireActiveBinding(
            String tenantId, String clientId, String agentId) {
        return lockCurrentRuntimes(tenantId, clientId, List.of(agentId)).get(agentId);
    }

    private AgentRuntimeEntity lockCurrentRuntimeForPersistedAuthorization(
            String tenantId, String clientId, String agentId, String bindingId) {
        AgentRuntimeEntity preview = runtimeDao.findExactOutputRuntime(
                tenantId, clientId, tenantId, agentId, false);
        if (preview == null || preview.getBindingId() == null
                || !Objects.equals(bindingId, Long.toString(preview.getBindingId()))) {
            return null;
        }
        long expectedBinding;
        try {
            expectedBinding = Long.parseLong(bindingId);
        } catch (NumberFormatException invalid) {
            return null;
        }
        if (!identityService.lockCurrentActiveIdentityForAuthorization(
                tenantId, clientId, tenantId, expectedBinding, agentId)) {
            return null;
        }
        AgentRuntimeEntity runtime = runtimeDao.findExactOutputRuntime(
                tenantId, clientId, tenantId, agentId, true);
        if (runtime == null || runtime.getBindingId() == null
                || !Objects.equals(preview.getBindingId(), runtime.getBindingId())
                || !Objects.equals(bindingId, Long.toString(runtime.getBindingId()))
                || !Objects.equals(tenantId, runtime.getTenantId())
                || !Objects.equals(clientId, runtime.getClientId())
                || !Objects.equals(tenantId, runtime.getOwnerJiacn())) {
            return null;
        }
        return runtime;
    }

    private OutputSourceBindingEntity requireActiveSourceBinding(
            OutputRunBindingEntity run, boolean forUpdate) {
        if (run == null) throw denied("Output run is unavailable");
        OutputSourceBindingEntity binding = sourceDao.findExact(
                run.getTenantId(), run.getClientId(),
                run.getSourceType(), run.getSourceId(), forUpdate);
        if (binding == null
                || !OutputConstants.OWNERSHIP_ACTIVE.equals(binding.getOwnershipState())
                || !Objects.equals(run.getTenantId(), binding.getOwnerJiacn())) {
            throw denied("Output source binding is revoked or has conflicting ownership");
        }
        return binding;
    }

    private void requireSameSource(
            OutputRunBindingEntity run, OutputSourceBindingEntity binding) {
        if (!Objects.equals(run.getTenantId(), binding.getTenantId())
                || !Objects.equals(run.getClientId(), binding.getClientId())
                || !Objects.equals(run.getSourceType(), binding.getSourceType())
                || !Objects.equals(run.getSourceId(), binding.getSourceId())) {
            throw denied("Output run does not match its source binding");
        }
    }

    private List<String> requireTicketableRun(
            OutputRunBindingEntity run, OutputSourceAuthorization sourceAuthorization,
            String producerAgentId, AgentRuntimeEntity runtime, long now) {
        if (run == null || !Objects.equals(producerAgentId, run.getProducerAgentId())
                || run.getRecoveryUntil() == null || run.getRecoveryUntil() < now) {
            throw denied("Output run is unavailable");
        }
        if (OutputConstants.RUN_ACTIVE.equals(run.getState())) {
            if (!sourceAuthorization.writable()) {
                throw denied("Output source no longer permits mutations");
            }
            boolean leaseCapable = Objects.equals(run.getPolicyVersion(), 1)
                    && OutputConstants.SOURCE_TASK.equals(run.getSourceType())
                    && run.getWorkItemId() != null
                    && Objects.equals(run.getOriginalRuntimeId(),
                            runtime.getOutputCapabilitiesRuntimeId())
                    && supportsCapabilitySnapshot(runtime,
                            runtime.getOutputCapabilitiesRuntimeId(), now, true, true);
            return leaseCapable ? OutputConstants.R2_LEASE_TICKET_OPERATIONS
                    : OutputConstants.R1_TICKET_OPERATIONS;
        }
        if (isTerminal(run.getState())) return List.of(OutputConstants.OP_STATUS);
        throw denied("Output run is unavailable");
    }

    private void requireRunRecoveryCurrent(OutputRunBindingEntity run, long now) {
        if (run.getRecoveryUntil() == null || run.getRecoveryUntil() < now
                || OutputConstants.RUN_REVOKED.equals(run.getState())
                || (!OutputConstants.RUN_ACTIVE.equals(run.getState())
                        && !isTerminal(run.getState()))) {
            throw denied("Output run is unavailable");
        }
    }

    private void requireAuthorizedRun(
            OutputRunBindingEntity run, OutputAccessTicketEntity ticket,
            OutputSourceAuthorization sourceAuthorization, String requiredOperation,
            boolean receiptReplay, long now) {
        requireTicketRunRoute(ticket, run);
        if (run.getRecoveryUntil() == null || run.getRecoveryUntil() < now) {
            throw denied("Output run recovery has expired");
        }
        if (OutputConstants.RUN_ACTIVE.equals(run.getState())) {
            if (!sourceAuthorization.writable()) {
                throw denied("Output source no longer permits mutations");
            }
            return;
        }
        if (isTerminal(run.getState()) && receiptReplay
                && OutputConstants.OP_STATUS.equals(requiredOperation)) return;
        throw denied("Output run no longer authorizes this operation");
    }

    private List<String> requireUsableTicket(
            OutputAccessTicketEntity ticket, String requiredOperation, long now) {
        if (ticket == null || ticket.getRevokedAt() != null
                || ticket.getExpiresAt() == null || ticket.getExpiresAt() < now) {
            throw unauthorized("Output ticket is expired or revoked");
        }
        List<String> operations = JsonUtil.jsonToList(ticket.getOperationsJson(), String.class);
        if (!operations.contains(requiredOperation)) {
            throw denied("Output ticket operation is forbidden");
        }
        return operations;
    }

    private void requireTicketRunRoute(
            OutputAccessTicketEntity ticket, OutputRunBindingEntity run) {
        if (ticket == null || run == null
                || !Objects.equals(ticket.getTenantId(), run.getTenantId())
                || !Objects.equals(ticket.getClientId(), run.getClientId())
                || !Objects.equals(ticket.getRunId(), run.getRunId())
                || !Objects.equals(ticket.getBindingId(), run.getBindingId())) {
            throw denied("Output ticket route no longer matches its run");
        }
    }

    private void requireSameRunRoute(
            OutputRunBindingEntity locked, OutputRunBindingEntity projected) {
        if (locked == null || projected == null
                || !Objects.equals(locked.getTenantId(), projected.getTenantId())
                || !Objects.equals(locked.getClientId(), projected.getClientId())
                || !Objects.equals(locked.getRunId(), projected.getRunId())
                || !Objects.equals(locked.getSourceType(), projected.getSourceType())
                || !Objects.equals(locked.getSourceId(), projected.getSourceId())
                || !Objects.equals(locked.getProducerAgentId(), projected.getProducerAgentId())
                || !Objects.equals(locked.getBindingId(), projected.getBindingId())) {
            throw denied("Output run route changed while authorization was locking");
        }
    }

    private void requireSameTicketRoute(
            OutputAccessTicketEntity locked, OutputAccessTicketEntity projected,
            List<String> projectedOperations) {
        if (locked == null || projected == null
                || !java.util.Arrays.equals(locked.getTicketHash(), projected.getTicketHash())
                || !Objects.equals(locked.getTenantId(), projected.getTenantId())
                || !Objects.equals(locked.getClientId(), projected.getClientId())
                || !Objects.equals(locked.getRunId(), projected.getRunId())
                || !Objects.equals(locked.getBindingId(), projected.getBindingId())
                || !Objects.equals(locked.getIssuedRuntimeId(), projected.getIssuedRuntimeId())
                || !Objects.equals(JsonUtil.jsonToList(
                        locked.getOperationsJson(), String.class), projectedOperations)) {
            throw denied("Output ticket route changed while authorization was locking");
        }
    }

    private void requireRunMatches(
            OutputRunBindingEntity run,
            OutputSourceBindingEntity source,
            AgentRuntimeEntity runtime,
            OutputRunRequest request) {
        if (!Objects.equals(run.getTenantId(), request.tenantId())
                || !Objects.equals(run.getClientId(), request.clientId())
                || !Objects.equals(run.getSourceType(), request.sourceType())
                || !Objects.equals(run.getSourceId(), request.sourceId())
                || !Objects.equals(run.getProducerAgentId(), request.producerAgentId())
                || !Objects.equals(run.getBindingId(), Long.toString(runtime.getBindingId()))
                || !Objects.equals(source.getOwnerJiacn(), request.tenantId())
                || !Objects.equals(run.getWorkItemId(), request.workItemId())
                || !Objects.equals(run.getPolicyVersion(), request.policyVersion())) {
            throw denied("Prior output run conflicts with trusted dispatch facts");
        }
    }

    private OutputContextDTO toContext(
            OutputRunBindingEntity run, AgentRuntimeEntity runtime) {
        List<String> runtimeCapabilities = JsonUtil.jsonToList(
                runtime.getOutputCapabilitiesJson(), String.class);
        List<String> capabilities = OutputConstants.SOURCE_TASK.equals(run.getSourceType())
                        && runtimeCapabilities.contains(OutputConstants.CAPABILITY_OWNER_SHARE_V1)
                ? Objects.equals(run.getPolicyVersion(), 1)
                        && runtimeCapabilities.contains(OutputConstants.CAPABILITY_DELIVERY_HTTP_V1)
                    ? List.of(OutputConstants.CAPABILITY_HTTP_V1,
                            OutputConstants.CAPABILITY_OWNER_SHARE_V1,
                            OutputConstants.CAPABILITY_DELIVERY_HTTP_V1)
                    : List.of(OutputConstants.CAPABILITY_HTTP_V1,
                            OutputConstants.CAPABILITY_OWNER_SHARE_V1)
                : List.of(OutputConstants.CAPABILITY_HTTP_V1);
        return new OutputContextDTO(
                1, run.getRunId(),
                new OutputSourceDTO(run.getSourceType(), run.getSourceId()),
                Long.toString(OutputConstants.DEFAULT_MAX_FILE_BYTES),
                Long.toString(run.getMaxBytes()),
                "outputs/" + run.getRunId() + "/manifest.json",
                capabilities);
    }

    private BatchRequest validateBatch(
            List<OutputRunRequest> requests, List<String> identityAgentIds) {
        if (requests == null || requests.isEmpty()) {
            throw denied("Output run batch is required");
        }
        OutputRunRequest first = requests.getFirst();
        validateRequest(first);
        String tenantId = first.tenantId();
        String clientId = first.clientId();
        String sourceType = first.sourceType();
        String sourceId = first.sourceId();
        List<OutputRunRequest> orderedRequests = new ArrayList<>(requests.size());
        Set<String> producerIds = new LinkedHashSet<>();
        Set<String> originKeys = new LinkedHashSet<>();
        for (OutputRunRequest request : requests) {
            validateRequest(request);
            if (!tenantId.equals(request.tenantId()) || !clientId.equals(request.clientId())) {
                throw denied("Output run batch crosses an owner scope");
            }
            if (!sourceType.equals(request.sourceType()) || !sourceId.equals(request.sourceId())) {
                throw denied("Output run batch crosses a business source");
            }
            if (!producerIds.add(request.producerAgentId())) {
                throw denied("Output run batch contains a duplicate producer");
            }
            String originKey = request.sourceType() + '\0' + request.sourceId() + '\0'
                    + request.producerAgentId() + '\0' + request.originType() + '\0'
                    + request.originId();
            if (!originKeys.add(originKey)) {
                throw denied("Output run batch contains a duplicate origin");
            }
            orderedRequests.add(request);
        }
        orderedRequests.sort(Comparator
                .comparing(OutputRunRequest::sourceType,
                        OutputRunAuthorizationServiceImpl::compareUtf8Unsigned)
                .thenComparing(OutputRunRequest::sourceId,
                        OutputRunAuthorizationServiceImpl::compareUtf8Unsigned)
                .thenComparing(OutputRunRequest::producerAgentId,
                        OutputRunAuthorizationServiceImpl::compareUtf8Unsigned)
                .thenComparing(OutputRunRequest::originType,
                        OutputRunAuthorizationServiceImpl::compareUtf8Unsigned)
                .thenComparing(OutputRunRequest::originId,
                        OutputRunAuthorizationServiceImpl::compareUtf8Unsigned));

        if (identityAgentIds == null || identityAgentIds.isEmpty()) {
            throw denied("Output identity lock set is required");
        }
        Set<String> identities = new LinkedHashSet<>();
        for (String agentId : identityAgentIds) {
            requireExact(agentId, "identityAgentId", 400);
            if (!identities.add(agentId)) {
                throw denied("Output identity lock set contains a duplicate");
            }
        }
        if (!identities.containsAll(producerIds)) {
            throw denied("Output identity lock set omits a producer");
        }
        List<String> orderedIdentities = new ArrayList<>(identities);
        orderedIdentities.sort(OutputRunAuthorizationServiceImpl::compareUtf8Unsigned);
        return new BatchRequest(tenantId, clientId,
                List.copyOf(orderedRequests), List.copyOf(orderedIdentities));
    }

    private void validateRequest(OutputRunRequest request) {
        if (request == null) throw denied("Output run request is required");
        requireExact(request.tenantId(), "tenantId", 200);
        requireExact(request.clientId(), "clientId", 200);
        requireExact(request.sourceId(), "sourceId", 400);
        requireExact(request.producerAgentId(), "producerAgentId", 400);
        requireExact(request.originType(), "originType", 20);
        requireExact(request.originId(), "originId", 400);
        if (!List.of(OutputConstants.SOURCE_CONVERSATION, OutputConstants.SOURCE_TASK)
                .contains(request.sourceType())) {
            throw denied("Output source type is invalid");
        }
        if (request.workItemId() != null) requireExact(request.workItemId(), "workItemId", 400);
        if (request.policyVersion() < 0) throw denied("Output policy version is invalid");
    }

    private void requireRunId(String runId) {
        if (runId == null || !runId.matches("[0-9a-f]{32}")) {
            throw denied("runId is invalid");
        }
    }

    private void requireExact(String value, String field, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw denied(field + " is invalid");
        }
    }

    private boolean isTerminal(String state) {
        return OutputConstants.RUN_RESULT_SUBMITTED.equals(state)
                || OutputConstants.RUN_CLOSED.equals(state);
    }

    private byte[] sha256(String bearer) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(bearer.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private OutputAuthorizationException denied(String message) {
        return new OutputAuthorizationException("OUTPUT_AUTH_FORBIDDEN", message);
    }

    private OutputAuthorizationException unauthorized(String message) {
        return new OutputAuthorizationException("OUTPUT_AUTH_UNAUTHORIZED", message);
    }

    private static int compareUtf8Unsigned(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int limit = Math.min(a.length, b.length);
        for (int i = 0; i < limit; i++) {
            int comparison = Integer.compare(
                    Byte.toUnsignedInt(a[i]), Byte.toUnsignedInt(b[i]));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(a.length, b.length);
    }

    private record SourceKey(
            String tenantId, String clientId, String sourceType, String sourceId) {
    }

    private record BatchRequest(
            String tenantId, String clientId,
            List<OutputRunRequest> requests, List<String> identityAgentIds) {
    }
}
