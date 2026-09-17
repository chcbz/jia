package cn.jia.agent.preview;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.config.EconomyReadOnlyPreviewProperties;
import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.agent.hosting.HostingRentApplicationException;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.hosting.HostingRentOwnerResolver;
import cn.jia.agent.mapper.EconomyReadOnlyPreviewMapper;
import cn.jia.agent.mapper.EconomyReadOnlyPreviewRows;
import cn.jia.agent.service.funding.FundedBountyPreviewPriceBook;
import cn.jia.agent.service.funding.FundedBountyQuoteCalculator;
import cn.jia.economy.entity.EconomyHostingLeaseEntity;
import cn.jia.economy.entity.EconomyHostingRentPlanEntity;
import cn.jia.economy.entity.EconomyWalletLedgerRow;
import cn.jia.economy.entity.EconomyWalletSnapshotRow;
import cn.jia.economy.entity.skill.SkillEntitlementEntity;
import cn.jia.economy.entity.skill.SkillInstallationEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.jia.agent.preview.EconomyReadOnlyPreviewDtos.*;

/**
 * Independent v1.7 query/application service. Dependencies are one SELECT-only mapper, immutable
 * configuration descriptors, and the pure funded-bounty calculator. It has no posting, publisher,
 * provisioner, package, quote, purchase, renewal, seed, or Provider dependency.
 */
@Service
public class EconomyReadOnlyPreviewService {
    public static final String CONTRACT_VERSION = "economy-readonly-v1";
    private static final String MODE = "READ_ONLY_PREVIEW";
    private static final String CURRENCY = "SILVER";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EconomyReadOnlyPreviewMapper mapper;
    private final EconomyReadOnlyPreviewProperties properties;
    private final AgentHostingRentProperties hosting;
    private final HostingRentOwnerResolver owners;

    public EconomyReadOnlyPreviewService(EconomyReadOnlyPreviewMapper mapper,
            EconomyReadOnlyPreviewProperties properties, AgentHostingRentProperties hosting,
            HostingRentOwnerResolver owners) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.hosting = Objects.requireNonNull(hosting, "hosting");
        this.owners = Objects.requireNonNull(owners, "owners");
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public Capabilities capabilities(Principal principal) {
        requirePrincipal(principal);
        boolean enabled = enabled();
        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("wallet", enabled);
        features.put("ledger", enabled);
        features.put("catalog", enabled);
        features.put("installationStatus", enabled);
        features.put("hostingPlan", enabled);
        features.put("hostingLease", enabled);
        Map<String, Boolean> actions = new LinkedHashMap<>();
        actions.put("estimate", enabled);
        actions.put("issue", false);
        actions.put("purchase", false);
        actions.put("settle", false);
        actions.put("refund", false);
        actions.put("install", false);
        actions.put("hostingActivate", false);
        actions.put("hostingRenew", false);
        return new Capabilities(CONTRACT_VERSION, MODE, enabled, fingerprint(principal),
                Map.copyOf(features), Map.copyOf(actions));
    }

    @Transactional(readOnly = true)
    public Wallet wallet(Principal principal) {
        requirePrincipal(principal);
        requireEnabled();
        return data(() -> {
            EconomyWalletSnapshotRow row = mapper.selectWallet(
                    principal.walletTenantId(), principal.clientId(), principal.actorId());
            if (row == null) throw unavailable("Wallet snapshot is unavailable");
            long available = nonNegative(row.getAvailableMicro(), "availableMicro");
            long held = nonNegative(row.getHeldMicro(), "heldMicro");
            nonNegative(row.getMinimumHeldComponentMicro(), "minimumHeldComponentMicro");
            long version = nonNegative(row.getVersion(), "version");
            return new Wallet(CURRENCY, number(available), number(held), number(version));
        });
    }

    @Transactional(readOnly = true)
    public LedgerPage ledger(Principal principal, Cursor cursor, int limit) {
        requirePrincipal(principal);
        if (limit < 1 || limit > 100 || (cursor != null
                && (cursor.postedAt() < 0 || cursor.rowId() < 1))) throw badRequest();
        requireEnabled();
        return data(() -> {
            List<EconomyWalletLedgerRow> rows = mapper.selectLedger(principal.walletTenantId(), principal.clientId(),
                    principal.actorId(), cursor == null ? null : cursor.postedAt(),
                    cursor == null ? null : cursor.rowId(), Math.addExact(limit, 1));
            if (rows == null) throw unavailable("Wallet ledger is unavailable");
            boolean more = rows.size() > limit;
            List<EconomyWalletLedgerRow> visible = more ? rows.subList(0, limit) : rows;
            List<LedgerItem> items = new ArrayList<>(visible.size());
            for (EconomyWalletLedgerRow row : visible) items.add(ledgerItem(row));
            String next = more ? encodeCursor(cursorOf(visible.getLast())) : null;
            return new LedgerPage(List.copyOf(items), next);
        });
    }

