package cn.jia.agent.skill;

import cn.jia.agent.config.skill.skillseed.PlatformSkillPackageCatalog;
import cn.jia.agent.config.skill.skillseed.PlatformSkillPackageCatalog.PlatformSkillPackage;
import cn.jia.agent.config.skill.skillseed.PlatformSkillPackageCatalog.PlatformSkillProduct;
import cn.jia.agent.entity.skill.SkillProductVersionEntity;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import static cn.jia.agent.skill.SkillMarketplaceException.require;

/** Package bytes are operation-local, never retained in a global cache.
 * Only immutable archive descriptions are shared; authority and price are read on every call. */
public final class SkillPackages {
    public record Description(PlatformSkillProduct product, SkillManifestCodec.Manifest manifest) {}
    private record CachedDescription(long packageSize, SkillManifestCodec.Manifest manifest, long weight) {}
    private final PlatformSkillPackageCatalog seed = new PlatformSkillPackageCatalog();
    private final SkillPackageStore store;
    private final SkillManifestCodec manifestCodec = new SkillManifestCodec();
    // One existing maximum archive's worth of description weight, not a request/package limit.
    // Weight estimates tree/string overhead; it is not an exact JVM retained-size measurement.
    private final long descriptionBudget;
    private final LinkedHashMap<String,CachedDescription> descriptions = new LinkedHashMap<>(16,0.75f,true);
    private long descriptionWeight;
    // Explicit synchronous operation scope. Cleared/restored in finally, never HTTP/session authority.
    private final ThreadLocal<Map<String,CachedDescription>> preparedDescriptions = new ThreadLocal<>();
    public SkillPackages() { this(Path.of("/home/isp/state/cyf-skill-packages")); }
    public SkillPackages(Path root) { this(root,SkillPackageStore.MAX_PACKAGE_BYTES); }
    SkillPackages(Path root,long descriptionBudget) {
        if(descriptionBudget<0) throw new IllegalArgumentException("negative description budget");
        store=new SkillPackageStore(root);this.descriptionBudget=descriptionBudget;
    }
    public <T> T withPreparedDescriptions(Supplier<T> action) {
        var previous=preparedDescriptions.get();
        // Nested scopes inherit already prepared immutable metadata but do not leak new entries outward.
        preparedDescriptions.set(previous==null?new HashMap<>():new HashMap<>(previous));
        try { return action.get(); }
        finally { if(previous==null) preparedDescriptions.remove();else preparedDescriptions.set(previous); }
    }
    public Description describe(SkillProductVersionEntity approved) {
        var product=product(approved);String digest=HexFormat.of().formatHex(product.packageSha256());
        var pinned=preparedDescriptions.get();var cached=pinned==null?null:pinned.get(digest);
        if(cached==null) synchronized(descriptions) { cached=descriptions.get(digest); }
        if(cached==null) {
            // Never turn cache eviction into archive IO while holding business locks. Callers that
            // span a transaction pin their cold/warm preparation for that operation explicitly.
            requireOutsideTransaction();
            var pkg=load(product);
            var manifest=SkillPackageDescriptor.read(pkg,manifestCodec);
            cached=new CachedDescription(product.packageSize(),manifest,
                    1024L+(manifest==null?0L:8L*manifest.document().toString().length()));
            remember(digest,cached);
        }
        require(cached.packageSize()==product.packageSize(),503,"SKILL_PACKAGE_UNAVAILABLE");
        verifyIdentity(product,cached.manifest());
        if(pinned!=null)pinned.put(digest,cached);
        return new Description(product,cached.manifest());
    }
    private void remember(String digest,CachedDescription value) {
        if(value.weight()>descriptionBudget)return; // no cache residency, not a business rejection
        synchronized(descriptions) {
            var old=descriptions.put(digest,value);
            if(old!=null)descriptionWeight-=old.weight();
            descriptionWeight+=value.weight();
            var entries=descriptions.entrySet().iterator();
            while(descriptionWeight>descriptionBudget && entries.hasNext()) {
                descriptionWeight-=entries.next().getValue().weight();entries.remove();
            }
        }
    }
    /** Actual downloads only: bytes live with this operation, not with the application singleton. */
    public PlatformSkillPackage prepare(SkillProductVersionEntity approved) {
        requireOutsideTransaction();
        var pkg=load(product(approved));
        manifest(pkg); // validate the actual bytes being returned, independently of description hits
        return pkg;
    }
    private PlatformSkillPackage load(PlatformSkillProduct product) {
        var bundled=seed.products().stream().filter(p->Arrays.equals(p.packageSha256(),product.packageSha256())).findFirst();
        byte[] bytes=bundled.isPresent()?seed.requirePackage(bundled.get().productVersionId()).bytes()
                :store.read(product.packageSha256(),product.packageSize());
        require(bytes.length==product.packageSize(),503,"SKILL_PACKAGE_UNAVAILABLE");
        return new PlatformSkillPackage(product,bytes);
    }
    public SkillManifestCodec.Manifest manifest(Description prepared) { return prepared.manifest(); }
    public SkillManifestCodec.Manifest manifest(PlatformSkillPackage prepared) {
        requireOutsideTransaction();
        var manifest=SkillPackageDescriptor.read(prepared,manifestCodec);
        verifyIdentity(prepared.product(),manifest);return manifest;
    }
    private static void verifyIdentity(PlatformSkillProduct product,SkillManifestCodec.Manifest manifest) {
        if(manifest!=null) {
            var document=manifest.document();
            require(product.skillKey().equals(document.path("skillKey").asText())
                    && product.skillVersion().equals(document.path("skillVersion").asText()),400,"SKILL_IDENTITY_MISMATCH");
        }
    }
    private static void requireOutsideTransaction() {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),503,"SKILL_PACKAGE_PREPARE_IN_TRANSACTION");
    }
    private static PlatformSkillProduct product(SkillProductVersionEntity approved) {
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
        return new PlatformSkillProduct(approved.getProductId(),approved.getProductVersionId(),approved.getSkillKey(),
                approved.getSkillVersion(),approved.getSkillKey(),"",approved.getPriceMicro(),approved.getDeploymentRestriction(),
                null,approved.getPackageSha256(),approved.getPackageSize(),approved.getApprovedPermissionsManifest(),approved.getApprovedPermissionsSha256());
    }
    /** Trusted maintenance: validate archive before immutable promotion. No DB publication. */
    public String publish(PlatformSkillPackage prepared) {
        requireOutsideTransaction();
        require(prepared!=null,400,"SKILL_PACKAGE_INVALID");
        var bytes=prepared.bytes();
        require(bytes.length==prepared.product().packageSize()
                && Arrays.equals(SkillPackageStore.sha256(bytes),prepared.product().packageSha256()),400,"SKILL_PACKAGE_INVALID");
        manifest(prepared);return store.publish(bytes);
    }
    public static void revalidate(SkillProductVersionEntity approved,PlatformSkillPackage prepared) {
        require(approved!=null && prepared!=null && "APPROVED".equals(approved.getReviewStatus()),503,"SKILL_PACKAGE_UNAVAILABLE");
        revalidate(approved,prepared.product());
    }
    public static void revalidate(SkillProductVersionEntity approved, Description prepared) {
        require(prepared!=null,503,"SKILL_PACKAGE_UNAVAILABLE");
        revalidate(approved,prepared.product());
    }
    private static void revalidate(SkillProductVersionEntity approved,PlatformSkillProduct p) {
        require(approved!=null && "APPROVED".equals(approved.getReviewStatus()),503,"SKILL_PACKAGE_UNAVAILABLE");
        require(p.productId().equals(approved.getProductId()) && p.productVersionId().equals(approved.getProductVersionId())
                && p.skillKey().equals(approved.getSkillKey()) && p.skillVersion().equals(approved.getSkillVersion())
                && Objects.equals(p.packageSize(),approved.getPackageSize()) && Arrays.equals(p.packageSha256(),approved.getPackageSha256())
                && Objects.equals(p.priceMicro(),approved.getPriceMicro())
                && p.approvedPermissionsManifest().equals(approved.getApprovedPermissionsManifest())
                && Arrays.equals(p.approvedPermissionsSha256(),approved.getApprovedPermissionsSha256())
                && p.deploymentRestriction().equals(approved.getDeploymentRestriction()),503,"SKILL_PACKAGE_UNAVAILABLE");
    }
}
