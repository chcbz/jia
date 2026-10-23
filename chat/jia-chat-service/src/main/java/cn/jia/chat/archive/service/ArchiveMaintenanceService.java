package cn.jia.chat.archive.service;

import cn.jia.agent.position.*;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.PositionAuthority.*;
import cn.jia.agent.security.AgentRuntimeAuthenticationService.Proof;
import cn.jia.chat.archive.content.*;
import cn.jia.chat.archive.model.*;
import cn.jia.chat.archive.store.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.jia.agent.position.PositionSkillException.require;

/** One fixed source, one persisted operation. Parsing belongs to an installed skill, authority to the backend. */
@Service
@ConditionalOnProperty(prefix="agent.position-skills",name="enabled",havingValue="true")
public class ArchiveMaintenanceService {
    public enum Mode { DRAFT_ONLY, AUTO }
    public record Create(String appointmentId,String agentId,String skillKey,String workId,String title,
            String author,String sourceName,String sourceRights,String sourceText,Mode mode) { }
    public record Specification(Create request,String editionId,String sourceSha256,Grant grant,
            String expectedActiveEdition,String installationId,String packageDigest,String manifestSha256) { }
    public record Result(String manifestJson,String manifestFileSha256,String errorCode) {
        public Result(String manifestJson,String manifestFileSha256){this(manifestJson,manifestFileSha256,null);}
    }
    private final PositionSkillService positions;
    private final AgentIdentityService identities;
    private final ArchiveMaintenanceStore jobs;
    private final ArchiveContentStore content;
    private final ArchiveTransactions tx;
    private final ObjectMapper json=new ObjectMapper();
    public ArchiveMaintenanceService(PositionSkillService positions,AgentIdentityService identities,
            ArchiveMaintenanceStore jobs,ArchiveContentStore content,ArchiveTransactions tx){
        this.positions=positions;this.identities=identities;this.jobs=jobs;this.content=content;this.tx=tx;
    }
    public Map<String,Object> create(Scope scope,String key,Create request){
        PositionSkillPolicy.key(key);validate(request);
        String digest=PositionSkillService.hash(encode(request));
        return tx.required(()->{
            identities.lockActiveCanonicalAgentIdsInScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),List.of(request.agentId()));
            var state=positions.read(scope).state();var appointment=state.appointments().get(request.appointmentId());
            require(appointment!=null && appointment.active() && appointment.agentId().equals(request.agentId()),403,"ARCHIVE_APPOINTMENT_REQUIRED");
            var position=state.positions().get(appointment.positionId());
            var grant=new Grant(appointment.id(),appointment.revision(),position.revision(),request.agentId(),appointment.bindingId(),request.workId(),request.mode()==Mode.AUTO);
            return positions.withAuthority(scope,grant,()->{
                require(jobs.publicationOperation(owner(scope),key,true)==null,409,"ARCHIVE_IDEMPOTENCY_CONFLICT");
                var existing=jobs.operation(owner(scope),key,true);
                if(existing!=null){require(existing.requestDigest().equals(digest),409,"ARCHIVE_IDEMPOTENCY_CONFLICT");return view(existing);}
                var current=positions.currentState(scope);
                var install=current.installations().values().stream().filter(i->i.agentId().equals(request.agentId())
                        && i.bindingId()==appointment.bindingId() && i.skillKey().equals(request.skillKey()) && i.usable()).findFirst().orElse(null);
                require(install!=null && install.packageDigest()!=null && install.manifestSha256()!=null,409,"ARCHIVE_SKILL_NOT_READY");
                String id="am_"+UUID.randomUUID().toString().replace("-","");
                var work=content.findWork(request.workId());
                var spec=new Specification(request,"edition-"+id,PositionSkillService.hash(request.sourceText()),grant,
                        work==null?null:work.activeEditionId(),PositionSkillRuntimeService.physical(install),install.packageDigest(),install.manifestSha256());
                var job=new ArchiveMaintenanceStore.Job(id,key,digest,request.agentId(),"WAITING",encode(spec),null,null);
                jobs.insert(owner(scope),job);return view(job);
            });
        });
    }
    /** A retry is a new idempotent job using the persisted source, never a reopened old result generation. */
    public Map<String,Object> retry(Scope scope,String key,String id){
        var original=requireJob(scope,id);require("FAILED".equals(original.state()),409,"ARCHIVE_JOB_NOT_FAILED");
        return create(scope,key,decode(original.specification()).request());
    }
    public List<Map<String,Object>> list(Scope scope){return jobs.list(owner(scope)).stream().map(this::view).toList();}
    public Map<String,Object> recover(Scope scope,String key){PositionSkillPolicy.key(key);var job=jobs.operation(owner(scope),key);if(job==null){String id=jobs.publicationOperation(owner(scope),key);if(id!=null)job=requireJob(scope,id);}require(job!=null,404,"ARCHIVE_OPERATION_NOT_FOUND");return view(job);}
    public Map<String,Object> read(Scope scope,String id){return view(requireJob(scope,id));}
    public String draft(Scope scope,String id){var j=requireJob(scope,id);require(j.resultManifest()!=null,409,"ARCHIVE_DRAFT_NOT_READY");return j.resultManifest();}
    public List<Map<String,Object>> commands(Proof proof){
        return tx.required(()->{
            Scope scope=scope(proof);List<Map<String,Object>> out=new ArrayList<>();
            for(var job:jobs.list(owner(scope))){
                if(!job.agentId().equals(proof.scope().agentId()) || !"WAITING".equals(job.state()))continue;
                var spec=decode(job.specification());
                if(!positions.authorityAvailable(scope,spec.grant()) || !installed(proof,job,spec))continue;
                var row=new LinkedHashMap<>(view(job));row.put("installationId",spec.installationId());
                row.put("packageDigest",spec.packageDigest());row.put("manifestSha256",spec.manifestSha256());out.add(row);
            }
            return out;
        });
    }
    public Map<String,Object> source(Proof proof,String id){var job=requireJob(scope(proof),id);return authorized(proof,job,()->{
        var s=decode(job.specification());var r=s.request();
        return Map.of("workId",r.workId(),"editionId",s.editionId(),"title",r.title(),"author",r.author(),
                "sourceName",r.sourceName(),"sourceRights",r.sourceRights(),"sourceText",r.sourceText(),"sourceSha256",s.sourceSha256());
    });}
    /** Read committed outcome after a lost response, even if the job authority has since been revoked. */
    public Map<String,Object> outcome(Proof proof,String id){
        var job=requireJob(scope(proof),id);
        require(job.agentId().equals(proof.scope().agentId()),403,"ARCHIVE_AGENT_FORBIDDEN");
        return view(job);
    }
    public Map<String,Object> complete(Proof proof,String id,Result result){
        require(result!=null,400,"ARCHIVE_RESULT_REQUIRED");
        if(result.errorCode()!=null)return fail(proof,id,result);
        require(result.manifestJson()!=null,400,"ARCHIVE_RESULT_REQUIRED");
        var bytes=result.manifestJson().getBytes(StandardCharsets.UTF_8);
        var loaded=new ArchiveManifestLoader().parseAndValidate(bytes,result.manifestFileSha256());
        var initial=requireJob(scope(proof),id);var spec=decode(initial.specification());var manifest=loaded.manifest();
        require(manifest.workId().equals(spec.request().workId()) && manifest.editionId().equals(spec.editionId())
                && manifest.title().equals(spec.request().title()) && manifest.source().sha256().equals(spec.sourceSha256())
                && manifest.source().utf8ByteLength()==spec.request().sourceText().getBytes(StandardCharsets.UTF_8).length,409,"ARCHIVE_SOURCE_MISMATCH");
        return authorized(proof,initial,()->{
            var job=jobs.find(owner(scope(proof)),id,true);
            if(!"WAITING".equals(job.state())){require(Objects.equals(job.resultDigest(),result.manifestFileSha256()),409,"ARCHIVE_RESULT_CONFLICT");return view(job);}
            String state="DRAFT";
            if(spec.request().mode()==Mode.AUTO){
                publish(spec,loaded);
                state="PUBLISHED";
            }
            require(jobs.complete(owner(scope(proof)),id,state,result.manifestJson(),result.manifestFileSha256())==1,409,"ARCHIVE_RESULT_CONFLICT");
            return view(jobs.find(owner(scope(proof)),id,false));
        });
    }
    /** User OAuth scope reaches this explicit action; the same backend grant/CAS/importer owns publication. */
    public Map<String,Object> publishDraft(Scope scope,String key,String id){
        PositionSkillPolicy.key(key);var initial=requireJob(scope,id);var spec=decode(initial.specification());
        return tx.required(()->{
            identities.lockActiveCanonicalAgentIdsInScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),List.of(initial.agentId()));
            var state=positions.currentState(scope);var a=state.appointments().get(spec.grant().appointmentId());
            require(a!=null && a.active() && a.bindingId()==spec.grant().bindingId(),403,"POSITION_AUTHORITY_REVOKED");
            var position=state.positions().get(a.positionId());
            var grant=new Grant(a.id(),a.revision(),position.revision(),a.agentId(),a.bindingId(),spec.request().workId(),true);
            return positions.withAuthority(scope,grant,()->{
                require(jobs.operation(owner(scope),key,true)==null,409,"ARCHIVE_IDEMPOTENCY_CONFLICT");
                String prior=jobs.publicationOperation(owner(scope),key,true);
                if(prior!=null){require(prior.equals(id),409,"ARCHIVE_IDEMPOTENCY_CONFLICT");return view(requireJob(scope,id));}
                var job=jobs.find(owner(scope),id,true);require("DRAFT".equals(job.state()),409,"ARCHIVE_DRAFT_NOT_PUBLISHABLE");
                require(state.installations().values().stream().anyMatch(i->i.usable() && i.agentId().equals(a.agentId())
                        && i.bindingId()==a.bindingId() && PositionSkillRuntimeService.physical(i).equals(spec.installationId())),403,"ARCHIVE_SKILL_REVOKED");
                var loaded=new ArchiveManifestLoader().parseAndValidate(job.resultManifest().getBytes(StandardCharsets.UTF_8),job.resultDigest());
                publish(spec,loaded);require(jobs.publishDraft(owner(scope),id)==1,409,"ARCHIVE_RESULT_CONFLICT");
                jobs.publicationOperation(owner(scope),key,id);return view(jobs.find(owner(scope),id,false));
            });
        });
    }
    private void publish(Specification spec,ArchiveManifestLoader.Loaded loaded){
        var manifest=loaded.manifest();
        var work=content.lockWork(manifest.workId());
        if(work==null){
            try{content.insertWork(new ArchiveWorkRecord(manifest.workId(),manifest.title(),null));}
            catch(DuplicateKeyException raced){/* re-read locked current row and apply CAS */}
            work=content.lockWork(manifest.workId());
        }
        require(work!=null && Objects.equals(work.activeEditionId(),spec.expectedActiveEdition()),409,"ARCHIVE_EDITION_CONFLICT");
        new ArchiveContentImporter(content,tx).importAndActivate(manifest,loaded.manifestFileSha256());
    }
    private Map<String,Object> fail(Proof proof,String id,Result result){
        require(Set.of("ARCHIVE_SCRIPT_FAILED","ARCHIVE_SCRIPT_RESULT_INVALID").contains(result.errorCode())
                && result.manifestJson()==null && result.manifestFileSha256()==null,400,"ARCHIVE_FAILURE_INVALID");
        var initial=requireJob(scope(proof),id);String digest=PositionSkillService.hash("error:"+result.errorCode());
        return authorized(proof,initial,()->{
            var job=jobs.find(owner(scope(proof)),id,true);
            if(!"WAITING".equals(job.state())){require("FAILED".equals(job.state()) && digest.equals(job.resultDigest()),409,"ARCHIVE_RESULT_CONFLICT");return view(job);}
            require(jobs.fail(owner(scope(proof)),id,result.errorCode(),digest)==1,409,"ARCHIVE_RESULT_CONFLICT");
            return view(jobs.find(owner(scope(proof)),id,false));
        });
    }
    private <T>T authorized(Proof proof,ArchiveMaintenanceStore.Job job,java.util.function.Supplier<T> action){
        var s=decode(job.specification());require(job.agentId().equals(proof.scope().agentId()),403,"ARCHIVE_AGENT_FORBIDDEN");
        return positions.withAuthority(scope(proof),s.grant(),()->{
            require(installed(proof,job,s),403,"ARCHIVE_SKILL_REVOKED");
            return action.get();
        });
    }
    private boolean installed(Proof proof,ArchiveMaintenanceStore.Job job,Specification s){
        var state=positions.currentState(scope(proof));
        return state.installations().values().stream().anyMatch(i->i.usable() && i.agentId().equals(job.agentId())
                    && i.bindingId()==s.grant().bindingId() && PositionSkillRuntimeService.physical(i).equals(s.installationId())
                    && Objects.equals(i.runtimeInstallationId(),proof.installationId()) && Objects.equals(i.runtimeHostId(),proof.hostId())
                    && Objects.equals(i.packageDigest(),s.packageDigest()) && Objects.equals(i.manifestSha256(),s.manifestSha256()));
    }
    private ArchiveMaintenanceStore.Job requireJob(Scope s,String id){PositionSkillPolicy.key(id);var job=jobs.find(owner(s),id,false);require(job!=null,404,"ARCHIVE_JOB_NOT_FOUND");return job;}
    private Map<String,Object> view(ArchiveMaintenanceStore.Job job){
        var s=decode(job.specification());var r=s.request();var row=new LinkedHashMap<String,Object>();
        row.put("jobId",job.id());row.put("operationKey",job.operationKey());row.put("agentId",job.agentId());row.put("state",job.state());
        row.put("appointmentId",r.appointmentId());row.put("skillKey",r.skillKey());row.put("workId",r.workId());row.put("editionId",s.editionId());
        row.put("title",r.title());row.put("author",r.author());row.put("mode",r.mode());row.put("sourceName",r.sourceName());
        row.put("sourceSha256",s.sourceSha256());row.put("resultDigest",job.resultDigest());row.put("errorCode",job.errorCode());return row;
    }
    private static void validate(Create r){
        require(r!=null && r.mode()!=null,400,"ARCHIVE_REQUEST_INVALID");
        PositionSkillPolicy.key(r.appointmentId());PositionSkillPolicy.key(r.agentId());PositionSkillPolicy.key(r.skillKey());
        require(r.workId()!=null && r.workId().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"),400,"ARCHIVE_WORK_INVALID");
        for(var v:Arrays.asList(r.title(),r.author(),r.sourceName(),r.sourceRights()))require(v!=null && !v.isBlank() && v.length()<=255,400,"ARCHIVE_SOURCE_METADATA_REQUIRED");
        require(r.sourceText()!=null && !r.sourceText().isBlank() && r.sourceText().indexOf(0)<0,400,"ARCHIVE_SOURCE_REQUIRED");
    }
    public static Scope scope(Proof proof){return new Scope(proof.scope().tenantId(),proof.scope().clientId(),proof.scope().ownerJiacn());}
    private static ArchiveOwnerScope owner(Scope s){return new ArchiveOwnerScope(s.tenantId(),s.clientId(),s.ownerJiacn());}
    private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new PositionSkillException(503,"ARCHIVE_STATE_UNAVAILABLE");}}
    private Specification decode(String v){try{return json.readValue(v,Specification.class);}catch(Exception e){throw new PositionSkillException(503,"ARCHIVE_STATE_UNAVAILABLE");}}
}
