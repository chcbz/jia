package cn.jia.economy.config.skillseed;

import cn.jia.economy.config.EconomySkillSeedGate;
import cn.jia.economy.config.EconomySkillSeedProperties;
import cn.jia.economy.entity.skill.SkillProductEntity;
import cn.jia.economy.entity.skill.SkillProductVersionEntity;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EconomyPlatformSkillSeederTest {
    private static final String TENANT = "preview-tenant";
    private static final String CLIENT = "preview-client";

    @Test
    void seedsSixPublishedImmutableProductsOnceAndSubsequentRunIsIdempotent() {
        EconomySkillMarketplaceMapper mapper = mock(EconomySkillMarketplaceMapper.class);
        Map<String, SkillProductEntity> products = new LinkedHashMap<>();
        Map<String, SkillProductVersionEntity> versions = new LinkedHashMap<>();
        when(mapper.selectProductForUpdate(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> products.get(invocation.getArgument(2)));
        when(mapper.insertProduct(any())).thenAnswer(invocation -> {
            SkillProductEntity product = invocation.getArgument(0);
            products.put(product.getProductId(), product);
            return 1;
        });
        when(mapper.selectApprovedProductVersionForUpdate(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> versions.get(invocation.getArgument(2)));
        when(mapper.insertProductVersion(any())).thenAnswer(invocation -> {
            SkillProductVersionEntity version = invocation.getArgument(0);
            versions.put(version.getProductVersionId(), version);
            return 1;
        });
        when(mapper.publishProductVersion(anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    SkillProductEntity product = products.get(invocation.getArgument(2));
                    product.setStatus("PUBLISHED");
                    product.setCurrentProductVersionId(invocation.getArgument(3));
                    product.setVersion(2L);
                    return 1;
                });

        PlatformSkillPackageCatalog catalog = new PlatformSkillPackageCatalog();
        EconomyPlatformSkillSeeder seeder = new EconomyPlatformSkillSeeder(mapper, gate(), catalog,
                directTransactions(), () -> 1234L);
        seeder.afterPropertiesSet();

        assertEquals(6, products.size());
        assertEquals(6, versions.size());
        for (PlatformSkillPackageCatalog.PlatformSkillProduct expected : catalog.products()) {
            SkillProductEntity product = products.get(expected.productId());
            SkillProductVersionEntity version = versions.get(expected.productVersionId());
            assertEquals("SYSTEM", product.getSellerType());
            assertEquals("SKILL_STORE", product.getSellerId());
            assertEquals("PUBLISHED", product.getStatus());
            assertEquals(expected.productVersionId(), product.getCurrentProductVersionId());
            assertEquals(2L, product.getVersion());
            assertEquals(expected.skillKey(), version.getSkillKey());
            assertEquals(expected.skillVersion(), version.getSkillVersion());
            assertEquals(expected.priceMicro(), version.getPriceMicro());
            assertEquals(expected.packageSize(), version.getPackageSize());
            assertEquals(expected.deploymentRestriction(), version.getDeploymentRestriction());
            assertEquals("[]", version.getApprovedPermissionsManifest());
        }

        seeder.afterPropertiesSet();
        verify(mapper, times(6)).insertProduct(any(SkillProductEntity.class));
        verify(mapper, times(6)).insertProductVersion(any(SkillProductVersionEntity.class));
        verify(mapper, times(6)).publishProductVersion(anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    void incompatibleExistingImmutableVersionFailsClosedBeforePublication() {
        EconomySkillMarketplaceMapper mapper = mock(EconomySkillMarketplaceMapper.class);
        PlatformSkillPackageCatalog catalog = new PlatformSkillPackageCatalog();
        PlatformSkillPackageCatalog.PlatformSkillProduct expected = catalog.products().getFirst();
        SkillProductEntity product = new SkillProductEntity()
                .setProductId(expected.productId()).setSellerType("SYSTEM").setSellerId("SKILL_STORE")
                .setName(expected.name()).setDescription(expected.description()).setStatus("DRAFT")
                .setVersion(1L).setTenantId(TENANT).setClientId(CLIENT);
        SkillProductVersionEntity incompatible = new SkillProductVersionEntity()
                .setProductVersionId(expected.productVersionId()).setProductId(expected.productId())
                .setVersionSequence(1L).setSkillKey(expected.skillKey()).setSkillVersion(expected.skillVersion())
                .setPriceMicro(expected.priceMicro() + 1L).setPackageSha256(expected.packageSha256())
                .setPackageSize(expected.packageSize()).setApprovedPermissionsManifest("[]")
                .setApprovedPermissionsSha256(expected.approvedPermissionsSha256())
                .setDeploymentRestriction(expected.deploymentRestriction()).setReviewStatus("APPROVED")
                .setTenantId(TENANT).setClientId(CLIENT);
        when(mapper.selectProductForUpdate(TENANT, CLIENT, expected.productId())).thenReturn(product);
        when(mapper.selectApprovedProductVersionForUpdate(TENANT, CLIENT, expected.productVersionId()))
                .thenReturn(incompatible);

        EconomyPlatformSkillSeeder seeder = new EconomyPlatformSkillSeeder(mapper, gate(), catalog,
                directTransactions(), () -> 1234L);
        IllegalStateException failure = assertThrows(IllegalStateException.class, seeder::afterPropertiesSet);
        assertEquals("Incompatible platform skill immutable product version: " + expected.productVersionId(),
                failure.getMessage());
        verify(mapper, times(0)).publishProductVersion(anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong());
    }

    private static EconomySkillSeedGate gate() {
        return new EconomySkillSeedGate(new EconomySkillSeedProperties(true,
                List.of(new EconomySkillSeedProperties.AllowedScope(TENANT, CLIENT))));
    }

    private static TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                TransactionStatus status = new SimpleTransactionStatus();
                return action.doInTransaction(status);
            }
        };
    }
}
