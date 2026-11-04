package cn.jia.agent.skill;
import cn.jia.agent.service.PositionAuthority;
import cn.jia.agent.position.PositionSkillException;
import java.util.function.Supplier;
import cn.jia.agent.config.skill.skillseed.PlatformSkillPackageCatalog;
import cn.jia.agent.entity.skill.*;
import cn.jia.economy.service.skill.*;
import cn.jia.agent.mapper.skill.SkillCatalogMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Trusted publisher unit boundaries; separate MySQL tests prove paired execution schema. */
class SkillPackagePublicationServiceTest {
    @TempDir Path root;
    final PositionAuthority.Scope actor=new PositionAuthority.Scope("0","client","owner");
    final PositionAuthority.Grant grant=new PositionAuthority.Grant("appointment",1,1,"agent",1,null,true);
    final PositionAuthority positions=mock(PositionAuthority.class);
    final SkillCatalogMapper mapper=mock(SkillCatalogMapper.class);
    final Map<String,SkillProductEntity> products=new HashMap<>();final Map<String,SkillProductVersionEntity> versions=new HashMap<>();
    SkillPackages packages;SkillPackagePublicationService service;PlatformSkillPackageCatalog.PlatformSkillPackage original;
    @BeforeEach void setup() {
        packages=new SkillPackages(root);original=new PlatformSkillPackageCatalog().requirePackage("spv_repo_inspector_1_0_0");
        @SuppressWarnings("unchecked") ObjectProvider<SkillPackages> provider=mock(ObjectProvider.class);when(provider.getIfUnique()).thenReturn(packages);
        service=new SkillPackagePublicationService(mapper,provider,positions);
        when(positions.withSkillManagementAuthority(eq(actor),eq(grant),any())).thenAnswer(i->((Supplier<?>)i.getArgument(2)).get());
        when(mapper.selectProductForUpdate(anyString(),anyString(),anyString())).thenAnswer(i->products.get(i.getArgument(2)));
        when(mapper.selectApprovedProductVersionForUpdate(anyString(),anyString(),anyString())).thenAnswer(i->versions.get(i.getArgument(2)));
        when(mapper.insertProduct(any())).thenAnswer(i->{SkillProductEntity p=i.getArgument(0);products.put(p.getProductId(),p);return 1;});when(mapper.insertProductVersion(any())).thenAnswer(i->{SkillProductVersionEntity v=i.getArgument(0);versions.put(v.getProductVersionId(),v);return 1;});
        when(mapper.publishProductVersion(anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong())).thenAnswer(i->{var p=products.get(i.getArgument(2));long expected=i.getArgument(4);if(p.getVersion()!=expected)return 0;p.setVersion(expected+1).setStatus("PUBLISHED").setCurrentProductVersionId(i.getArgument(3));return 1;});
    }
    @AfterEach void clear(){TransactionSynchronizationManager.setActualTransactionActive(false);}
    SkillPackagePublicationService.Publish request(String version,long expected){var p=original.product();return new SkillPackagePublicationService.Publish("product",version,p.name(),p.description(),p.skillKey(),p.skillVersion(),0,"NONE","[]",HexFormat.of().formatHex(p.packageSha256()),null,expected,null);}
    @Test void maintenanceReadbackUsesTheResourceGrantAndDoesNotMutateProducts() {
        service.publish(actor,grant,request("v1",0),original.bytes());
        clearInvocations(mapper,positions);
        var view=service.readProduct(actor,grant,"product");
        assertEquals("product",view.productId());assertEquals("v1",view.productVersionId());
        assertEquals("2",view.productVersion());assertEquals("PUBLISHED",view.status());
        verify(positions).withSkillManagementAuthority(eq(actor),eq(grant),any());
        verify(mapper).selectProductForUpdate("0","client","product");verifyNoMoreInteractions(mapper);
        assertEquals(403,assertThrows(SkillMarketplaceException.class,()->service.readProduct(actor,null,"product")).status());
        assertEquals(404,assertThrows(SkillMarketplaceException.class,()->service.readProduct(actor,grant,"missing")).status());
    }
    @Test void immutableBytesPublicationReplayAndNextVersionWithoutAppSeedChange() {
        var first=service.publish(actor,grant,request("v1",0),original.bytes());assertEquals("PUBLISHED",first.disposition());assertEquals(2,first.productVersion());
        assertEquals("RECORDED",service.publish(actor,grant,request("v1",0),original.bytes()).disposition());assertEquals(1,versions.size());
        var next=service.publish(actor,grant,request("v2",2),original.bytes());assertEquals(3,next.productVersion());assertEquals(2,versions.get("v2").getVersionSequence());
        assertThrows(SkillMarketplaceException.class,()->service.publish(actor,grant,request("v1",3),original.bytes()));assertEquals("v2",products.get("product").getCurrentProductVersionId());
        assertTrue(Files.exists(root.resolve(first.packageSha256()+".zip")));
    }
    @Test void permissionDenialAndArchiveMutationHappenBeforeCatalogWrites() {
        assertEquals(403,assertThrows(SkillMarketplaceException.class,()->service.publish(actor,null,request("v1",0),original.bytes())).status());
        var changed=original.bytes();changed[changed.length-1]^=1;assertThrows(SkillMarketplaceException.class,()->service.publish(actor,grant,request("v1",0),changed));verifyNoInteractions(mapper);
    }
    SkillPackagePublicationService.Publish covered(String version,long expected,String source) {
        var c=request(version,expected);
        return new SkillPackagePublicationService.Publish(c.productId(),c.productVersionId(),c.name(),c.description(),c.skillKey(),c.skillVersion(),c.priceMicro(),c.deploymentRestriction(),c.approvedPermissionsManifest(),c.packageSha256(),c.manifestSha256(),c.expectedProductVersion(),source);
    }
    @Test void explicitCoveredUpgradeUsesSameProductAndReplaysWithoutAnotherPolicyWrite() {
        service.publish(actor,grant,request("v1",0),original.bytes());
        when(mapper.insertCoveredUpgradePolicy("0","client","v1","v2")).thenReturn(1);
        var receipt=service.publish(actor,grant,covered("v2",2,"v1"),original.bytes());
        assertEquals("v1",receipt.coveredUpgradeFromProductVersionId());
        when(mapper.selectUpgradePolicy("0","client","v1","v2")).thenReturn("COVERED");
        assertEquals("RECORDED",service.publish(actor,grant,covered("v2",2,"v1"),original.bytes()).disposition());
        verify(mapper,times(1)).insertCoveredUpgradePolicy("0","client","v1","v2");
    }
    @Test void coveredUpgradeNeverOverwritesPaidPolicyOrCoversOtherProducts() {
        service.publish(actor,grant,request("v1",0),original.bytes());
        service.publish(actor,grant,request("v2",2),original.bytes());
        when(mapper.selectUpgradePolicy("0","client","v1","v2")).thenReturn("PAID");
        assertEquals("SKILL_UPGRADE_POLICY_CONFLICT",assertThrows(SkillMarketplaceException.class,
                ()->service.publish(actor,grant,covered("v2",2,"v1"),original.bytes())).code());
        versions.get("v1").setProductId("other");
        assertEquals("SKILL_UPGRADE_SOURCE_INVALID",assertThrows(SkillMarketplaceException.class,
                ()->service.publish(actor,grant,covered("v2",2,"v1"),original.bytes())).code());
        assertEquals("SKILL_UPGRADE_SOURCE_INVALID",assertThrows(SkillMarketplaceException.class,
                ()->service.publish(actor,grant,covered("v2",2,"v2"),original.bytes())).code());
        verify(mapper,never()).insertCoveredUpgradePolicy(anyString(),anyString(),anyString(),anyString());
    }
    @Test void revokedAppointmentBetweenPreparationAndCommitCannotPublish() {
        when(positions.withSkillManagementAuthority(eq(actor),eq(grant),any()))
                .thenAnswer(i->((Supplier<?>)i.getArgument(2)).get())
                .thenThrow(new PositionSkillException(403,"POSITION_AUTHORITY_REVOKED"));
        assertThrows(PositionSkillException.class,()->service.publish(actor,grant,request("v1",0),original.bytes()));
        verifyNoInteractions(mapper);
    }
    @Test void missingPositionAuthorityCannotPrepareOrPublish() {
        when(positions.withSkillManagementAuthority(eq(actor),eq(grant),any()))
                .thenThrow(new PositionSkillException(403,"POSITION_RESOURCE_FORBIDDEN"));
        assertThrows(PositionSkillException.class,()->service.publish(actor,grant,request("v1",0),original.bytes()));
        verifyNoInteractions(mapper);
        try(var files=Files.list(root)){assertEquals(0,files.count());}catch(java.io.IOException e){throw new AssertionError(e);}
    }
    @Test void transactionUsesPreparedDescriptionWithoutRetainingPackageBytes() {
        var p=original.product();var approved=new SkillProductVersionEntity().setProductId(p.productId()).setProductVersionId(p.productVersionId()).setSkillKey(p.skillKey()).setSkillVersion(p.skillVersion()).setPackageSha256(p.packageSha256()).setPackageSize(p.packageSize()).setPriceMicro(p.priceMicro()).setApprovedPermissionsManifest(p.approvedPermissionsManifest()).setApprovedPermissionsSha256(p.approvedPermissionsSha256()).setDeploymentRestriction(p.deploymentRestriction()).setReviewStatus("APPROVED");
        TransactionSynchronizationManager.setActualTransactionActive(true);assertThrows(SkillMarketplaceException.class,()->packages.prepare(approved));
        TransactionSynchronizationManager.setActualTransactionActive(false);var prepared=packages.describe(approved);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(SkillMarketplaceException.class,()->packages.prepare(approved));
        assertNull(packages.manifest(prepared));
        assertDoesNotThrow(()->SkillPackages.revalidate(approved,prepared));
        assertThrows(SkillMarketplaceException.class,()->packages.publish(original));
    }
    @Test void metadataUpdatesDiscoveryCopyOnlyAndRetryDoesNotBumpAgain() {
        service.publish(actor,grant,request("v1",0),original.bytes());
        when(mapper.updateProductMetadata(eq("0"),eq("client"),eq("product"),anyString(),anyString(),anyLong(),anyLong()))
                .thenAnswer(i->{var p=products.get("product");p.setName(i.getArgument(3)).setDescription(i.getArgument(4)).setVersion(p.getVersion()+1);return 1;});
        var command=new SkillPackagePublicationService.UpdateMetadata("product","资料分析与对比","依据输入资料提炼结论；不代替网页检索。",2);
        var updated=service.updateMetadata(actor,grant,command);
        assertEquals("UPDATED",updated.disposition());assertEquals(3,updated.productVersion());
        assertEquals("RECORDED",service.updateMetadata(actor,grant,command).disposition());
        assertEquals("v1",products.get("product").getCurrentProductVersionId());assertEquals(1,versions.size());
        assertEquals(0,versions.get("v1").getPriceMicro());
        verify(mapper,times(1)).updateProductMetadata(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong());
        assertThrows(SkillMarketplaceException.class,()->service.updateMetadata(actor,grant,new SkillPackagePublicationService.UpdateMetadata("product","stale","stale",2)));
    }
    @Test void metadataRequiresCurrentSkillManagementPositionAndCannotTouchPackageStore() {
        when(positions.withSkillManagementAuthority(eq(actor),eq(grant),any())).thenThrow(new PositionSkillException(403,"POSITION_AUTHORITY_REVOKED"));
        assertThrows(PositionSkillException.class,()->service.updateMetadata(actor,grant,new SkillPackagePublicationService.UpdateMetadata("product","标题","用途",2)));
        verifyNoInteractions(mapper);
    }


