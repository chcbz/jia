package cn.jia.agent.skill;

import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.InstalledSkillDescriptorLookup;
import cn.jia.agent.service.SkillCapabilityLookup;
import cn.jia.economy.entity.skill.*;
import cn.jia.economy.mapper.EconomySkillApplicationMapper;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;

/** Read-only join; package IO outside transactions, no ownership/version CAS or economy writes.
 * Only immutable package bytes are cached by SkillPackages. Entitlement is read on EVERY request. */
@Service
public final class InstalledSkillDescriptorService implements InstalledSkillDescriptorLookup {
    private final AgentIdentityService identities;
    private final EconomySkillMarketplaceMapper market;
    private final EconomySkillApplicationMapper app;
    private final ObjectProvider<SkillPackages> packages;
    public InstalledSkillDescriptorService(AgentIdentityService identities, EconomySkillMarketplaceMapper market,
            EconomySkillApplicationMapper app, ObjectProvider<SkillPackages> packages) {
        this.identities=Objects.requireNonNull(identities);this.market=Objects.requireNonNull(market);
        this.app=Objects.requireNonNull(app);this.packages=Objects.requireNonNull(packages);
    }
    @Override public Snapshot read(SkillCapabilityLookup.Scope scope) {
        Objects.requireNonNull(scope);
        try {
            if(!scope.agentId().equals(identities.requireCanonicalAgentIdInScope(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),scope.agentId()))) throw new SourceUnavailable();
            var rows=Objects.requireNonNull(market.selectEntitlementsByAgent(scope.tenantId(),scope.clientId(),scope.agentId()));
            var result=new ArrayList<Descriptor>();
            for(var e:rows) {
                if(!exact(scope,e.getTenantId(),e.getClientId(),e.getTargetAgentId()) || e.getSkillKey()==null
                        || e.getSkillVersion()==null) throw new SourceUnavailable();
                result.add(descriptor(scope,e));
            }
            return new Snapshot(result);
        } catch(SourceUnavailable failure) { throw failure; }
        catch(RuntimeException failure) { throw new SourceUnavailable(failure); }
    }
    private Descriptor descriptor(SkillCapabilityLookup.Scope s,SkillEntitlementEntity e) {
        if(!"ACTIVE".equals(e.getStatus())) return unavailable(e,"SKILL_NOT_INSTALLED");
        var i=app.installation(s.tenantId(),s.clientId(),e.getInstallationId());
        var o=app.order(s.tenantId(),s.clientId(),e.getOrderId());
        var v=market.selectApprovedProductVersion(s.tenantId(),s.clientId(),e.getProductVersionId());
        if(i==null||o==null||v==null || !exact(s,i.getTenantId(),i.getClientId(),i.getTargetAgentId())
                || !exact(s,o.getTenantId(),o.getClientId(),o.getTargetAgentId())
                || !Objects.equals(s.tenantId(),v.getTenantId())||!Objects.equals(s.clientId(),v.getClientId())
                || !"SUCCEEDED".equals(i.getStatus())||!"ACTIVE".equals(o.getStatus())||!"APPROVED".equals(v.getReviewStatus())
                || !Objects.equals(e.getInstallationId(),i.getInstallationId())
                || !Objects.equals(e.getOrderId(),i.getOrderId())||!Objects.equals(e.getOrderId(),o.getOrderId())
                || !Objects.equals(e.getProductVersionId(),i.getProductVersionId())
                || !Objects.equals(e.getProductVersionId(),o.getProductVersionId())
                || !Objects.equals(e.getProductVersionId(),v.getProductVersionId())
                || !Objects.equals(e.getSkillKey(),i.getSkillKey())||!Objects.equals(e.getSkillVersion(),i.getSkillVersion())
                || !Objects.equals(e.getSkillKey(),v.getSkillKey())||!Objects.equals(e.getSkillVersion(),v.getSkillVersion())
                || !Objects.equals(i.getPackageSize(),v.getPackageSize())||!Arrays.equals(i.getPackageSha256(),v.getPackageSha256())
                || e.getPermissionGrantVersion()==null||e.getPermissionGrantVersion()<1
                || !Objects.equals(e.getPermissionGrantVersion(),o.getPermissionGrantVersion())
                || !Objects.equals(e.getApprovedPermissionsManifest(),o.getApprovedPermissionsManifest())
                || !Objects.equals(e.getApprovedPermissionsManifest(),v.getApprovedPermissionsManifest())
                || !Arrays.equals(e.getApprovedPermissionsSha256(),o.getApprovedPermissionsSha256())
                || !Arrays.equals(e.getApprovedPermissionsSha256(),v.getApprovedPermissionsSha256()))
            return unavailable(e,"SKILL_INSTALLATION_EVIDENCE_INVALID");
        if(app.successfulReceipts(s.tenantId(),s.clientId(),e.getInstallationId())<1)
            return unavailable(e,"SKILL_INSTALLATION_RECEIPT_MISSING");
        var binding=app.deliveryBinding(s.tenantId(),s.clientId(),e.getInstallationId());
        if(binding==null||!s.agentId().equals(binding.getCanonicalAgentId())
                || !valid(binding.getRuntimeInstallationId())||!valid(binding.getRuntimeHostId()))
            return unavailable(e,"SKILL_INSTALLATION_BINDING_INVALID");
        var store=packages.getIfUnique(); if(store==null) throw new SourceUnavailable();
        final SkillManifestCodec.Manifest manifest;
        try { manifest=store.manifest(store.prepare(v)); }
        catch(SkillMarketplaceException invalid) { throw new SourceUnavailable(invalid); }
        if(manifest==null) return unavailable(e,"SKILL_NOT_EXECUTABLE");
        // Re-read mutable authorization AFTER immutable IO, never publish an old ACTIVE snapshot.
        var current=market.selectEntitlementsByAgent(s.tenantId(),s.clientId(),s.agentId()).stream()
                .filter(r->Objects.equals(e.getEntitlementId(),r.getEntitlementId())).toList();
        if(current.size()!=1||!e.equals(current.getFirst())) return unavailable(e,"SKILL_INSTALLATION_CHANGED");
        return new Descriptor(e.getSkillKey(),e.getSkillVersion(),e.getInstallationId(),
                HexFormat.of().formatHex(v.getPackageSha256()),manifest.manifestSha256(),manifest.document().toString(),
                binding.getRuntimeInstallationId(),binding.getRuntimeHostId(),null);
    }
    private static boolean exact(SkillCapabilityLookup.Scope s,String t,String c,String a) {
        return s.tenantId().equals(t)&&s.clientId().equals(c)&&s.agentId().equals(a);
    }
    private static boolean valid(String value) { return value!=null&&!value.isBlank()&&value.equals(value.strip()); }
    private static Descriptor unavailable(SkillEntitlementEntity e,String reason) {
        return new Descriptor(e.getSkillKey(),e.getSkillVersion(),e.getInstallationId(),null,null,null,null,null,reason);
    }
}
