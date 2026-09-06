package cn.jia.agent.service.funding;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.funding.AgentSkillRequirementDTO;
import cn.jia.agent.entity.funding.AgentTaskClaimOperationEntity;
import cn.jia.agent.entity.funding.AgentTaskClaimReceiptDTO;
import cn.jia.agent.entity.funding.AgentTaskClaimRequestDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingEntity;
import cn.jia.agent.entity.funding.AgentTaskQuoteDTO;
import cn.jia.agent.entity.funding.AgentTaskQuoteEntity;
import cn.jia.agent.entity.funding.AgentTaskQuoteRequestDTO;
import cn.jia.agent.entity.funding.AgentTokenEstimateDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.mapper.AgentTaskBountyQuoteMapper;
import cn.jia.agent.mapper.AgentTaskFundingMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.impl.AgentCommandTransportCapture;
import cn.jia.agent.service.impl.AgentLegacyTaskCompatibilityService;
import cn.jia.core.util.JsonUtil;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.common.MicroSilver;
import cn.jia.economy.exception.EconomyPostingException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
@ConditionalOnProperty(prefix = "economy.preview", name = "enabled", havingValue = "true")
public final class FundedBountyQuoteClaimServiceImpl implements FundedBountyQuoteClaimService {
    private static final String FUNDS_HELD = "FUNDS_HELD";
    private static final String PRINCIPAL_TYPE = "USER";

    private final AgentTaskBountyQuoteMapper quoteMapper;
    private final AgentTaskFundingMapper fundingMapper;
    private final AgentTaskMutationTransaction mutationTransaction;
    private final AgentIdentityService identityService;
    private final AgentRuntimeDao runtimeDao;
    private final AgentLegacyTaskCompatibilityService assignmentService;
    private final AgentCommandTransportCapture transportCapture;
    private final FundedBountySkillEntitlementLookup skillLookup;
    private final TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Autowired
    public FundedBountyQuoteClaimServiceImpl(AgentTaskBountyQuoteMapper quoteMapper,
            AgentTaskFundingMapper fundingMapper, AgentTaskMutationTransaction mutationTransaction,
            AgentIdentityService identityService, AgentRuntimeDao runtimeDao,
            AgentLegacyTaskCompatibilityService assignmentService,
            AgentCommandTransportCapture transportCapture,
            ObjectProvider<FundedBountySkillEntitlementLookup> skillLookupProvider,
            PlatformTransactionManager transactionManager) {
        this(quoteMapper, fundingMapper, mutationTransaction, identityService, runtimeDao,
                assignmentService, transportCapture,
                Objects.requireNonNull(skillLookupProvider, "skillLookupProvider")
                        .getIfAvailable(FailClosedFundedBountySkillEntitlementLookup::new),
                transactionManager);
    }

