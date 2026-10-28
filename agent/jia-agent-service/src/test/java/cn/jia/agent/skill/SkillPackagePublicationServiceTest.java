package cn.jia.agent.skill;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog;
import cn.jia.economy.entity.skill.*;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Trusted publisher unit boundaries; separate MySQL tests prove paired execution schema. */
class SkillPackagePublicationServiceTest {
    @TempDir Path root;
    final HostingRentHttp.Actor actor=new HostingRentHttp.Actor("admin","0","client","owner");
    final EconomySkillMarketplaceMapper mapper=mock(EconomySkillMarketplaceMapper.class);
    final Map<String,SkillProductEntity> products=new HashMap<>();final Map<String,SkillProductVersionEntity> versions=new HashMap<>();
    SkillPackages packages;SkillPackagePublicationService service;PlatformSkillPackageCatalog.PlatformSkillPackage original;
    @BeforeEach void setup() {
        packages=new SkillPackages(root);original=new PlatformSkillPackageCatalog().requirePackage("spv_repo_inspector_1_0_0");
        @SuppressWarnings("unchecked") ObjectProvider<SkillPackages> provider=mock(ObjectProvider.class);when(provider.getIfUnique()).thenReturn(packages);
        var tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());service=new SkillPackagePublicationService(mapper,provider,tx);
        when(mapper.selectProductForUpdate(anyString(),anyString(),anyString())).thenAnswer(i->products.get(i.getArgument(2)));
        when(mapper.selectApprovedProductVersionForUpdate(anyString(),anyString(),anyString())).thenAnswer(i->versions.get(i.getArgument(2)));
        when(mapper.insertProduct(any())).thenAnswer(i->{SkillProductEntity p=i.getArgument(0);products.put(p.getProductId(),p);return 1;});when(mapper.insertProductVersion(any())).thenAnswer(i->{SkillProductVersionEntity v=i.getArgument(0);versions.put(v.getProductVersionId(),v);return 1;});
        when(mapper.publishProductVersion(anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong())).thenAnswer(i->{var p=products.get(i.getArgument(2));long expected=i.getArgument(4);if(p.getVersion()!=expected)return 0;p.setVersion(expected+1).setStatus("PUBLISHED").setCurrentProductVersionId(i.getArgument(3));return 1;});
    }
    @AfterEach void clear(){TransactionSynchronizationManager.setActualTransactionActive(false);}
    SkillPackagePublicationService.Publish request(String version,long expected){var p=original.product();return new SkillPackagePublicationService.Publish("product",version,p.name(),p.description(),p.skillKey(),p.skillVersion(),0,"NONE","[]",HexFormat.of().formatHex(p.packageSha256()),null,expected,null);}
    @Test void immutableBytesPublicationReplayAndNextVersionWithoutAppSeedChange() {
        var first=service.publish(actor,true,request("v1",0),original.bytes());assertEquals("PUBLISHED",first.disposition());assertEquals(2,first.productVersion());
        assertEquals("RECORDED",service.publish(actor,true,request("v1",0),original.bytes()).disposition());assertEquals(1,versions.size());
        var next=service.publish(actor,true,request("v2",2),original.bytes());assertEquals(3,next.productVersion());assertEquals(2,versions.get("v2").getVersionSequence());
        assertThrows(SkillMarketplaceException.class,()->service.publish(actor,true,request("v1",3),original.bytes()));assertEquals("v2",products.get("product").getCurrentProductVersionId());
        assertTrue(Files.exists(root.resolve(first.packageSha256()+".zip")));
    }
    @Test void permissionDenialAndArchiveMutationHappenBeforeCatalogWrites() {
        assertEquals(403,assertThrows(SkillMarketplaceException.class,()->service.publish(actor,false,request("v1",0),original.bytes())).status());
        var changed=original.bytes();changed[changed.length-1]^=1;assertThrows(SkillMarketplaceException.class,()->service.publish(actor,true,request("v1",0),changed));verifyNoInteractions(mapper);
    }
    SkillPackagePublicationService.Publish covered(String version,long expected,String source) {
        var c=request(version,expected);
        return new SkillPackagePublicationService.Publish(c.productId(),c.productVersionId(),c.name(),c.description(),c.skillKey(),c.skillVersion(),c.priceMicro(),c.deploymentRestriction(),c.approvedPermissionsManifest(),c.packageSha256(),c.manifestSha256(),c.expectedProductVersion(),source);
    }
    @Test void explicitCoveredUpgradeUsesSameProductAndReplaysWithoutAnotherPolicyWrite() {
        service.publish(actor,true,request("v1",0),original.bytes());
        when(mapper.insertCoveredUpgradePolicy("0","client","v1","v2")).thenReturn(1);
        var receipt=service.publish(actor,true,covered("v2",2,"v1"),original.bytes());
        assertEquals("v1",receipt.coveredUpgradeFromProductVersionId());
        when(mapper.selectUpgradePolicy("0","client","v1","v2")).thenReturn("COVERED");
        assertEquals("RECORDED",service.publish(actor,true,covered("v2",2,"v1"),original.bytes()).disposition());
        verify(mapper,times(1)).insertCoveredUpgradePolicy("0","client","v1","v2");
    }
    @Test void coveredUpgradeNeverOverwritesPaidPolicyOrCoversOtherProducts() {
        service.publish(actor,true,request("v1",0),original.bytes());
        service.publish(actor,true,request("v2",2),original.bytes());
        when(mapper.selectUpgradePolicy("0","client","v1","v2")).thenReturn("PAID");
        assertEquals("SKILL_UPGRADE_POLICY_CONFLICT",assertThrows(SkillMarketplaceException.class,
                ()->service.publish(actor,true,covered("v2",2,"v1"),original.bytes())).code());
        versions.get("v1").setProductId("other");
        assertEquals("SKILL_UPGRADE_SOURCE_INVALID",assertThrows(SkillMarketplaceException.class,
                ()->service.publish(actor,true,covered("v2",2,"v1"),original.bytes())).code());
        assertEquals("SKILL_UPGRADE_SOURCE_INVALID",assertThrows(SkillMarketplaceException.class,
                ()->service.publish(actor,true,covered("v2",2,"v2"),original.bytes())).code());
        verify(mapper,never()).insertCoveredUpgradePolicy(anyString(),anyString(),anyString(),anyString());
    }
    @Test void transactionReadOnlyImmutableCacheMissIsFailClosedAndPreparedBytesAreReusable() {
        var p=original.product();var approved=new SkillProductVersionEntity().setProductId(p.productId()).setProductVersionId(p.productVersionId()).setSkillKey(p.skillKey()).setSkillVersion(p.skillVersion()).setPackageSha256(p.packageSha256()).setPackageSize(p.packageSize()).setPriceMicro(p.priceMicro()).setApprovedPermissionsManifest(p.approvedPermissionsManifest()).setApprovedPermissionsSha256(p.approvedPermissionsSha256()).setDeploymentRestriction(p.deploymentRestriction()).setReviewStatus("APPROVED");
        TransactionSynchronizationManager.setActualTransactionActive(true);assertThrows(SkillMarketplaceException.class,()->packages.prepare(approved));
        TransactionSynchronizationManager.setActualTransactionActive(false);var prepared=packages.prepare(approved);
        TransactionSynchronizationManager.setActualTransactionActive(true);assertArrayEquals(prepared.bytes(),packages.prepare(approved).bytes());assertNull(packages.manifest(prepared));
        assertThrows(SkillMarketplaceException.class,()->packages.publish(prepared));
    }
}
