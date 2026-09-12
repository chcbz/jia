package cn.jia.economy.service.impl;

import cn.jia.economy.common.EconomyAccountOwnerType;
import cn.jia.economy.common.EconomyAccountPurpose;
import cn.jia.economy.common.EconomyConstants;
import cn.jia.economy.common.EconomyEscrowType;
import cn.jia.economy.common.EconomyJournalType;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.entity.EconomyAccountEntity;
import cn.jia.economy.entity.EconomyHostingLeaseEntity;
import cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity;
import cn.jia.economy.entity.EconomyHostingRentPlanEntity;
import cn.jia.economy.entity.EconomyHostingRentQuoteEntity;
import cn.jia.economy.hosting.HostingRentException;
import cn.jia.economy.hosting.HostingRentIntentStatus;
import cn.jia.economy.hosting.HostingRentLeaseStatus;
import cn.jia.economy.hosting.HostingRentLedgerService;
import cn.jia.economy.hosting.HostingRentMutationReceipt;
import cn.jia.economy.hosting.HostingRentOutcomeCommand;
import cn.jia.economy.hosting.HostingRentQuoteCommand;
import cn.jia.economy.hosting.HostingRentQuotePurpose;
import cn.jia.economy.hosting.HostingRentQuoteReceipt;
import cn.jia.economy.hosting.HostingRentReserveCommand;
import cn.jia.economy.hosting.HostingRentSettlementCommand;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.service.EconomyAccountKey;
import cn.jia.economy.service.EconomyEscrowFunding;
import cn.jia.economy.service.EconomyEscrowSettlement;
import cn.jia.economy.service.EconomyPostingCommand;
import cn.jia.economy.service.EconomyPostingLine;
import cn.jia.economy.service.EconomyPostingResult;
import cn.jia.economy.service.EconomyPostingService;
import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static cn.jia.economy.hosting.HostingRentException.Reason.CONCURRENCY_CONFLICT;
import static cn.jia.economy.hosting.HostingRentException.Reason.DATA_CORRUPT;
import static cn.jia.economy.hosting.HostingRentException.Reason.IDEMPOTENCY_CONFLICT;
import static cn.jia.economy.hosting.HostingRentException.Reason.INTENT_CONFLICT;
import static cn.jia.economy.hosting.HostingRentException.Reason.INVALID_COMMAND;
import static cn.jia.economy.hosting.HostingRentException.Reason.LEASE_CONFLICT;
import static cn.jia.economy.hosting.HostingRentException.Reason.NOT_CONFIGURED;
import static cn.jia.economy.hosting.HostingRentException.Reason.NOT_FOUND_OR_FORBIDDEN;
import static cn.jia.economy.hosting.HostingRentException.Reason.PROVISIONING_OUTCOME_UNKNOWN;
import static cn.jia.economy.hosting.HostingRentException.Reason.QUOTE_ALREADY_CONSUMED;
import static cn.jia.economy.hosting.HostingRentException.Reason.QUOTE_EXPIRED;
import static cn.jia.economy.hosting.HostingRentException.Reason.REFUND_NOT_ALLOWED;

/**
 * Paid-hosting reservation, readiness settlement and explicit manual-renewal domain. It performs database work only: no filesystem,
 * process, broker, profile, runtime, or Agent binding operation is invoked here.
 */
@Service
public final class HostingRentLedgerServiceImpl implements HostingRentLedgerService {
    private final EconomyHostingRentMapper hostingMapper;
    private final EconomyLedgerMapper ledgerMapper;
    private final EconomyPostingService postingService;
    private final TransactionTemplate transactions;
    private final Supplier<String> quoteIds;
    private final Supplier<String> leaseIds;
    private final Supplier<String> intentIds;
    private final LongSupplier clock;

    @org.springframework.beans.factory.annotation.Autowired
    public HostingRentLedgerServiceImpl(
            EconomyHostingRentMapper hostingMapper,
            EconomyLedgerMapper ledgerMapper,
            EconomyPostingService postingService,
            PlatformTransactionManager transactionManager) {
        this(hostingMapper, ledgerMapper, postingService, transactionManager,
                () -> "hrq_" + UUID.randomUUID(),
                () -> "hrl_" + UUID.randomUUID(),
                () -> "hri_" + UUID.randomUUID(),
                System::currentTimeMillis);
    }

