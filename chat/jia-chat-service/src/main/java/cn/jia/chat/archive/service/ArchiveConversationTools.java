package cn.jia.chat.archive.service;

import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.position.PositionSkillPolicy;
import cn.jia.agent.service.PositionArchiveAccess;
import cn.jia.agent.service.PositionArchiveAccess.Session;
import cn.jia.agent.service.PositionAuthority;
import cn.jia.agent.service.InstalledSkillDescriptorLookup;
import cn.jia.agent.service.PersonalWorkspaceService;
import cn.jia.agent.service.SkillCapabilityLookup;
import cn.jia.chat.service.ConversationToolProvider;
import cn.jia.chat.archive.content.*;
import cn.jia.chat.archive.model.*;
import cn.jia.chat.archive.store.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import static cn.jia.agent.position.PositionSkillException.require;

/** Archive-owned native conversation tools. No polling, shell, model-produced full text or legacy task replay. */
@Service
public final class ArchiveConversationTools implements ConversationToolProvider {
    private static final Map<String,Object> TEXT=Map.of("type","string");
    private static final Map<String,Object> VERSION=Map.of("type","string","pattern","^[1-9][0-9]*$");
    private static final ObjectMapper JSON=new ObjectMapper();
    private final PositionArchiveAccess positions;
    private final InstalledSkillDescriptorLookup installed;
    private final PersonalWorkspaceService files;
    private final ArchiveMaintenanceStore jobs;
    private final ArchiveContentStore content;
    private final ArchiveTransactions transactions;
    public ArchiveConversationTools(PositionArchiveAccess positions,InstalledSkillDescriptorLookup installed,
            PersonalWorkspaceService files,ArchiveMaintenanceStore jobs,ArchiveContentStore content,ArchiveTransactions transactions) {
        this.positions=positions;this.installed=installed;this.files=files;this.jobs=jobs;this.content=content;this.transactions=transactions;
    }
    private static Map<String,Object> tool(String name,String description,Map<String,Object> fields) {
        return Map.of("type","function","name",name,"description",description,"inputSchema",Map.of("type","object","properties",fields,"required",List.copyOf(fields.keySet()),"additionalProperties",false));
    }
    public static List<Map<String,Object>> schemas() {
        return List.of(
            tool("archive_source","仅查询用户指定的个人文件库固定版本及摘要；不返回全文、不联网。先确认完整底本与使用权限。",Map.of("fileId",TEXT,"fileVersion",VERSION)),
            tool("archive_prepare","从用户明确选定的UTF-8全文固定版本生成草稿。逐字读取源文件并核对SHA-256，不由模型重写。保存operationKey用于响应丢失时恢复；不会上架。",Map.ofEntries(
                Map.entry("operationKey",TEXT),Map.entry("fileId",TEXT),Map.entry("fileVersion",VERSION),Map.entry("sourceSha256",TEXT),Map.entry("workId",TEXT),Map.entry("title",TEXT),Map.entry("author",TEXT),Map.entry("sourceName",TEXT),Map.entry("sourceRights",TEXT))),
            tool("archive_publish","仅在用户已明确要求上架且草稿来源/章节核验后调用。按草稿固定的原活动版本CAS上架；冲突不能自动覆盖。",Map.of("operationKey",TEXT,"jobId",TEXT)),
            tool("archive_read","读回该好汉的草稿/上架回执、实际活动版本和首末章摘要；只有PUBLISHED且active=true才表示当前在架。",Map.of("jobId",TEXT)),
            tool("archive_recover","按原operationKey只读恢复已提交结果；未知响应先恢复，不能换key盲重试。",Map.of("operationKey",TEXT)));
    }
    @Override public Set<String> names(){return Set.of("archive_source","archive_prepare","archive_publish","archive_read","archive_recover");}
    @Override public List<Map<String,Object>> catalog(Context context,Boundary boundary) {
        var descriptors=descriptors(context);
        try {
            var session=boundary.execute(()->authorized(context,descriptors,"catalog",false,Function.identity()));
            return schemas();
        } catch(PositionSkillException failure) {if(failure.status()!=403&&failure.status()!=409)throw failure;return List.of();}
        catch(cn.jia.chat.service.PrivateConversationToolAccess.Denied denied){return List.of();}
    }
    @Override public Object call(Context context,Boundary boundary,String name,Map<String,Object> args) {
        validate(name,args);var descriptors=descriptors(context);
        return switch(name) {
            case "archive_source" -> boundary.execute(()->authorized(context,descriptors,"catalog",false,s->source(context,args)));
            case "archive_prepare" -> prepare(context,boundary,descriptors,args);
            case "archive_publish" -> publish(context,boundary,descriptors,args);
            case "archive_read" -> boundary.execute(()->authorized(context,descriptors,"catalog",false,s->view(context,job(context,text(args,"jobId")))));
            case "archive_recover" -> boundary.execute(()->authorized(context,descriptors,"catalog",false,s->{
                String key=text(args,"operationKey");PositionSkillPolicy.key(key);
                var job=jobs.operation(owner(context),key);
                if(job==null){String id=jobs.publicationOperation(owner(context),key);if(id!=null)job=job(context,id);}
                require(job!=null,404,"ARCHIVE_OPERATION_NOT_FOUND");requireAgent(context,job);return view(context,job);
            }));
            default -> throw new IllegalArgumentException();
        };
    }
    private Object prepare(Context c,Boundary boundary,List<InstalledSkillDescriptorLookup.Descriptor> descriptors,Map<String,Object> args) {
        String work=text(args,"workId"),key=text(args,"operationKey");PositionSkillPolicy.key(key);
        require(work.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"),400,"ARCHIVE_WORK_INVALID");
        for(String field:List.of("title","author","sourceName","sourceRights"))require(text(args,field).length()<=255,400,"ARCHIVE_SOURCE_METADATA_INVALID");
        require(text(args,"sourceSha256").matches("[0-9a-f]{64}"),400,"ARCHIVE_SOURCE_DIGEST_INVALID");
        String requestDigest=ArchiveEtags.sha256(encode(new TreeMap<>(args)).getBytes(StandardCharsets.UTF_8));
        record Prepared(Session session,String edition,String expectedActive,Map<String,Object> previous) { }
        var prepared=boundary.execute(()->authorized(c,descriptors,work,false,s->{
            var prior=jobs.operation(owner(c),key,true);
            if(prior!=null){requireAgent(c,prior);require(requestDigest.equals(prior.requestDigest()),409,"ARCHIVE_IDEMPOTENCY_CONFLICT");return new Prepared(s,null,null,view(c,prior));}
            require(jobs.publicationOperation(owner(c),key,true)==null,409,"ARCHIVE_IDEMPOTENCY_CONFLICT");
            var metadata=source(c,args);require(text(args,"sourceSha256").equals(metadata.get("sha256")),409,"ARCHIVE_SOURCE_MISMATCH");
            var current=content.findWork(work);
            return new Prepared(s,"edition-ac_"+UUID.randomUUID().toString().replace("-",""),current==null?null:current.activeEditionId(),null);
        }));
        if(prepared.previous()!=null)return prepared.previous();
        // Storage and deterministic compilation happen outside native/conversation/position locks.
        var source=files.readContent(fileScope(c),text(args,"fileId"),version(args));
        byte[] bytes=source.bytes();
        ArchiveTextCompiler.Result compiled;
        try {compiled=new ArchiveTextCompiler().compile(work,prepared.edition(),text(args,"title"),text(args,"sourceName"),bytes,text(args,"sourceSha256"));}
        catch(ArchiveContentException invalid){throw new PositionSkillException(400,"ARCHIVE_SOURCE_INVALID");}
        return boundary.execute(()->authorized(c,descriptors,work,false,s->{
            require(prepared.session().equals(s),403,"ARCHIVE_AUTHORITY_CHANGED");source(c,args);
            var prior=jobs.operation(owner(c),key,true);
            if(prior!=null){requireAgent(c,prior);require(requestDigest.equals(prior.requestDigest()),409,"ARCHIVE_IDEMPOTENCY_CONFLICT");return view(c,prior);}
            require(jobs.publicationOperation(owner(c),key,true)==null,409,"ARCHIVE_IDEMPOTENCY_CONFLICT");
            String id=prepared.edition().substring("edition-".length());
            var request=new ArchiveMaintenanceService.Create(s.grant().appointmentId(),c.subject().agentId(),s.skill().skillKey(),work,text(args,"title"),text(args,"author"),text(args,"sourceName"),text(args,"sourceRights"),new String(bytes,StandardCharsets.UTF_8),ArchiveMaintenanceService.Mode.DRAFT_ONLY);
            var spec=new ArchiveMaintenanceService.Specification(request,prepared.edition(),text(args,"sourceSha256"),s.grant(),prepared.expectedActive(),s.skill().installationId(),s.skill().packageSha256(),null,text(args,"fileId"),version(args));
            jobs.insert(owner(c),new ArchiveMaintenanceStore.Job(id,key,requestDigest,c.subject().agentId(),"WAITING",encode(spec),null,null));
            require(jobs.complete(owner(c),id,"DRAFT",compiled.manifestJson(),compiled.manifestFileSha256())==1,409,"ARCHIVE_RESULT_CONFLICT");
            return view(c,job(c,id));
        }));
    }
    private Object publish(Context c,Boundary boundary,List<InstalledSkillDescriptorLookup.Descriptor> descriptors,Map<String,Object> args) {
        String id=text(args,"jobId"),key=text(args,"operationKey");PositionSkillPolicy.key(key);
        // Read immutable result bytes outside the final mutation boundary, then compare again under its lock.
        var original=boundary.execute(()->authorized(c,descriptors,"catalog",false,s->job(c,id)));
        var spec=spec(original);require(spec.sourceFileId()!=null&&spec.manifestSha256()==null,409,"ARCHIVE_LEGACY_READ_ONLY");
        require(original.resultManifest()!=null,409,"ARCHIVE_DRAFT_NOT_READY");
        var loaded=new ArchiveManifestLoader().parseAndValidate(original.resultManifest().getBytes(StandardCharsets.UTF_8),original.resultDigest());
        return boundary.execute(()->authorized(c,descriptors,spec.request().workId(),true,s->{
            require(spec.grant().appointmentId().equals(s.grant().appointmentId())&&spec.grant().appointmentRevision()==s.grant().appointmentRevision()
                    &&spec.grant().positionRevision()==s.grant().positionRevision()&&spec.grant().bindingId()==s.grant().bindingId()
                    &&spec.installationId().equals(s.skill().installationId())&&spec.packageDigest().equals(s.skill().packageSha256()),403,"ARCHIVE_AUTHORITY_CHANGED");
            require(jobs.operation(owner(c),key,true)==null,409,"ARCHIVE_IDEMPOTENCY_CONFLICT");
            String prior=jobs.publicationOperation(owner(c),key,true);
            if(prior!=null){require(prior.equals(id),409,"ARCHIVE_IDEMPOTENCY_CONFLICT");return view(c,job(c,id));}
            var current=jobs.find(owner(c),id,true);require(current!=null&&current.equals(original),409,"ARCHIVE_DRAFT_CHANGED");
            require("DRAFT".equals(current.state()),409,"ARCHIVE_DRAFT_NOT_PUBLISHABLE");
            var work=content.lockWork(spec.request().workId());
            if(work==null){
                try{content.insertWork(new ArchiveWorkRecord(spec.request().workId(),spec.request().title(),null));}
                catch(DuplicateKeyException raced){/* Compare the winner below; never replace its active edition. */}
                work=content.lockWork(spec.request().workId());
            }
            require(work!=null&&Objects.equals(work.activeEditionId(),spec.expectedActiveEdition()),409,"ARCHIVE_EDITION_CONFLICT");
            new ArchiveContentImporter(content,transactions).importAndActivate(loaded.manifest(),loaded.manifestFileSha256());
            require(jobs.publishDraft(owner(c),id)==1,409,"ARCHIVE_RESULT_CONFLICT");jobs.publicationOperation(owner(c),key,id);
            return view(c,job(c,id));
        }));
    }
    private Map<String,Object> source(Context c,Map<String,Object> args) {
        String id=text(args,"fileId");int version=version(args);
        var file=files.get(fileScope(c),id);require("ACTIVE".equals(file.file().state()),409,"ARCHIVE_SOURCE_NOT_ACTIVE");
        var metadata=files.versions(fileScope(c),id).stream().filter(v->v.version()==version).findFirst().orElse(null);
        require(metadata!=null,404,"ARCHIVE_SOURCE_NOT_FOUND");require("text/plain".equals(metadata.contentMimeType()),400,"ARCHIVE_SOURCE_MUST_BE_TEXT");
        return Map.of("fileId",id,"fileVersion",Integer.toString(version),"sha256",metadata.sha256(),"byteLength",Long.toString(metadata.byteLength()),"filename",metadata.originalFilename());
    }
    private <T>T authorized(Context c,List<InstalledSkillDescriptorLookup.Descriptor> descriptors,String work,boolean publish,Function<Session,T> operation) {
        return positions.withAuthority(scope(c),c.subject().agentId(),c.runtimeInstallationId(),c.runtimeHostId(),work,publish,descriptors,operation);
    }
    private List<InstalledSkillDescriptorLookup.Descriptor> descriptors(Context c) {
        var s=c.subject();return installed.readCurrent(new SkillCapabilityLookup.Scope(s.tenantId(),s.clientId(),s.ownerJiacn(),s.agentId())).skills().stream()
                .filter(d->d.verifiedNative()&&d.manifestSha256()==null
                    &&Objects.equals(d.runtimeInstallationId(),c.runtimeInstallationId())&&Objects.equals(d.runtimeHostId(),c.runtimeHostId())).toList();
    }
    private ArchiveMaintenanceStore.Job job(Context c,String id){PositionSkillPolicy.key(id);var job=jobs.find(owner(c),id,false);require(job!=null,404,"ARCHIVE_JOB_NOT_FOUND");requireAgent(c,job);return job;}
    private static void requireAgent(Context c,ArchiveMaintenanceStore.Job job){require(c.subject().agentId().equals(job.agentId()),403,"ARCHIVE_AGENT_FORBIDDEN");}
    private Map<String,Object> view(Context c,ArchiveMaintenanceStore.Job job) {
        requireAgent(c,job);var s=spec(job);var row=new LinkedHashMap<String,Object>();
        row.put("jobId",job.id());row.put("operationKey",job.operationKey());row.put("state",job.state());row.put("workId",s.request().workId());row.put("editionId",s.editionId());row.put("title",s.request().title());
        row.put("sourceName",s.request().sourceName());row.put("sourceRights",s.request().sourceRights());row.put("sourceSha256",s.sourceSha256());row.put("sourceFileId",s.sourceFileId());row.put("sourceFileVersion",s.sourceFileVersion()==null?null:s.sourceFileVersion().toString());row.put("manifestFileSha256",job.resultDigest());
        var work=content.findWork(s.request().workId());row.put("activeEditionId",work==null?null:work.activeEditionId());row.put("active",work!=null&&s.editionId().equals(work.activeEditionId()));
        if(job.resultManifest()!=null){var manifest=new ArchiveManifestLoader().parseAndValidate(job.resultManifest().getBytes(StandardCharsets.UTF_8),job.resultDigest()).manifest();
            row.put("chapterCount",Integer.toString(manifest.chapterCount()));row.put("paragraphCount",Integer.toString(manifest.readerParagraphCount()));row.put("sourceByteLength",Long.toString(manifest.source().utf8ByteLength()));
            row.put("firstChapter",manifest.chapters().getFirst().title());row.put("lastChapter",manifest.chapters().getLast().title());
        }
        return row;
    }
    private static void validate(String name,Map<String,Object> args) {
        var schema=schemas().stream().filter(s->name.equals(s.get("name"))).findFirst().orElseThrow(IllegalArgumentException::new);
        var fields=(Map<?,?>)((Map<?,?>)schema.get("inputSchema")).get("properties");
        require(args!=null&&args.keySet().equals(fields.keySet()),400,"BAD_REQUEST");for(String field:args.keySet())text(args,field);
    }
    private static int version(Map<String,Object> args){String value=text(args,"fileVersion");require(value.matches("[1-9][0-9]*"),400,"BAD_REQUEST");try{return Integer.parseInt(value);}catch(NumberFormatException error){throw new IllegalArgumentException();}}
    private static String text(Map<String,Object> args,String key){Object value=args.get(key);require(value instanceof String&&!((String)value).isBlank(),400,"BAD_REQUEST");return (String)value;}
    private static PositionAuthority.Scope scope(Context c){var s=c.subject();return new PositionAuthority.Scope(s.tenantId(),s.clientId(),s.ownerJiacn());}
    private static ArchiveOwnerScope owner(Context c){var s=c.subject();return new ArchiveOwnerScope(s.tenantId(),s.clientId(),s.ownerJiacn());}
    private static PersonalWorkspaceService.Scope fileScope(Context c){var s=c.subject();return new PersonalWorkspaceService.Scope(s.tenantId(),s.clientId(),s.ownerJiacn());}
    private static String encode(Object value){try{return JSON.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException("Archive state unavailable",error);}}
    private static ArchiveMaintenanceService.Specification spec(ArchiveMaintenanceStore.Job job){try{return JSON.readValue(job.specification(),ArchiveMaintenanceService.Specification.class);}catch(Exception error){throw new IllegalStateException("Archive state unavailable",error);}}
}
