package cn.jia.agent.config.skill.skillseed;

import cn.jia.agent.config.skill.SkillSeedGate;
import cn.jia.agent.config.skill.SkillSeedProperties;
import cn.jia.agent.entity.skill.SkillProductEntity;
import cn.jia.agent.entity.skill.SkillProductVersionEntity;
import cn.jia.agent.mapper.skill.SkillCatalogMapper;
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

class PlatformSkillSeederTest {
    private static final String TENANT = "preview-tenant";
    private static final String CLIENT = "preview-client";

    @Test
    void seedsSixPublishedImmutableProductsOnceAndSubsequentRunIsIdempotent() {
        SkillCatalogMapper mapper = mock(SkillCatalogMapper.class);
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
        PlatformSkillSeeder seeder = new PlatformSkillSeeder(mapper, gate(), catalog,
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
        var first=catalog.products().getFirst();String newerId=first.productVersionId()+"_new";
        versions.put(newerId,new SkillProductVersionEntity().setProductVersionId(newerId).setProductId(first.productId()).setVersionSequence(2L).setSkillKey(first.skillKey()).setSkillVersion("1.1.0").setReviewStatus("APPROVED").setTenantId(TENANT).setClientId(CLIENT));
        products.get(first.productId()).setCurrentProductVersionId(newerId).setVersion(3L);
        seeder.afterPropertiesSet();
        assertEquals(newerId,products.get(first.productId()).getCurrentProductVersionId());
        assertEquals(3L,products.get(first.productId()).getVersion());
        products.get(first.productId()).setStatus("RETIRED").setVersion(4L);
        seeder.afterPropertiesSet();
        assertEquals("RETIRED",products.get(first.productId()).getStatus());
        assertEquals(4L,products.get(first.productId()).getVersion());
        assertEquals(newerId,products.get(first.productId()).getCurrentProductVersionId());
        verify(mapper, times(6)).publishProductVersion(anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong());

    }

    @Test
    void incompatibleExistingImmutableVersionFailsClosedBeforePublication() {
        SkillCatalogMapper mapper = mock(SkillCatalogMapper.class);
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

        PlatformSkillSeeder seeder = new PlatformSkillSeeder(mapper, gate(), catalog,
                directTransactions(), () -> 1234L);
        IllegalStateException failure = assertThrows(IllegalStateException.class, seeder::afterPropertiesSet);
        assertEquals("Incompatible platform skill immutable product version: " + expected.productVersionId(),
                failure.getMessage());
        verify(mapper, times(0)).publishProductVersion(anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong());
    }

    private static SkillSeedGate gate() {
        return new SkillSeedGate(new SkillSeedProperties(true,
                List.of(new SkillSeedProperties.AllowedScope(TENANT, CLIENT))));
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
