package cn.jia.agent.skill;

import cn.jia.economy.entity.skill.SkillProductVersionEntity;
import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SkillPackagesTest {
    @TempDir Path root;
    private SkillProductVersionEntity approved(byte[] bytes) {
        String permissions="{}";
        return new SkillProductVersionEntity().setProductId("sp_pdf").setProductVersionId("spv_pdf_1")
                .setSkillKey("pdf-export").setSkillVersion("1.0.0").setPackageSize((long)bytes.length)
                .setPackageSha256(SkillPackageStore.sha256(bytes)).setReviewStatus("APPROVED").setPriceMicro(0L)
                .setDeploymentRestriction("NONE").setApprovedPermissionsManifest(permissions)
                .setApprovedPermissionsSha256(SkillPackageStore.sha256(permissions.getBytes(StandardCharsets.UTF_8)))
                .setTenantId("tenant").setClientId("client");
    }
    @Test void newApprovedPackageDoesNotNeedClasspathOrApplicationRestart() {
        byte[] bytes="new pdf skill".getBytes(StandardCharsets.UTF_8);var packages=new SkillPackages(root);
        new SkillPackageStore(root).publish(bytes);var v=approved(bytes);
        var prepared=packages.prepare(v);assertArrayEquals(bytes,prepared.bytes());SkillPackages.revalidate(v,prepared);
        byte[] another="new xlsx skill".getBytes(StandardCharsets.UTF_8);new SkillPackageStore(root).publish(another);
        assertArrayEquals(another,packages.prepare(approved(another).setSkillKey("xlsx-export").setProductVersionId("spv_xlsx_1")).bytes());
    }
    @Test void builtinSeedBytesStillVerifiedWithoutProvisioningStore() {
        var catalog=new PlatformSkillPackageCatalog();var seed=catalog.products().getFirst();byte[] bytes=catalog.requirePackage(seed.productVersionId()).bytes();
        var packages=new SkillPackages(root.resolve("absent"));assertArrayEquals(bytes,packages.prepare(approved(bytes)).bytes());
        assertFalse(Files.exists(root.resolve("absent")));
    }
    @Test void storedObjectWithoutApprovalCannotBecomeAuthorization() {
        byte[] bytes="unapproved".getBytes(StandardCharsets.UTF_8);new SkillPackageStore(root).publish(bytes);
        var packages=new SkillPackages(root);
        assertThrows(SkillMarketplaceException.class,()->packages.prepare(approved(bytes).setReviewStatus("PENDING")));
        assertThrows(SkillMarketplaceException.class,()->packages.prepare(null));
    }
    @Test void cacheSharesBytesButNeverPriceOrPermissionIdentity() {
        byte[] bytes="same content".getBytes(StandardCharsets.UTF_8);new SkillPackageStore(root).publish(bytes);var packages=new SkillPackages(root);
        var first=packages.prepare(approved(bytes));var second=packages.prepare(approved(bytes).setPriceMicro(5L).setProductVersionId("spv_2"));
        assertEquals(0L,first.product().priceMicro());assertEquals(5L,second.product().priceMicro());
        byte[] copy=first.bytes();copy[0]^=1;assertArrayEquals(bytes,packages.prepare(approved(bytes)).bytes());
    }
    @Test void transactionPreparationForbiddenButFactRevalidationDoesNotReadFiles() throws Exception {
        byte[] bytes="ready".getBytes(StandardCharsets.UTF_8);var store=new SkillPackageStore(root);String key=store.publish(bytes);var packages=new SkillPackages(root);
        var v=approved(bytes);var prepared=packages.prepare(v);Files.delete(root.resolve(key+".zip"));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(SkillMarketplaceException.class,()->packages.prepare(v)); assertDoesNotThrow(()->SkillPackages.revalidate(v,prepared)); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    @Test void approvedFactDriftRejectedInsideTransaction() {
        byte[] bytes="facts".getBytes(StandardCharsets.UTF_8);new SkillPackageStore(root).publish(bytes);var packages=new SkillPackages(root);var prepared=packages.prepare(approved(bytes));
        assertThrows(SkillMarketplaceException.class,()->SkillPackages.revalidate(approved(bytes).setPriceMicro(1L),prepared));
        assertThrows(SkillMarketplaceException.class,()->SkillPackages.revalidate(approved(bytes).setProductVersionId("other"),prepared));
        assertThrows(SkillMarketplaceException.class,()->SkillPackages.revalidate(approved(bytes).setSkillKey("other"),prepared));
        assertThrows(SkillMarketplaceException.class,()->SkillPackages.revalidate(approved(bytes).setReviewStatus("REJECTED"),prepared));
        assertThrows(SkillMarketplaceException.class,()->SkillPackages.revalidate(approved(bytes).setDeploymentRestriction("ADMIN_ONLY"),prepared));
        assertThrows(SkillMarketplaceException.class,()->SkillPackages.revalidate(approved(bytes).setPackageSha256(new byte[32]),prepared));
    }
    @Test void malformedImmutableMetadataRejected() {
        byte[] bytes="metadata".getBytes(StandardCharsets.UTF_8);var packages=new SkillPackages(root);
        assertThrows(SkillMarketplaceException.class,()->packages.prepare(approved(bytes).setApprovedPermissionsSha256(new byte[32])));
        assertThrows(SkillMarketplaceException.class,()->packages.prepare(approved(bytes).setDeploymentRestriction(null)));
        assertThrows(SkillMarketplaceException.class,()->packages.prepare(approved(bytes).setPackageSize(Long.MAX_VALUE)));
    }
}
