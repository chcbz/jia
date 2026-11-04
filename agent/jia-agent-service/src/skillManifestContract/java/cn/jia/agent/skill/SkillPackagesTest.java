package cn.jia.agent.skill;

import cn.jia.agent.entity.skill.SkillProductVersionEntity;
import cn.jia.agent.config.skill.skillseed.PlatformSkillPackageCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SkillPackagesTest {
    @TempDir Path root;
    private byte[] archive(String label) {
        try {
            var buffer=new java.io.ByteArrayOutputStream();
            try(var zip=new java.util.zip.ZipOutputStream(buffer)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("SKILL.md"));
                zip.write(label.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
            return buffer.toByteArray();
        } catch(java.io.IOException e) { throw new IllegalStateException(e); }
    }
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
        byte[] bytes=archive("new pdf skill");var packages=new SkillPackages(root);
        new SkillPackageStore(root).publish(bytes);var v=approved(bytes);
        var prepared=packages.prepare(v);assertArrayEquals(bytes,prepared.bytes());SkillPackages.revalidate(v,prepared);
        byte[] another=archive("new xlsx skill");new SkillPackageStore(root).publish(another);
        assertArrayEquals(another,packages.prepare(approved(another).setSkillKey("xlsx-export").setProductVersionId("spv_xlsx_1")).bytes());
    }
    @Test void builtinSeedBytesStillVerifiedWithoutProvisioningStore() {
        var catalog=new PlatformSkillPackageCatalog();var seed=catalog.products().getFirst();byte[] bytes=catalog.requirePackage(seed.productVersionId()).bytes();
        var packages=new SkillPackages(root.resolve("absent"));assertArrayEquals(bytes,packages.prepare(approved(bytes)).bytes());
        assertFalse(Files.exists(root.resolve("absent")));
    }
    @Test void storedObjectWithoutApprovalCannotBecomeAuthorization() {
        byte[] bytes=archive("unapproved");new SkillPackageStore(root).publish(bytes);
        var packages=new SkillPackages(root);
        assertThrows(SkillMarketplaceException.class,()->packages.prepare(approved(bytes).setReviewStatus("PENDING")));
        assertThrows(SkillMarketplaceException.class,()->packages.prepare(null));
    }
    @Test void downloadsAreIsolatedAndDescriptionsNeverSharePriceOrPermissionIdentity() {
        byte[] bytes=archive("same content");new SkillPackageStore(root).publish(bytes);var packages=new SkillPackages(root);
        var first=packages.describe(approved(bytes));var second=packages.describe(approved(bytes).setPriceMicro(5L).setProductVersionId("spv_2"));
        assertEquals(0L,first.product().priceMicro());assertEquals(5L,second.product().priceMicro());
        byte[] copy=packages.prepare(approved(bytes)).bytes();copy[0]^=1;assertArrayEquals(bytes,packages.prepare(approved(bytes)).bytes());
    }
    @Test void operationPreparationSurvivesZeroCacheBudgetAndTransactionWithoutFiles() throws Exception {
        byte[] bytes=archive("ready");var store=new SkillPackageStore(root);String key=store.publish(bytes);
        var packages=new SkillPackages(root,0);var v=approved(bytes);
        packages.withPreparedDescriptions(()->{
            var prepared=packages.describe(v);
            try { Files.delete(root.resolve(key+".zip")); } catch(java.io.IOException e) { throw new AssertionError(e); }
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThrows(SkillMarketplaceException.class,()->packages.prepare(v));
                assertNull(packages.manifest(prepared));
                assertNull(packages.describe(v).manifest());
                assertDoesNotThrow(()->SkillPackages.revalidate(v,prepared));
                assertThrows(SkillMarketplaceException.class,()->packages.describe(approved(bytes).setReviewStatus("REJECTED")));
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            return null;
        });
        // Scope exit does not leave hidden per-thread bytes/metadata behind.
        assertThrows(SkillMarketplaceException.class,()->packages.describe(v));
    }
    @Test void directoryHitDoesNotReadZipButDownloadNeverUsesStaleBytes() throws Exception {
        byte[] bytes=archive("directory");String key=new SkillPackageStore(root).publish(bytes);
        var packages=new SkillPackages(root);var v=approved(bytes);var description=packages.describe(v);
        Files.delete(root.resolve(key+".zip"));
        assertEquals(description.product().productVersionId(),packages.describe(v).product().productVersionId());
        assertNull(packages.describe(v).manifest());
        assertThrows(SkillMarketplaceException.class,()->packages.prepare(v));
        assertThrows(SkillMarketplaceException.class,()->packages.describe(approved(bytes).setPackageSize((long)bytes.length+1)));
    }
    @Test void descriptionBudgetEvictsWithoutInvalidatingPreparedOperations() throws Exception {
        byte[] a=archive("a"),b=archive("b");var store=new SkillPackageStore(root);
        String aKey=store.publish(a);store.publish(b);var packages=new SkillPackages(root,1024);
        packages.withPreparedDescriptions(()->{
            var prepared=packages.describe(approved(a));
            packages.describe(approved(b)); // evicts a from global description cache
            try { Files.delete(root.resolve(aKey+".zip")); } catch(java.io.IOException e) { throw new AssertionError(e); }
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertNull(packages.describe(approved(a)).manifest());SkillPackages.revalidate(approved(a),prepared); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            return null;
        });
        assertThrows(SkillMarketplaceException.class,()->packages.describe(approved(a)));
        assertDoesNotThrow(()->packages.describe(approved(b)));
    }
    @Test void exceptionalAndNestedScopesReleaseTheirOwnPreparation() throws Exception {
        byte[] a=archive("outer"),b=archive("inner");var store=new SkillPackageStore(root);
        String aKey=store.publish(a),bKey=store.publish(b);var packages=new SkillPackages(root,0);
        assertThrows(IllegalStateException.class,()->packages.withPreparedDescriptions(()->{
            packages.describe(approved(a));
            assertThrows(IllegalArgumentException.class,()->packages.withPreparedDescriptions(()->{
                packages.describe(approved(b));throw new IllegalArgumentException("nested");
            }));
            try { Files.delete(root.resolve(aKey+".zip"));Files.delete(root.resolve(bKey+".zip")); }
            catch(java.io.IOException e) {throw new AssertionError(e);}
            assertNull(packages.describe(approved(a)).manifest());
            assertThrows(SkillMarketplaceException.class,()->packages.describe(approved(b)));
            throw new IllegalStateException("outer");
        }));
        assertThrows(SkillMarketplaceException.class,()->packages.describe(approved(a)));
    }
    @Test void preparedDescriptionsNeverLeakToAnotherThreadOrItsNextOperation() throws Exception {
        byte[] bytes=archive("thread-local");String key=new SkillPackageStore(root).publish(bytes);
        var packages=new SkillPackages(root,0);var v=approved(bytes);
        try(var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            packages.withPreparedDescriptions(()->{
                packages.describe(v);
                try {
                    Files.delete(root.resolve(key+".zip"));
                    worker.submit(()->assertThrows(SkillMarketplaceException.class,()->packages.describe(v))).get();
                } catch(Exception e) {throw new AssertionError(e);}
                assertNull(packages.describe(v).manifest());
                return null;
            });
            assertThrows(SkillMarketplaceException.class,()->packages.describe(v));
        }
    }
    @Test void approvedFactDriftRejectedInsideTransaction() {
        byte[] bytes=archive("facts");new SkillPackageStore(root).publish(bytes);var packages=new SkillPackages(root);var prepared=packages.prepare(approved(bytes));
        assertThrows(SkillMarketplaceException.class,()->SkillPackages.revalidate(approved(bytes).setPriceMicro(1L),prepared));
        assertThrows(SkillMarketplaceException.class,()->SkillPackages.revalidate(approved(bytes).setProductVersionId("other"),prepared));
        assertThrows(SkillMarketplaceException.class,()->SkillPackages.revalidate(approved(bytes).setSkillKey("other"),prepared));
        assertThrows(SkillMarketplaceException.class,()->SkillPackages.revalidate(approved(bytes).setReviewStatus("REJECTED"),prepared));
        assertThrows(SkillMarketplaceException.class,()->SkillPackages.revalidate(approved(bytes).setDeploymentRestriction("ADMIN_ONLY"),prepared));
        assertThrows(SkillMarketplaceException.class,()->SkillPackages.revalidate(approved(bytes).setPackageSha256(new byte[32]),prepared));
    }
    @Test void malformedImmutableMetadataRejected() {
        byte[] bytes=archive("metadata");var packages=new SkillPackages(root);
        assertThrows(SkillMarketplaceException.class,()->packages.prepare(approved(bytes).setApprovedPermissionsSha256(new byte[32])));
        assertThrows(SkillMarketplaceException.class,()->packages.prepare(approved(bytes).setDeploymentRestriction(null)));
        assertThrows(SkillMarketplaceException.class,()->packages.prepare(approved(bytes).setPackageSize(Long.MAX_VALUE)));
    }
    @Test void executableDescriptionIsIdentityBoundAndCannotBeMutatedByConsumers() throws Exception {
        byte[] manifest=null;
        try(var input=getClass().getResourceAsStream("/skill-manifest-contract-v1.json")) {
            for(var item:new com.fasterxml.jackson.databind.ObjectMapper().readTree(input).path("cases"))
                if("image-edit".equals(item.path("id").asText()))manifest=item.path("json").asText().getBytes(StandardCharsets.UTF_8);
        }
        assertNotNull(manifest);
        var output=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(output)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("SKILL.md"));zip.write("edit".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));zip.write(manifest);zip.closeEntry();
        }
        byte[] bytes=output.toByteArray();new SkillPackageStore(root).publish(bytes);
        var packages=new SkillPackages(root);var v=approved(bytes).setSkillKey("image-edit");
        var description=packages.describe(v);
        ((com.fasterxml.jackson.databind.node.ObjectNode)description.manifest().document()).put("skillKey","tampered");
        assertEquals("image-edit",packages.describe(v).manifest().document().path("skillKey").asText());
        assertThrows(SkillMarketplaceException.class,()->packages.describe(approved(bytes).setSkillKey("wrong")));
        assertThrows(SkillMarketplaceException.class,()->packages.describe(approved(bytes).setSkillKey("image-edit").setSkillVersion("2.0.0")));
        assertEquals("image-edit",packages.describe(v).manifest().document().path("skillKey").asText());
    }

}
