package cn.jia.economy.api;

import cn.jia.economy.common.EconomyAccountOwnerType;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.common.EconomyAccountPurpose;
import cn.jia.economy.common.EconomyConstants;
import cn.jia.economy.common.EconomyJournalType;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.entity.EconomyAccountEntity;
import cn.jia.economy.entity.EconomyWalletLedgerRow;
import cn.jia.economy.entity.EconomyWalletSnapshotRow;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.service.EconomyAccountKey;
import cn.jia.economy.service.EconomyPostingCommand;
import cn.jia.economy.service.EconomyPostingLine;
import cn.jia.economy.service.EconomyPostingResult;
import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.service.EconomyTreasuryPostingService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/** Narrow W03 application service; it has no generic posting authority. */
@Service
public final class EconomyWalletService {
    private static final String ISSUANCE_OWNER_ID = "silver";
    private static final String ISSUANCE_ACCOUNT_ID = "system_silver_issuance";

    private final EconomyLedgerMapper mapper;
    private final EconomyTreasuryPostingService treasury;
    private final Environment environment;
    private final EconomyPreviewGate gate;

    public EconomyWalletService(EconomyLedgerMapper mapper, EconomyTreasuryPostingService treasury, Environment environment,
            EconomyPreviewGate gate) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    public WalletSnapshot wallet(EconomyScope scope, String actorId) {
        EconomyWalletSnapshotRow row = mapper.selectUserWalletSnapshot(
                scope.tenantId(), scope.clientId(), actorId);
        if (row == null) {
            throw new EconomyPostingException(EconomyPostingException.Reason.JOURNAL_CORRUPT,
                    "wallet snapshot query returned no result");
        }
        long available = requiredNonNegative(row.getAvailableMicro(), "available balance");
        long held = requiredNonNegative(row.getHeldMicro(), "held balance");
        requiredNonNegative(row.getMinimumHeldComponentMicro(), "held balance component");
        long version = requiredNonNegative(row.getVersion(), "wallet version");
        return new WalletSnapshot(available, held, version);
    }

    public LedgerPage ledger(EconomyScope scope, String actorId, Cursor cursor, int limit) {
        List<EconomyWalletLedgerRow> rows = mapper.selectUserAvailableLedger(
                scope.tenantId(), scope.clientId(), actorId,
                cursor == null ? null : cursor.postedAt(), cursor == null ? null : cursor.rowId(), limit + 1);
        if (rows == null) {
            throw new EconomyPostingException(EconomyPostingException.Reason.JOURNAL_CORRUPT,
                    "wallet ledger query returned no result");
        }
        boolean hasMore = rows.size() > limit;
        List<EconomyWalletLedgerRow> items = hasMore ? rows.subList(0, limit) : rows;
        Cursor next = hasMore ? cursorOf(items.getLast()) : null;
        return new LedgerPage(List.copyOf(items), next);
    }

    public EconomyPostingResult issue(
            EconomyScope scope, String actorId, String idempotencyKey, byte[] requestHash,
            long amountMicro, String campaignRef) {
        gate.requireMutationAllowed(scope.tenantId(), scope.clientId());
        if (!gate.testIssuanceEnabled() || !isExplicitPreviewEnvironment()) {
            throw new EconomyPostingException(EconomyPostingException.Reason.FEATURE_DISABLED,
                    "test issuance is only available in the explicit dev/test preview environment");
        }
        provisionIssuanceAccounts(scope, actorId);
        EconomyAccountKey user = new EconomyAccountKey(EconomyConstants.CURRENCY_SILVER,
                EconomyAccountOwnerType.USER, actorId, EconomyAccountPurpose.AVAILABLE);
        EconomyAccountKey issuance = new EconomyAccountKey(EconomyConstants.CURRENCY_SILVER,
                EconomyAccountOwnerType.SYSTEM, ISSUANCE_OWNER_ID, EconomyAccountPurpose.SILVER_ISSUANCE);
        return treasury.issue(new EconomyPostingCommand(
                scope, new EconomyPrincipal(EconomyPrincipalType.USER, actorId), idempotencyKey, requestHash,
                EconomyJournalType.ISSUE_SILVER, campaignRef, List.of(
                new EconomyPostingLine(user, amountMicro),
                new EconomyPostingLine(issuance, Math.negateExact(amountMicro))), null));
    }

    private boolean isExplicitPreviewEnvironment() {
        return !environment.acceptsProfiles("prod", "production")
                && environment.acceptsProfiles("dev", "test");
    }

    private void provisionIssuanceAccounts(EconomyScope scope, String actorId) {
        long now = System.currentTimeMillis();
        mapper.insertAccountIfAbsent(account(scope, walletAccountId(actorId), "USER", actorId,
                "AVAILABLE", 0, now));
        mapper.insertAccountIfAbsent(account(scope, ISSUANCE_ACCOUNT_ID, "SYSTEM", ISSUANCE_OWNER_ID,
                "SILVER_ISSUANCE", 1, now));
    }

    private static EconomyAccountEntity account(
            EconomyScope scope, String accountId, String ownerType, String ownerId,
            String purpose, int allowNegative, long now) {
        return new EconomyAccountEntity()
                .setAccountId(accountId).setOwnerType(ownerType).setOwnerId(ownerId).setPurpose(purpose)
                .setCurrency(EconomyConstants.CURRENCY_SILVER).setBalanceMicro(0L)
                .setAllowNegative(allowNegative).setStatus("ACTIVE").setVersion(0L)
                .setTenantId(scope.tenantId()).setClientId(scope.clientId())
                .setCreateTime(now).setUpdateTime(now);
    }

    private static String walletAccountId(String actorId) {
        return "wallet_" + hex(sha256(lengthPrefixed(actorId)));
    }

    private static byte[] lengthPrefixed(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(Integer.BYTES + bytes.length).putInt(bytes.length).put(bytes).array();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static long requiredNonNegative(Long value, String label) {
        if (value == null || value < 0) {
            throw new EconomyPostingException(EconomyPostingException.Reason.JOURNAL_CORRUPT,
                    label + " is invalid");
        }
        return value;
    }

    public record WalletSnapshot(long availableMicro, long heldMicro, long version) {
    }

    public record LedgerPage(List<EconomyWalletLedgerRow> rows, Cursor nextCursor) {
    }

    public record Cursor(long postedAt, long rowId) {
    }

    private static Cursor cursorOf(EconomyWalletLedgerRow row) {
        if (row == null || row.getPostedAt() == null || row.getPostedAt() < 0
                || row.getRowId() == null || row.getRowId() < 1) {
            throw new EconomyPostingException(EconomyPostingException.Reason.JOURNAL_CORRUPT,
                    "wallet ledger cursor state is invalid");
        }
        return new Cursor(row.getPostedAt(), row.getRowId());
    }
}
