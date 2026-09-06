package cn.jia.economy.service.impl;

import cn.jia.economy.bounty.FundedBountyCaptureCommand;
import cn.jia.economy.bounty.FundedBountyCaptureReceipt;
import cn.jia.economy.bounty.FundedBountyCaptureService;
import cn.jia.economy.common.*;
import cn.jia.economy.entity.EconomyAccountEntity;
import cn.jia.economy.entity.EconomyEscrowEntity;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.service.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

/** All three captures share one physical transaction and one globally sorted account lock set. */
@Service
@ConditionalOnProperty(prefix = "economy.preview", name = "enabled", havingValue = "true")
public class FundedBountyCaptureServiceImpl implements FundedBountyCaptureService {
    private final EconomyLedgerMapper mapper;
    private final EconomyPostingService posting;
    private final cn.jia.economy.config.EconomyPreviewGate gate;

    public FundedBountyCaptureServiceImpl(EconomyLedgerMapper mapper, EconomyPostingService posting,
            cn.jia.economy.config.EconomyPreviewGate gate) {
        this.mapper = mapper;
        this.posting = posting;
        this.gate = gate;
    }

    @Override
    public void requirePreviewScope(EconomyScope scope) {
        if (scope == null) throw invalid("Capture scope is required");
        gate.requireMutationAllowed(scope.tenantId(), scope.clientId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public FundedBountyCaptureReceipt capture(FundedBountyCaptureCommand c) {
        validate(c);
        requirePreviewScope(c.scope());
        long now = System.currentTimeMillis();
        if (now <= 0) throw invalid("Invalid clock");
        EconomyAccountKey escrow = key(EconomyAccountOwnerType.TASK, c.taskId(), EconomyAccountPurpose.ESCROW);
        EconomyAccountKey payer = key(EconomyAccountOwnerType.USER, c.principal().id(), EconomyAccountPurpose.AVAILABLE);
        EconomyAccountKey model = key(EconomyAccountOwnerType.SYSTEM, "MODEL_COST", EconomyAccountPurpose.MODEL_COST);
        EconomyAccountKey fee = key(EconomyAccountOwnerType.SYSTEM, "PLATFORM_FEE", EconomyAccountPurpose.PLATFORM_FEE);
        EconomyAccountKey agent = key(EconomyAccountOwnerType.AGENT, c.agentId(), EconomyAccountPurpose.EARNINGS);
        TreeMap<EconomyAccountKey, EconomyAccountEntity> accounts = new TreeMap<>(EconomyAccountKey.LOCK_ORDER);
        for (EconomyAccountKey account : List.of(escrow, payer, model, fee, agent)) accounts.put(account, null);
        // Provision only recipient accounts, in the SAME order as the subsequent posting locks.
        // Business root/funding/quote/claim locks have already been acquired by the caller.
        for (EconomyAccountKey account : accounts.keySet()) {
            if (!account.equals(escrow) && !account.equals(payer)) {
                String identity = c.scope().tenantId() + '\0' + c.scope().clientId() + '\0'
                        + account.ownerType() + '\0' + account.ownerId() + '\0' + account.purpose();
                mapper.insertAccountIfAbsent(new EconomyAccountEntity()
                        .setAccountId("bounty_pay_" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)))
                        .setOwnerType(account.ownerType().name()).setOwnerId(account.ownerId())
                        .setPurpose(account.purpose().name()).setCurrency(account.currency())
                        .setBalanceMicro(0L).setAllowNegative(0).setStatus("ACTIVE").setVersion(0L)
                        .setTenantId(c.scope().tenantId()).setClientId(c.scope().clientId())
                        .setCreateTime(now).setUpdateTime(now));
            }
            EconomyAccountEntity row = mapper.selectAccountForUpdate(c.scope().tenantId(), c.scope().clientId(),
                    account.currency(), account.ownerType().name(), account.ownerId(), account.purpose().name());
            if (row == null || !"ACTIVE".equals(row.getStatus())) throw invalid("Capture account unavailable");
            accounts.put(account, row);
        }
        EconomyEscrowEntity root = mapper.selectEscrowByBusinessForUpdate(c.scope().tenantId(), c.scope().clientId(),
                EconomyEscrowType.BOUNTY.name(), c.taskId());
        if (root == null || !"ACTIVE".equals(root.getStatus())
                || !Long.valueOf(c.expectedEscrowVersion()).equals(root.getVersion())
                || !Long.valueOf(c.grossMicro()).equals(root.getGrossMicro())
                || !Long.valueOf(0).equals(root.getCapturedMicro()) || !Long.valueOf(0).equals(root.getRefundedMicro())
                || !accounts.get(payer).getAccountId().equals(root.getPayerAccountId())
                || !accounts.get(escrow).getAccountId().equals(root.getEscrowAccountId())
                || !Long.valueOf(c.grossMicro()).equals(accounts.get(escrow).getBalanceMicro())) {
            throw new EconomyPostingException(EconomyPostingException.Reason.ESCROW_CONFLICT,
                    "Full bounty capture requires its exact owned, untouched reserve");
        }
        List<String> ids = new ArrayList<>();
        long version = c.expectedEscrowVersion();
        long postedAt = now;
        List<Component> components = List.of(new Component(EconomyJournalType.CAPTURE_COMPUTE, model, c.actualComputeMicro()),
                new Component(EconomyJournalType.CAPTURE_FEE, fee, c.platformFeeMicro()),
                new Component(EconomyJournalType.PAY_AGENT, agent, c.agentPayoutMicro()));
        for (Component component : components) {
            if (component.amount() == 0) continue; // zero is audited in the receipt, never a zero ledger line.
            String seed = "bounty-complete-v0:" + c.idempotencyKey() + ':' + component.type();
            String componentKey = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.US_ASCII)).toString();
            EconomyPostingResult result = posting.post(new EconomyPostingCommand(c.scope(), c.principal(),
                    componentKey, c.requestHash(), component.type(), c.taskId(), List.of(
                        new EconomyPostingLine(escrow, -component.amount()),
                        new EconomyPostingLine(component.account(), component.amount())), null,
                    new EconomyEscrowSettlement(EconomyEscrowType.BOUNTY, escrow, component.account(),
                            component.amount(), version, c.reserveTransactionId())));
            ids.add(result.transactionId());
            version = Math.addExact(version, 1);
            postedAt = Math.max(postedAt, result.postedAt());
        }
        EconomyEscrowEntity captured = mapper.selectEscrowByBusinessForUpdate(c.scope().tenantId(), c.scope().clientId(),
                EconomyEscrowType.BOUNTY.name(), c.taskId());
        if (!"CAPTURED".equals(captured.getStatus()) || !Long.valueOf(c.grossMicro()).equals(captured.getCapturedMicro())
                || !Long.valueOf(0).equals(captured.getRefundedMicro()) || !Long.valueOf(version).equals(captured.getVersion())) {
            throw new EconomyPostingException(EconomyPostingException.Reason.JOURNAL_CORRUPT, "Incomplete bounty capture");
        }
        return new FundedBountyCaptureReceipt(ids, version, postedAt);
    }

    private static void validate(FundedBountyCaptureCommand c) {
        if (c == null || c.scope() == null || c.principal() == null || c.idempotencyKey() == null
                || c.principal().type() != EconomyPrincipalType.USER || c.requestHash() == null || c.requestHash().length != 32
                || c.grossMicro() <= 0 || c.actualComputeMicro() < 0 || c.platformFeeMicro() < 0 || c.agentPayoutMicro() < 0
                || c.expectedEscrowVersion() <= 0 || c.expectedEscrowVersion() > Long.MAX_VALUE - 3) throw invalid("Invalid capture");
        for (String id : new String[]{c.scope().tenantId(), c.scope().clientId(), c.principal().id(), c.taskId(),
                c.agentId(), c.reserveTransactionId()}) {
            if (id == null || id.isBlank() || !id.equals(id.strip()) || id.codePoints().anyMatch(Character::isISOControl)
                    || malformed(id)) throw invalid("Invalid capture identity");
        }
        if (!c.agentId().matches("agt_[0-9a-f]{32}")) throw invalid("Capture requires explicit canonical Agent");
        try {
            if (!UUID.fromString(c.idempotencyKey()).toString().equals(c.idempotencyKey())
                    || Math.addExact(Math.addExact(c.actualComputeMicro(), c.platformFeeMicro()), c.agentPayoutMicro())
                    != c.grossMicro()) throw invalid("Capture conservation mismatch");
        } catch (IllegalArgumentException | ArithmeticException failure) { throw invalid("Invalid capture amount/key"); }
    }

    private static boolean malformed(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return true;
            } else if (Character.isLowSurrogate(ch)) return true;
        }
        return false;
    }

    private static EconomyAccountKey key(EconomyAccountOwnerType type, String id, EconomyAccountPurpose purpose) {
        return new EconomyAccountKey(EconomyConstants.CURRENCY_SILVER, type, id, purpose);
    }
    private static EconomyPostingException invalid(String message) {
        return new EconomyPostingException(EconomyPostingException.Reason.INVALID_COMMAND, message);
    }
    private record Component(EconomyJournalType type, EconomyAccountKey account, long amount) { }
}