    @Test void retirementPreservesVersionsAndPackagePointerAndReplays() {
        service.publish(actor,grant,request("v1",0),original.bytes());
        when(mapper.retireProduct(eq("0"),eq("client"),eq("product"),eq(2L),anyLong()))
                .thenAnswer(i->{products.get("product").setStatus("RETIRED").setVersion(3L);return 1;});
        var command=new SkillPackagePublicationService.RetireProduct("product",2);
        var receipt=service.retireProduct(actor,grant,command);
        assertEquals("RETIRED",receipt.status());assertEquals(3,receipt.productVersion());
        assertEquals("RECORDED",service.retireProduct(actor,grant,command).disposition());
        assertEquals("v1",products.get("product").getCurrentProductVersionId());assertEquals(1,versions.size());
        verify(mapper,times(1)).retireProduct(anyString(),anyString(),anyString(),anyLong(),anyLong());
        assertThrows(SkillMarketplaceException.class,()->service.retireProduct(actor,grant,new SkillPackagePublicationService.RetireProduct("product",1)));
        assertThrows(SkillMarketplaceException.class,()->service.publish(actor,grant,request("v2",3),original.bytes()));
    }
    @Test void retirementRequiresLivePositionAndCurrentProductVersion() {
        assertEquals(403,assertThrows(SkillMarketplaceException.class,()->service.retireProduct(actor,null,new SkillPackagePublicationService.RetireProduct("product",2))).status());
        service.publish(actor,grant,request("v1",0),original.bytes());
        assertThrows(SkillMarketplaceException.class,()->service.retireProduct(actor,grant,new SkillPackagePublicationService.RetireProduct("product",1)));
        when(positions.withSkillManagementAuthority(eq(actor),eq(grant),any())).thenThrow(new PositionSkillException(403,"POSITION_AUTHORITY_REVOKED"));
        assertThrows(PositionSkillException.class,()->service.retireProduct(actor,grant,new SkillPackagePublicationService.RetireProduct("product",2)));
        verify(mapper,never()).retireProduct(anyString(),anyString(),anyString(),anyLong(),anyLong());
    }

    @Test void preparedPublicationIsBoundAndCommitDoesNotRepeatPackageIo() {
        var prepared=service.prepare(actor,grant,request("v1",0),original.bytes());
        assertTrue(prepared.matches(actor,grant));
        assertFalse(prepared.matches(new PositionAuthority.Scope("0","client","other"),grant));
        verifyNoInteractions(mapper);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertEquals("PUBLISHED",service.commitPrepared(prepared).disposition());
    }
    @Test void immutablePackageDirectoryAndFileAreReadFromVerifiedBytes() {
        service.publish(actor,grant,request("v1",0),original.bytes());
        var directory=service.readPackage(actor,grant,"v1","");
        assertEquals(HexFormat.of().formatHex(original.product().packageSha256()),directory.get("packageSha256"));
        assertTrue(((List<?>)directory.get("data")).stream().anyMatch(f->"SKILL.md".equals(((Map<?,?>)f).get("path"))));
        var file=(Map<?,?>)service.readPackage(actor,grant,"v1","SKILL.md").get("data");
        assertEquals("utf8",file.get("encoding"));assertFalse(((String)file.get("content")).isBlank());
        assertThrows(SkillMarketplaceException.class,()->service.readPackage(actor,grant,"v1","missing"));
    }

}
