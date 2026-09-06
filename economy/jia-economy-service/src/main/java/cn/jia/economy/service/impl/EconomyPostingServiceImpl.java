package cn.jia.economy.service.impl;

import cn.jia.economy.common.EconomyAccountOwnerType;
import cn.jia.economy.common.EconomyAccountPurpose;
import cn.jia.economy.common.EconomyConstants;
import cn.jia.economy.common.EconomyEscrowType;
import cn.jia.economy.common.EconomyJournalType;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.entity.EconomyAccountEntity;
import cn.jia.economy.entity.EconomyEntryEntity;
import cn.jia.economy.entity.EconomyEscrowEntity;
import cn.jia.economy.entity.EconomyEscrowFundingLotEntity;
import cn.jia.economy.entity.EconomyTransactionEntity;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.service.EconomyAccountKey;
import cn.jia.economy.service.EconomyEscrowFunding;
import cn.jia.economy.service.EconomyEscrowResult;
import cn.jia.economy.service.EconomyEscrowSettlement;
import cn.jia.economy.service.EconomyPostedLine;
import cn.jia.economy.service.EconomyPostingCommand;
import cn.jia.economy.service.EconomyPostingLine;
import cn.jia.economy.service.EconomyPostingResult;
import cn.jia.economy.service.EconomyPostingService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static cn.jia.economy.exception.EconomyPostingException.Reason.ACCOUNT_NOT_ACTIVE;
import static cn.jia.economy.exception.EconomyPostingException.Reason.ACCOUNT_NOT_FOUND;
import static cn.jia.economy.exception.EconomyPostingException.Reason.AMOUNT_RANGE_EXCEEDED;
import static cn.jia.economy.exception.EconomyPostingException.Reason.CONCURRENCY_CONFLICT;
import static cn.jia.economy.exception.EconomyPostingException.Reason.ESCROW_CONFLICT;
import static cn.jia.economy.exception.EconomyPostingException.Reason.IDEMPOTENCY_CONFLICT;
import static cn.jia.economy.exception.EconomyPostingException.Reason.IMBALANCED_TRANSACTION;
import static cn.jia.economy.exception.EconomyPostingException.Reason.INSUFFICIENT_FUNDS;
import static cn.jia.economy.exception.EconomyPostingException.Reason.INVALID_COMMAND;
import static cn.jia.economy.exception.EconomyPostingException.Reason.JOURNAL_CORRUPT;
import static cn.jia.economy.exception.EconomyPostingException.Reason.JOURNAL_ID_CONFLICT;

@Service
public class EconomyPostingServiceImpl implements EconomyPostingService {
    private static final Set<EconomyJournalType> V0_ENABLED_TYPES = Set.of(
            EconomyJournalType.ISSUE_SILVER,
            EconomyJournalType.RESERVE_BOUNTY,
            EconomyJournalType.RESERVE_SKILL,
            EconomyJournalType.RESERVE_HOSTING_RENT,
            EconomyJournalType.CAPTURE_HOSTING_RENT,
            EconomyJournalType.REFUND_HOSTING_RENT,
            EconomyJournalType.REFUND_BOUNTY,
            EconomyJournalType.CAPTURE_COMPUTE, EconomyJournalType.CAPTURE_FEE, EconomyJournalType.PAY_AGENT,
            EconomyJournalType.CAPTURE_SKILL, EconomyJournalType.REFUND_SKILL);

    private final EconomyLedgerMapper mapper;
    private final EconomyPreviewGate gate;
    private final TransactionTemplate transactions;
    private final Supplier<String> transactionIds;
    private final Supplier<String> escrowIds;
    private final LongSupplier clock;

    public EconomyPostingServiceImpl(
            EconomyLedgerMapper mapper,
            PlatformTransactionManager transactionManager,
            EconomyPreviewGate gate) {
        this(mapper, transactionManager, gate,
                () -> "etx_" + UUID.randomUUID(),
                () -> "esc_" + UUID.randomUUID(),
                System::currentTimeMillis);
    }

