package cn.jia.economy.service.impl;

import cn.jia.economy.bounty.FundedBountyLedgerService;
import cn.jia.economy.bounty.FundedBountyRefundCommand;
import cn.jia.economy.bounty.FundedBountyRefundReceipt;
import cn.jia.economy.bounty.FundedBountyReserveCommand;
import cn.jia.economy.bounty.FundedBountyReserveReceipt;
import cn.jia.economy.common.EconomyAccountOwnerType;
import cn.jia.economy.common.EconomyAccountPurpose;
import cn.jia.economy.common.EconomyConstants;
import cn.jia.economy.common.EconomyEscrowType;
import cn.jia.economy.common.EconomyJournalType;
import cn.jia.economy.entity.EconomyAccountEntity;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.service.EconomyAccountKey;
import cn.jia.economy.service.EconomyEscrowFunding;
import cn.jia.economy.service.EconomyEscrowSettlement;
import cn.jia.economy.service.EconomyPostingCommand;
import cn.jia.economy.service.EconomyPostingLine;
import cn.jia.economy.service.EconomyPostingResult;
import cn.jia.economy.service.EconomyPostingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** W04-only account provisioning and exact reserve/refund templates. */
@Service
@ConditionalOnProperty(prefix = "economy.preview", name = "enabled", havingValue = "true")
public class FundedBountyLedgerServiceImpl implements FundedBountyLedgerService {
    private final EconomyLedgerMapper mapper;
    private final EconomyPostingService postingService;

    public FundedBountyLedgerServiceImpl(EconomyLedgerMapper mapper, EconomyPostingService postingService) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.postingService = Objects.requireNonNull(postingService, "postingService");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FundedBountyReserveReceipt reserve(FundedBountyReserveCommand command) {
        requireReserve(command);
        long now = positiveNow();
        EconomyAccountKey payer = userAvailable(command.principal().id());
        EconomyAccountKey escrow = taskEscrow(command.taskId());
        ensureAccount(command.scope(), payer, walletAccountId(command.principal().id()), now);
        ensureAccount(command.scope(), escrow, taskEscrowAccountId(command.scope().tenantId(),
                command.scope().clientId(), command.taskId()), now);
        EconomyPostingResult result = postingService.post(new EconomyPostingCommand(
                command.scope(), command.principal(), command.idempotencyKey(), command.requestHash(),
                EconomyJournalType.RESERVE_BOUNTY, command.taskId(), List.of(
                new EconomyPostingLine(payer, Math.negateExact(command.amountMicro())),
                new EconomyPostingLine(escrow, command.amountMicro())),
                new EconomyEscrowFunding(EconomyEscrowType.BOUNTY, payer, escrow,
                        command.amountMicro(), null)));
        if (result.escrow() == null || result.escrow().escrowVersion() != 1
                || result.escrow().fundingAmountMicro() != command.amountMicro()) {
            throw new EconomyPostingException(EconomyPostingException.Reason.JOURNAL_CORRUPT,
                    "bounty reserve escrow receipt is invalid");
        }
        return new FundedBountyReserveReceipt(result.transactionId(), result.escrow().escrowId(),
                result.escrow().escrowVersion(), command.amountMicro(), result.postedAt());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FundedBountyRefundReceipt refund(FundedBountyRefundCommand command) {
        requireRefund(command);
        EconomyAccountKey payer = userAvailable(command.principal().id());
        EconomyAccountKey escrow = taskEscrow(command.taskId());
        EconomyPostingResult result = postingService.post(new EconomyPostingCommand(
                command.scope(), command.principal(), command.idempotencyKey(), command.requestHash(),
                EconomyJournalType.REFUND_BOUNTY, command.taskId(), List.of(
                new EconomyPostingLine(escrow, Math.negateExact(command.amountMicro())),
                new EconomyPostingLine(payer, command.amountMicro())), null,
                new EconomyEscrowSettlement(EconomyEscrowType.BOUNTY, escrow, payer,
                        command.amountMicro(), command.expectedEscrowVersion(),
                        command.reserveTransactionId())));
        return new FundedBountyRefundReceipt(result.transactionId(),
                Math.addExact(command.expectedEscrowVersion(), 1L), command.amountMicro(), result.postedAt());
    }

    private void ensureAccount(cn.jia.economy.service.EconomyScope scope, EconomyAccountKey key,
            String accountId, long now) {
        mapper.insertAccountIfAbsent(new EconomyAccountEntity()
                .setAccountId(accountId).setOwnerType(key.ownerType().name()).setOwnerId(key.ownerId())
                .setPurpose(key.purpose().name()).setCurrency(key.currency()).setBalanceMicro(0L)
                .setAllowNegative(0).setStatus("ACTIVE").setVersion(0L)
                .setTenantId(scope.tenantId()).setClientId(scope.clientId())
                .setCreateTime(now).setUpdateTime(now));
    }

    private static EconomyAccountKey userAvailable(String actorId) {
        return new EconomyAccountKey(EconomyConstants.CURRENCY_SILVER,
                EconomyAccountOwnerType.USER, actorId, EconomyAccountPurpose.AVAILABLE);
    }

    private static EconomyAccountKey taskEscrow(String taskId) {
        return new EconomyAccountKey(EconomyConstants.CURRENCY_SILVER,
                EconomyAccountOwnerType.TASK, taskId, EconomyAccountPurpose.ESCROW);
    }

    private static String walletAccountId(String actorId) {
        return "wallet_" + digest(lengthPrefixed(actorId));
    }

    private static String taskEscrowAccountId(String tenantId, String clientId, String taskId) {
        return "bounty_escrow_" + digest(lengthPrefixed(tenantId, clientId, taskId));
    }

    private static byte[] lengthPrefixed(String... values) {
        int size = 0;
        for (String value : values) size = Math.addExact(size, Integer.BYTES + value.getBytes(StandardCharsets.UTF_8).length);
        ByteBuffer buffer = ByteBuffer.allocate(size);
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            buffer.putInt(bytes.length).put(bytes);
        }
        return buffer.array();
    }

    private static String digest(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireReserve(FundedBountyReserveCommand command) {
        if (command == null || command.scope() == null || command.principal() == null
                || command.amountMicro() <= 0 || command.taskId() == null) {
            throw new EconomyPostingException(EconomyPostingException.Reason.INVALID_COMMAND,
                    "funded bounty reserve command is incomplete");
        }
    }

    private static void requireRefund(FundedBountyRefundCommand command) {
        if (command == null || command.scope() == null || command.principal() == null
                || command.amountMicro() <= 0 || command.expectedEscrowVersion() <= 0
                || command.taskId() == null || command.reserveTransactionId() == null) {
            throw new EconomyPostingException(EconomyPostingException.Reason.INVALID_COMMAND,
                    "funded bounty refund command is incomplete");
        }
    }

    private static long positiveNow() {
        long now = System.currentTimeMillis();
        if (now <= 0) throw new IllegalStateException("clock must be positive");
        return now;
    }
}
