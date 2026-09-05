package cn.jia.economy.config.skillseed;

import cn.jia.economy.config.EconomySkillSeedGate;
import cn.jia.economy.entity.skill.SkillProductEntity;
import cn.jia.economy.entity.skill.SkillProductVersionEntity;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Inserts only the immutable V0 platform products for explicitly configured preview scopes. */
public final class EconomyPlatformSkillSeeder implements InitializingBean {
    private final EconomySkillMarketplaceMapper mapper;
    private final EconomySkillSeedGate gate;
    private final PlatformSkillPackageCatalog catalog;
    private final TransactionOperations transactions;
    private final LongSupplier clock;

    public EconomyPlatformSkillSeeder(
            EconomySkillMarketplaceMapper mapper,
            EconomySkillSeedGate gate,
            PlatformSkillPackageCatalog catalog,
            TransactionOperations transactions) {
        this(mapper, gate, catalog, transactions, System::currentTimeMillis);
    }

    EconomyPlatformSkillSeeder(
            EconomySkillMarketplaceMapper mapper,
            EconomySkillSeedGate gate,
            PlatformSkillPackageCatalog catalog,
            TransactionOperations transactions,
            LongSupplier clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void afterPropertiesSet() {
        for (EconomySkillSeedGate.Scope scope : gate.allowedScopes()) {
            transactions.executeWithoutResult(ignored -> seedScope(scope));
        }
    }

    void seedScope(EconomySkillSeedGate.Scope scope) {
        if (!gate.allows(scope.tenantId(), scope.clientId())) {
            throw new IllegalStateException("Platform skill seeding is outside the configured preview scope");
        }
        for (PlatformSkillPackageCatalog.PlatformSkillProduct product : catalog.products()) {
            seedProduct(scope, product);
        }
    }

    private void seedProduct(
            EconomySkillSeedGate.Scope scope, PlatformSkillPackageCatalog.PlatformSkillProduct expected) {
        SkillProductEntity product = mapper.selectProductForUpdate(scope.tenantId(), scope.clientId(), expected.productId());
        if (product == null) {
            long now = clock.getAsLong();
            product = draft(scope, expected, now);
            requireOne(mapper.insertProduct(product), "insert platform skill product " + expected.productId());
        } else {
            validateProductIdentity(product, scope, expected);
        }

        SkillProductVersionEntity version = mapper.selectApprovedProductVersionForUpdate(
                scope.tenantId(), scope.clientId(), expected.productVersionId());
        if (version == null) {
            PlatformSkillPackageCatalog.PlatformSkillPackage packageBytes = catalog.requirePackage(expected.productVersionId());
            requireOne(mapper.insertProductVersion(version(scope, expected, packageBytes, clock.getAsLong())),
                    "insert platform skill product version " + expected.productVersionId());
        } else {
            validateVersion(version, scope, expected);
        }

        if ("DRAFT".equals(product.getStatus())) {
            if (product.getCurrentProductVersionId() != null || !Long.valueOf(1L).equals(product.getVersion())) {
                throw incompatible("product draft publication state", expected.productId());
            }
            requireOne(mapper.publishProductVersion(scope.tenantId(), scope.clientId(), expected.productId(),
                    expected.productVersionId(), 1L, clock.getAsLong()),
                    "publish platform skill product " + expected.productId());
        } else if ("PUBLISHED".equals(product.getStatus())) {
            if (!expected.productVersionId().equals(product.getCurrentProductVersionId())
                    || !Long.valueOf(2L).equals(product.getVersion())) {
                throw incompatible("published product pointer", expected.productId());
            }
        } else {
            throw incompatible("product status", expected.productId());
        }
    }

    private static SkillProductEntity draft(
            EconomySkillSeedGate.Scope scope, PlatformSkillPackageCatalog.PlatformSkillProduct expected, long now) {
        return new SkillProductEntity()
                .setProductId(expected.productId()).setSellerType(PlatformSkillPackageCatalog.SELLER_TYPE)
                .setSellerId(PlatformSkillPackageCatalog.SELLER_ID).setCreatorAgentId(null)
                .setName(expected.name()).setDescription(expected.description()).setStatus("DRAFT")
                .setCurrentProductVersionId(null).setVersion(1L).setTenantId(scope.tenantId())
                .setClientId(scope.clientId()).setCreateTime(now).setUpdateTime(now);
    }

    private static SkillProductVersionEntity version(
            EconomySkillSeedGate.Scope scope,
            PlatformSkillPackageCatalog.PlatformSkillProduct expected,
            PlatformSkillPackageCatalog.PlatformSkillPackage packageBytes,
            long now) {
        if (!Arrays.equals(expected.packageSha256(), packageBytes.product().packageSha256())
                || expected.packageSize() != packageBytes.bytes().length) {
            throw incompatible("bundled package metadata", expected.productVersionId());
        }
        return new SkillProductVersionEntity()
                .setProductVersionId(expected.productVersionId()).setProductId(expected.productId())
                .setVersionSequence(1L).setSkillKey(expected.skillKey()).setSkillVersion(expected.skillVersion())
                .setPriceMicro(expected.priceMicro()).setPackageSha256(expected.packageSha256())
                .setPackageSize(expected.packageSize())
                .setApprovedPermissionsManifest(expected.approvedPermissionsManifest())
                .setApprovedPermissionsSha256(expected.approvedPermissionsSha256())
                .setDeploymentRestriction(expected.deploymentRestriction()).setReviewStatus("APPROVED")
                .setTenantId(scope.tenantId()).setClientId(scope.clientId()).setCreateTime(now);
    }

    private static void validateProductIdentity(
            SkillProductEntity actual, EconomySkillSeedGate.Scope scope,
            PlatformSkillPackageCatalog.PlatformSkillProduct expected) {
        if (!expected.productId().equals(actual.getProductId())
                || !PlatformSkillPackageCatalog.SELLER_TYPE.equals(actual.getSellerType())
                || !PlatformSkillPackageCatalog.SELLER_ID.equals(actual.getSellerId())
                || actual.getCreatorAgentId() != null
                || !expected.name().equals(actual.getName())
                || !expected.description().equals(actual.getDescription())
                || !scope.tenantId().equals(actual.getTenantId())
                || !scope.clientId().equals(actual.getClientId())) {
            throw incompatible("product identity", expected.productId());
        }
    }

    private static void validateVersion(
            SkillProductVersionEntity actual, EconomySkillSeedGate.Scope scope,
            PlatformSkillPackageCatalog.PlatformSkillProduct expected) {
        if (!expected.productVersionId().equals(actual.getProductVersionId())
                || !expected.productId().equals(actual.getProductId())
                || !Long.valueOf(1L).equals(actual.getVersionSequence())
                || !expected.skillKey().equals(actual.getSkillKey())
                || !expected.skillVersion().equals(actual.getSkillVersion())
                || !Long.valueOf(expected.priceMicro()).equals(actual.getPriceMicro())
                || !Arrays.equals(expected.packageSha256(), actual.getPackageSha256())
                || !Long.valueOf(expected.packageSize()).equals(actual.getPackageSize())
                || !expected.approvedPermissionsManifest().equals(actual.getApprovedPermissionsManifest())
                || !Arrays.equals(expected.approvedPermissionsSha256(), actual.getApprovedPermissionsSha256())
                || !expected.deploymentRestriction().equals(actual.getDeploymentRestriction())
                || !"APPROVED".equals(actual.getReviewStatus())
                || !scope.tenantId().equals(actual.getTenantId())
                || !scope.clientId().equals(actual.getClientId())) {
            throw incompatible("immutable product version", expected.productVersionId());
        }
    }

    private static void requireOne(int affected, String action) {
        if (affected != 1) throw new IllegalStateException("Expected exactly one row to " + action);
    }

    private static IllegalStateException incompatible(String subject, String id) {
        return new IllegalStateException("Incompatible platform skill " + subject + ": " + id);
    }
}
