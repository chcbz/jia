package cn.jia.economy.api;

import cn.jia.economy.common.EconomyAccountOwnerType;
import cn.jia.economy.config.EconomyCampaignProperties;
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
import java.util.UUID;

/** Narrow W03 application service; it has no generic posting authority. */
@Service
public final class EconomyWalletService {
    private static final String ISSUANCE_OWNER_ID = "wen";
    private static final String ISSUANCE_ACCOUNT_ID = "system_wen_issuance";

    private final EconomyLedgerMapper mapper;
    private final EconomyTreasuryPostingService treasury;
    private final Environment environment;
    private final EconomyPreviewGate gate;
    private final EconomyCampaignProperties campaigns;

    @org.springframework.beans.factory.annotation.Autowired
    public EconomyWalletService(EconomyLedgerMapper mapper, EconomyTreasuryPostingService treasury, Environment environment,
            EconomyPreviewGate gate, EconomyCampaignProperties campaigns) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.campaigns = Objects.requireNonNull(campaigns, "campaigns");
    }

    /** Non-Spring construction with no configured campaigns. */
    public EconomyWalletService(EconomyLedgerMapper mapper, EconomyTreasuryPostingService treasury, Environment environment,
            EconomyPreviewGate gate) {
        this(mapper, treasury, environment, gate, EconomyCampaignProperties.disabled());
    }

    public WalletSnapshot wallet(EconomyScope scope, String actorId) {
        EconomyWalletSnapshotRow row = mapper.selectUserWalletSnapshot(
                scope.tenantId(), scope.clientId(), actorId);
        if (row == null) {
            throw new EconomyPostingException(EconomyPostingException.Reason.JOURNAL_CORRUPT,
                    "wallet snapshot query returned no result");
        }
        long available = requiredNonNegative(row.getAvailableWen(), "available balance");
        long held = requiredNonNegative(row.getHeldWen(), "held balance");
        requiredNonNegative(row.getMinimumHeldComponentWen(), "held balance component");
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
            long amountWen, String campaignRef) {
        gate.requireMutationAllowed(scope.tenantId(), scope.clientId());
        if (!gate.testIssuanceEnabled() || !isExplicitPreviewEnvironment()) {
            throw new EconomyPostingException(EconomyPostingException.Reason.FEATURE_DISABLED,
                    "test issuance is only available in the explicit dev/test preview environment");
        }
        provisionIssuanceAccounts(scope, actorId);
        EconomyAccountKey user = new EconomyAccountKey(EconomyConstants.CURRENCY_WEN,
                EconomyAccountOwnerType.USER, actorId, EconomyAccountPurpose.AVAILABLE);
        EconomyAccountKey issuance = new EconomyAccountKey(EconomyConstants.CURRENCY_WEN,
                EconomyAccountOwnerType.SYSTEM, ISSUANCE_OWNER_ID, EconomyAccountPurpose.WEN_ISSUANCE);
        return treasury.issue(new EconomyPostingCommand(
                scope, new EconomyPrincipal(EconomyPrincipalType.USER, actorId), idempotencyKey, requestHash,
                EconomyJournalType.ISSUE_WEN, campaignRef, List.of(
                new EconomyPostingLine(user, amountWen),
                new EconomyPostingLine(issuance, Math.negateExact(amountWen))), null));
    }

    /** Authenticated business event. Each campaign posts atomically; retry replays committed facts. */
    public List<CampaignReceipt> observeEvent(EconomyScope scope, String actorId,
            EconomyCampaignProperties.Event event) {
        gate.requireMutationAllowed(scope.tenantId(), scope.clientId());
        Objects.requireNonNull(event, "event");
        long now = System.currentTimeMillis();
        var eligible = campaigns.grants().stream().filter(c -> c.eligible(event, now)).toList();
        if (eligible.isEmpty()) return List.of();
        provisionIssuanceAccounts(scope, actorId);
        var receipts = new java.util.ArrayList<CampaignReceipt>();
        for (var campaign : eligible) {
            String idempotencyKey = deterministicUuid(scope, actorId, campaign.id());
            // The claim is identified by campaign + recipient, NOT mutable configuration amounts.
            byte[] requestHash = sha256(canonicalGrant(scope, actorId, campaign.id()));
            EconomyAccountKey user = new EconomyAccountKey(EconomyConstants.CURRENCY_WEN,
                    EconomyAccountOwnerType.USER, actorId, EconomyAccountPurpose.AVAILABLE);
            EconomyAccountKey issuance = new EconomyAccountKey(EconomyConstants.CURRENCY_WEN,
                    EconomyAccountOwnerType.SYSTEM, ISSUANCE_OWNER_ID, EconomyAccountPurpose.WEN_ISSUANCE);
            EconomyPostingResult result = treasury.issue(new EconomyPostingCommand(
                    scope, new EconomyPrincipal(EconomyPrincipalType.USER, actorId), idempotencyKey, requestHash,
                    EconomyJournalType.ISSUE_WEN, campaign.id(), List.of(
                    new EconomyPostingLine(user, campaign.amountWen()),
                    new EconomyPostingLine(issuance, Math.negateExact(campaign.amountWen()))), null));
            // Replays return the ORIGINAL posted amount, even after changing the campaign rule.
            receipts.add(new CampaignReceipt(result.transactionId(), result.status(),
                    result.creditTotalWen(), campaign.id()));
        }
        return List.copyOf(receipts);
    }

    private static String deterministicUuid(EconomyScope scope, String actorId, String campaignRef) {
        byte[] digest = sha256(canonicalGrant(scope, actorId, campaignRef));
        ByteBuffer bytes = ByteBuffer.wrap(digest);
        long most = bytes.getLong();
        long least = bytes.getLong();
        most = (most & 0xffffffffffff0fffL) | 0x0000000000005000L;
        least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(most, least).toString();
    }

    private static byte[] canonicalGrant(EconomyScope scope, String actorId, String campaignRef) {
        byte[] domain = lengthPrefixed("campaign-claim:wen:v1");
        byte[] tenant = lengthPrefixed(scope.tenantId());
        byte[] client = lengthPrefixed(scope.clientId());
        byte[] actor = lengthPrefixed(actorId);
        byte[] campaign = lengthPrefixed(campaignRef);
        return ByteBuffer.allocate(domain.length + tenant.length + client.length + actor.length + campaign.length)
                .put(domain).put(tenant).put(client).put(actor).put(campaign).array();
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
                "WEN_ISSUANCE", 1, now));
    }

    private static EconomyAccountEntity account(
            EconomyScope scope, String accountId, String ownerType, String ownerId,
            String purpose, int allowNegative, long now) {
        return new EconomyAccountEntity()
                .setAccountId(accountId).setOwnerType(ownerType).setOwnerId(ownerId).setPurpose(purpose)
                .setCurrency(EconomyConstants.CURRENCY_WEN).setBalanceWen(0L)
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

    public record WalletSnapshot(long availableWen, long heldWen, long version) {
    }

    public record CampaignReceipt(String transactionId, String status, long amountWen, String campaignRef) {
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
