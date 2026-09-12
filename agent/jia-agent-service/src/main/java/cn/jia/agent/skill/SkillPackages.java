package cn.jia.agent.skill;
import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog;
import java.util.*;
/** Startup-only resource I/O. Purchase and package authorization only read verified in-memory bytes. */
public final class SkillPackages {
    private final Map<String,PlatformSkillPackageCatalog.PlatformSkillPackage> packages;
    public SkillPackages() {
        var catalog=new PlatformSkillPackageCatalog();
        Map<String,PlatformSkillPackageCatalog.PlatformSkillPackage> loaded=new HashMap<>();
        for(var p:catalog.products()) loaded.put(p.productVersionId(),catalog.requirePackage(p.productVersionId()));
        packages=Map.copyOf(loaded);
    }
    public PlatformSkillPackageCatalog.PlatformSkillPackage get(String id) {
        var p=packages.get(id);
        SkillMarketplaceException.require(p!=null,404,"SKILL_PRODUCT_NOT_FOUND"); return p;
    }
}