    @Transactional(readOnly = true)
    public ProductPage products(Principal principal, int offset, int limit) {
        requirePrincipal(principal);
        if (offset < 0 || limit < 1 || limit > 100) throw badRequest();
        try {
            Math.addExact(offset, limit);
        } catch (ArithmeticException exception) {
            throw badRequest();
        }
        requireEnabled();
        return data(() -> {
            List<EconomyReadOnlyPreviewRows.ProductRow> rows = mapper.selectProducts(
                    principal.marketplaceTenantId(), principal.clientId(), offset, Math.addExact(limit, 1));
            if (rows == null) throw unavailable("Skill catalog is unavailable");
            boolean more = rows.size() > limit;
            List<EconomyReadOnlyPreviewRows.ProductRow> visible = more ? rows.subList(0, limit) : rows;
            List<Product> items = new ArrayList<>(visible.size());
            for (EconomyReadOnlyPreviewRows.ProductRow row : visible) items.add(product(row));
            String next = more ? number(Math.addExact(offset, limit)) : null;
            return new ProductPage(List.copyOf(items), next);
        });
    }

    @Transactional(readOnly = true)
    public Product product(Principal principal, String productId) {
        requirePrincipal(principal);
        if (!exact(productId, 100)) throw badRequest();
        requireEnabled();
        return data(() -> {
            List<EconomyReadOnlyPreviewRows.ProductRow> rows = mapper.selectProduct(
                    principal.marketplaceTenantId(), principal.clientId(), productId);
            if (rows == null) throw unavailable("Skill product is unavailable");
            if (rows.isEmpty()) throw notFound();
            if (rows.size() != 1) throw unavailable("Skill product identity is ambiguous");
            return product(rows.getFirst());
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AgentSkills agentSkills(Principal principal, String agentId) {
        requirePrincipal(principal);
        if (!exact(agentId, 100)) throw badRequest();
        requireEnabled();
        return data(() -> {
            ownedAgent(principal, agentId);
            List<SkillEntitlementEntity> entitlements = mapper.selectEntitlements(
                    principal.marketplaceTenantId(), principal.clientId(), agentId);
            List<SkillInstallationEntity> installations = mapper.selectInstallations(
                    principal.marketplaceTenantId(), principal.clientId(), agentId);
            if (entitlements == null || installations == null) {
                throw unavailable("Agent skill evidence is unavailable");
            }
            Map<String, SkillInstallationEntity> byId = new HashMap<>();
            for (SkillInstallationEntity installation : installations) {
                requireInstallationShape(installation, principal, agentId);
                if (byId.put(installation.getInstallationId(), installation) != null) {
                    throw unavailable("Duplicate installation evidence");
                }
            }
            List<Entitlement> rights = new ArrayList<>(entitlements.size());
            List<InstallationEvidence> evidence = new ArrayList<>(entitlements.size());
            for (SkillEntitlementEntity entitlement : entitlements) {
                requireEntitlementShape(entitlement, principal, agentId);
                rights.add(new Entitlement(entitlement.getEntitlementId(), entitlement.getOrderId(),
                        entitlement.getInstallationId(), entitlement.getProductVersionId(),
                        entitlement.getSkillKey(), entitlement.getSkillVersion(), entitlement.getStatus(),
                        number(nonNegative(entitlement.getPermissionGrantVersion(), "permissionGrantVersion"))));
                SkillInstallationEntity installation = byId.get(entitlement.getInstallationId());
                boolean verified = verified(entitlement, installation, agentId);
                evidence.add(new InstallationEvidence(entitlement.getInstallationId(), entitlement.getSkillKey(),
                        entitlement.getSkillVersion(), verified ? "VERIFIED_INSTALLED" : "UNCONFIRMED",
                        verified ? "PERSISTED_INSTALL_RESULT" : installation == null
                                ? "NO_PERSISTED_INSTALLATION" : "PERSISTED_INSTALLATION_UNVERIFIED",
                        verified ? number(installation.getInstalledAt()) : null));
            }
            return new AgentSkills(agentId, List.copyOf(rights), List.copyOf(evidence),
                    number(System.currentTimeMillis()));
        });
    }

    @Transactional(readOnly = true)
    public HostingPlan hostingPlan(Principal principal) {
        requirePrincipal(principal);
        requireEnabled();
        try {
            if (hosting.descriptorConfigured()) {
                return new HostingPlan("CONFIGURATION_REFERENCE", hosting.planVersion(), hosting.amountMicro(),
                        hosting.periodSeconds(), CURRENCY, false);
            }
            EconomyHostingRentPlanEntity row = mapper.selectLatestPlan(principal.hostingTenantId(), principal.clientId());
            if (row == null || !"ACTIVE".equals(row.getStatus()) || !CURRENCY.equals(row.getCurrency())
                    || !principal.hostingTenantId().equals(row.getTenantId())
                    || !principal.clientId().equals(row.getClientId())) {
                throw planUnavailable();
            }
            return new HostingPlan("PERSISTED_REFERENCE", number(positive(row.getPlanVersion(), "planVersion")),
                    number(positive(row.getAmountMicro(), "amountMicro")),
                    number(positive(row.getPeriodSeconds(), "periodSeconds")), CURRENCY, false);
        } catch (EconomyReadOnlyPreviewException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw planUnavailable();
        } catch (RuntimeException exception) {
            throw planUnavailable();
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HostingLease hostingLease(Principal principal, String agentId) {
        requirePrincipal(principal);
        if (!exact(agentId, 100)) throw badRequest();
        requireEnabled();
        return data(() -> {
            EconomyReadOnlyPreviewRows.AgentOwnershipRow owner = ownedAgent(principal, agentId);
            List<AgentHostedProfileEntity> hosted = mapper.selectHostedProfile(principal.agentRegistryTenantId(), principal.clientId(), principal.ownerJiacn(),
                    requiredPositive(owner.getBindingId(), "bindingId"), agentId);
            if (hosted == null) throw unavailable("Hosting applicability is unavailable");
            if (hosted.size() > 1) throw unavailable("Hosting applicability is ambiguous");
            if (hosted.isEmpty()) return new HostingLease(agentId, "NOT_APPLICABLE", null);
            requireHostedShape(hosted.getFirst(), principal, owner, agentId);
            EconomyHostingLeaseEntity lease = mapper.selectLatestLease(principal.hostingTenantId(), principal.clientId(),
                    principal.actorId(), agentId);
            if (lease == null) return new HostingLease(agentId, "APPLICABLE", null);
            requireLeaseShape(lease, principal, agentId);
            return new HostingLease(agentId, "APPLICABLE", new Lease(lease.getLeaseId(),
                    number(nonNegative(lease.getVersion(), "leaseVersion")), lease.getStatus(),
                    number(positive(lease.getPlanVersion(), "planVersion")),
                    number(positive(lease.getAmountMicro(), "amountMicro")),
                    number(positive(lease.getPeriodSeconds(), "periodSeconds")),
                    nullableNumber(lease.getPaidFrom()), nullableNumber(lease.getPaidThrough())));
        });
    }

    public BountyEstimate estimate(Principal principal, EstimateInput input) {
        requirePrincipal(principal);
        requireEnabled();
        Objects.requireNonNull(input, "input");
        if (!lessOrEqual(input.estimatedTokens(), input.worstTokens())) throw badRequest();
        try {
            FundedBountyPreviewPriceBook.TokenClasses estimated = tokens(input.estimatedTokens());
            FundedBountyPreviewPriceBook.TokenClasses worst = tokens(input.worstTokens());
            FundedBountyQuoteCalculator.QuoteAmounts quote = FundedBountyQuoteCalculator.calculate(
                    input.grossBountyAmountMicro(), input.minimumAcceptedPayoutMicro(), estimated, worst);
            return new BountyEstimate("SIMULATION", false, false, CURRENCY,
                    FundedBountyPreviewPriceBook.VERSION, FundedBountyPreviewPriceBook.RATE_PROVENANCE,
                    wire(input.estimatedTokens()), wire(input.worstTokens()),
                    number(quote.estimatedComputeMicro()), number(quote.worstComputeMicro()),
                    number(quote.platformFeeMicro()), number(quote.estimatedAgentPayoutMicro()),
                    number(quote.worstAgentPayoutMicro()), number(quote.budgetHeadroomMicro()),
                    quote.budgetCovered());
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw badRequest();
        }
    }

    private EconomyReadOnlyPreviewRows.AgentOwnershipRow ownedAgent(Principal principal, String agentId) {
        try {
            String provenOwner = owners.requireOwner(new HostingRentHttp.Actor(principal.actorId(),
                    principal.agentRegistryTenantId(), principal.clientId(), principal.ownerJiacn()));
            if (!principal.ownerJiacn().equals(provenOwner)) throw notFound();
        } catch (HostingRentApplicationException exception) {
            throw notFound();
        }
        List<EconomyReadOnlyPreviewRows.AgentOwnershipRow> rows = mapper.selectOwnedAgent(
                principal.agentRegistryTenantId(), principal.clientId(), principal.ownerJiacn(), agentId);
        if (rows == null) throw unavailable("Agent ownership is unavailable");
        if (rows.isEmpty()) throw notFound();
        if (rows.size() != 1) throw unavailable("Agent ownership is ambiguous");
        EconomyReadOnlyPreviewRows.AgentOwnershipRow row = rows.getFirst();
        if (!agentId.equals(row.getAgentId()) || row.getBindingId() == null || row.getBindingId() < 1
                || !principal.agentRegistryTenantId().equals(row.getTenantId())
                || !principal.clientId().equals(row.getClientId())
                || !principal.ownerJiacn().equals(row.getOwnerJiacn())
                || !exact(row.getPersonaCode(), 100)) throw unavailable("Agent ownership row is invalid");
        return row;
    }

    private static Product product(EconomyReadOnlyPreviewRows.ProductRow row) {
        if (row == null || !exact(row.getProductId(), 100) || !exact(row.getName(), 255)
                || row.getDescription() == null || !exact(row.getProductVersionId(), 100)
                || !exact(row.getSkillKey(), 100) || !exact(row.getSkillVersion(), 100)
                || row.getPriceMicro() == null || row.getPriceMicro() < 0
                || !exact(row.getDeploymentRestriction(), 20)) {
            throw unavailable("Skill product row is invalid");
        }
        return new Product(row.getProductId(), row.getName(), row.getDescription(),
                row.getProductVersionId(), row.getSkillKey(), row.getSkillVersion(),
                number(row.getPriceMicro()), permissions(row.getApprovedPermissionsManifest()),
                row.getDeploymentRestriction(), "CATALOG", false);
    }

    private static List<String> permissions(String raw) {
        try {
            List<Object> values = JSON.readValue(raw, new TypeReference<List<Object>>() { });
            if (values == null || values.size() > 32) throw unavailable("Skill permissions are invalid");
            List<String> result = new ArrayList<>(values.size());
            Set<String> unique = new HashSet<>();
            for (Object value : values) {
                if (!(value instanceof String text) || !exact(text, 100) || !unique.add(text)) {
                    throw unavailable("Skill permissions are invalid");
                }
                result.add(text);
            }
            return List.copyOf(result);
        } catch (EconomyReadOnlyPreviewException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable("Skill permissions are invalid");
        }
    }

    private static LedgerItem ledgerItem(EconomyWalletLedgerRow row) {
        if (row == null || !exact(row.getTransactionId(), 100) || !exact(row.getEntryId(), 140)
                || !exact(row.getBusinessType(), 32) || !exact(row.getBusinessRef(), 100)
                || !"POSTED".equals(row.getStatus()) || row.getPostedAt() == null || row.getPostedAt() < 0
                || row.getSignedAmountMicro() == null || row.getSignedAmountMicro() == 0) {
            throw unavailable("Wallet ledger row is invalid");
        }
        long signed = row.getSignedAmountMicro();
        long amount;
        try { amount = signed > 0 ? signed : Math.negateExact(signed); }
        catch (ArithmeticException exception) { throw unavailable("Wallet ledger amount is invalid"); }
        return new LedgerItem(row.getTransactionId(), row.getEntryId(), row.getBusinessType(),
                row.getBusinessRef(), signed > 0 ? "CREDIT" : "DEBIT", number(amount),
                row.getStatus(), number(row.getPostedAt()));
    }

    private static Cursor cursorOf(EconomyWalletLedgerRow row) {
        if (row == null || row.getPostedAt() == null || row.getPostedAt() < 0
                || row.getRowId() == null || row.getRowId() < 1) {
            throw unavailable("Wallet ledger cursor is invalid");
        }
        return new Cursor(row.getPostedAt(), row.getRowId());
    }

    public static String encodeCursor(Cursor cursor) {
        if (cursor == null) return null;
        if (cursor.postedAt() < 0 || cursor.rowId() < 1) throw badRequest();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteBuffer.allocate(16).putLong(cursor.postedAt()).putLong(cursor.rowId()).array());
    }

    public static Cursor decodeCursor(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{22}")) throw badRequest();
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length != 16 || !Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(decoded).equals(value)) throw badRequest();
            ByteBuffer bytes = ByteBuffer.wrap(decoded);
            Cursor cursor = new Cursor(bytes.getLong(), bytes.getLong());
            if (cursor.postedAt() < 0 || cursor.rowId() < 1) throw badRequest();
            return cursor;
        } catch (EconomyReadOnlyPreviewException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw badRequest();
        }
    }

    private static boolean verified(SkillEntitlementEntity entitlement,
            SkillInstallationEntity installation, String agentId) {
        return installation != null && "ACTIVE".equals(entitlement.getStatus())
                && "SUCCEEDED".equals(installation.getStatus())
                && installation.getInstalledAt() != null && installation.getInstalledAt() >= 0
                && agentId.equals(installation.getTargetAgentId())
                && entitlement.getInstallationId().equals(installation.getInstallationId())
                && entitlement.getOrderId().equals(installation.getOrderId())
                && entitlement.getProductVersionId().equals(installation.getProductVersionId())
                && entitlement.getSkillKey().equals(installation.getSkillKey())
                && entitlement.getSkillVersion().equals(installation.getSkillVersion());
    }

    private static void requireEntitlementShape(
            SkillEntitlementEntity row, Principal principal, String agentId) {
        if (row == null || !exact(row.getEntitlementId(), 100) || !exact(row.getOrderId(), 100)
                || !exact(row.getInstallationId(), 100) || !exact(row.getProductVersionId(), 100)
                || !agentId.equals(row.getTargetAgentId()) || !exact(row.getSkillKey(), 100)
                || !exact(row.getSkillVersion(), 100) || !exact(row.getStatus(), 32)
                || !principal.marketplaceTenantId().equals(row.getTenantId())
                || !principal.clientId().equals(row.getClientId())) {
            throw unavailable("Entitlement row is invalid");
        }
    }

    private static void requireInstallationShape(
            SkillInstallationEntity row, Principal principal, String agentId) {
        if (row == null || !exact(row.getInstallationId(), 100) || !exact(row.getOrderId(), 100)
                || !exact(row.getProductVersionId(), 100) || !agentId.equals(row.getTargetAgentId())
                || !exact(row.getSkillKey(), 100) || !exact(row.getSkillVersion(), 100)
                || !exact(row.getStatus(), 32)
                || !principal.marketplaceTenantId().equals(row.getTenantId())
                || !principal.clientId().equals(row.getClientId())) {
            throw unavailable("Installation row is invalid");
        }
    }

    private static void requireHostedShape(AgentHostedProfileEntity row, Principal principal,
            EconomyReadOnlyPreviewRows.AgentOwnershipRow owner, String agentId) {
        if (row == null || !Objects.equals(owner.getBindingId(), row.getBindingId())
                || !principal.agentRegistryTenantId().equals(row.getTenantId())
                || !principal.clientId().equals(row.getClientId())
                || !principal.ownerJiacn().equals(row.getOwnerJiacn())
                || !agentId.equals(row.getCanonicalAgentId())
                || !owner.getPersonaCode().equals(row.getPersonaCode())) {
            throw unavailable("Hosted Agent profile is invalid");
        }
    }

    private static void requireLeaseShape(
            EconomyHostingLeaseEntity row, Principal principal, String agentId) {
        if (!exact(row.getLeaseId(), 100) || !agentId.equals(row.getAgentId())
                || !"USER".equals(row.getPrincipalType()) || !principal.actorId().equals(row.getPrincipalId())
                || !principal.hostingTenantId().equals(row.getTenantId())
                || !principal.clientId().equals(row.getClientId())
                || !exact(row.getStatus(), 24)) throw unavailable("Hosting lease row is invalid");
        if ((row.getPaidFrom() == null) != (row.getPaidThrough() == null)
                || (row.getPaidFrom() != null && (row.getPaidFrom() < 0
                || row.getPaidThrough() <= row.getPaidFrom()))) {
            throw unavailable("Hosting lease period is invalid");
        }
    }

    private static FundedBountyPreviewPriceBook.TokenClasses tokens(ParsedTokens value) {
        return new FundedBountyPreviewPriceBook.TokenClasses(
                value.input(), value.cachedInput(), value.output(), value.reasoning());
    }

    private static TokenClasses wire(ParsedTokens value) {
        return new TokenClasses(number(value.input()), number(value.cachedInput()),
                number(value.output()), number(value.reasoning()));
    }

    private static boolean lessOrEqual(ParsedTokens estimated, ParsedTokens worst) {
        return estimated != null && worst != null && estimated.input() <= worst.input()
                && estimated.cachedInput() <= worst.cachedInput() && estimated.output() <= worst.output()
                && estimated.reasoning() <= worst.reasoning();
    }

    private <T> T data(java.util.function.Supplier<T> supplier) {
        try { return supplier.get(); }
        catch (EconomyReadOnlyPreviewException exception) { throw exception; }
        catch (DataAccessException exception) { throw unavailable("Preview data is unavailable"); }
        catch (RuntimeException exception) { throw unavailable("Preview data is unavailable"); }
    }

    private static void requirePrincipal(Principal principal) {
        if (principal == null || !exact(principal.walletTenantId(), 50)
                || !principal.walletTenantId().equals(principal.ownerJiacn())
                || !"0".equals(principal.marketplaceTenantId())
                || !"0".equals(principal.hostingTenantId())
                || !"0".equals(principal.agentRegistryTenantId())
                || !exact(principal.clientId(), 50) || !exact(principal.ownerJiacn(), 50)
                || !exact(principal.actorId(), 100) || "0".equals(principal.clientId())
                || "0".equals(principal.ownerJiacn()) || "0".equals(principal.actorId())
                || "anonymousUser".equals(principal.actorId())) {
            throw new EconomyReadOnlyPreviewException(HttpStatus.FORBIDDEN,
                    "PREVIEW_SCOPE_UNAVAILABLE", "Preview scope is unavailable");
        }
    }

    private void requireEnabled() {
        if (!enabled()) throw new EconomyReadOnlyPreviewException(HttpStatus.FORBIDDEN,
                "PREVIEW_DISABLED", "Read-only economy preview is disabled");
    }

    private static String fingerprint(Principal principal) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("economy-readonly-scope-v1".getBytes(StandardCharsets.UTF_8));
            put(digest, principal.walletTenantId());
            put(digest, principal.marketplaceTenantId());
            put(digest, principal.hostingTenantId());
            put(digest, principal.agentRegistryTenantId());
            put(digest, principal.clientId());
            put(digest, principal.ownerJiacn());
            put(digest, principal.actorId());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void put(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static long nonNegative(Long value, String field) {
        if (value == null || value < 0) throw unavailable(field + " is invalid");
        return value;
    }

    private static long positive(Long value, String field) {
        if (value == null || value < 1) throw unavailable(field + " is invalid");
        return value;
    }

    private static long requiredPositive(Long value, String field) {
        return positive(value, field);
    }

    private static String nullableNumber(Long value) {
        if (value == null) return null;
        if (value < 0) throw unavailable("negative timestamp");
        return number(value);
    }

    private static String number(long value) { return Long.toString(value); }

    public static boolean exact(String value, int maxBytes) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || padding(value.codePointAt(0)) || padding(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(Character::isISOControl)) return false;
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(unit)) return false;
        }
        return true;
    }

    private static boolean padding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static EconomyReadOnlyPreviewException unavailable(String message) {
        return new EconomyReadOnlyPreviewException(HttpStatus.SERVICE_UNAVAILABLE,
                "PREVIEW_DATA_UNAVAILABLE", message);
    }

    private static EconomyReadOnlyPreviewException planUnavailable() {
        return new EconomyReadOnlyPreviewException(HttpStatus.SERVICE_UNAVAILABLE,
                "PREVIEW_PLAN_UNAVAILABLE", "Hosting plan is unavailable");
    }

    private static EconomyReadOnlyPreviewException notFound() {
        return new EconomyReadOnlyPreviewException(HttpStatus.NOT_FOUND,
                "PREVIEW_RESOURCE_NOT_FOUND", "Preview resource was not found");
    }

    public static EconomyReadOnlyPreviewException badRequest() {
        return new EconomyReadOnlyPreviewException(HttpStatus.BAD_REQUEST,
                "PREVIEW_BAD_REQUEST", "Invalid preview request");
    }

    public record Cursor(long postedAt, long rowId) { }
}
