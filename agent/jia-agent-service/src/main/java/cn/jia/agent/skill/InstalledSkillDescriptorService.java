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
    private SkillManagementService management;
    private cn.jia.economy.mapper.EconomySkillManagementMapper managed;
    @org.springframework.beans.factory.annotation.Autowired
    public void setManagement(SkillManagementService management,cn.jia.economy.mapper.EconomySkillManagementMapper managed) {
        this.management=management;this.managed=managed;
    }
    private SkillCostInfoSource costs;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCostInfoSource(SkillCostInfoSource costs) {this.costs=Objects.requireNonNull(costs);}
    @Override public Snapshot read(SkillCapabilityLookup.Scope scope) {
        Objects.requireNonNull(scope);
        try {
            if(!scope.agentId().equals(identities.requireCanonicalAgentIdInScope(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),scope.agentId()))) throw new SourceUnavailable(SourceUnavailable.Reason.FORBIDDEN,null);
            var rows=Objects.requireNonNull(market.selectEntitlementsByAgent(scope.tenantId(),scope.clientId(),scope.agentId())).stream()
                    .filter(e->managed==null||managed.findAuthorization(scope.tenantId(),scope.clientId(),e.getInstallationId())==null).toList();
            var result=new ArrayList<Descriptor>();
            for(var e:rows) {
                if(!exact(scope,e.getTenantId(),e.getClientId(),e.getTargetAgentId()) || e.getSkillKey()==null
                        || e.getSkillVersion()==null) throw new SourceUnavailable();
                result.add(descriptor(scope,e));
            }
            // One final authorization read per catalog, not per skill (avoid quadratic/N+1 reads).
            if(result.stream().anyMatch(Descriptor::verified)) {
                var current=Objects.requireNonNull(market.selectEntitlementsByAgent(scope.tenantId(),scope.clientId(),scope.agentId()));
                var byId=new HashMap<String,List<SkillEntitlementEntity>>();
                for(var row:current) {
                    if(!exact(scope,row.getTenantId(),row.getClientId(),row.getTargetAgentId())) throw new SourceUnavailable();
                    byId.computeIfAbsent(row.getEntitlementId(),unused->new ArrayList<>()).add(row);
                }
                for(int index=0;index<rows.size();index++) {
                    if(!result.get(index).verified()) continue;
                    var original=rows.get(index); var observed=byId.getOrDefault(original.getEntitlementId(),List.of());
                    if(observed.size()!=1||!original.equals(observed.getFirst()))
                        result.set(index,unavailable(original,"SKILL_INSTALLATION_CHANGED"));
                }
            }
            if(management!=null)result.addAll(management.descriptors(scope));
            // Decoration changes no package/manifest identity or readiness reason. Snapshot consumers
            // retain these informational bytes at admission; a later price update does not invalidate work.
            if(costs!=null) {
                var config=costs.read();
                for(int index=0;index<result.size();index++) {
                    var d=result.get(index);if(!d.verified())continue;
                    result.set(index,new Descriptor(d.skillKey(),d.skillVersion(),d.installationId(),d.packageSha256(),
                            d.manifestSha256(),costs.decorate(config,scope.agentId(),d.manifestJson()),
                            d.runtimeInstallationId(),d.runtimeHostId(),d.reasonCode()));
                }
            }
            return new Snapshot(result);
        } catch(SourceUnavailable failure) { throw failure; }
        catch(cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException failure) {
            var reason=cn.jia.agent.common.AgentErrorConstants.AGENT_FORBIDDEN.equals(failure.getCode())
                    ? SourceUnavailable.Reason.FORBIDDEN : SourceUnavailable.Reason.UNAVAILABLE;
            throw new SourceUnavailable(reason,failure);
        }
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
                || e.getActivatedAt()==null||e.getActivatedAt()<1||!Objects.equals(e.getActivatedAt(),i.getInstalledAt())
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