    HostingRentLedgerServiceImpl(
            EconomyHostingRentMapper hostingMapper,
            EconomyLedgerMapper ledgerMapper,
            EconomyPostingService postingService,
            PlatformTransactionManager transactionManager,
            Supplier<String> quoteIds,
            Supplier<String> leaseIds,
            Supplier<String> intentIds,
            LongSupplier clock) {
        this.hostingMapper = Objects.requireNonNull(hostingMapper, "hostingMapper");
        this.ledgerMapper = Objects.requireNonNull(ledgerMapper, "ledgerMapper");
        this.postingService = Objects.requireNonNull(postingService, "postingService");
        this.quoteIds = Objects.requireNonNull(quoteIds, "quoteIds");
        this.leaseIds = Objects.requireNonNull(leaseIds, "leaseIds");
        this.intentIds = Objects.requireNonNull(intentIds, "intentIds");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public HostingRentQuoteReceipt quote(HostingRentQuoteCommand command) {
        ValidatedQuote validated = validateQuote(command);
        HostingRentQuoteReceipt receipt = transactions.execute(status -> quoteInTransaction(validated));
        if (receipt == null) throw new HostingRentException(DATA_CORRUPT, "quote transaction returned no receipt");
        return receipt;
    }

    private HostingRentQuoteReceipt quoteInTransaction(ValidatedQuote command) {
        EconomyHostingRentQuoteEntity replay = hostingMapper.selectQuoteByActorKeyForUpdate(
                command.scope().tenantId(), command.scope().clientId(),
                command.principal().type().name(), command.principal().id(), command.idempotencyKey());
        if (replay != null) return replayQuote(command, replay);

        EconomyHostingRentPlanEntity plan = hostingMapper.selectPlanForUpdate(
                command.scope().tenantId(), command.scope().clientId(),
                command.command().planId(), command.command().planVersion());
        requireActivePlan(command, plan);
        replay = hostingMapper.selectQuoteByActorKeyForUpdate(
                command.scope().tenantId(), command.scope().clientId(),
                command.principal().type().name(), command.principal().id(), command.idempotencyKey());
        if (replay != null) return replayQuote(command, replay);

        if (command.command().purpose() == HostingRentQuotePurpose.RENEWAL) {
            EconomyHostingLeaseEntity lease = hostingMapper.selectLeaseForUpdate(
                    command.scope().tenantId(), command.scope().clientId(), command.command().leaseId());
            requireRenewalLease(command, lease);
        }
        long now = positiveNow();
        long expiresAt = checkedAdd(now, checkedMultiply(plan.getQuoteTtlSeconds(), 1_000L));
        EconomyHostingRentQuoteEntity quote = new EconomyHostingRentQuoteEntity()
                .setQuoteId(requireGeneratedId(quoteIds.get(), "quoteId"))
                .setQuotePurpose(command.command().purpose().name())
                .setPlanId(plan.getPlanId())
                .setPlanVersion(plan.getPlanVersion())
                .setAmountMicro(plan.getAmountMicro())
                .setPeriodSeconds(plan.getPeriodSeconds())
                .setPrincipalType(command.principal().type().name())
                .setPrincipalId(command.principal().id())
                .setPersonaCode(command.command().personaCode())
                .setAgentId(command.command().agentId())
                .setLeaseId(command.command().leaseId())
                .setExpectedLeaseVersion(command.command().expectedLeaseVersion())
                .setIdempotencyKey(command.idempotencyKey())
                .setRequestHash(command.command().requestHash())
                .setExpiresAt(expiresAt)
                .setTenantId(command.scope().tenantId())
                .setClientId(command.scope().clientId())
                .setCreateTime(now);
        try {
            requireOne(hostingMapper.insertQuote(quote), "quote insert");
        } catch (DataIntegrityViolationException duplicate) {
            EconomyHostingRentQuoteEntity existing = hostingMapper.selectQuoteByActorKeyForUpdate(
                    command.scope().tenantId(), command.scope().clientId(),
                    command.principal().type().name(), command.principal().id(), command.idempotencyKey());
            if (existing != null) return replayQuote(command, existing);
            throw new HostingRentException(CONCURRENCY_CONFLICT, "quote identifier conflict");
        }
        return toQuoteReceipt(quote);
    }

    @Override
    public HostingRentMutationReceipt reserve(HostingRentReserveCommand command) {
        if (command == null) throw new HostingRentException(INVALID_COMMAND, "reserve command is incomplete");
        ValidatedMutation validated = validateMutation(command.scope(), command.principal(),
                command.idempotencyKey(), command.requestHash(), command.quoteId(), "quoteId");
        HostingRentMutationReceipt receipt = transactions.execute(status -> reserveInTransaction(command, validated));
        if (receipt == null) throw new HostingRentException(DATA_CORRUPT, "reserve transaction returned no receipt");
        return receipt;
    }

    private HostingRentMutationReceipt reserveInTransaction(
            HostingRentReserveCommand command, ValidatedMutation validated) {
        // Frozen R01 order: quote/business root -> account provisioning -> REQUIRED W02 posting.
        EconomyHostingRentQuoteEntity quote = hostingMapper.selectQuoteForUpdate(
                validated.scope().tenantId(), validated.scope().clientId(), command.quoteId());
        requireOwnedQuote(validated, quote);
        EconomyHostingProvisioningIntentEntity existing = hostingMapper.selectIntentByQuoteForUpdate(
                validated.scope().tenantId(), validated.scope().clientId(), command.quoteId());
        if (existing != null) return replayReserve(validated, existing);
        if (quote.getExpiresAt() <= positiveNow()) {
            throw new HostingRentException(QUOTE_EXPIRED, "hosting-rent quote has expired");
        }
        if (!HostingRentQuotePurpose.INITIAL.name().equals(quote.getQuotePurpose())) {
            throw new HostingRentException(LEASE_CONFLICT,
                    "renewal requires the explicit atomic renewal operation");
        }
        if (hostingMapper.selectLiveLeaseByAgentForUpdate(
                validated.scope().tenantId(), validated.scope().clientId(), quote.getAgentId()) != null) {
            throw new HostingRentException(LEASE_CONFLICT, "canonical Agent already has a hosting lease");
        }

        // A fully refunded lease stays as history. Only its live slot is released by the
        // refund transaction; every retry uses fresh lease/intent/escrow IDs. The unique live
        // slot arbitrates competing quotes, and a losing insert rolls back its REQUIRED posting.
        long now = positiveNow();
        String leaseId = requireGeneratedId(leaseIds.get(), "leaseId");
        String intentId = requireGeneratedId(intentIds.get(), "intentId");
        EconomyAccountKey escrowAccount = leaseEscrow(leaseId);
        ensureAccount(validated.scope(), escrowAccount, escrowAccountId(validated.scope(), leaseId), now);
        EconomyPostingResult posting = postingService.post(new EconomyPostingCommand(
                validated.scope(), validated.principal(), command.idempotencyKey(), command.requestHash(),
                EconomyJournalType.RESERVE_HOSTING_RENT, leaseId, List.of(
                    new EconomyPostingLine(userAvailable(validated.principal().id()), -quote.getAmountMicro()),
                    new EconomyPostingLine(escrowAccount, quote.getAmountMicro())),
                new EconomyEscrowFunding(EconomyEscrowType.HOSTING_RENT,
                        userAvailable(validated.principal().id()), escrowAccount,
                        quote.getAmountMicro(), null)));
        if (posting.escrow() == null || posting.escrow().escrowVersion() <= 0) {
            throw new HostingRentException(DATA_CORRUPT, "hosting reserve did not create escrow evidence");
        }

        EconomyHostingLeaseEntity lease = new EconomyHostingLeaseEntity()
                .setLeaseId(leaseId)
                .setPrincipalType(validated.principal().type().name())
                .setPrincipalId(validated.principal().id())
                .setPersonaCode(quote.getPersonaCode())
                .setAgentId(quote.getAgentId())
                .setBindingId(null)
                .setPlanId(quote.getPlanId())
                .setPlanVersion(quote.getPlanVersion())
                .setAmountMicro(quote.getAmountMicro())
                .setPeriodSeconds(quote.getPeriodSeconds())
                .setStatus(HostingRentLeaseStatus.PROVISIONING.name())
                .setPaidFrom(null)
                .setPaidThrough(null)
                .setLatestIntentId(intentId)
                .setVersion(1L)
                .setTenantId(validated.scope().tenantId())
                .setClientId(validated.scope().clientId())
                .setCreateTime(now)
                .setUpdateTime(now);
        EconomyHostingProvisioningIntentEntity intent = new EconomyHostingProvisioningIntentEntity()
                .setIntentId(intentId)
                .setLeaseId(leaseId)
                .setQuoteId(quote.getQuoteId())
                .setQuotePurpose(quote.getQuotePurpose())
                .setPrincipalType(validated.principal().type().name())
                .setPrincipalId(validated.principal().id())
                .setPersonaCode(quote.getPersonaCode())
                .setAgentId(quote.getAgentId())
                .setAmountMicro(quote.getAmountMicro())
                .setPeriodSeconds(quote.getPeriodSeconds())
                .setStatus(HostingRentIntentStatus.FUNDS_RESERVED.name())
                .setReserveIdempotencyKey(validated.idempotencyKey())
                .setReserveRequestHash(command.requestHash())
                .setReserveTransactionId(posting.transactionId())
                .setReservedAt(posting.postedAt())
                .setEscrowVersion(posting.escrow().escrowVersion())
                .setVersion(1L)
                .setTenantId(validated.scope().tenantId())
                .setClientId(validated.scope().clientId())
                .setCreateTime(now)
                .setUpdateTime(now);
        try {
            requireOne(hostingMapper.insertLease(lease), "lease insert");
        } catch (DataIntegrityViolationException conflict) {
            throw new HostingRentException(LEASE_CONFLICT, "hosting lease lifecycle conflict");
        }
        requireOne(hostingMapper.insertIntent(intent), "provisioning intent insert");
        return reserveReceipt(intent);
    }

    @Override
    public HostingRentMutationReceipt renew(HostingRentReserveCommand command) {
        if (command == null) throw new HostingRentException(INVALID_COMMAND, "renewal command is incomplete");
        ValidatedMutation validated = validateMutation(command.scope(), command.principal(),
                command.idempotencyKey(), command.requestHash(), command.quoteId(), "quoteId");
        return transactions.execute(status -> {
            EconomyHostingRentQuoteEntity quote = hostingMapper.selectQuoteForUpdate(
                    validated.scope().tenantId(), validated.scope().clientId(), command.quoteId());
            requireOwnedQuote(validated, quote);
            if (!"RENEWAL".equals(quote.getQuotePurpose())) {
                throw new HostingRentException(INVALID_COMMAND, "explicit renewal quote required");
            }
            EconomyHostingProvisioningIntentEntity old = hostingMapper.selectIntentByQuoteForUpdate(
                    validated.scope().tenantId(), validated.scope().clientId(), quote.getQuoteId());
            if (old != null) {
                replayReserve(validated, old);
                if (!"ACTIVE".equals(old.getStatus()) || old.getCaptureTransactionId() == null) {
                    throw new HostingRentException(DATA_CORRUPT, "incomplete atomic renewal");
                }
                return new HostingRentMutationReceipt(old.getIntentId(), old.getLeaseId(), old.getQuoteId(),
                        old.getCaptureTransactionId(), "ACTIVE", old.getAmountMicro(), old.getPeriodSeconds(), old.getCapturedAt());
            }
            if (quote.getExpiresAt() <= positiveNow()) throw new HostingRentException(QUOTE_EXPIRED, "renewal quote expired");
            EconomyHostingLeaseEntity lease = hostingMapper.selectLeaseForUpdate(
                    validated.scope().tenantId(), validated.scope().clientId(), quote.getLeaseId());
            if (lease == null || !"ACTIVE".equals(lease.getStatus())
                    || !exact(lease.getPrincipalId(), validated.principal().id())
                    || !exact(lease.getPrincipalType(), "USER") || !exact(lease.getAgentId(), quote.getAgentId())
                    || !exact(lease.getPersonaCode(), quote.getPersonaCode())
                    || !Objects.equals(lease.getVersion(), quote.getExpectedLeaseVersion())
                    || lease.getPaidThrough() == null || lease.getVersion() == Long.MAX_VALUE) {
                throw new HostingRentException(LEASE_CONFLICT, "renewal lease ownership/version conflict");
            }
            // Each manual order has a fresh escrow business root; never reopen a captured escrow.
            String intentId = requireGeneratedId(intentIds.get(), "intentId");
            EconomyAccountKey held = leaseEscrow(intentId);
            long now = positiveNow();
            ensureAccount(validated.scope(), held, escrowAccountId(validated.scope(), intentId), now);
            EconomyPostingResult reserved = postingService.post(new EconomyPostingCommand(
                    validated.scope(), validated.principal(), command.idempotencyKey(), command.requestHash(),
                    EconomyJournalType.RESERVE_HOSTING_RENT, intentId, List.of(
                    new EconomyPostingLine(userAvailable(validated.principal().id()), -quote.getAmountMicro()),
                    new EconomyPostingLine(held, quote.getAmountMicro())),
                    new EconomyEscrowFunding(EconomyEscrowType.HOSTING_RENT,
                            userAvailable(validated.principal().id()), held, quote.getAmountMicro(), null)));
            EconomyHostingProvisioningIntentEntity intent = new EconomyHostingProvisioningIntentEntity()
                    .setIntentId(intentId).setLeaseId(lease.getLeaseId()).setQuoteId(quote.getQuoteId())
                    .setQuotePurpose("RENEWAL").setPrincipalType("USER").setPrincipalId(validated.principal().id())
                    .setPersonaCode(quote.getPersonaCode()).setAgentId(quote.getAgentId())
                    .setAmountMicro(quote.getAmountMicro()).setPeriodSeconds(quote.getPeriodSeconds())
                    .setStatus("SERVICE_READY").setReserveIdempotencyKey(validated.idempotencyKey())
                    .setReserveRequestHash(command.requestHash()).setReserveTransactionId(reserved.transactionId())
                    .setReservedAt(reserved.postedAt()).setServiceReadyAt(reserved.postedAt())
                    .setOutcomeEvidenceRef("manual-renewal:" + quote.getQuoteId()).setEscrowVersion(1L).setVersion(1L)
                    .setTenantId(validated.scope().tenantId()).setClientId(validated.scope().clientId())
                    .setCreateTime(now).setUpdateTime(now);
            requireOne(hostingMapper.insertIntent(intent), "renewal intent insert");
            requirePersistedRowIdentity(intent.getId(), "renewal intent insert");
            ensureAccount(validated.scope(), hostingRevenue(), "system_hosting_rent", now);
            String captureKey = UUID.nameUUIDFromBytes(("hosting-renewal-capture:" + intentId)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            EconomyPostingResult captured = postingService.post(new EconomyPostingCommand(
                    validated.scope(), validated.principal(), captureKey, command.requestHash(),
                    EconomyJournalType.CAPTURE_HOSTING_RENT, intentId, List.of(
                    new EconomyPostingLine(held, -quote.getAmountMicro()),
                    new EconomyPostingLine(hostingRevenue(), quote.getAmountMicro())), null,
                    new EconomyEscrowSettlement(EconomyEscrowType.HOSTING_RENT, held, hostingRevenue(),
                            quote.getAmountMicro(), 1L, reserved.transactionId())));
            long paidFrom = Math.max(captured.postedAt(), lease.getPaidThrough());
            long paidThrough = checkedAdd(paidFrom, checkedMultiply(quote.getPeriodSeconds(), 1_000L));
            intent.setPaidFrom(paidFrom).setPaidThrough(paidThrough);
            requireOne(hostingMapper.markIntentActive(intent, validated.scope().tenantId(), validated.scope().clientId(),
                    canonicalUuid(captureKey), command.requestHash(), captured.transactionId(), captured.postedAt(), 2L, 2L),
                    "renewal capture CAS");
            requireOne(hostingMapper.renewLease(validated.scope().tenantId(), validated.scope().clientId(),
                    lease, quote, intentId, paidFrom, paidThrough, captured.postedAt()), "renewal lease CAS");
            return new HostingRentMutationReceipt(intentId, lease.getLeaseId(), quote.getQuoteId(),
                    captured.transactionId(), "ACTIVE", quote.getAmountMicro(), quote.getPeriodSeconds(), captured.postedAt());
        });
    }

    @Override
    public void markProvisioningUnknown(HostingRentOutcomeCommand command) {
        updateOutcome(command, true);
    }

    @Override
    public void confirmProvisioningFailedNoEffect(HostingRentOutcomeCommand command) {
        updateOutcome(command, false);
    }

    private void updateOutcome(HostingRentOutcomeCommand command, boolean unknown) {
        ValidatedOutcome validated = validateOutcome(command);
        transactions.executeWithoutResult(status -> {
            EconomyHostingProvisioningIntentEntity intent = requireOwnedIntent(
                    validated.scope(), validated.principal(), validated.intentId());
            String targetStatus = unknown
                    ? HostingRentIntentStatus.PROVISIONING_UNKNOWN.name()
                    : HostingRentIntentStatus.FAILED_NO_EFFECT.name();
            if (targetStatus.equals(intent.getStatus())) {
                if (intent.getVersion() == checkedAdd(validated.expectedIntentVersion(), 1L)
                        && exact(intent.getOutcomeEvidenceRef(), validated.evidenceRef())) return;
                throw new HostingRentException(INTENT_CONFLICT, "provisioning outcome replay conflict");
            }
            if (intent.getVersion() != validated.expectedIntentVersion()
                    || !(HostingRentIntentStatus.FUNDS_RESERVED.name().equals(intent.getStatus())
                         || !unknown && HostingRentIntentStatus.PROVISIONING_UNKNOWN.name().equals(intent.getStatus()))) {
                throw new HostingRentException(INTENT_CONFLICT, "provisioning intent version or state conflict");
            }
            long now = positiveNow();
            long nextVersion = checkedAdd(intent.getVersion(), 1L);
            int rows = unknown
                    ? hostingMapper.markIntentProvisioningUnknown(intent, validated.scope().tenantId(),
                            validated.scope().clientId(), validated.evidenceRef(), nextVersion, now)
                    : hostingMapper.markIntentFailedNoEffect(intent, validated.scope().tenantId(),
                            validated.scope().clientId(), validated.evidenceRef(), nextVersion, now);
            requireOne(rows, "provisioning outcome CAS");
        });
    }

    @Override
    public void confirmProvisioningSucceeded(HostingRentOutcomeCommand command) {
        ValidatedOutcome validated = validateOutcome(command);
        transactions.executeWithoutResult(status -> {
            EconomyHostingProvisioningIntentEntity intent = requireOwnedIntent(
                    validated.scope(), validated.principal(), validated.intentId());
            long readyVersion = checkedAdd(validated.expectedIntentVersion(), 1L);
            boolean ready = HostingRentIntentStatus.SERVICE_READY.name().equals(intent.getStatus());
            boolean active = HostingRentIntentStatus.ACTIVE.name().equals(intent.getStatus());
            // A duplicate success report remains harmless even after capture; no timestamp reset.
            if ((ready && intent.getVersion() == readyVersion
                    || active && intent.getVersion() == checkedAdd(readyVersion, 1L))
                    && exact(intent.getOutcomeEvidenceRef(), validated.evidenceRef())
                    && intent.getServiceReadyAt() != null
                    && (validated.serviceReadyAt() == null || validated.serviceReadyAt().equals(intent.getServiceReadyAt()))) return;
            if (intent.getVersion() != validated.expectedIntentVersion()
                    || !(HostingRentIntentStatus.FUNDS_RESERVED.name().equals(intent.getStatus())
                         || HostingRentIntentStatus.PROVISIONING_UNKNOWN.name().equals(intent.getStatus()))) {
                throw new HostingRentException(INTENT_CONFLICT, "successful reconciliation is stale or terminal");
            }
            long observedNow = positiveNow();
            long now = validated.serviceReadyAt() == null ? observedNow : validated.serviceReadyAt();
            if (now > observedNow || intent.getReservedAt() == null || now < intent.getReservedAt()) {
                throw new HostingRentException(INVALID_COMMAND, "service-ready clock is outside reservation/observation bounds");
            }
            requireOne(hostingMapper.markIntentServiceReady(intent, validated.scope().tenantId(),
                    validated.scope().clientId(), validated.evidenceRef(), readyVersion, now),
                    "successful provisioning reconciliation CAS");
        });
    }

    @Override
    public HostingRentMutationReceipt capture(HostingRentSettlementCommand command) {
        return settle(command, true);
    }

    @Override
    public HostingRentMutationReceipt refund(HostingRentSettlementCommand command) {
        return settle(command, false);
    }

    private HostingRentMutationReceipt settle(HostingRentSettlementCommand command, boolean capture) {
        if (command == null) throw new HostingRentException(INVALID_COMMAND, "settlement command is incomplete");
        ValidatedMutation validated = validateMutation(command.scope(), command.principal(),
                command.idempotencyKey(), command.requestHash(), command.intentId(), "intentId");
        HostingRentMutationReceipt receipt = transactions.execute(status ->
                settleInTransaction(command, validated, capture));
        if (receipt == null) throw new HostingRentException(DATA_CORRUPT, "settlement returned no receipt");
        return receipt;
    }

    private HostingRentMutationReceipt settleInTransaction(
            HostingRentSettlementCommand command, ValidatedMutation validated, boolean capture) {
        // Frozen R01 order: intent -> lease -> account provisioning -> REQUIRED W02 posting.
        EconomyHostingProvisioningIntentEntity intent = requireOwnedIntent(
                validated.scope(), validated.principal(), command.intentId());
        HostingRentMutationReceipt replay = replaySettlementIfPresent(validated, intent, capture);
        if (replay != null) return replay;
        if (intent.getVersion() != command.expectedIntentVersion()) {
            throw new HostingRentException(INTENT_CONFLICT, "provisioning intent version conflict");
        }
        if (capture && HostingRentIntentStatus.PROVISIONING_UNKNOWN.name().equals(intent.getStatus())) {
            throw new HostingRentException(PROVISIONING_OUTCOME_UNKNOWN,
                    "unknown provisioning outcome requires reconciliation before capture");
        }
        if (capture && (!HostingRentIntentStatus.SERVICE_READY.name().equals(intent.getStatus())
                || intent.getServiceReadyAt() == null || intent.getOutcomeEvidenceRef() == null)) {
            throw new HostingRentException(INTENT_CONFLICT, "only confirmed prepared intent may capture");
        }
        if (!capture && !HostingRentIntentStatus.FAILED_NO_EFFECT.name().equals(intent.getStatus())) {
            throw new HostingRentException(REFUND_NOT_ALLOWED,
                    "refund requires durable FAILED_NO_EFFECT provisioning evidence");
        }
        EconomyHostingLeaseEntity lease = hostingMapper.selectLeaseForUpdate(
                validated.scope().tenantId(), validated.scope().clientId(), intent.getLeaseId());
        requireLeaseMatchesIntent(validated, lease, intent);

        long now = positiveNow();
        EconomyAccountKey escrow = leaseEscrow(intent.getLeaseId());
        EconomyAccountKey destination = capture
                ? hostingRevenue() : userAvailable(validated.principal().id());
        if (capture) ensureAccount(validated.scope(), destination, "system_hosting_rent", now);
        EconomyPostingResult posting = postingService.post(new EconomyPostingCommand(
                validated.scope(), validated.principal(), command.idempotencyKey(), command.requestHash(),
                capture ? EconomyJournalType.CAPTURE_HOSTING_RENT : EconomyJournalType.REFUND_HOSTING_RENT,
                intent.getLeaseId(), List.of(
                    new EconomyPostingLine(escrow, -intent.getAmountMicro()),
                    new EconomyPostingLine(destination, intent.getAmountMicro())), null,
                new EconomyEscrowSettlement(EconomyEscrowType.HOSTING_RENT,
                        escrow, destination, intent.getAmountMicro(), intent.getEscrowVersion(),
                        intent.getReserveTransactionId())));
        long nextIntentVersion = checkedAdd(intent.getVersion(), 1L);
        long nextLeaseVersion = checkedAdd(lease.getVersion(), 1L);
        long nextEscrowVersion = checkedAdd(intent.getEscrowVersion(), 1L);
        if (capture) {
            long paidFrom = intent.getServiceReadyAt();
            long paidThrough = checkedAdd(paidFrom, checkedMultiply(intent.getPeriodSeconds(), 1_000L));
            intent.setPaidFrom(paidFrom).setPaidThrough(paidThrough);
            requireOne(hostingMapper.markIntentActive(intent, validated.scope().tenantId(),
                    validated.scope().clientId(), validated.idempotencyKey(), command.requestHash(),
                    posting.transactionId(), posting.postedAt(), nextEscrowVersion, nextIntentVersion),
                    "intent capture CAS");
            requireOne(hostingMapper.markLeaseActive(lease, validated.scope().tenantId(),
                    validated.scope().clientId(), paidFrom, paidThrough, nextLeaseVersion, posting.postedAt()),
                    "lease activation CAS");
        } else {
            requireOne(hostingMapper.markIntentRefunded(intent, validated.scope().tenantId(),
                    validated.scope().clientId(), validated.idempotencyKey(), command.requestHash(),
                    posting.transactionId(), posting.postedAt(), nextEscrowVersion, nextIntentVersion),
                    "intent refund CAS");
            requireOne(hostingMapper.markLeaseRefunded(lease, validated.scope().tenantId(),
                    validated.scope().clientId(), nextLeaseVersion, now), "lease refund CAS");
        }
        return new HostingRentMutationReceipt(intent.getIntentId(), intent.getLeaseId(), intent.getQuoteId(),
                posting.transactionId(), capture ? HostingRentIntentStatus.ACTIVE.name()
                        : HostingRentIntentStatus.REFUNDED.name(),
                intent.getAmountMicro(), intent.getPeriodSeconds(), posting.postedAt());
    }

    private ValidatedQuote validateQuote(HostingRentQuoteCommand command) {
        if (command == null || command.purpose() == null) {
            throw new HostingRentException(INVALID_COMMAND, "quote command is incomplete");
        }
        ValidatedMutation base = validateMutation(command.scope(), command.principal(),
                command.idempotencyKey(), command.requestHash(), command.planId(), "planId");
        requireExact(command.personaCode(), "personaCode", 100);
        requireExact(command.agentId(), "agentId", 100);
        if (command.planVersion() <= 0) throw new HostingRentException(INVALID_COMMAND, "planVersion must be positive");
        if (command.purpose() == HostingRentQuotePurpose.INITIAL) {
            if (command.leaseId() != null || command.expectedLeaseVersion() != null) {
                throw new HostingRentException(INVALID_COMMAND, "initial quote must not carry renewal state");
            }
        } else {
            requireExact(command.leaseId(), "leaseId", 100);
            if (command.expectedLeaseVersion() == null || command.expectedLeaseVersion() <= 0) {
                throw new HostingRentException(INVALID_COMMAND, "renewal quote requires expectedLeaseVersion");
            }
        }
        return new ValidatedQuote(command, base.scope(), base.principal(), base.idempotencyKey());
    }

    private ValidatedMutation validateMutation(
            EconomyScope scope, EconomyPrincipal principal, String idempotencyKey,
            byte[] requestHash, String businessId, String businessName) {
        requireScopeAndPrincipal(scope, principal);
        requireExact(businessId, businessName, 100);
        byte[] key = canonicalUuid(idempotencyKey);
        if (requestHash == null || requestHash.length != EconomyConstants.REQUEST_HASH_BYTES) {
            throw new HostingRentException(INVALID_COMMAND, "requestHash must be exactly 32 bytes");
        }
        return new ValidatedMutation(scope, principal, key, requestHash.clone());
    }

    private ValidatedOutcome validateOutcome(HostingRentOutcomeCommand command) {
        if (command == null || command.expectedIntentVersion() <= 0) {
            throw new HostingRentException(INVALID_COMMAND, "outcome command is incomplete");
        }
        requireScopeAndPrincipal(command.scope(), command.principal());
        requireExact(command.intentId(), "intentId", 100);
        requireExact(command.evidenceRef(), "evidenceRef", 100);
        return new ValidatedOutcome(command.scope(), command.principal(), command.intentId(),
                command.expectedIntentVersion(), command.evidenceRef(), command.serviceReadyAt());
    }

    private void requireScopeAndPrincipal(EconomyScope scope, EconomyPrincipal principal) {
        if (scope == null || principal == null || principal.type() != EconomyPrincipalType.USER) {
            throw new HostingRentException(INVALID_COMMAND, "authenticated USER scope is required");
        }
        requireExact(scope.tenantId(), "tenantId", 50);
        requireExact(scope.clientId(), "clientId", 50);
        requireExact(principal.id(), "principalId", 100);
    }

    private void requireActivePlan(ValidatedQuote command, EconomyHostingRentPlanEntity plan) {
        if (plan == null) throw new HostingRentException(NOT_CONFIGURED, "hosting-rent plan is not configured");
        if (plan.getId() == null || !"ACTIVE".equals(plan.getStatus())
                || !EconomyConstants.CURRENCY_SILVER.equals(plan.getCurrency())
                || plan.getPlanVersion() == null || plan.getPlanVersion() <= 0
                || plan.getAmountMicro() == null || plan.getAmountMicro() <= 0
                || plan.getPeriodSeconds() == null || plan.getPeriodSeconds() <= 0
                || plan.getQuoteTtlSeconds() == null || plan.getQuoteTtlSeconds() <= 0
                || !exact(plan.getTenantId(), command.scope().tenantId())
                || !exact(plan.getClientId(), command.scope().clientId())
                || !exact(plan.getPlanId(), command.command().planId())
                || plan.getPlanVersion() != command.command().planVersion()) {
            throw new HostingRentException(NOT_CONFIGURED, "hosting-rent plan is disabled or corrupt");
        }
    }

    private void requireRenewalLease(ValidatedQuote command, EconomyHostingLeaseEntity lease) {
        if (lease == null) throw new HostingRentException(NOT_FOUND_OR_FORBIDDEN, "lease is not visible");
        if (!HostingRentLeaseStatus.ACTIVE.name().equals(lease.getStatus())
                || !exact(lease.getPrincipalType(), command.principal().type().name())
                || !exact(lease.getPrincipalId(), command.principal().id())
                || !exact(lease.getPersonaCode(), command.command().personaCode())
                || !exact(lease.getAgentId(), command.command().agentId())
                || lease.getVersion() == null
                || !lease.getVersion().equals(command.command().expectedLeaseVersion())) {
            throw new HostingRentException(NOT_FOUND_OR_FORBIDDEN, "lease is not visible");
        }
    }

    private void requireOwnedQuote(ValidatedMutation command, EconomyHostingRentQuoteEntity quote) {
        if (quote == null || quote.getId() == null
                || !exact(quote.getTenantId(), command.scope().tenantId())
                || !exact(quote.getClientId(), command.scope().clientId())
                || !exact(quote.getPrincipalType(), command.principal().type().name())
                || !exact(quote.getPrincipalId(), command.principal().id())
                || quote.getAmountMicro() == null || quote.getAmountMicro() <= 0
                || quote.getPeriodSeconds() == null || quote.getPeriodSeconds() <= 0
                || quote.getExpiresAt() == null || quote.getRequestHash() == null
                || quote.getAgentId() == null || quote.getPersonaCode() == null) {
            throw new HostingRentException(NOT_FOUND_OR_FORBIDDEN, "quote is not visible");
        }
    }

    private EconomyHostingProvisioningIntentEntity requireOwnedIntent(
            EconomyScope scope, EconomyPrincipal principal, String intentId) {
        EconomyHostingProvisioningIntentEntity intent = hostingMapper.selectIntentForUpdate(
                scope.tenantId(), scope.clientId(), intentId);
        if (intent == null || intent.getId() == null
                || !exact(intent.getPrincipalType(), principal.type().name())
                || !exact(intent.getPrincipalId(), principal.id())
                || !exact(intent.getTenantId(), scope.tenantId())
                || !exact(intent.getClientId(), scope.clientId())
                || intent.getVersion() == null || intent.getVersion() <= 0
                || intent.getEscrowVersion() == null || intent.getEscrowVersion() <= 0) {
            throw new HostingRentException(NOT_FOUND_OR_FORBIDDEN, "provisioning intent is not visible");
        }
        return intent;
    }

    private void requireLeaseMatchesIntent(
            ValidatedMutation command, EconomyHostingLeaseEntity lease,
            EconomyHostingProvisioningIntentEntity intent) {
        if (lease == null || lease.getId() == null || lease.getVersion() == null
                || !HostingRentLeaseStatus.PROVISIONING.name().equals(lease.getStatus())
                || !exact(lease.getPrincipalType(), command.principal().type().name())
                || !exact(lease.getPrincipalId(), command.principal().id())
                || !exact(lease.getAgentId(), intent.getAgentId())
                || !exact(lease.getPersonaCode(), intent.getPersonaCode())
                || !exact(lease.getLatestIntentId(), intent.getIntentId())) {
            throw new HostingRentException(DATA_CORRUPT, "lease and provisioning intent association is corrupt");
        }
    }

    private HostingRentQuoteReceipt replayQuote(
            ValidatedQuote command, EconomyHostingRentQuoteEntity quote) {
        if (!MessageDigest.isEqual(command.command().requestHash(), quote.getRequestHash())
                || !exact(quote.getQuotePurpose(), command.command().purpose().name())
                || !exact(quote.getPlanId(), command.command().planId())
                || quote.getPlanVersion() != command.command().planVersion()
                || !exact(quote.getPersonaCode(), command.command().personaCode())
                || !exact(quote.getAgentId(), command.command().agentId())
                || !Objects.equals(quote.getLeaseId(), command.command().leaseId())
                || !Objects.equals(quote.getExpectedLeaseVersion(), command.command().expectedLeaseVersion())) {
            throw new HostingRentException(IDEMPOTENCY_CONFLICT, "quote idempotency key has different bytes");
        }
        return toQuoteReceipt(quote);
    }

    private HostingRentMutationReceipt replayReserve(
            ValidatedMutation command, EconomyHostingProvisioningIntentEntity intent) {
        if (!MessageDigest.isEqual(command.requestHash(), intent.getReserveRequestHash())
                || !MessageDigest.isEqual(command.idempotencyKey(), intent.getReserveIdempotencyKey())) {
            throw new HostingRentException(QUOTE_ALREADY_CONSUMED,
                    "quote is already bound to a different reserve operation");
        }
        return reserveReceipt(intent);
    }

    private HostingRentMutationReceipt replaySettlementIfPresent(
            ValidatedMutation command, EconomyHostingProvisioningIntentEntity intent, boolean capture) {
        byte[] key = capture ? intent.getCaptureIdempotencyKey() : intent.getRefundIdempotencyKey();
        if (key == null) return null;
        byte[] hash = capture ? intent.getCaptureRequestHash() : intent.getRefundRequestHash();
        if (!MessageDigest.isEqual(command.idempotencyKey(), key)
                || !MessageDigest.isEqual(command.requestHash(), hash)) {
            throw new HostingRentException(IDEMPOTENCY_CONFLICT,
                    "settlement idempotency key has different bytes");
        }
        return new HostingRentMutationReceipt(intent.getIntentId(), intent.getLeaseId(), intent.getQuoteId(),
                capture ? intent.getCaptureTransactionId() : intent.getRefundTransactionId(),
                capture ? HostingRentIntentStatus.ACTIVE.name() : HostingRentIntentStatus.REFUNDED.name(),
                intent.getAmountMicro(), intent.getPeriodSeconds(),
                capture ? intent.getCapturedAt() : intent.getRefundedAt());
    }

    private HostingRentQuoteReceipt toQuoteReceipt(EconomyHostingRentQuoteEntity quote) {
        return new HostingRentQuoteReceipt(quote.getQuoteId(), HostingRentQuotePurpose.valueOf(quote.getQuotePurpose()),
                quote.getPlanId(), quote.getPlanVersion(), quote.getAmountMicro(), quote.getPeriodSeconds(),
                quote.getPersonaCode(), quote.getAgentId(), quote.getLeaseId(),
                quote.getExpectedLeaseVersion(), quote.getExpiresAt());
    }

    private HostingRentMutationReceipt reserveReceipt(EconomyHostingProvisioningIntentEntity intent) {
        return new HostingRentMutationReceipt(intent.getIntentId(), intent.getLeaseId(), intent.getQuoteId(),
                intent.getReserveTransactionId(), HostingRentIntentStatus.FUNDS_RESERVED.name(),
                intent.getAmountMicro(), intent.getPeriodSeconds(), intent.getReservedAt());
    }

    private void ensureAccount(EconomyScope scope, EconomyAccountKey key, String accountId, long now) {
        EconomyAccountEntity account = new EconomyAccountEntity()
                .setAccountId(accountId)
                .setOwnerType(key.ownerType().name())
                .setOwnerId(key.ownerId())
                .setPurpose(key.purpose().name())
                .setCurrency(key.currency())
                .setBalanceMicro(0L)
                .setAllowNegative(0)
                .setStatus("ACTIVE")
                .setVersion(0L)
                .setTenantId(scope.tenantId())
                .setClientId(scope.clientId())
                .setCreateTime(now)
                .setUpdateTime(now);
        ledgerMapper.insertAccountIfAbsent(account);
    }

    private EconomyAccountKey userAvailable(String actorId) {
        return new EconomyAccountKey(EconomyConstants.CURRENCY_SILVER,
                EconomyAccountOwnerType.USER, actorId, EconomyAccountPurpose.AVAILABLE);
    }

    private EconomyAccountKey leaseEscrow(String leaseId) {
        return new EconomyAccountKey(EconomyConstants.CURRENCY_SILVER,
                EconomyAccountOwnerType.LEASE, leaseId, EconomyAccountPurpose.ESCROW);
    }

    private EconomyAccountKey hostingRevenue() {
        return new EconomyAccountKey(EconomyConstants.CURRENCY_SILVER,
                EconomyAccountOwnerType.SYSTEM, EconomyConstants.HOSTING_RENT_SYSTEM_OWNER_ID,
                EconomyAccountPurpose.HOSTING_RENT);
    }

    private String escrowAccountId(EconomyScope scope, String leaseId) {
        String input = "CYF_HOSTING_ESCROW_ACCOUNT_V1\0" + scope.tenantId() + "\0"
                + scope.clientId() + "\0" + leaseId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return "hosting_esc_" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private byte[] canonicalUuid(String value) {
        if (value == null || value.length() != 36 || !value.chars().allMatch(unit -> unit < 128)) {
            throw new HostingRentException(INVALID_COMMAND, "idempotencyKey must be a canonical lowercase UUID");
        }
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            throw new HostingRentException(INVALID_COMMAND, "idempotencyKey must be a canonical lowercase UUID");
        }
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private void requireExact(String value, String name, int maxBytes) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || isPadding(value.codePointAt(0)) || isPadding(value.codePointBefore(value.length()))) {
            throw new HostingRentException(INVALID_COMMAND, name + " is not an exact identity");
        }
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new HostingRentException(INVALID_COMMAND, name + " is not valid UTF-8");
                }
            } else if (Character.isLowSurrogate(unit) || Character.isISOControl(unit)) {
                throw new HostingRentException(INVALID_COMMAND, name + " is not an exact identity");
            }
        }
    }

    private boolean exact(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private String requireGeneratedId(String id, String name) {
        requireExact(id, name, 100);
        return id;
    }

    private long positiveNow() {
        long now = clock.getAsLong();
        if (now <= 0) throw new HostingRentException(INVALID_COMMAND, "clock must return positive epoch millis");
        return now;
    }

    private long checkedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new HostingRentException(INVALID_COMMAND, "hosting-rent numeric overflow");
        }
    }

    private long checkedMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new HostingRentException(INVALID_COMMAND, "hosting-rent numeric overflow");
        }
    }

    private void requireOne(int rows, String operation) {
        if (rows != 1) {
            throw new HostingRentException(CONCURRENCY_CONFLICT,
                    operation + " affected " + rows + " rows; expected one");
        }
    }

    private void requirePersistedRowIdentity(Long id, String operation) {
        if (id == null || id <= 0) {
            throw new HostingRentException(DATA_CORRUPT,
                    operation + " did not hydrate the generated row identity");
        }
    }

    private record ValidatedMutation(
            EconomyScope scope, EconomyPrincipal principal, byte[] idempotencyKey, byte[] requestHash) {
        private ValidatedMutation {
            idempotencyKey = idempotencyKey.clone();
            requestHash = requestHash.clone();
        }

        @Override
        public byte[] idempotencyKey() {
            return idempotencyKey.clone();
        }

        @Override
        public byte[] requestHash() {
            return requestHash.clone();
        }
    }

    private record ValidatedQuote(
            HostingRentQuoteCommand command,
            EconomyScope scope,
            EconomyPrincipal principal,
            byte[] idempotencyKey) {
        private ValidatedQuote {
            idempotencyKey = idempotencyKey.clone();
        }

        @Override
        public byte[] idempotencyKey() {
            return idempotencyKey.clone();
        }
    }

    private record ValidatedOutcome(
            EconomyScope scope,
            EconomyPrincipal principal,
            String intentId,
            long expectedIntentVersion,
            String evidenceRef, Long serviceReadyAt) {
    }
}
