package cn.jia.chat.service;
import cn.jia.agent.service.*;
import cn.jia.agent.skill.*;
import cn.jia.economy.entity.skill.*;
import cn.jia.economy.mapper.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ChatSkillColdPreparationTransactionTest {
    @Configuration @EnableTransactionManagement(proxyTargetClass=true) static class TxConfig { }
    @TempDir Path packageRoot;
    final SkillCapabilityLookup.Scope scope=new SkillCapabilityLookup.Scope("0","client","owner","agent");
    final EconomySkillMarketplaceMapper market=mock(EconomySkillMarketplaceMapper.class);
    final EconomySkillApplicationMapper app=mock(EconomySkillApplicationMapper.class);
    SkillEntitlementEntity entitlement;InstalledSkillDescriptorService installed;
    void fixture() throws Exception {
        var manifest="""
          {"schemaVersion":1,"skillKey":"analysis","skillVersion":"1.0.0","title":"Analysis","description":"read data",
           "runner":"native-model","entrypoint":"analysis.read",
           "parametersSchema":{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object"},
           "inputs":{"minItems":0,"maxItems":0,"acceptedMimeTypes":[]},
           "outputs":{"dataSchema":{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object"},"mimeTypes":[],"multiple":false,"artifactsRequired":false},"requirements":{}}
          """.getBytes(StandardCharsets.UTF_8);
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));zip.write("Read-only analysis".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(manifest);zip.closeEntry();
        }
        byte[] pkg=bytes.toByteArray(),hash=MessageDigest.getInstance("SHA-256").digest(pkg),permissionsHash=MessageDigest.getInstance("SHA-256").digest("[]".getBytes(StandardCharsets.UTF_8));
        new SkillPackageStore(packageRoot).publish(pkg);
        var packages=new SkillPackages(packageRoot);@SuppressWarnings("unchecked") ObjectProvider<SkillPackages> provider=mock(ObjectProvider.class);when(provider.getIfUnique()).thenReturn(packages);
        var identities=mock(AgentIdentityService.class);when(identities.requireCanonicalAgentIdInScope("0","client","owner","agent")).thenReturn("agent");
        installed=new InstalledSkillDescriptorService(identities,market,app,provider);
        entitlement=new SkillEntitlementEntity().setTenantId("0").setClientId("client").setTargetAgentId("agent").setSkillKey("analysis").setSkillVersion("1.0.0")
                .setEntitlementId("ent").setInstallationId("ins").setOrderId("ord").setProductVersionId("pv").setStatus("ACTIVE").setPermissionGrantVersion(1L)
                .setApprovedPermissionsManifest("[]").setApprovedPermissionsSha256(permissionsHash).setVersion(2L).setActivatedAt(1L);
        var installation=new SkillInstallationEntity().setTenantId("0").setClientId("client").setTargetAgentId("agent").setInstallationId("ins").setOrderId("ord").setProductVersionId("pv")
                .setStatus("SUCCEEDED").setSkillKey("analysis").setSkillVersion("1.0.0").setPackageSha256(hash).setPackageSize((long)pkg.length).setInstalledAt(1L);
        var order=new SkillOrderEntity().setTenantId("0").setClientId("client").setTargetAgentId("agent").setOrderId("ord").setProductVersionId("pv").setStatus("ACTIVE")
                .setPermissionGrantVersion(1L).setApprovedPermissionsManifest("[]").setApprovedPermissionsSha256(permissionsHash);
        var version=new SkillProductVersionEntity().setTenantId("0").setClientId("client").setProductId("product").setProductVersionId("pv").setReviewStatus("APPROVED").setSkillKey("analysis").setSkillVersion("1.0.0")
                .setPackageSha256(hash).setPackageSize((long)pkg.length).setPriceMicro(0L).setDeploymentRestriction("NONE").setApprovedPermissionsManifest("[]").setApprovedPermissionsSha256(permissionsHash);
        when(market.selectEntitlementsByAgent("0","client","agent")).thenReturn(List.of(entitlement));
        when(market.selectApprovedProductVersion("0","client","pv")).thenReturn(version);
        when(app.installation("0","client","ins")).thenReturn(installation);when(app.order("0","client","ord")).thenReturn(order);when(app.successfulReceipts("0","client","ins")).thenReturn(1L);
        var binding=new SkillDeliveryBindingEntity();binding.setCanonicalAgentId("agent");binding.setRuntimeInstallationId("runtime-install");binding.setRuntimeHostId("host");when(app.deliveryBinding("0","client","ins")).thenReturn(binding);
    }
    @Test void coldTransactionMissIs503RatherThanIoUnderLock() throws Exception {
        fixture();var source=new DriverManagerDataSource("jdbc:h2:mem:cold_"+UUID.randomUUID(),"sa","");var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.execute(status->{assertThrows(InstalledSkillDescriptorLookup.SourceUnavailable.class,()->installed.read(scope));return null;});
        assertTrue(installed.read(scope).skills().getFirst().verified());
    }
    @Test void preparationSuspendsCallerTransactionAndWarmReadHasFreshAuthorizationWithoutPackageIo() throws Exception {
        fixture();var source=new DriverManagerDataSource("jdbc:h2:mem:warm_"+UUID.randomUUID(),"sa","");var manager=new DataSourceTransactionManager(source);var tx=new TransactionTemplate(manager);
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(TxConfig.class);context.registerBean("transactionManager",DataSourceTransactionManager.class,()->manager);
            context.registerBean(ChatSkillPackagePreparationService.class,()->new ChatSkillPackagePreparationService(installed));context.refresh();
            var preparation=context.getBean(ChatSkillPackagePreparationService.class);
            tx.execute(status->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());preparation.prepare(scope);
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return null;});
            // A real immutable package/manifest cache is primed. Removing private storage proves locked reads do not reopen it.
            try(var files=Files.list(packageRoot)) {for(Path path:files.toList()) Files.delete(path);}
            tx.execute(status->{assertTrue(installed.read(scope).skills().getFirst().verified());entitlement.setStatus("REVOKED");
                assertFalse(installed.read(scope).skills().getFirst().verified());return null;});
            verify(market,atLeast(4)).selectEntitlementsByAgent("0","client","agent");
        }
    }
}
