package cn.jia.agent.output.service;

import cn.jia.agent.dao.AgentRuntimeDao;
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
import cn.jia.core.util.JsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
        long now = System.currentTimeMillis();
        OutputSourceAuthorization source = sourceRegistry.lockAndAuthorize(
                request.tenantId(), request.clientId(), request.sourceType(),
                request.sourceId(), request.producerAgentId());
        OutputSourceBindingEntity binding = lockOrCreateSource(source, now);

        OutputRunBindingEntity prior = runDao.findExactByOrigin(
                request.tenantId(), request.clientId(), request.sourceType(), request.sourceId(),
                request.producerAgentId(), request.originType(), request.originId());
        AgentRuntimeEntity runtime = requireFreshRuntime(
                request.tenantId(), request.clientId(), request.producerAgentId(), null);
        now = System.currentTimeMillis();
        if (prior != null) {
            if (OutputConstants.RUN_ACTIVE.equals(prior.getState())
                    && prior.getRecoveryUntil() != null && prior.getRecoveryUntil() >= now) {
                requireRunMatches(prior, binding, runtime, request);
                return Optional.of(toContext(prior, runtime));
            }
            throw new OutputAuthorizationException("OUTPUT_RUN_REDISPATCH_REQUIRED",
                    "The original dispatch run is terminal, revoked, or outside recovery");
        }

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
        run.setRecoveryUntil(Math.addExact(now, OutputConstants.RUN_RECOVERY_MILLIS));
        run.setMaxBytes(OutputConstants.DEFAULT_MAX_RUN_BYTES);
        run.setMaxFiles(OutputConstants.DEFAULT_MAX_FILES);
        run.setWorkItemId(request.workItemId());
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        run.setRowVersion(0L);
        if (runDao.insert(run) != 1) throw denied("Output run could not be persisted");
        return Optional.of(toContext(run, runtime));
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
        List<String> ticketOperations = requireTicketableRun(
                run, sourceAuthorization, producerAgentId, now);
        AgentRuntimeEntity runtime = requireFreshRuntime(
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
        requireCapabilitySnapshot(runtime, runtimeInstanceId, now);

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
            throw denied("Output ticket is invalid");
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
        return new OutputTicketAuthorization(
                ticket.getTenantId(), ticket.getClientId(), run.getRunId(),
                run.getSourceType(), run.getSourceId(), run.getProducerAgentId(),
                run.getBindingId(), ticket.getIssuedRuntimeId(),
                operations, ticket.getExpiresAt());
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

    private AgentRuntimeEntity requireFreshRuntime(
            String tenantId, String clientId, String agentId,
            String expectedRuntimeId) {
        AgentRuntimeEntity preview = runtimeDao.findExactOutputRuntime(
                tenantId, clientId, tenantId, agentId, false);
        if (preview == null || preview.getBindingId() == null) {
            throw denied("Agent runtime is outside the output scope");
        }
        if (!List.of(agentId).equals(identityService.lockActiveCanonicalAgentIdsInScope(
                tenantId, clientId, tenantId, List.of(agentId)))) {
            throw denied("Agent binding is unavailable");
        }
        AgentRuntimeEntity runtime = runtimeDao.findExactOutputRuntime(
                tenantId, clientId, tenantId, agentId, true);
        if (runtime == null || runtime.getBindingId() == null
                || !Objects.equals(tenantId, runtime.getTenantId())
                || !Objects.equals(clientId, runtime.getClientId())
                || !Objects.equals(tenantId, runtime.getOwnerJiacn())) {
            throw denied("Agent runtime is outside the output scope");
        }
        identityService.requireActiveIdentityForBinding(
                tenantId, clientId, tenantId, runtime.getBindingId(), agentId);
        if (!Objects.equals(preview.getBindingId(), runtime.getBindingId())) {
            throw denied("Agent binding changed while output authorization was locking");
        }
        requireCapabilitySnapshot(runtime, expectedRuntimeId, System.currentTimeMillis());
        return runtime;
    }

    private void requireCapabilitySnapshot(
            AgentRuntimeEntity runtime, String expectedRuntimeId, long now) {
        if (runtime.getOutputCapabilitiesJson() == null
                || runtime.getOutputCapabilitiesRuntimeId() == null
                || runtime.getOutputCapabilitiesUpdatedAt() == null
                || runtime.getOutputCapabilitiesUpdatedAt() > now
                || now - runtime.getOutputCapabilitiesUpdatedAt()
                        > OutputConstants.CAPABILITY_FRESHNESS_MILLIS
                || (expectedRuntimeId != null
                        && !expectedRuntimeId.equals(runtime.getOutputCapabilitiesRuntimeId()))) {
            throw denied("Agent output capability snapshot is absent, stale, or from another runtime");
        }
        List<String> capabilities = JsonUtil.jsonToList(
                runtime.getOutputCapabilitiesJson(), String.class);
        if (!capabilities.contains(OutputConstants.CAPABILITY_HTTP_V1)) {
            throw denied("Agent runtime does not support output HTTP v1");
        }
    }

    private AgentRuntimeEntity requireActiveBinding(
            String tenantId, String clientId, String agentId) {
        AgentRuntimeEntity preview = runtimeDao.findExactOutputRuntime(
                tenantId, clientId, tenantId, agentId, false);
        if (preview == null || preview.getBindingId() == null
                || !List.of(agentId).equals(identityService.lockActiveCanonicalAgentIdsInScope(
                        tenantId, clientId, tenantId, List.of(agentId)))) {
            throw denied("Agent binding is unavailable");
        }
        AgentRuntimeEntity runtime = runtimeDao.findExactOutputRuntime(
                tenantId, clientId, tenantId, agentId, true);
        if (runtime == null || !Objects.equals(preview.getBindingId(), runtime.getBindingId())) {
            throw denied("Agent binding changed while output authorization was locking");
        }
        identityService.requireActiveIdentityForBinding(
                tenantId, clientId, tenantId, runtime.getBindingId(), agentId);
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
            String producerAgentId, long now) {
        if (run == null || !Objects.equals(producerAgentId, run.getProducerAgentId())
                || run.getRecoveryUntil() == null || run.getRecoveryUntil() < now) {
            throw denied("Output run is unavailable");
        }
        if (OutputConstants.RUN_ACTIVE.equals(run.getState())) {
            if (!sourceAuthorization.writable()) {
                throw denied("Output source no longer permits mutations");
            }
            return OutputConstants.R1_TICKET_OPERATIONS;
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
            throw denied("Output ticket is expired or revoked");
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
                ? List.of(OutputConstants.CAPABILITY_HTTP_V1,
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
}