    FundedBountyQuoteClaimServiceImpl(AgentTaskBountyQuoteMapper quoteMapper,
            AgentTaskFundingMapper fundingMapper, AgentTaskMutationTransaction mutationTransaction,
            AgentIdentityService identityService, AgentRuntimeDao runtimeDao,
            AgentLegacyTaskCompatibilityService assignmentService,
            AgentCommandTransportCapture transportCapture,
            FundedBountySkillEntitlementLookup skillLookup,
            PlatformTransactionManager transactionManager) {
        this.quoteMapper = Objects.requireNonNull(quoteMapper, "quoteMapper");
        this.fundingMapper = Objects.requireNonNull(fundingMapper, "fundingMapper");
        this.mutationTransaction = Objects.requireNonNull(mutationTransaction, "mutationTransaction");
        this.identityService = Objects.requireNonNull(identityService, "identityService");
        this.runtimeDao = Objects.requireNonNull(runtimeDao, "runtimeDao");
        this.assignmentService = Objects.requireNonNull(assignmentService, "assignmentService");
        this.transportCapture = Objects.requireNonNull(transportCapture, "transportCapture");
        this.skillLookup = Objects.requireNonNull(skillLookup, "skillLookup");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public AgentTaskQuoteDTO quote(FundedBountyActor actor, String idempotencyKey, byte[] requestHash,
            String taskId, AgentTaskQuoteRequestDTO request) {
        validateActor(actor);
        validateHash(requestHash);
        byte[] key = asciiUuid(idempotencyKey);
        validateId(taskId, "taskId", 100);
        QuoteRequest parsed = validateQuoteRequest(request);
        try {
            AgentTaskQuoteDTO result = transactions.execute(status -> mutationTransaction.executeWithLockedTaskRoot(
                    actor.tenantId(), actor.clientId(), taskId,
                    root -> quoteLocked(actor, key, requestHash, parsed, root)));
            if (result == null) throw unavailable("Quote transaction returned no result");
            return result;
        } catch (AgentTaskCollaborationException exception) {
            throw mapTaskScope(exception);
        }
    }

    private AgentTaskQuoteDTO quoteLocked(FundedBountyActor actor, byte[] key, byte[] requestHash,
            QuoteRequest request, AgentTaskMetaEntity root) {
        AgentTaskFundingEntity funding = fundingMapper.selectFundingForUpdate(
                actor.tenantId(), actor.clientId(), root.getTaskId());
        requireOwnedFunding(actor, funding);
        AgentTaskQuoteEntity replay = quoteMapper.selectQuoteByActorKeyForUpdate(
                actor.tenantId(), actor.clientId(), PRINCIPAL_TYPE, actor.userId(), key);
        if (replay != null) return replayQuote(requestHash, root.getTaskId(), replay);
        requireOwnedHeldFunding(actor, funding);
        if (!AgentConstants.TASK_STATUS_OPEN.equals(root.getRewardStatus())
                || root.getAssignedAt() != null || root.getStartedAt() != null
                || fundingMapper.countMembers(actor.tenantId(), actor.clientId(), root.getTaskId()) != 0
                || fundingMapper.countWorkItems(actor.tenantId(), actor.clientId(), root.getTaskId()) != 0) {
            throw conflict("Funded task is not open for quotation");
        }
        List<String> canonical = identityService.lockActiveCanonicalAgentIdsInScope(
                actor.tenantId(), actor.clientId(), actor.tenantId(), List.of(request.agentId()));
        if (!canonical.equals(List.of(request.agentId()))) throw notFound("Agent not found");
        AgentRuntimeEntity runtime = requireScopedRuntime(actor,
                runtimeDao.findByAgentIdForUpdate(request.agentId()), request.agentId());
        List<AgentSkillRequirementDTO> requirements = parseRequirements(funding);
        FundedBountySkillEntitlementLookup.VerifiedSkillSnapshot skillSnapshot =
                requireSkillSnapshot(skillLookup.lookup(actor, request.agentId(), requirements));
        boolean agentReady = isReady(runtime, false);
        boolean abilityMatch = abilityMatch(root, runtime);
        long gross = requirePositive(funding.getRemainingMicro(), "remainingMicro");
        FundedBountyQuoteCalculator.QuoteAmounts amounts;
        try {
            amounts = FundedBountyQuoteCalculator.calculate(gross, request.minimumAcceptedPayout(),
                    FundedBountyPreviewPriceBook.ESTIMATED_TOKENS,
                    FundedBountyPreviewPriceBook.WORST_TOKENS);
        } catch (IllegalArgumentException arithmeticFailure) {
            throw badRequest("Quote arithmetic exceeds the supported range");
        }
        Recommendation recommendation = recommendation(agentReady,
                skillSnapshot.allRequirementsMatched(), abilityMatch, amounts, gross);
        long now = positiveNow();
        long expiresAt;
        try {
            expiresAt = Math.addExact(now, FundedBountyPreviewPriceBook.QUOTE_TTL_MILLIS);
        } catch (ArithmeticException overflow) {
            throw unavailable("Quote expiry exceeds the supported range");
        }
        AgentTaskQuoteEntity quote = new AgentTaskQuoteEntity();
        quote.setQuoteId("q_" + UUID.randomUUID().toString().replace("-", ""));
        quote.setTaskId(root.getTaskId());
        quote.setAgentId(request.agentId());
        quote.setPrincipalType(EconomyPrincipalType.USER.name());
        quote.setPrincipalId(actor.userId());
        quote.setIdempotencyKey(key);
        quote.setRequestHash(requestHash);
        quote.setTaskVersion(root.getTaskVersion());
        quote.setPriceBookVersion(FundedBountyPreviewPriceBook.VERSION);
        quote.setTaskInputHash(taskInputHash(root, funding, request));
        quote.setSkillSetHash(skillSnapshot.skillSetHash());
        quote.setModelRouteVersion(FundedBountyPreviewPriceBook.MODEL_ROUTE_VERSION);
        applyTokens(quote, FundedBountyPreviewPriceBook.ESTIMATED_TOKENS);
        quote.setEstimatedComputeMicro(amounts.estimatedComputeMicro());
        quote.setWorstComputeMicro(amounts.worstComputeMicro());
        quote.setPlatformFeeMicro(amounts.platformFeeMicro());
        quote.setGrossAllocationMicro(gross);
        quote.setEstimatedAgentPayoutMicro(amounts.estimatedAgentPayoutMicro());
        quote.setWorstAgentPayoutMicro(amounts.worstAgentPayoutMicro());
        quote.setMinimumAcceptedPayoutMicro(request.minimumAcceptedPayout());
        quote.setBudgetHeadroomMicro(amounts.budgetHeadroomMicro());
        quote.setVerifiedSkillMatch(skillSnapshot.allRequirementsMatched());
        quote.setAdvisoryAbilityMatch(abilityMatch);
        quote.setBudgetCovered(amounts.budgetCovered());
        quote.setAgentReady(agentReady);
        quote.setRecommendation(recommendation.value());
        quote.setReasonCodes(requireJson(recommendation.reasonCodes()));
        quote.setStatus("OPEN");
        quote.setExpiresAt(expiresAt);
        quote.setTenantId(actor.tenantId());
        quote.setClientId(actor.clientId());
        quote.setCreateTime(now);
        quote.setUpdateTime(now);
        try {
            requireOne(quoteMapper.insertQuote(quote), "quote insert");
        } catch (DataIntegrityViolationException duplicate) {
            AgentTaskQuoteEntity existing = quoteMapper.selectQuoteByActorKeyForUpdate(
                    actor.tenantId(), actor.clientId(), PRINCIPAL_TYPE, actor.userId(), key);
            if (existing == null) {
                FundedBountyException unresolved = conflict("Unable to resolve quote idempotency collision");
                unresolved.initCause(duplicate);
                throw unresolved;
            }
            return replayQuote(requestHash, root.getTaskId(), existing);
        }
        return quoteDto(quote);
    }

    @Override
    public AgentTaskClaimReceiptDTO claim(FundedBountyActor actor, String idempotencyKey,
            byte[] requestHash, String taskId, AgentTaskClaimRequestDTO request) {
        validateActor(actor);
        validateHash(requestHash);
        byte[] key = asciiUuid(idempotencyKey);
        validateId(taskId, "taskId", 100);
        ClaimRequest parsed = validateClaimRequest(request);
        try {
            AgentTaskClaimReceiptDTO result = transactions.execute(status ->
                    mutationTransaction.executeWithLockedTaskRoot(actor.tenantId(), actor.clientId(), taskId,
                            root -> claimLocked(actor, key, requestHash, parsed, root)));
            if (result == null) throw unavailable("Claim transaction returned no result");
            return result;
        } catch (AgentTaskCollaborationException exception) {
            throw mapTaskScope(exception);
        }
    }

    private AgentTaskClaimReceiptDTO claimLocked(FundedBountyActor actor, byte[] key, byte[] requestHash,
            ClaimRequest request, AgentTaskMetaEntity root) {
        AgentTaskFundingEntity funding = fundingMapper.selectFundingForUpdate(
                actor.tenantId(), actor.clientId(), root.getTaskId());
        requireOwnedFunding(actor, funding);
        AgentTaskQuoteEntity quote = quoteMapper.selectQuoteForUpdate(
                actor.tenantId(), actor.clientId(), root.getTaskId(), request.quoteId());
        if (quote == null || !exact(quote.getPrincipalId(), actor.userId())
                || !PRINCIPAL_TYPE.equals(quote.getPrincipalType())) {
            throw notFound("Quote not found");
        }
        AgentTaskClaimOperationEntity replay = quoteMapper.selectClaimOperationForUpdate(
                actor.tenantId(), actor.clientId(), PRINCIPAL_TYPE, actor.userId(), key);
        if (replay != null) return replayClaim(requestHash, root.getTaskId(), request, replay);
        requireOwnedHeldFunding(actor, funding);
        validateClaimableQuote(root, request, quote);
        AgentTaskClaimOperationEntity operation = claimOperation(actor, key, requestHash, request, root);
        try {
            requireOne(quoteMapper.insertClaimOperation(operation), "claim idempotency reservation");
        } catch (DataIntegrityViolationException duplicate) {
            AgentTaskClaimOperationEntity existing = quoteMapper.selectClaimOperationForUpdate(
                    actor.tenantId(), actor.clientId(), PRINCIPAL_TYPE, actor.userId(), key);
            if (existing == null) throw conflict("Quote was claimed concurrently");
            return replayClaim(requestHash, root.getTaskId(), request, existing);
        }

        AtomicReference<AgentRuntimeEntity> assignedRuntime = new AtomicReference<>();
        AgentLegacyTaskCompatibilityService.AssignOutcome outcome = assignmentService.assignResolvedVersioned(
                actor.tenantId(), actor.clientId(), root.getTaskId(), List.of(request.agentId()), false,
                request.taskVersion(),
                (lockedTask, agentIds) -> {
                    AgentRuntimeEntity runtime = requireScopedRuntime(actor,
                            runtimeDao.findByAgentIdForUpdate(request.agentId()), request.agentId());
                    if (!isReady(runtime, request.allowQueue())) {
                        throw new FundedBountyException(HttpStatus.CONFLICT, "AGENT_NOT_READY",
                                "Agent is not ready for this claim");
                    }
                    FundedBountySkillEntitlementLookup.VerifiedSkillSnapshot currentSkills =
                            requireSkillSnapshot(skillLookup.lookup(actor, request.agentId(), parseRequirements(funding)));
                    if (!currentSkills.allRequirementsMatched()
                            || !exact(currentSkills.skillSetHash(), quote.getSkillSetHash())) {
                        throw new FundedBountyException(HttpStatus.CONFLICT, "REQUIRED_SKILLS_MISMATCH",
                                "Required skill entitlement snapshot no longer matches the quote");
                    }
                    assignedRuntime.set(runtime);
                });
        if (!outcome.changed() || outcome.taskAssignedEventId() == null || outcome.occurredAt() == null) {
            throw conflict("Funded task was already claimed");
        }
        AgentRuntimeEntity runtime = assignedRuntime.get();
        if (runtime == null) throw unavailable("Claim did not retain its locked Agent runtime");
        long claimedAt = outcome.occurredAt();
        requireOne(quoteMapper.markClaimed(actor.tenantId(), actor.clientId(), root.getTaskId(),
                quote.getQuoteId(), claimedAt), "quote claim CAS");
        long receiptVersion = request.taskVersion() + 1;
        requireOne(quoteMapper.completeClaimOperation(actor.tenantId(), actor.clientId(), PRINCIPAL_TYPE,
                actor.userId(), key, receiptVersion, claimedAt), "claim receipt completion");
        transportCapture.captureTaskInvites(claimTaskDto(root, request.agentId(), receiptVersion),
                List.of(runtime), outcome.taskAssignedEventId(), claimedAt);
        return new AgentTaskClaimReceiptDTO(root.getTaskId(), request.agentId(), quote.getQuoteId(),
                AgentConstants.TASK_STATUS_ASSIGNED, Long.toString(receiptVersion), Long.toString(claimedAt));
    }

    private void validateClaimableQuote(AgentTaskMetaEntity root, ClaimRequest request,
            AgentTaskQuoteEntity quote) {
        if (!request.agentId().equals(quote.getAgentId())) throw notFound("Quote not found");
        if (!Long.valueOf(request.taskVersion()).equals(root.getTaskVersion())
                || !Long.valueOf(request.taskVersion()).equals(quote.getTaskVersion())) {
            throw versionConflict(root.getTaskVersion());
        }
        if (!"OPEN".equals(quote.getStatus())) throw conflict("Quote is not open");
        if (quote.getExpiresAt() == null || positiveNow() >= quote.getExpiresAt()) {
            throw new FundedBountyException(HttpStatus.CONFLICT, "QUOTE_EXPIRED", "Quote has expired");
        }
        if (!Boolean.TRUE.equals(quote.getVerifiedSkillMatch())) {
            throw new FundedBountyException(HttpStatus.CONFLICT, "REQUIRED_SKILLS_MISMATCH",
                    "Quote does not verify required skills");
        }
        if (!Boolean.TRUE.equals(quote.getBudgetCovered())) {
            throw new FundedBountyException(HttpStatus.CONFLICT, "INSUFFICIENT_BOUNTY_BUDGET",
                    "Worst-case compute, fee, and minimum payout exceed the funded allocation");
        }
        if (!AgentConstants.TASK_STATUS_OPEN.equals(root.getRewardStatus())
                || root.getAssignedAt() != null || root.getStartedAt() != null
                || fundingMapper.countMembers(root.getTenantId(), root.getClientId(), root.getTaskId()) != 0
                || fundingMapper.countWorkItems(root.getTenantId(), root.getClientId(), root.getTaskId()) != 0) {
            throw conflict("Funded task is not open for claim");
        }
    }

    private static AgentTaskClaimOperationEntity claimOperation(FundedBountyActor actor, byte[] key,
            byte[] requestHash, ClaimRequest request, AgentTaskMetaEntity root) {
        long now = positiveNow();
        AgentTaskClaimOperationEntity operation = new AgentTaskClaimOperationEntity();
        operation.setPrincipalType(PRINCIPAL_TYPE);
        operation.setPrincipalId(actor.userId());
        operation.setIdempotencyKey(key);
        operation.setRequestHash(requestHash);
        operation.setTaskId(root.getTaskId());
        operation.setAgentId(request.agentId());
        operation.setQuoteId(request.quoteId());
        operation.setStatus("POSTING");
        operation.setTenantId(actor.tenantId());
        operation.setClientId(actor.clientId());
        operation.setCreateTime(now);
        operation.setUpdateTime(now);
        return operation;
    }

    private static AgentTaskClaimReceiptDTO replayClaim(byte[] requestHash, String taskId,
            ClaimRequest request, AgentTaskClaimOperationEntity operation) {
        if (!MessageDigest.isEqual(requestHash, operation.getRequestHash())
                || !exact(taskId, operation.getTaskId()) || !exact(request.agentId(), operation.getAgentId())
                || !exact(request.quoteId(), operation.getQuoteId())
                || !"COMPLETED".equals(operation.getStatus()) || operation.getReceiptTaskVersion() == null
                || operation.getClaimedAt() == null) {
            throw idempotencyConflict();
        }
        return new AgentTaskClaimReceiptDTO(operation.getTaskId(), operation.getAgentId(),
                operation.getQuoteId(), AgentConstants.TASK_STATUS_ASSIGNED,
                Long.toString(operation.getReceiptTaskVersion()), Long.toString(operation.getClaimedAt()));
    }

    private static AgentTaskQuoteDTO replayQuote(byte[] requestHash, String taskId,
            AgentTaskQuoteEntity quote) {
        if (!MessageDigest.isEqual(requestHash, quote.getRequestHash()) || !exact(taskId, quote.getTaskId())) {
            throw idempotencyConflict();
        }
        return quoteDto(quote);
    }

    private static AgentTaskQuoteDTO quoteDto(AgentTaskQuoteEntity quote) {
        List<String> reasons = JsonUtil.jsonToList(quote.getReasonCodes(), String.class);
        if (reasons.isEmpty() && !"[]".equals(quote.getReasonCodes())) {
            throw unavailable("Quote reason-code snapshot is corrupt");
        }
        return new AgentTaskQuoteDTO(quote.getQuoteId(), quote.getTaskId(), quote.getAgentId(),
                decimal(quote.getTaskVersion()), quote.getPriceBookVersion(), quote.getTaskInputHash(),
                quote.getSkillSetHash(), quote.getModelRouteVersion(),
                new AgentTokenEstimateDTO(decimal(quote.getEstimatedInputTokens()),
                        decimal(quote.getEstimatedCachedInputTokens()), decimal(quote.getEstimatedOutputTokens()),
                        decimal(quote.getEstimatedReasoningTokens())), decimal(quote.getEstimatedComputeMicro()),
                decimal(quote.getWorstComputeMicro()), decimal(quote.getPlatformFeeMicro()),
                decimal(quote.getGrossAllocationMicro()), decimal(quote.getEstimatedAgentPayoutMicro()),
                decimal(quote.getWorstAgentPayoutMicro()), decimal(quote.getMinimumAcceptedPayoutMicro()),
                decimal(quote.getBudgetHeadroomMicro()), Boolean.TRUE.equals(quote.getVerifiedSkillMatch()),
                Boolean.TRUE.equals(quote.getAdvisoryAbilityMatch()), quote.getRecommendation(), reasons,
                decimal(quote.getExpiresAt()));
    }

    private static void applyTokens(AgentTaskQuoteEntity quote,
            FundedBountyPreviewPriceBook.TokenClasses tokens) {
        quote.setEstimatedInputTokens(tokens.input());
        quote.setEstimatedCachedInputTokens(tokens.cachedInput());
        quote.setEstimatedOutputTokens(tokens.output());
        quote.setEstimatedReasoningTokens(tokens.reasoning());
    }

    private static String taskInputHash(AgentTaskMetaEntity root, AgentTaskFundingEntity funding,
            QuoteRequest request) {
        String canonical = root.getTaskId() + '\0' + root.getTaskVersion() + '\0'
                + Objects.toString(root.getRequiredAbilities(), "") + '\0'
                + Objects.toString(funding.getRequiredSkillRequirements(), "") + '\0'
                + request.contextRevision() + '\0' + request.provider() + '\0' + request.model();
        return "sha256:" + TaskEventPayload.ContentDigest.fromUtf8(canonical).sha256();
    }

    private static Recommendation recommendation(boolean ready, boolean skills, boolean ability,
            FundedBountyQuoteCalculator.QuoteAmounts amounts, long gross) {
        List<String> reasons = new ArrayList<>();
        reasons.add(skills ? "VERIFIED_SKILLS_MATCH" : "REQUIRED_SKILLS_UNVERIFIED");
        reasons.add(ability ? "ADVISORY_ABILITY_MATCH" : "ADVISORY_ABILITY_MISMATCH");
        reasons.add(ready ? "AGENT_READY" : "AGENT_NOT_READY");
        reasons.add(amounts.budgetCovered() ? "BUDGET_COVERED" : "BUDGET_NOT_COVERED");
        if (!ready || !skills || !amounts.budgetCovered()) return new Recommendation("reject", reasons);
        boolean lowHeadroom = amounts.budgetHeadroomMicro() < gross / 10L;
        if (lowHeadroom) reasons.add("LOW_BUDGET_HEADROOM");
        return new Recommendation(ability && !lowHeadroom ? "recommended" : "caution", reasons);
    }

    private static boolean abilityMatch(AgentTaskMetaEntity root, AgentRuntimeEntity runtime) {
        List<String> required = exactStringList(root.getRequiredAbilities(), "required abilities");
        if (required.isEmpty()) return true;
        Set<String> available = new LinkedHashSet<>();
        for (String ability : exactStringList(runtime.getAbilities(), "Agent abilities")) {
            available.add(ability.toLowerCase(Locale.ROOT));
        }
        return required.stream().map(value -> value.toLowerCase(Locale.ROOT)).allMatch(available::contains);
    }

    private static List<String> exactStringList(String json, String field) {
        if (json == null) return List.of();
        List<String> values = JsonUtil.jsonToList(json, String.class);
        if (values == null || (values.isEmpty() && !"[]".equals(json))) throw unavailable(field + " snapshot is corrupt");
        for (String value : values) validateId(value, field, 100);
        return List.copyOf(values);
    }

    private static List<AgentSkillRequirementDTO> parseRequirements(AgentTaskFundingEntity funding) {
        String json = funding.getRequiredSkillRequirements();
        List<AgentSkillRequirementDTO> requirements = JsonUtil.jsonToList(json, AgentSkillRequirementDTO.class);
        if (requirements == null || (requirements.isEmpty() && !"[]".equals(json))) {
            throw unavailable("Required skill snapshot is corrupt");
        }
        return List.copyOf(requirements);
    }

    private static FundedBountySkillEntitlementLookup.VerifiedSkillSnapshot requireSkillSnapshot(
            FundedBountySkillEntitlementLookup.VerifiedSkillSnapshot snapshot) {
        if (snapshot == null || snapshot.skillSetHash() == null
                || !snapshot.skillSetHash().matches("sha256:[0-9a-f]{64}")
                || snapshot.installedSkills() == null) {
            throw unavailable("Skill entitlement lookup returned an invalid snapshot");
        }
        return snapshot;
    }

    private AgentRuntimeEntity requireScopedRuntime(FundedBountyActor actor,
            AgentRuntimeEntity runtime, String expectedAgentId) {
        if (runtime == null) throw notFound("Agent not found");
        if (!exact(expectedAgentId, runtime.getAgentId())
                || !exact(actor.clientId(), runtime.getClientId())
                || !exact(actor.tenantId(), runtime.getOwnerJiacn())
                || !exact(actor.tenantId(), runtime.getTenantId())
                || runtime.getBindingId() == null || runtime.getBindingId() <= 0) {
            throw notFound("Agent not found");
        }
        // Canonical identity/binding locks precede this runtime FOR UPDATE in both quote and
        // assignment's claim callback. Verify the runtime references THAT active scoped binding.
        try {
            cn.jia.agent.entity.AgentIdentityRegistryEntity identity = identityService.requireActiveIdentityForBinding(
                    actor.tenantId(), actor.clientId(), actor.tenantId(), runtime.getBindingId(), expectedAgentId);
            if (identity == null || !runtime.getBindingId().equals(identity.getBindingId())
                    || !exact(expectedAgentId, identity.getCanonicalAgentId())
                    || !exact(actor.tenantId(), identity.getTenantId())
                    || !exact(actor.tenantId(), identity.getOwnerJiacn())
                    || !exact(actor.clientId(), identity.getClientId())) throw notFound("Agent not found");
        } catch (cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException incompatible) {
            throw notFound("Agent not found");
        }
        return runtime;
    }

    private static boolean isReady(AgentRuntimeEntity runtime, boolean allowQueue) {
        if (AgentConstants.STATUS_ERROR.equals(runtime.getStatus())) return false;
        if (AgentConstants.STATUS_OFFLINE.equals(runtime.getStatus())
                || AgentConstants.STATUS_BUSY.equals(runtime.getStatus())) return allowQueue;
        return AgentConstants.STATUS_ONLINE.equals(runtime.getStatus());
    }

    private static AgentTaskDTO claimTaskDto(AgentTaskMetaEntity root, String agentId, long version) {
        AgentTaskDTO task = new AgentTaskDTO();
        task.setId(root.getTaskId());
        task.setTenantId(root.getTenantId());
        task.setClientId(root.getClientId());
        task.setTitle(root.getTaskId());
        task.setStatus(AgentConstants.TASK_STATUS_ASSIGNED);
        task.setRequiredAbilities(exactStringList(root.getRequiredAbilities(), "required abilities"));
        task.setAssignedAgentId(agentId);
        task.setAssignedAgentIds(List.of(agentId));
        task.setAssignedAt(root.getAssignedAt());
        task.setTaskVersion(Long.toString(version));
        return task;
    }

    private static QuoteRequest validateQuoteRequest(AgentTaskQuoteRequestDTO request) {
        if (request == null) throw badRequest("quote request is required");
        validateId(request.getAgentId(), "agentId", 100);
        if (request.getWorkItemId() != null) throw badRequest("workItemId is not supported in V0");
        if (request.getModelPreference() == null) throw badRequest("modelPreference is required");
        validateId(request.getModelPreference().getProvider(), "modelPreference.provider", 100);
        validateId(request.getModelPreference().getModel(), "modelPreference.model", 100);
        if (!FundedBountyPreviewPriceBook.PROVIDER.equals(request.getModelPreference().getProvider())
                || !FundedBountyPreviewPriceBook.MODEL.equals(request.getModelPreference().getModel())) {
            throw new FundedBountyException(HttpStatus.CONFLICT, "MODEL_ROUTE_UNAVAILABLE",
                    "Only the configured synthetic V0 preview model route is available");
        }
        long contextRevision = canonicalUnsigned(request.getContextRevision(), "contextRevision", true);
        long minimum = canonicalMoney(request.getMinimumAcceptedPayoutMicro(), "minimumAcceptedPayoutMicro");
        return new QuoteRequest(request.getAgentId(), request.getModelPreference().getProvider(),
                request.getModelPreference().getModel(), Long.toString(contextRevision), minimum);
    }

    private static ClaimRequest validateClaimRequest(AgentTaskClaimRequestDTO request) {
        if (request == null) throw badRequest("claim request is required");
        validateId(request.getAgentId(), "agentId", 100);
        validateId(request.getQuoteId(), "quoteId", 100);
        if (!request.getQuoteId().matches("q_[0-9a-f]{32}")) throw badRequest("quoteId is invalid");
        long version = canonicalUnsigned(request.getTaskVersion(), "taskVersion", false);
        if (request.getAllowQueue() == null) throw badRequest("allowQueue is required");
        return new ClaimRequest(request.getAgentId(), request.getQuoteId(), version, request.getAllowQueue());
    }

    private static long canonicalMoney(String value, String field) {
        try {
            return MicroSilver.parseUnsigned(value);
        } catch (EconomyPostingException exception) {
            throw badRequest(field + " must be a canonical unsigned decimal string");
        }
    }

    private static long canonicalUnsigned(String value, String field, boolean allowMax) {
        if (value == null || value.isEmpty() || !(value.equals("0") || value.charAt(0) >= '1' && value.charAt(0) <= '9')
                || value.chars().anyMatch(character -> character < '0' || character > '9')) {
            throw badRequest(field + " must be a canonical unsigned decimal string");
        }
        try {
            long parsed = Long.parseLong(value);
            if (!allowMax && parsed == Long.MAX_VALUE) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw badRequest(field + " is outside the supported range");
        }
    }

    private static void requireOwnedFunding(FundedBountyActor actor, AgentTaskFundingEntity funding) {
        if (funding == null || !exact(actor.tenantId(), funding.getTenantId())
                || !exact(actor.clientId(), funding.getClientId())
                || !PRINCIPAL_TYPE.equals(funding.getPayerPrincipalType())
                || !exact(actor.userId(), funding.getPayerPrincipalId())) {
            throw notFound("Task not found");
        }
    }

    private static void requireOwnedHeldFunding(FundedBountyActor actor, AgentTaskFundingEntity funding) {
        requireOwnedFunding(actor, funding);
        if (!FUNDS_HELD.equals(funding.getFundingStatus()) || funding.getRemainingMicro() == null
                || funding.getVersion() == null || funding.getVersion() < 1) {
            throw conflict("Task funding is not available for quote or claim");
        }
    }

    private static FundedBountyException mapTaskScope(AgentTaskCollaborationException exception) {
        if (exception.getReason() == AgentTaskCollaborationException.Reason.NOT_FOUND
                || exception.getReason() == AgentTaskCollaborationException.Reason.FORBIDDEN) {
            return notFound("Task not found");
        }
        return unavailable("Task root is unavailable");
    }

    private static String requireJson(List<String> values) {
        String json = JsonUtil.toJson(values);
        if (json == null) throw unavailable("Unable to encode quote reason codes");
        return json;
    }

    private static long requirePositive(Long value, String field) {
        if (value == null || value <= 0) throw unavailable(field + " is invalid");
        return value;
    }

    private static String decimal(Long value) {
        if (value == null || value < 0) throw unavailable("Quote contains an invalid numeric snapshot");
        return Long.toString(value);
    }

    private static byte[] asciiUuid(String value) {
        if (value == null) throw badRequest("Idempotency-Key is required");
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw badRequest("Idempotency-Key must be a canonical lowercase UUID");
        }
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void validateActor(FundedBountyActor actor) {
        if (actor == null) throw new FundedBountyException(HttpStatus.UNAUTHORIZED,
                "ECONOMY_UNAUTHENTICATED", "Authentication is required");
        validateId(actor.tenantId(), "jiacn", 50);
        validateId(actor.clientId(), "client_id", 50);
        validateId(actor.userId(), "sub", 100);
    }

    private static void validateHash(byte[] hash) {
        if (hash == null || hash.length != 32) throw badRequest("request hash is invalid");
    }

    private static void validateId(String value, String field, int maxBytes) {
        if (value == null || value.isEmpty() || hasUnpairedSurrogate(value)
                || !value.equals(value.strip()) || value.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw badRequest(field + " is invalid");
        }
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return true;
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean exact(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static long positiveNow() {
        long now = System.currentTimeMillis();
        if (now <= 0) throw unavailable("System clock is invalid");
        return now;
    }

    private static void requireOne(int rows, String mutation) {
        if (rows != 1) throw unavailable(mutation + " affected " + rows + " rows");
    }

    private static FundedBountyException badRequest(String message) {
        return new FundedBountyException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    private static FundedBountyException notFound(String message) {
        return new FundedBountyException(HttpStatus.NOT_FOUND, "TASK_OR_COUNTERPARTY_NOT_FOUND", message);
    }

    private static FundedBountyException conflict(String message) {
        return new FundedBountyException(HttpStatus.CONFLICT, "FUNDED_BOUNTY_CONFLICT", message);
    }

    private static FundedBountyException unavailable(String message) {
        return new FundedBountyException(HttpStatus.SERVICE_UNAVAILABLE, "FUNDED_BOUNTY_UNAVAILABLE", message, true);
    }

    private static FundedBountyException idempotencyConflict() {
        return new FundedBountyException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                "Idempotency-Key is already bound to a different request");
    }

    private static FundedBountyException versionConflict(Long actual) {
        return new FundedBountyException(HttpStatus.CONFLICT, "TASK_VERSION_CONFLICT",
                "Task version does not match; current version is " + actual);
    }

    private record QuoteRequest(String agentId, String provider, String model,
            String contextRevision, long minimumAcceptedPayout) {
    }

    private record ClaimRequest(String agentId, String quoteId, long taskVersion, boolean allowQueue) {
    }

    private record Recommendation(String value, List<String> reasonCodes) {
        private Recommendation {
            reasonCodes = List.copyOf(reasonCodes);
        }
    }
}
