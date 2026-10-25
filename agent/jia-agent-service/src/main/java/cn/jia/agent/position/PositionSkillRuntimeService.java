package cn.jia.agent.position;

import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.PositionAuthority.Scope;
import cn.jia.agent.security.AgentRuntimeAuthenticationService.Proof;
import cn.jia.agent.skill.SkillPackages;
import cn.jia.agent.skill.SkillManifestCodec;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog.PlatformSkillPackage;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.jia.agent.position.PositionSkillException.require;
import static cn.jia.agent.position.PositionSkillState.*;

/** A non-paid installation source using the existing approved packages and Runtime installer.
 * The durable desired-state revision is the delivery/recovery identity, never a fake order. */
@Service
public class PositionSkillRuntimeService {
    public record Prepared(Installation installation,Skill skill,PlatformSkillPackage bytes,SkillManifestCodec.Manifest manifest) { }
    public record Receipt(String grantId,String revision,String physicalInstallationId,boolean installed,
            String packageDigest,String manifestSha256,String errorCode) { }
    private final PositionSkillService positions;
    private final AgentIdentityService identities;
    private final EconomySkillMarketplaceMapper market;
    private final SkillPackages packages;
    public PositionSkillRuntimeService(PositionSkillService positions,AgentIdentityService identities,
            EconomySkillMarketplaceMapper market,SkillPackages packages){
        this.positions=positions;this.identities=identities;this.market=market;this.packages=packages;
    }
    /** IO occurs before the native transaction; exact persisted metadata is rechecked inside it. */
    public List<Prepared> prepare(Scope scope,String agentId) {
        var state=positions.read(scope).state();var out=new ArrayList<Prepared>();
        for(var i:state.installations().values()) {
            if(!i.agentId().equals(agentId) || !(i.state()==InstallState.INSTALL_PENDING || i.state()==InstallState.REMOVE_PENDING))continue;
            var skill=state.skills().get(i.skillKey());
            if(!i.desired()){out.add(new Prepared(i,skill,null,null));continue;}
            var version=market.selectApprovedProductVersion(scope.tenantId(),scope.clientId(),skill.productVersionId());
            require(version!=null && skill.key().equals(version.getSkillKey()),503,"POSITION_PACKAGE_UNAVAILABLE");
            var bytes=packages.prepare(version);var manifest=packages.manifest(bytes);
            out.add(new Prepared(i,skill,bytes,manifest));
        }
        return out;
    }
    private cn.jia.agent.skill.SkillManagementService management;
    @org.springframework.beans.factory.annotation.Autowired
    public void setManagement(cn.jia.agent.skill.SkillManagementService value){management=value;}
    public List<Map<String,Object>> commands(Proof proof,List<Prepared> prepared) {
        var rows=new ArrayList<Map<String,Object>>();
        for(var item:prepared) {
            var command=management.reconcilePosition(proof,item);
            if(command!=null&&!rows.contains(command))rows.add(command);
        }
        return rows;
    }
    public Map<String,Object> managementResult(Proof proof,Map<String,Object> result) {
        // This endpoint cannot be used to inject a user/market result into a different source.
        var data=cn.jia.agent.skill.SkillManagementCodec.object(result.get("data"));
        require(data.get("skillChangeId") instanceof String id&&id.startsWith("ps_"),400,"POSITION_RECEIPT_INVALID");
        return management.result(proof,result);
    }
    public byte[] packageBytes(Proof proof,String id,long revision,List<Prepared> prepared) {
        var item=prepared.stream().filter(p->p.installation().id().equals(id) && p.installation().revision()==revision).findFirst().orElse(null);
        require(item!=null && item.bytes()!=null,404,"POSITION_PACKAGE_NOT_FOUND");
        return positions.runtimeState(scope(proof),proof.scope().agentId(),p->{
            var i=current(proof,p.state(),id);require(i.desired() && i.revision()==revision && i.state()==InstallState.INSTALL_PENDING,409,"POSITION_INSTALLATION_FENCED");
            SkillPackages.revalidate(market.selectApprovedProductVersion(scope(proof).tenantId(),scope(proof).clientId(),item.skill().productVersionId()),item.bytes());
            return item.bytes().bytes();
        });
    }
    public Prepared prepareReceipt(Scope scope,String agentId,String id,Receipt receipt) {
        if(receipt==null || receipt.errorCode()!=null || !receipt.installed())return null;
        var state=positions.read(scope).state();var i=state.installations().get(id);
        require(i!=null && i.agentId().equals(agentId) && i.desired(),409,"POSITION_INSTALLATION_FENCED");
        var skill=state.skills().get(i.skillKey());
        var version=market.selectApprovedProductVersion(scope.tenantId(),scope.clientId(),skill.productVersionId());
        require(version!=null,503,"POSITION_PACKAGE_UNAVAILABLE");
        var bytes=packages.prepare(version);var manifest=packages.manifest(bytes);
        return new Prepared(i,skill,bytes,manifest);
    }
    public Map<String,Object> receipt(Proof proof,String id,Receipt receipt,Prepared prepared) {
        require(receipt!=null && id.equals(receipt.grantId()),400,"POSITION_RECEIPT_INVALID");
        long revision=decimal(receipt.revision());
        return positions.runtimeState(scope(proof),proof.scope().agentId(),p->{
            var i=current(proof,p.state(),id);require(revision==i.revision(),409,"POSITION_INSTALLATION_FENCED");
            require(physical(i).equals(receipt.physicalInstallationId()),409,"POSITION_RECEIPT_CONFLICT");
            if(i.desired() && receipt.errorCode()==null){
                var skill=p.state().skills().get(i.skillKey());
                var version=market.selectApprovedProductVersion(scope(proof).tenantId(),scope(proof).clientId(),skill.productVersionId());
                require(version!=null && "APPROVED".equals(version.getReviewStatus()) && version.getPackageSha256()!=null
                        && ("sha256:"+HexFormat.of().formatHex(version.getPackageSha256())).equals(receipt.packageDigest())
                        && (receipt.manifestSha256()==null || receipt.manifestSha256().matches("[0-9a-f]{64}"))
                        && prepared!=null && prepared.installation().id().equals(id)
                        && prepared.installation().revision()==revision
                        && Objects.equals(prepared.manifest()==null?null:prepared.manifest().manifestSha256(),receipt.manifestSha256()),409,"POSITION_RECEIPT_CONFLICT");
                SkillPackages.revalidate(version,prepared.bytes());
            }
            String digest=PositionSkillService.hash(String.join("\0",id,receipt.revision(),receipt.physicalInstallationId(),
                    Boolean.toString(receipt.installed()),Objects.toString(receipt.packageDigest(),""),Objects.toString(receipt.manifestSha256(),""),Objects.toString(receipt.errorCode(),"")));
            p.receipt(id,revision,receipt.installed(),digest,proof.installationId(),proof.hostId(),receipt.errorCode(),receipt.packageDigest(),receipt.manifestSha256());
            return Map.of("grantId",id,"revision",receipt.revision(),"state",p.state().installations().get(id).state().name());
        });
    }
    public Map<String,Object> outcome(Proof proof,String id) {
        return positions.runtimeState(scope(proof),proof.scope().agentId(),p->{
            var i=current(proof,p.state(),id);
            require(i.receiptDigest()!=null && proof.installationId().equals(i.runtimeInstallationId()) && proof.hostId().equals(i.runtimeHostId()),404,"POSITION_RECEIPT_NOT_FOUND");
            var row=new LinkedHashMap<String,Object>();row.put("grantId",i.id());row.put("revision",Long.toString(i.revision()));
            row.put("physicalInstallationId",physical(i));row.put("state",i.state().name());row.put("packageDigest",i.packageDigest());
            row.put("manifestSha256",i.manifestSha256());row.put("errorCode",i.errorCode());return row;
        });
    }
    private Installation current(Proof proof,PositionSkillState state,String id){
        var i=state.installations().get(id);
        require(i!=null && i.agentId().equals(proof.scope().agentId()),404,"POSITION_INSTALLATION_NOT_FOUND");
        var identity=identities.requireRegistrationIdentityInScope(proof.scope().tenantId(),proof.scope().clientId(),proof.scope().ownerJiacn(),proof.scope().agentId());
        require(Objects.equals(identity.getBindingId(),i.bindingId()) && i.agentId().equals(identity.getCanonicalAgentId()),403,"POSITION_BINDING_CHANGED");return i;
    }
    public static Scope scope(Proof p){return new Scope(p.scope().tenantId(),p.scope().clientId(),p.scope().ownerJiacn());}
    public static String physical(Installation i){return i.managedInstallationId()!=null?i.managedInstallationId():i.id()+"-"+i.revision();}
    public static long decimal(String value){require(value!=null && value.matches("[1-9][0-9]*"),400,"POSITION_REVISION_INVALID");try{return Long.parseLong(value);}catch(NumberFormatException e){throw new PositionSkillException(400,"POSITION_REVISION_INVALID");}}
}
