package cn.jia.agent.skill;

import cn.jia.agent.service.PositionAuthority;
import cn.jia.agent.service.PositionAuthority.Scope;
import cn.jia.agent.service.PositionAuthority.Grant;
import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog;
import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog.*;
import cn.jia.economy.entity.skill.*;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;

/** Platform-maintainer publication, not task execution or cost approval. */
@Service
public final class SkillPackagePublicationService {
    public record Publish(String productId,String productVersionId,String name,String description,String skillKey,
            String skillVersion,long priceMicro,String deploymentRestriction,String approvedPermissionsManifest,
            String packageSha256,String manifestSha256,long expectedProductVersion,String coveredUpgradeFromProductVersionId) { }
    public record Receipt(String productId,String productVersionId,long productVersion,String packageSha256,
            String manifestSha256,String disposition,String coveredUpgradeFromProductVersionId) { }
    private final EconomySkillMarketplaceMapper market;
    private final ObjectProvider<SkillPackages> packages;
    private final PositionAuthority positions;
    public SkillPackagePublicationService(EconomySkillMarketplaceMapper market,ObjectProvider<SkillPackages> packages,PositionAuthority positions) {
        this.positions=Objects.requireNonNull(positions);this.market=Objects.requireNonNull(market);this.packages=Objects.requireNonNull(packages);
    }
    public Receipt publish(Scope actor,Grant grant,Publish command,byte[] archive) {
        require(actor!=null&&grant!=null&&grant.publish(),403,"SKILL_PUBLICATION_FORBIDDEN");
        require(!TransactionSynchronizationManager.isActualTransactionActive(),503,"SKILL_PACKAGE_PREPARE_IN_TRANSACTION");
        positions.withSkillManagementAuthority(actor,grant,()->null);
        validate(command,archive);
        byte[] digest=SkillPackageStore.sha256(archive);
        require(command.packageSha256().equals(HexFormat.of().formatHex(digest)),409,"SKILL_PACKAGE_CHANGED");
        byte[] permissions=SkillPackageStore.sha256(command.approvedPermissionsManifest().getBytes(StandardCharsets.UTF_8));
        var product=new PlatformSkillProduct(command.productId(),command.productVersionId(),command.skillKey(),command.skillVersion(),command.name(),command.description(),command.priceMicro(),command.deploymentRestriction(),null,digest,archive.length,command.approvedPermissionsManifest(),permissions);
        var prepared=new PlatformSkillPackage(product,archive.clone());var store=packages.getIfUnique();require(store!=null,503,"SKILL_PACKAGE_UNAVAILABLE");
        var manifest=store.manifest(prepared);
        require(Objects.equals(command.manifestSha256(),manifest==null?null:manifest.manifestSha256()),409,"SKILL_MANIFEST_CHANGED");
        store.publish(prepared); // immutable bytes first; failed DB transaction can leave only an unauthorized object.
        try{return positions.withSkillManagementAuthority(actor,grant,()->persist(actor,command,digest,permissions,archive.length));}
        catch(org.springframework.dao.DuplicateKeyException race){throw new SkillMarketplaceException(409,"SKILL_PRODUCT_CHANGED");}
    }
    /** Mutable discovery copy only; executable packages and grants remain immutable. */
    public record UpdateMetadata(String productId,String name,String description,long expectedProductVersion) { }
    public record MetadataReceipt(String productId,String name,String description,long productVersion,String disposition) { }
    public MetadataReceipt updateMetadata(Scope actor,Grant grant,UpdateMetadata command) {
        require(actor!=null&&grant!=null&&grant.publish(),403,"SKILL_PUBLICATION_FORBIDDEN");
        require(command!=null&&command.productId()!=null&&command.productId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                &&command.name()!=null&&!command.name().isBlank()&&command.name().length()<=255
                &&command.description()!=null&&!command.description().isBlank()&&command.description().length()<=4000
                &&command.expectedProductVersion()>0,400,"BAD_REQUEST");
        return positions.withSkillManagementAuthority(actor,grant,()->{
            var p=market.selectProductForUpdate(actor.tenantId(),actor.clientId(),command.productId());
            require(p!=null,404,"RESOURCE_NOT_FOUND");
            require(PlatformSkillPackageCatalog.SELLER_TYPE.equals(p.getSellerType())
                    &&PlatformSkillPackageCatalog.SELLER_ID.equals(p.getSellerId())&&p.getCreatorAgentId()==null
                    &&Set.of("DRAFT","PUBLISHED").contains(p.getStatus()),409,"SKILL_PRODUCT_CHANGED");
            boolean same=command.name().equals(p.getName())&&command.description().equals(p.getDescription());
            require(p.getVersion()!=null&&(p.getVersion()==command.expectedProductVersion()
                    ||same&&p.getVersion()-1==command.expectedProductVersion()),409,"SKILL_PRODUCT_CHANGED");
            if(same)return new MetadataReceipt(p.getProductId(),p.getName(),p.getDescription(),p.getVersion(),"RECORDED");
            long nextVersion=p.getVersion()+1;
            one(market.updateProductMetadata(actor.tenantId(),actor.clientId(),p.getProductId(),command.name(),
                    command.description(),command.expectedProductVersion(),System.currentTimeMillis()));
            return new MetadataReceipt(p.getProductId(),command.name(),command.description(),nextVersion,"UPDATED");
        });
    }
    /** Retiring discovery does not revoke installed packages, grants or completed facts. */
    public record RetireProduct(String productId,long expectedProductVersion) { }
    public record RetirementReceipt(String productId,String status,long productVersion,String disposition) { }
    public RetirementReceipt retireProduct(Scope actor,Grant grant,RetireProduct command) {
        require(actor!=null&&grant!=null&&grant.publish(),403,"SKILL_PUBLICATION_FORBIDDEN");
        require(command!=null&&command.productId()!=null&&command.productId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                &&command.expectedProductVersion()>0,400,"BAD_REQUEST");
        return positions.withSkillManagementAuthority(actor,grant,()->{
            var p=market.selectProductForUpdate(actor.tenantId(),actor.clientId(),command.productId());
            require(p!=null,404,"RESOURCE_NOT_FOUND");
            require(PlatformSkillPackageCatalog.SELLER_TYPE.equals(p.getSellerType())
                    &&PlatformSkillPackageCatalog.SELLER_ID.equals(p.getSellerId())&&p.getCreatorAgentId()==null
                    &&Set.of("PUBLISHED","RETIRED").contains(p.getStatus()),409,"SKILL_PRODUCT_CHANGED");
            boolean retired="RETIRED".equals(p.getStatus());
            require(p.getVersion()!=null&&(p.getVersion()==command.expectedProductVersion()
                    ||retired&&p.getVersion()-1==command.expectedProductVersion()),409,"SKILL_PRODUCT_CHANGED");
            if(retired)return new RetirementReceipt(p.getProductId(),"RETIRED",p.getVersion(),"RECORDED");
            long next=Math.addExact(p.getVersion(),1L);
            one(market.retireProduct(actor.tenantId(),actor.clientId(),p.getProductId(),command.expectedProductVersion(),System.currentTimeMillis()));
            return new RetirementReceipt(p.getProductId(),"RETIRED",next,"RETIRED");
        });
    }
    private Receipt persist(Scope a,Publish c,byte[] digest,byte[] permissions,long byteLength) {
        var product=market.selectProductForUpdate(a.tenantId(),a.clientId(),c.productId());long now=System.currentTimeMillis();
        if(product==null){require(c.expectedProductVersion()==0,409,"SKILL_PRODUCT_CHANGED");product=new SkillProductEntity().setProductId(c.productId()).setSellerType(PlatformSkillPackageCatalog.SELLER_TYPE).setSellerId(PlatformSkillPackageCatalog.SELLER_ID).setCreatorAgentId(null).setName(c.name()).setDescription(c.description()).setStatus("DRAFT").setCurrentProductVersionId(null).setVersion(1L).setTenantId(a.tenantId()).setClientId(a.clientId()).setCreateTime(now).setUpdateTime(now);one(market.insertProduct(product));}
        require(a.tenantId().equals(product.getTenantId())&&a.clientId().equals(product.getClientId())&&c.productId().equals(product.getProductId())&&PlatformSkillPackageCatalog.SELLER_TYPE.equals(product.getSellerType())&&PlatformSkillPackageCatalog.SELLER_ID.equals(product.getSellerId())&&product.getCreatorAgentId()==null&&c.name().equals(product.getName())&&c.description().equals(product.getDescription())&&Set.of("DRAFT","PUBLISHED").contains(product.getStatus()),409,"SKILL_PRODUCT_CHANGED");
        var existing=market.selectApprovedProductVersionForUpdate(a.tenantId(),a.clientId(),c.productVersionId());
        if(existing!=null){require(matches(a,c,existing,digest,permissions,byteLength)&&c.productVersionId().equals(product.getCurrentProductVersionId())&&"PUBLISHED".equals(product.getStatus()),409,"SKILL_VERSION_IMMUTABLE");coverUpgrade(a,c,existing);return receipt(c,product.getVersion(),"RECORDED");}
        require(product.getVersion()!=null&&(c.expectedProductVersion()==0&&product.getVersion()==1||c.expectedProductVersion()==product.getVersion()),409,"SKILL_PRODUCT_CHANGED");
        long sequence=1;if(product.getCurrentProductVersionId()!=null){var previous=market.selectApprovedProductVersionForUpdate(a.tenantId(),a.clientId(),product.getCurrentProductVersionId());require(previous!=null&&a.tenantId().equals(previous.getTenantId())&&a.clientId().equals(previous.getClientId())&&c.productId().equals(previous.getProductId())&&c.skillKey().equals(previous.getSkillKey())&&previous.getVersionSequence()!=null,409,"SKILL_PRODUCT_CHANGED");sequence=Math.addExact(previous.getVersionSequence(),1L);}
        var version=new SkillProductVersionEntity().setProductVersionId(c.productVersionId()).setProductId(c.productId()).setVersionSequence(sequence).setSkillKey(c.skillKey()).setSkillVersion(c.skillVersion()).setPriceMicro(c.priceMicro()).setPackageSha256(digest).setPackageSize(byteLength);
        version.setApprovedPermissionsManifest(c.approvedPermissionsManifest()).setApprovedPermissionsSha256(permissions).setDeploymentRestriction(c.deploymentRestriction()).setReviewStatus("APPROVED").setTenantId(a.tenantId()).setClientId(a.clientId()).setCreateTime(now);
        one(market.insertProductVersion(version));long baseVersion=product.getVersion();one(market.publishProductVersion(a.tenantId(),a.clientId(),c.productId(),c.productVersionId(),baseVersion,now));coverUpgrade(a,c,version);return receipt(c,baseVersion+1,"PUBLISHED");
    }
    private void coverUpgrade(Scope actor,Publish command,SkillProductVersionEntity target) {
        String sourceId=command.coveredUpgradeFromProductVersionId();
        if(sourceId==null)return;
        var source=market.selectApprovedProductVersionForUpdate(actor.tenantId(),actor.clientId(),sourceId);
        require(source!=null&&actor.tenantId().equals(source.getTenantId())&&actor.clientId().equals(source.getClientId())
                &&command.productId().equals(source.getProductId())&&command.skillKey().equals(source.getSkillKey())
                &&"APPROVED".equals(source.getReviewStatus())&&source.getVersionSequence()!=null
                &&source.getVersionSequence()<target.getVersionSequence(),409,"SKILL_UPGRADE_SOURCE_INVALID");
        String policy=market.selectUpgradePolicy(actor.tenantId(),actor.clientId(),sourceId,command.productVersionId());
        if(policy==null)one(market.insertCoveredUpgradePolicy(actor.tenantId(),actor.clientId(),sourceId,command.productVersionId()));
        else require("COVERED".equals(policy),409,"SKILL_UPGRADE_POLICY_CONFLICT");
    }
    private static boolean matches(Scope a,Publish c,SkillProductVersionEntity v,byte[] hash,byte[] permissions,long byteLength) {return a.tenantId().equals(v.getTenantId())&&a.clientId().equals(v.getClientId())&&c.productId().equals(v.getProductId())&&c.productVersionId().equals(v.getProductVersionId())&&c.skillKey().equals(v.getSkillKey())&&c.skillVersion().equals(v.getSkillVersion())&&Objects.equals(c.priceMicro(),v.getPriceMicro())&&Arrays.equals(hash,v.getPackageSha256())&&Objects.equals(byteLength,v.getPackageSize())&&Arrays.equals(permissions,v.getApprovedPermissionsSha256())&&c.approvedPermissionsManifest().equals(v.getApprovedPermissionsManifest())&&c.deploymentRestriction().equals(v.getDeploymentRestriction())&&"APPROVED".equals(v.getReviewStatus());}
    private static Receipt receipt(Publish c,long version,String disposition){return new Receipt(c.productId(),c.productVersionId(),version,c.packageSha256(),c.manifestSha256(),disposition,c.coveredUpgradeFromProductVersionId());}
    private static void one(int count){require(count==1,409,"SKILL_PRODUCT_CHANGED");}
    private static void validate(Publish c,byte[] archive) {
        require(c!=null&&archive!=null&&archive.length>0&&archive.length<=SkillPackageStore.MAX_PACKAGE_BYTES,400,"SKILL_PACKAGE_INVALID");
        for(String id:Arrays.asList(c.productId(),c.productVersionId()))require(id!=null&&id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"),400,"BAD_REQUEST");
        require(c.coveredUpgradeFromProductVersionId()==null||c.coveredUpgradeFromProductVersionId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"),400,"BAD_REQUEST");
        for(String id:Arrays.asList(c.skillKey(),c.skillVersion()))require(id!=null&&id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"),400,"BAD_REQUEST");
        require(c.name()!=null&&!c.name().isBlank()&&c.name().length()<=255&&c.description()!=null&&c.description().length()<=4000&&c.priceMicro()>=0&&c.expectedProductVersion()>=0&&c.deploymentRestriction()!=null&&Set.of("NONE","ADMIN_ONLY").contains(c.deploymentRestriction())&&c.packageSha256()!=null&&c.packageSha256().matches("[0-9a-f]{64}")&&(c.manifestSha256()==null||c.manifestSha256().matches("[0-9a-f]{64}")),400,"BAD_REQUEST");
        try {SkillMarketplaceHttp.permissions(c.approvedPermissionsManifest());}catch(RuntimeException invalid){throw new SkillMarketplaceException(400,"SKILL_PERMISSIONS_INVALID");}
    }
}