    EconomyPostingServiceImpl(
            EconomyLedgerMapper mapper,
            PlatformTransactionManager transactionManager,
            EconomyPreviewGate gate,
            Supplier<String> transactionIds,
            Supplier<String> escrowIds,
            LongSupplier clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.transactionIds = Objects.requireNonNull(transactionIds, "transactionIds");
        this.escrowIds = Objects.requireNonNull(escrowIds, "escrowIds");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public EconomyPostingResult post(EconomyPostingCommand command) {
        return post(command, false);
    }

    /** Package-owned path used only by {@link EconomyTreasuryPostingServiceImpl}. */
    EconomyPostingResult postTreasuryIssue(EconomyPostingCommand command) {
        return post(command, true);
    }

    private EconomyPostingResult post(EconomyPostingCommand command, boolean treasuryAuthority) {
        ValidatedPosting validated = validate(command, treasuryAuthority);
        EconomyPostingResult result = transactions.execute(status -> postInTransaction(validated));
        if (result == null) throw new EconomyPostingException(JOURNAL_CORRUPT, "posting returned no result");
        return result;
    }

    private EconomyPostingResult postInTransaction(ValidatedPosting posting) {
        long now = positiveNow();
        String transactionId = requireGeneratedId(transactionIds.get(), "transactionId");
        EconomyTransactionEntity reservation = new EconomyTransactionEntity()
                .setTransactionId(transactionId)
                .setPrincipalType(posting.command().principal().type().name())
                .setPrincipalId(posting.command().principal().id())
                .setIdempotencyKey(posting.idempotencyKey())
                .setRequestHash(posting.command().requestHash())
                .setBusinessType(posting.command().journalType().name())
                .setBusinessId(posting.command().businessId())
                .setCurrency(EconomyConstants.CURRENCY_SILVER)
                .setStatus("POSTING")
                .setEntryCount(0)
                .setDebitTotalMicro(0L)
                .setCreditTotalMicro(0L)
                .setTenantId(posting.command().scope().tenantId())
                .setClientId(posting.command().scope().clientId())
                .setCreateTime(now)
                .setUpdateTime(now);
        try {
            requireOne(mapper.insertTransaction(reservation), "idempotency reservation insert");
        } catch (DataIntegrityViolationException duplicate) {
            EconomyTransactionEntity existing = mapper.selectTransactionByActorKeyForUpdate(
                    reservation.getTenantId(), reservation.getClientId(),
                    reservation.getPrincipalType(), reservation.getPrincipalId(),
                    reservation.getIdempotencyKey());
            if (existing != null) return replay(posting, existing);
            EconomyTransactionEntity idCollision = mapper.selectTransactionByIdForUpdate(
                    reservation.getTenantId(), reservation.getClientId(), transactionId);
            if (idCollision != null) {
                throw new EconomyPostingException(JOURNAL_ID_CONFLICT,
                        "generated transaction identifier already exists", duplicate);
            }
            throw new EconomyPostingException(CONCURRENCY_CONFLICT,
                    "unable to resolve idempotency reservation conflict", duplicate);
        }

        // Frozen order: caller-held funding/allocation state -> sorted economy accounts -> escrow root.
        // W02 owns no task/order funding root and performs no external I/O; its immutable funding
        // request is resolved before account locks, while the escrow root remains last.
        FundingState fundingState = resolveFundingState(posting);
        TreeMap<EconomyAccountKey, LockedAccount> accounts = lockAccounts(posting);
        EscrowPlan escrow = planEscrow(posting, fundingState, accounts, transactionId, now);
        EscrowSettlementPlan settlement = planEscrowSettlement(posting, accounts, now);

        for (LockedAccount locked : accounts.values()) {
            requireOne(mapper.updateAccountBalance(
                    locked.entity(), reservation.getTenantId(), reservation.getClientId(),
                    locked.balanceAfter(), locked.versionAfter(), now),
                    "account balance CAS");
        }

        if (escrow != null) persistEscrow(escrow);
        if (settlement != null) persistEscrowSettlement(settlement);

        List<EconomyPostedLine> postedLines = new ArrayList<>();
        int sequence = 0;
        for (Map.Entry<EconomyAccountKey, LockedAccount> entry : accounts.entrySet()) {
            sequence = Math.incrementExact(sequence);
            LockedAccount locked = entry.getValue();
            EconomyEntryEntity journalEntry = new EconomyEntryEntity()
                    .setEntryId(transactionId + ":" + sequence)
                    .setTransactionId(transactionId)
                    .setAccountId(locked.entity().getAccountId())
                    .setEntrySequence(sequence)
                    .setSignedAmountMicro(locked.signedAmount())
                    .setBalanceAfterMicro(locked.balanceAfter())
                    .setCurrency(EconomyConstants.CURRENCY_SILVER)
                    .setStatus("POSTED")
                    .setPostedAt(now)
                    .setTenantId(reservation.getTenantId())
                    .setClientId(reservation.getClientId())
                    .setCreateTime(now);
            requireOne(mapper.insertEntry(journalEntry), "journal entry insert");
            postedLines.add(new EconomyPostedLine(
                    journalEntry.getAccountId(), sequence,
                    locked.signedAmount(), locked.balanceAfter()));
        }

        requireOne(mapper.markTransactionPosted(
                reservation.getTenantId(), reservation.getClientId(), transactionId,
                postedLines.size(), posting.debitTotal(), posting.creditTotal(), now),
                "journal POSTED finalize");

        return new EconomyPostingResult(
                transactionId, "POSTED", EconomyConstants.CURRENCY_SILVER, now,
                posting.debitTotal(), posting.creditTotal(), postedLines,
                escrow == null ? null : escrow.result());
    }

    private TreeMap<EconomyAccountKey, LockedAccount> lockAccounts(ValidatedPosting posting) {
        TreeMap<EconomyAccountKey, LockedAccount> locked = new TreeMap<>(EconomyAccountKey.LOCK_ORDER);
        for (EconomyPostingLine line : posting.lines()) {
            EconomyAccountKey key = line.account();
            EconomyAccountEntity account = mapper.selectAccountForUpdate(
                    posting.command().scope().tenantId(), posting.command().scope().clientId(),
                    key.currency(), key.ownerType().name(), key.ownerId(), key.purpose().name());
            if (account == null) {
                throw new EconomyPostingException(ACCOUNT_NOT_FOUND, "economy account is not provisioned");
            }
            requireAccountMatches(key, account, posting.command());
            if (!"ACTIVE".equals(account.getStatus())) {
                throw new EconomyPostingException(ACCOUNT_NOT_ACTIVE, "economy account is not active");
            }
            long balanceAfter = checkedAdd(account.getBalanceMicro(), line.signedAmountMicro());
            boolean negativeCapable = isNegativeCapableSystemAccount(key);
            if (balanceAfter < 0
                    && (!negativeCapable || !Integer.valueOf(1).equals(account.getAllowNegative()))) {
                throw new EconomyPostingException(INSUFFICIENT_FUNDS, "insufficient SILVER balance");
            }
            long versionAfter = checkedAdd(account.getVersion(), 1L);
            locked.put(key, new LockedAccount(account, line.signedAmountMicro(), balanceAfter, versionAfter));
        }
        return locked;
    }

    private FundingState resolveFundingState(ValidatedPosting posting) {
        EconomyEscrowFunding funding = posting.command().escrowFunding();
        if (funding == null) return FundingState.none();
        return new FundingState(funding);
    }

    private EscrowPlan planEscrow(
            ValidatedPosting posting,
            FundingState fundingState,
            TreeMap<EconomyAccountKey, LockedAccount> accounts,
            String transactionId,
            long now) {
        EconomyEscrowFunding funding = fundingState.funding();
        if (funding == null) return null;
        LockedAccount payer = accounts.get(funding.payerAccount());
        LockedAccount held = accounts.get(funding.escrowAccount());
        EconomyEscrowEntity current = mapper.selectEscrowByBusinessForUpdate(
                posting.command().scope().tenantId(), posting.command().scope().clientId(),
                funding.escrowType().name(), posting.command().businessId());
        EconomyEscrowEntity target;
        int fundingSequence;
        boolean insert;
        if (current == null) {
            if (funding.expectedEscrowVersion() != null) {
                throw new EconomyPostingException(ESCROW_CONFLICT, "escrow root does not exist at expected version");
            }
            target = new EconomyEscrowEntity()
                    .setEscrowId(requireGeneratedId(escrowIds.get(), "escrowId"))
                    .setBusinessType(funding.escrowType().name())
                    .setBusinessId(posting.command().businessId())
                    .setPayerAccountId(payer.entity().getAccountId())
                    .setEscrowAccountId(held.entity().getAccountId())
                    .setCurrency(EconomyConstants.CURRENCY_SILVER)
                    .setGrossMicro(funding.amountMicro())
                    .setCapturedMicro(0L)
                    .setRefundedMicro(0L)
                    .setStatus("ACTIVE")
                    .setVersion(1L)
                    .setTenantId(posting.command().scope().tenantId())
                    .setClientId(posting.command().scope().clientId())
                    .setCreateTime(now)
                    .setUpdateTime(now);
            fundingSequence = 1;
            insert = true;
        } else {
            requireEscrowMatches(posting, funding, payer.entity(), held.entity(), current);
            if (funding.expectedEscrowVersion() == null
                    || !funding.expectedEscrowVersion().equals(current.getVersion())) {
                throw new EconomyPostingException(ESCROW_CONFLICT, "escrow version conflict");
            }
            Integer maxSequence = mapper.selectMaxFundingSequence(
                    current.getTenantId(), current.getClientId(), current.getEscrowId());
            if (maxSequence == null || maxSequence < 1 || maxSequence == Integer.MAX_VALUE) {
                throw new EconomyPostingException(JOURNAL_CORRUPT, "escrow funding sequence is invalid");
            }
            fundingSequence = maxSequence + 1;
            target = new EconomyEscrowEntity()
                    .setId(current.getId())
                    .setEscrowId(current.getEscrowId())
                    .setBusinessType(current.getBusinessType())
                    .setBusinessId(current.getBusinessId())
                    .setPayerAccountId(current.getPayerAccountId())
                    .setEscrowAccountId(current.getEscrowAccountId())
                    .setCurrency(current.getCurrency())
                    .setGrossMicro(checkedAdd(current.getGrossMicro(), funding.amountMicro()))
                    .setCapturedMicro(current.getCapturedMicro())
                    .setRefundedMicro(current.getRefundedMicro())
                    .setStatus(current.getStatus())
                    .setVersion(checkedAdd(current.getVersion(), 1L))
                    .setTenantId(current.getTenantId())
                    .setClientId(current.getClientId())
                    .setCreateTime(current.getCreateTime())
                    .setUpdateTime(now);
            insert = false;
        }
        EconomyEscrowFundingLotEntity lot = new EconomyEscrowFundingLotEntity()
                .setEscrowId(target.getEscrowId())
                .setFundingSequence(fundingSequence)
                .setReserveTransactionId(transactionId)
                .setPayerAccountId(target.getPayerAccountId())
                .setAmountMicro(funding.amountMicro())
                .setEscrowGrossAfterMicro(target.getGrossMicro())
                .setEscrowVersionAfter(target.getVersion())
                .setCurrency(EconomyConstants.CURRENCY_SILVER)
                .setTenantId(target.getTenantId())
                .setClientId(target.getClientId())
                .setCreatedAt(now);
        return new EscrowPlan(current, target, lot, insert,
                new EconomyEscrowResult(target.getEscrowId(), fundingSequence,
                        funding.amountMicro(), target.getGrossMicro(), target.getVersion()));
    }

    private void persistEscrow(EscrowPlan escrow) {
        if (escrow.insert()) {
            requireOne(mapper.insertEscrow(escrow.target()), "escrow root insert");
        } else {
            requireOne(mapper.updateEscrowGross(
                    escrow.current(), escrow.target().getTenantId(), escrow.target().getClientId(),
                    escrow.target().getGrossMicro(), escrow.target().getVersion(),
                    escrow.target().getUpdateTime()), "escrow root CAS");
        }
        requireOne(mapper.insertFundingLot(escrow.lot()), "escrow funding lot insert");
    }


    private EscrowSettlementPlan planEscrowSettlement(
            ValidatedPosting posting,
            TreeMap<EconomyAccountKey, LockedAccount> accounts,
            long now) {
        EconomyEscrowSettlement settlement = posting.command().escrowSettlement();
        if (settlement == null) return null;
        EconomyEscrowEntity current = mapper.selectEscrowByBusinessForUpdate(
                posting.command().scope().tenantId(), posting.command().scope().clientId(),
                settlement.escrowType().name(), posting.command().businessId());
        if (current == null || current.getId() == null || current.getVersion() == null
                || current.getGrossMicro() == null || current.getCapturedMicro() == null
                || current.getRefundedMicro() == null
                || current.getVersion() != settlement.expectedEscrowVersion()
                || !settlement.escrowType().name().equals(current.getBusinessType())
                || !posting.command().businessId().equals(current.getBusinessId())
                || !accounts.get(settlement.escrowAccount()).entity().getAccountId()
                        .equals(current.getEscrowAccountId())
                || ((posting.command().journalType() == EconomyJournalType.REFUND_HOSTING_RENT
                    || posting.command().journalType() == EconomyJournalType.REFUND_BOUNTY
                    || posting.command().journalType() == EconomyJournalType.REFUND_SKILL)
                    && !accounts.get(settlement.destinationAccount()).entity().getAccountId()
                            .equals(current.getPayerAccountId()))
                || !("ACTIVE".equals(current.getStatus())
                    || isBountyCapture(posting.command().journalType()) && "PARTIALLY_CAPTURED".equals(current.getStatus()))
                || !EconomyConstants.CURRENCY_SILVER.equals(current.getCurrency())) {
            throw new EconomyPostingException(ESCROW_CONFLICT, "escrow settlement root is incompatible");
        }
        EconomyEscrowFundingLotEntity fundingLot = mapper.selectFundingLotByTransaction(
                current.getTenantId(), current.getClientId(), settlement.reserveTransactionId());
        if (fundingLot == null || !current.getEscrowId().equals(fundingLot.getEscrowId())) {
            throw new EconomyPostingException(ESCROW_CONFLICT,
                    "escrow settlement does not reference its immutable reserve transaction");
        }
        long remaining = checkedAdd(checkedAdd(current.getGrossMicro(), -current.getCapturedMicro()),
                -current.getRefundedMicro());
        if (settlement.amountMicro() > remaining) {
            throw new EconomyPostingException(ESCROW_CONFLICT, "escrow settlement exceeds remaining funds");
        }
        boolean capture = posting.command().journalType() == EconomyJournalType.CAPTURE_HOSTING_RENT
                || isBountyCapture(posting.command().journalType())
                || posting.command().journalType() == EconomyJournalType.CAPTURE_SKILL;
        long capturedAfter = capture
                ? checkedAdd(current.getCapturedMicro(), settlement.amountMicro())
                : current.getCapturedMicro();
        long refundedAfter = capture
                ? current.getRefundedMicro()
                : checkedAdd(current.getRefundedMicro(), settlement.amountMicro());
        long remainingAfter = checkedAdd(checkedAdd(current.getGrossMicro(), -capturedAfter), -refundedAfter);
        String status;
        if (remainingAfter == 0 && capturedAfter == current.getGrossMicro()) status = "CAPTURED";
        else if (remainingAfter == 0 && refundedAfter == current.getGrossMicro()) status = "REFUNDED";
        else if (capturedAfter > 0) status = "PARTIALLY_CAPTURED";
        else status = "ACTIVE";
        return new EscrowSettlementPlan(current, capturedAfter, refundedAfter, status,
                checkedAdd(current.getVersion(), 1L), now);
    }

    private void persistEscrowSettlement(EscrowSettlementPlan settlement) {
        requireOne(mapper.updateEscrowSettlement(
                settlement.current(), settlement.current().getTenantId(), settlement.current().getClientId(),
                settlement.capturedAfter(), settlement.refundedAfter(), settlement.status(),
                settlement.versionAfter(), settlement.now()), "escrow settlement CAS");
    }

    private EconomyPostingResult replay(ValidatedPosting posting, EconomyTransactionEntity transaction) {
        if (!"POSTED".equals(transaction.getStatus())
                || transaction.getPostedAt() == null
                || !MessageDigest.isEqual(posting.command().requestHash(), transaction.getRequestHash())
                || !transaction.getBusinessType().equals(posting.command().journalType().name())
                || !transaction.getBusinessId().equals(posting.command().businessId())
                || !transaction.getCurrency().equals(EconomyConstants.CURRENCY_SILVER)) {
            throw new EconomyPostingException(IDEMPOTENCY_CONFLICT,
                    "idempotency key is already bound to a different or incomplete operation");
        }
        List<EconomyEntryEntity> entries = mapper.selectEntriesByTransaction(
                transaction.getTenantId(), transaction.getClientId(), transaction.getTransactionId());
        if (entries.size() != transaction.getEntryCount() || entries.size() < 2) {
            throw new EconomyPostingException(JOURNAL_CORRUPT, "POSTED transaction entry count is inconsistent");
        }
        long signedTotal = 0;
        long debit = 0;
        long credit = 0;
        List<EconomyPostedLine> lines = new ArrayList<>();
        int expectedSequence = 0;
        for (EconomyEntryEntity entry : entries) {
            expectedSequence++;
            if (!Integer.valueOf(expectedSequence).equals(entry.getEntrySequence())
                    || !"POSTED".equals(entry.getStatus())
                    || !transaction.getTransactionId().equals(entry.getTransactionId())
                    || !EconomyConstants.CURRENCY_SILVER.equals(entry.getCurrency())
                    || entry.getSignedAmountMicro() == null || entry.getSignedAmountMicro() == 0
                    || entry.getBalanceAfterMicro() == null) {
                throw new EconomyPostingException(JOURNAL_CORRUPT, "POSTED entry is inconsistent");
            }
            signedTotal = checkedAdd(signedTotal, entry.getSignedAmountMicro());
            if (entry.getSignedAmountMicro() < 0) {
                debit = checkedAdd(debit, checkedNegate(entry.getSignedAmountMicro()));
            } else {
                credit = checkedAdd(credit, entry.getSignedAmountMicro());
            }
            lines.add(new EconomyPostedLine(entry.getAccountId(), entry.getEntrySequence(),
                    entry.getSignedAmountMicro(), entry.getBalanceAfterMicro()));
        }
        if (signedTotal != 0 || debit != transaction.getDebitTotalMicro()
                || credit != transaction.getCreditTotalMicro() || debit != credit) {
            throw new EconomyPostingException(JOURNAL_CORRUPT, "POSTED transaction is not balanced");
        }
        EconomyEscrowResult escrowResult = null;
        if (posting.command().journalType().requiresFundingLot()) {
            EconomyEscrowFundingLotEntity lot = mapper.selectFundingLotByTransaction(
                    transaction.getTenantId(), transaction.getClientId(), transaction.getTransactionId());
            if (lot == null || !transaction.getTransactionId().equals(lot.getReserveTransactionId())) {
                throw new EconomyPostingException(JOURNAL_CORRUPT, "POSTED reserve lacks immutable funding lot");
            }
            escrowResult = new EconomyEscrowResult(lot.getEscrowId(), lot.getFundingSequence(),
                    lot.getAmountMicro(), lot.getEscrowGrossAfterMicro(), lot.getEscrowVersionAfter());
        }
        return new EconomyPostingResult(transaction.getTransactionId(), transaction.getStatus(),
                transaction.getCurrency(), transaction.getPostedAt(), debit, credit, lines, escrowResult);
    }

    private ValidatedPosting validate(EconomyPostingCommand command, boolean treasuryAuthority) {
        if (command == null || command.scope() == null || command.principal() == null
                || command.principal().type() == null || command.journalType() == null) {
            throw new EconomyPostingException(INVALID_COMMAND, "posting command is incomplete");
        }
        requireExact(command.scope().tenantId(), "tenantId", 50);
        requireExact(command.scope().clientId(), "clientId", 50);
        gate.requireMutationAllowed(command.scope().tenantId(), command.scope().clientId());
        requireExact(command.principal().id(), "principalId", 100);
        requireExact(command.businessId(), "businessId", 100);
        if (!V0_ENABLED_TYPES.contains(command.journalType())) {
            throw new EconomyPostingException(INVALID_COMMAND,
                    "journal type is reserved for a later economy work package");
        }
        byte[] idempotency = canonicalUuid(command.idempotencyKey());
        byte[] requestHash = command.requestHash();
        if (requestHash == null || requestHash.length != EconomyConstants.REQUEST_HASH_BYTES) {
            throw new EconomyPostingException(INVALID_COMMAND, "requestHash must be exactly 32 bytes");
        }
        if (command.lines() == null || command.lines().size() < 2) {
            throw new EconomyPostingException(IMBALANCED_TRANSACTION,
                    "a journal transaction requires at least two entries");
        }
        TreeMap<EconomyAccountKey, EconomyPostingLine> sorted = new TreeMap<>(EconomyAccountKey.LOCK_ORDER);
        long signedTotal = 0;
        long debit = 0;
        long credit = 0;
        for (EconomyPostingLine line : command.lines()) {
            if (line == null || line.account() == null || line.signedAmountMicro() == 0) {
                throw new EconomyPostingException(INVALID_COMMAND, "journal lines must be non-zero and complete");
            }
            validateAccountKey(line.account());
            if (sorted.put(line.account(), line) != null) {
                throw new EconomyPostingException(INVALID_COMMAND, "duplicate account line is forbidden");
            }
            signedTotal = checkedAdd(signedTotal, line.signedAmountMicro());
            if (line.signedAmountMicro() < 0) {
                debit = checkedAdd(debit, checkedNegate(line.signedAmountMicro()));
            } else {
                credit = checkedAdd(credit, line.signedAmountMicro());
            }
        }
        if (signedTotal != 0 || debit == 0 || debit != credit) {
            throw new EconomyPostingException(IMBALANCED_TRANSACTION,
                    "journal debits and credits must balance exactly");
        }
        List<EconomyPostingLine> lines = List.copyOf(sorted.values());
        validateTemplate(command, lines, treasuryAuthority);
        return new ValidatedPosting(command, idempotency, lines, debit, credit);
    }

    private void validateTemplate(
            EconomyPostingCommand command,
            List<EconomyPostingLine> lines,
            boolean treasuryAuthority) {
        if (command.journalType() == EconomyJournalType.ISSUE_SILVER) {
            if (!treasuryAuthority) {
                throw new EconomyPostingException(INVALID_COMMAND,
                        "ISSUE_SILVER requires the internal treasury authority");
            }
            if (command.escrowFunding() != null || command.escrowSettlement() != null || lines.size() != 2
                    || !matches(lines, EconomyAccountOwnerType.SYSTEM,
                            EconomyAccountPurpose.SILVER_ISSUANCE, true)
                    || !matches(lines, EconomyAccountOwnerType.USER,
                            EconomyAccountPurpose.AVAILABLE, false)) {
                throw new EconomyPostingException(INVALID_COMMAND, "ISSUE_SILVER journal template mismatch");
            }
            return;
        }
        if (command.journalType() == EconomyJournalType.CAPTURE_HOSTING_RENT
                || command.journalType() == EconomyJournalType.REFUND_HOSTING_RENT
                || command.journalType() == EconomyJournalType.REFUND_BOUNTY
                || isBountyCapture(command.journalType())
                || command.journalType() == EconomyJournalType.CAPTURE_SKILL
                || command.journalType() == EconomyJournalType.REFUND_SKILL) {
            validateSettlementTemplate(command, lines);
            return;
        }
        EconomyEscrowFunding funding = command.escrowFunding();
        if (funding == null || command.escrowSettlement() != null || funding.escrowType() == null
                || funding.payerAccount() == null || funding.escrowAccount() == null
                || funding.amountMicro() <= 0 || lines.size() != 2) {
            throw new EconomyPostingException(INVALID_COMMAND, "reserve journal requires exact escrow funding data");
        }
        validateAccountKey(funding.payerAccount());
        validateAccountKey(funding.escrowAccount());
        EconomyEscrowType expectedType;
        EconomyAccountOwnerType expectedOwner;
        if (command.journalType() == EconomyJournalType.RESERVE_BOUNTY) {
            expectedType = EconomyEscrowType.BOUNTY;
            expectedOwner = EconomyAccountOwnerType.TASK;
        } else if (command.journalType() == EconomyJournalType.RESERVE_SKILL) {
            expectedType = EconomyEscrowType.SKILL_ORDER;
            expectedOwner = EconomyAccountOwnerType.ORDER;
        } else {
            expectedType = EconomyEscrowType.HOSTING_RENT;
            expectedOwner = EconomyAccountOwnerType.LEASE;
        }
        Map<EconomyAccountKey, Long> amounts = new HashMap<>();
        lines.forEach(line -> amounts.put(line.account(), line.signedAmountMicro()));
        if (command.principal().type() != EconomyPrincipalType.USER
                || !exactIdentityEquals(funding.payerAccount().ownerId(), command.principal().id())
                || funding.escrowType() != expectedType
                || funding.payerAccount().ownerType() != EconomyAccountOwnerType.USER
                || funding.payerAccount().purpose() != EconomyAccountPurpose.AVAILABLE
                || funding.escrowAccount().ownerType() != expectedOwner
                || funding.escrowAccount().purpose() != EconomyAccountPurpose.ESCROW
                || !funding.escrowAccount().ownerId().equals(command.businessId())
                || !Long.valueOf(-funding.amountMicro()).equals(amounts.get(funding.payerAccount()))
                || !Long.valueOf(funding.amountMicro()).equals(amounts.get(funding.escrowAccount()))) {
            throw new EconomyPostingException(INVALID_COMMAND, "reserve journal template mismatch");
        }
    }


    private static boolean isBountyCapture(EconomyJournalType type) {
        return type == EconomyJournalType.CAPTURE_COMPUTE || type == EconomyJournalType.CAPTURE_FEE
                || type == EconomyJournalType.PAY_AGENT;
    }

    private void validateSettlementTemplate(
            EconomyPostingCommand command, List<EconomyPostingLine> lines) {
        EconomyEscrowSettlement settlement = command.escrowSettlement();
        if (command.escrowFunding() != null || settlement == null || lines.size() != 2
                || settlement.escrowAccount() == null || settlement.destinationAccount() == null
                || settlement.amountMicro() <= 0 || settlement.expectedEscrowVersion() <= 0) {
            throw new EconomyPostingException(INVALID_COMMAND, "escrow settlement data is incomplete");
        }
        requireExact(settlement.reserveTransactionId(), "reserveTransactionId", 100);
        validateAccountKey(settlement.escrowAccount());
        validateAccountKey(settlement.destinationAccount());
        Map<EconomyAccountKey, Long> amounts = new HashMap<>();
        lines.forEach(line -> amounts.put(line.account(), line.signedAmountMicro()));
        boolean capture = command.journalType() == EconomyJournalType.CAPTURE_HOSTING_RENT;
        boolean bountyRefund = command.journalType() == EconomyJournalType.REFUND_BOUNTY
                || isBountyCapture(command.journalType());
        boolean skill = command.journalType() == EconomyJournalType.CAPTURE_SKILL
                || command.journalType() == EconomyJournalType.REFUND_SKILL;
        EconomyEscrowType expectedEscrowType = skill ? EconomyEscrowType.SKILL_ORDER
                : bountyRefund ? EconomyEscrowType.BOUNTY : EconomyEscrowType.HOSTING_RENT;
        EconomyAccountOwnerType expectedEscrowOwner = skill ? EconomyAccountOwnerType.ORDER
                : bountyRefund ? EconomyAccountOwnerType.TASK : EconomyAccountOwnerType.LEASE;
        boolean destinationMatches = capture
                ? settlement.destinationAccount().ownerType() == EconomyAccountOwnerType.SYSTEM
                    && settlement.destinationAccount().purpose() == EconomyAccountPurpose.HOSTING_RENT
                    && EconomyConstants.HOSTING_RENT_SYSTEM_OWNER_ID.equals(
                            settlement.destinationAccount().ownerId())
                : settlement.destinationAccount().ownerType() == EconomyAccountOwnerType.USER
                    && settlement.destinationAccount().purpose() == EconomyAccountPurpose.AVAILABLE
                    && exactIdentityEquals(settlement.destinationAccount().ownerId(), command.principal().id());
        if (isBountyCapture(command.journalType())) {
            EconomyAccountKey destination = settlement.destinationAccount();
            destinationMatches = switch (command.journalType()) {
                case CAPTURE_COMPUTE -> destination.ownerType() == EconomyAccountOwnerType.SYSTEM
                        && destination.purpose() == EconomyAccountPurpose.MODEL_COST
                        && "MODEL_COST".equals(destination.ownerId());
                case CAPTURE_FEE -> destination.ownerType() == EconomyAccountOwnerType.SYSTEM
                        && destination.purpose() == EconomyAccountPurpose.PLATFORM_FEE
                        && "PLATFORM_FEE".equals(destination.ownerId());
                case PAY_AGENT -> destination.ownerType() == EconomyAccountOwnerType.AGENT
                        && destination.purpose() == EconomyAccountPurpose.EARNINGS
                        && destination.ownerId().matches("agt_[0-9a-f]{32}");
                default -> false;
            };
        }
        if (command.journalType() == EconomyJournalType.CAPTURE_SKILL) {
            destinationMatches = settlement.destinationAccount().ownerType() == EconomyAccountOwnerType.SYSTEM
                    && settlement.destinationAccount().purpose() == EconomyAccountPurpose.SKILL_STORE
                    && "SKILL_STORE".equals(settlement.destinationAccount().ownerId());
        }
        if (command.principal().type() != EconomyPrincipalType.USER
                || settlement.escrowType() != expectedEscrowType
                || settlement.escrowAccount().ownerType() != expectedEscrowOwner
                || settlement.escrowAccount().purpose() != EconomyAccountPurpose.ESCROW
                || !settlement.escrowAccount().ownerId().equals(command.businessId())
                || !destinationMatches
                || !Long.valueOf(-settlement.amountMicro()).equals(amounts.get(settlement.escrowAccount()))
                || !Long.valueOf(settlement.amountMicro()).equals(amounts.get(settlement.destinationAccount()))) {
            throw new EconomyPostingException(INVALID_COMMAND, "escrow settlement template mismatch");
        }
    }

    private boolean matches(
            List<EconomyPostingLine> lines,
            EconomyAccountOwnerType ownerType,
            EconomyAccountPurpose purpose,
            boolean negative) {
        return lines.stream().anyMatch(line -> line.account().ownerType() == ownerType
                && line.account().purpose() == purpose
                && (negative ? line.signedAmountMicro() < 0 : line.signedAmountMicro() > 0));
    }

    private void validateAccountKey(EconomyAccountKey key) {
        if (!EconomyConstants.CURRENCY_SILVER.equals(key.currency())
                || key.ownerType() == null || key.purpose() == null) {
            throw new EconomyPostingException(INVALID_COMMAND, "only typed SILVER accounts are supported");
        }
        requireExact(key.ownerId(), "account ownerId", 100);
    }

    private void requireAccountMatches(
            EconomyAccountKey key, EconomyAccountEntity account, EconomyPostingCommand command) {
        if (account.getId() == null || account.getBalanceMicro() == null || account.getVersion() == null
                || account.getAllowNegative() == null || account.getAccountId() == null
                || !command.scope().tenantId().equals(account.getTenantId())
                || !command.scope().clientId().equals(account.getClientId())
                || !key.currency().equals(account.getCurrency())
                || !key.ownerType().name().equals(account.getOwnerType())
                || !key.ownerId().equals(account.getOwnerId())
                || !key.purpose().name().equals(account.getPurpose())) {
            throw new EconomyPostingException(JOURNAL_CORRUPT, "locked account identity is inconsistent");
        }
        boolean negativeCapable = isNegativeCapableSystemAccount(key);
        if (Integer.valueOf(1).equals(account.getAllowNegative()) && !negativeCapable) {
            throw new EconomyPostingException(JOURNAL_CORRUPT,
                    "only approved SYSTEM contra/variance accounts may allow negative balances");
        }
        if ((!negativeCapable || !Integer.valueOf(1).equals(account.getAllowNegative()))
                && account.getBalanceMicro() < 0) {
            throw new EconomyPostingException(JOURNAL_CORRUPT,
                    "non-negative economy account contains a negative balance");
        }
    }

    private boolean isNegativeCapableSystemAccount(EconomyAccountKey key) {
        return key.ownerType() == EconomyAccountOwnerType.SYSTEM
                && (key.purpose() == EconomyAccountPurpose.SILVER_ISSUANCE
                || key.purpose() == EconomyAccountPurpose.PROVIDER_VARIANCE);
    }

    private void requireEscrowMatches(
            ValidatedPosting posting,
            EconomyEscrowFunding funding,
            EconomyAccountEntity payer,
            EconomyAccountEntity held,
            EconomyEscrowEntity escrow) {
        if (!"ACTIVE".equals(escrow.getStatus())
                || !funding.escrowType().name().equals(escrow.getBusinessType())
                || !posting.command().businessId().equals(escrow.getBusinessId())
                || !payer.getAccountId().equals(escrow.getPayerAccountId())
                || !held.getAccountId().equals(escrow.getEscrowAccountId())
                || !EconomyConstants.CURRENCY_SILVER.equals(escrow.getCurrency())
                || escrow.getGrossMicro() == null || escrow.getGrossMicro() <= 0
                || escrow.getCapturedMicro() == null || escrow.getRefundedMicro() == null
                || escrow.getVersion() == null || escrow.getVersion() <= 0) {
            throw new EconomyPostingException(ESCROW_CONFLICT, "existing escrow root is incompatible");
        }
    }

    private byte[] canonicalUuid(String value) {
        if (value == null || value.length() != 36
                || !value.chars().allMatch(character -> character < 128)) {
            throw new EconomyPostingException(INVALID_COMMAND,
                    "idempotencyKey must be a canonical lowercase UUID");
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new EconomyPostingException(INVALID_COMMAND,
                    "idempotencyKey must be a canonical lowercase UUID", exception);
        }
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private void requireExact(String value, String name, int maxBytes) {
        if (!isExactIdentity(value, maxBytes)) {
            throw new EconomyPostingException(INVALID_COMMAND,
                    name + " must be valid byte-exact UTF-8 without padding/control characters and at most "
                            + maxBytes + " bytes");
        }
    }

    private boolean isExactIdentity(String value, int maxBytes) {
        if (value == null || value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            int codePoint;
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
                codePoint = Character.toCodePoint(unit, value.charAt(index));
            } else if (Character.isLowSurrogate(unit)) {
                return false;
            } else {
                codePoint = unit;
            }
            if (Character.isISOControl(codePoint)) return false;
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= maxBytes
                && !isPadding(value.codePointAt(0))
                && !isPadding(value.codePointBefore(value.length()));
    }

    private boolean exactIdentityEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private String requireGeneratedId(String value, String name) {
        requireExact(value, name, 100);
        return value;
    }

    private long positiveNow() {
        long now = clock.getAsLong();
        if (now <= 0) throw new EconomyPostingException(INVALID_COMMAND, "clock must return positive epoch millis");
        return now;
    }

    private long checkedAdd(Long left, long right) {
        if (left == null) throw new EconomyPostingException(JOURNAL_CORRUPT, "required BIGINT value is null");
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new EconomyPostingException(AMOUNT_RANGE_EXCEEDED, "micro-silver BIGINT overflow", exception);
        }
    }

    private long checkedNegate(long value) {
        try {
            return Math.negateExact(value);
        } catch (ArithmeticException exception) {
            throw new EconomyPostingException(AMOUNT_RANGE_EXCEEDED, "micro-silver BIGINT overflow", exception);
        }
    }

    private void requireOne(int rows, String operation) {
        if (rows != 1) {
            throw new EconomyPostingException(CONCURRENCY_CONFLICT,
                    operation + " affected " + rows + " rows; expected exactly one");
        }
    }

    private record ValidatedPosting(
            EconomyPostingCommand command,
            byte[] idempotencyKey,
            List<EconomyPostingLine> lines,
            long debitTotal,
            long creditTotal) {
        private ValidatedPosting {
            idempotencyKey = idempotencyKey.clone();
            lines = List.copyOf(lines);
        }

        @Override
        public byte[] idempotencyKey() {
            return idempotencyKey.clone();
        }
    }

    private record LockedAccount(
            EconomyAccountEntity entity,
            long signedAmount,
            long balanceAfter,
            long versionAfter) {
    }

    private record FundingState(EconomyEscrowFunding funding) {
        private static FundingState none() {
            return new FundingState(null);
        }
    }

    private record EscrowPlan(
            EconomyEscrowEntity current,
            EconomyEscrowEntity target,
            EconomyEscrowFundingLotEntity lot,
            boolean insert,
            EconomyEscrowResult result) {
    }

    private record EscrowSettlementPlan(
            EconomyEscrowEntity current,
            long capturedAfter,
            long refundedAfter,
            String status,
            long versionAfter,
            long now) {
    }
}
