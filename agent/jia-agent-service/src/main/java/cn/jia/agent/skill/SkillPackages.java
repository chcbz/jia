package cn.jia.agent.skill;

import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog;
import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog.PlatformSkillPackage;
import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog.PlatformSkillProduct;
import cn.jia.economy.entity.skill.SkillProductVersionEntity;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static cn.jia.agent.skill.SkillMarketplaceException.require;

/** Prepare immutable bytes outside money/install locks, then revalidate exact approved facts inside.
 * Only bytes are cached by digest. Tenant/version/price/permissions are never shared as authorization. */
public final class SkillPackages {
    private final PlatformSkillPackageCatalog seed = new PlatformSkillPackageCatalog();
    private final SkillPackageStore store;
    private final Map<String,byte[]> bytesByDigest = new ConcurrentHashMap<>();
    public SkillPackages() { this(Path.of("/home/isp/state/cyf-skill-packages")); }
    public SkillPackages(Path root) { store = new SkillPackageStore(root); }
    public PlatformSkillPackage prepare(SkillProductVersionEntity approved) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),503,"SKILL_PACKAGE_PREPARE_IN_TRANSACTION");
        require(approved!=null && "APPROVED".equals(approved.getReviewStatus())
                && approved.getPackageSha256()!=null && approved.getPackageSha256().length==32
                && approved.getPackageSize()!=null && approved.getPackageSize()>0
                && approved.getPackageSize()<=SkillPackageStore.MAX_PACKAGE_BYTES
                && approved.getPriceMicro()!=null && approved.getPriceMicro()>=0
                && approved.getApprovedPermissionsSha256()!=null && approved.getApprovedPermissionsSha256().length==32
                && approved.getApprovedPermissionsManifest()!=null
                && Arrays.equals(SkillPackageStore.sha256(approved.getApprovedPermissionsManifest().getBytes(java.nio.charset.StandardCharsets.UTF_8)),approved.getApprovedPermissionsSha256())
                && approved.getProductVersionId()!=null && approved.getProductId()!=null
                && approved.getSkillKey()!=null && approved.getSkillVersion()!=null
                && approved.getDeploymentRestriction()!=null
                && Set.of("NONE","ADMIN_ONLY").contains(approved.getDeploymentRestriction()),503,"SKILL_PACKAGE_UNAVAILABLE");
        String hex=HexFormat.of().formatHex(approved.getPackageSha256());
        byte[] bytes=bytesByDigest.computeIfAbsent(hex,key->{
            var bundled=seed.products().stream().filter(p->Arrays.equals(p.packageSha256(),approved.getPackageSha256())).findFirst();
            return bundled.isPresent()?seed.requirePackage(bundled.get().productVersionId()).bytes()
                    :store.read(approved.getPackageSha256(),approved.getPackageSize());
        });
        require(bytes.length==approved.getPackageSize(),503,"SKILL_PACKAGE_UNAVAILABLE");
        var metadata=new PlatformSkillProduct(approved.getProductId(),approved.getProductVersionId(),approved.getSkillKey(),
                approved.getSkillVersion(),approved.getSkillKey(),"",approved.getPriceMicro(),approved.getDeploymentRestriction(),
                null,approved.getPackageSha256(),approved.getPackageSize(),approved.getApprovedPermissionsManifest(),approved.getApprovedPermissionsSha256());
        return new PlatformSkillPackage(metadata,bytes);
    }
    public static void revalidate(SkillProductVersionEntity approved,PlatformSkillPackage prepared) {
        require(approved!=null && prepared!=null && "APPROVED".equals(approved.getReviewStatus()),503,"SKILL_PACKAGE_UNAVAILABLE");
        var p=prepared.product();
        require(p.productId().equals(approved.getProductId()) && p.productVersionId().equals(approved.getProductVersionId())
                && p.skillKey().equals(approved.getSkillKey()) && p.skillVersion().equals(approved.getSkillVersion())
                && Objects.equals(p.packageSize(),approved.getPackageSize()) && Arrays.equals(p.packageSha256(),approved.getPackageSha256())
                && Objects.equals(p.priceMicro(),approved.getPriceMicro())
                && p.approvedPermissionsManifest().equals(approved.getApprovedPermissionsManifest())
                && Arrays.equals(p.approvedPermissionsSha256(),approved.getApprovedPermissionsSha256())
                && p.deploymentRestriction().equals(approved.getDeploymentRestriction()),503,"SKILL_PACKAGE_UNAVAILABLE");
    }
}
