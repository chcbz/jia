package cn.jia.chat.archive.service;

import cn.jia.agent.position.*;
import cn.jia.agent.service.PositionAuthority.*;
import cn.jia.agent.security.AgentRuntimeAuthenticationService.Proof;
import cn.jia.chat.archive.model.*;
import cn.jia.chat.archive.store.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;
import static cn.jia.agent.position.PositionSkillException.require;

/** Historical maintenance records only. New mutations require native current-turn conversation tools. */
@Service
@ConditionalOnProperty(prefix="agent.position-skills",name="enabled",havingValue="true")
public class ArchiveMaintenanceService {
    public enum Mode { DRAFT_ONLY, AUTO }
    public record Create(String appointmentId,String agentId,String skillKey,String workId,String title,
            String author,String sourceName,String sourceRights,String sourceText,Mode mode) { }
    public record Specification(Create request,String editionId,String sourceSha256,Grant grant,
            String expectedActiveEdition,String installationId,String packageDigest,String manifestSha256,
            String sourceFileId,Integer sourceFileVersion) {
        public Specification(Create request,String editionId,String sourceSha256,Grant grant,String expectedActiveEdition,
                String installationId,String packageDigest,String manifestSha256) {
            this(request,editionId,sourceSha256,grant,expectedActiveEdition,installationId,packageDigest,manifestSha256,null,null);
        }
    }
    private final ArchiveMaintenanceStore jobs;
    private final ObjectMapper json=new ObjectMapper();
    public ArchiveMaintenanceService(ArchiveMaintenanceStore jobs){this.jobs=jobs;}

    public List<Map<String,Object>> list(Scope scope){return jobs.list(owner(scope)).stream().map(this::view).toList();}
    public Map<String,Object> recover(Scope scope,String key){PositionSkillPolicy.key(key);var job=jobs.operation(owner(scope),key);if(job==null){String id=jobs.publicationOperation(owner(scope),key);if(id!=null)job=requireJob(scope,id);}require(job!=null,404,"ARCHIVE_OPERATION_NOT_FOUND");return view(job);}
    public Map<String,Object> read(Scope scope,String id){return view(requireJob(scope,id));}
    public String draft(Scope scope,String id){var j=requireJob(scope,id);require(j.resultManifest()!=null,409,"ARCHIVE_DRAFT_NOT_READY");return j.resultManifest();}

    /** Read committed outcome after a lost response, even if the job authority has since been revoked. */
    public Map<String,Object> outcome(Proof proof,String id){
        var job=requireJob(scope(proof),id);
        require(job.agentId().equals(proof.scope().agentId()),403,"ARCHIVE_AGENT_FORBIDDEN");
        return view(job);
    }

    private ArchiveMaintenanceStore.Job requireJob(Scope s,String id){PositionSkillPolicy.key(id);var job=jobs.find(owner(s),id,false);require(job!=null,404,"ARCHIVE_JOB_NOT_FOUND");return job;}
    private Map<String,Object> view(ArchiveMaintenanceStore.Job job){
        var s=decode(job.specification());var r=s.request();var row=new LinkedHashMap<String,Object>();
        row.put("jobId",job.id());row.put("operationKey",job.operationKey());row.put("agentId",job.agentId());row.put("state",job.state());
        row.put("appointmentId",r.appointmentId());row.put("skillKey",r.skillKey());row.put("workId",r.workId());row.put("editionId",s.editionId());
        row.put("title",r.title());row.put("author",r.author());row.put("mode",r.mode());row.put("sourceName",r.sourceName());
        row.put("sourceSha256",s.sourceSha256());row.put("resultDigest",job.resultDigest());row.put("errorCode",job.errorCode());return row;
    }

    public static Scope scope(Proof proof){return new Scope(proof.scope().tenantId(),proof.scope().clientId(),proof.scope().ownerJiacn());}
    private static ArchiveOwnerScope owner(Scope s){return new ArchiveOwnerScope(s.tenantId(),s.clientId(),s.ownerJiacn());}

    private Specification decode(String v){try{return json.readValue(v,Specification.class);}catch(Exception e){throw new PositionSkillException(503,"ARCHIVE_STATE_UNAVAILABLE");}}
}
