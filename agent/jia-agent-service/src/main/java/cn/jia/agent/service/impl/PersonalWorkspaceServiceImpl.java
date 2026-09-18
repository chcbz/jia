package cn.jia.agent.service.impl;

import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceOperationEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.entity.PersonalWorkspaceViews;
import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.service.PersonalWorkspaceService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 1.10 UPLOAD-only personal workspace. It intentionally has no task/runtime/artifact access. */
@Named
public class PersonalWorkspaceServiceImpl implements PersonalWorkspaceService {
    private static final Set<String> MIME_TYPES = Set.of("image/png", "image/jpeg", "text/plain", "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", ".png", "image/jpeg", ".jpg", "text/plain", ".txt", "application/pdf", ".pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx");
    private static final int PAGE_SIZE = 50;
    private final PersonalWorkspaceDao dao;
    private final PersonalWorkspaceStorage storage;
    private final PersonalWorkspaceWriteService writes;
    private final PersonalWorkspaceTaskLinkDao taskLinks;
    private final PersonalWorkspaceExecutionDao executionDao;
    private final PersonalWorkspacePreviewRenderer previewRenderer = new PersonalWorkspacePreviewRenderer();

    @Inject public PersonalWorkspaceServiceImpl(PersonalWorkspaceDao dao, PersonalWorkspaceStorage storage,
            PersonalWorkspaceWriteService writes, PersonalWorkspaceTaskLinkDao taskLinks,
            PersonalWorkspaceExecutionDao executionDao) {
        this.dao=dao; this.storage=storage; this.writes=writes; this.taskLinks=taskLinks; this.executionDao=executionDao;
    }
    /** Compatibility constructor for narrow isolated unit fixtures. */
    public PersonalWorkspaceServiceImpl(PersonalWorkspaceDao dao, PersonalWorkspaceStorage storage,
            PersonalWorkspaceWriteService writes) {
        this(dao, storage, writes, null, null);
    }

    @Override public PersonalWorkspaceViews.ListView list(Scope scope, ListQuery query) {
        validateScope(scope); query= query == null ? new ListQuery(null,null,null,null) : query;
        String q=blankToNull(query.q()); if(q!=null) text(q,100);
        String family=blankToNull(query.mediaFamily()); if(family!=null && !Set.of("IMAGE","TEXT","DOCUMENT","SPREADSHEET","PRESENTATION","PDF").contains(family)) bad();
        String state=blankToNull(query.state()); if(state==null) state="ACTIVE"; if(!Set.of("ACTIVE","TRASHED").contains(state)) bad();
        Cursor cursor=parseCursor(query.cursor()); List<PersonalWorkspaceFileEntity> rows=dao.listFiles(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),q,family,state,cursor==null?null:cursor.createdAt(),cursor==null?null:cursor.fileId(),PAGE_SIZE+1);
        boolean more=rows.size()>PAGE_SIZE; if(more) rows=rows.subList(0,PAGE_SIZE); String next=more?cursor(rows.get(rows.size()-1)):null;
        return new PersonalWorkspaceViews.ListView(rows.stream().map(this::view).toList(),next);
    }
    @Override public PersonalWorkspaceViews.FileDetailView get(Scope scope,String fileId){
        PersonalWorkspaceFileEntity file=file(scope,fileId); List<PersonalWorkspaceViews.VersionView> versions=versions(scope,fileId);
        PersonalWorkspaceViews.VersionView latest=versions.stream().filter(v->v.version()==file.getLatestVersion()).findFirst().orElseThrow(()->new PersonalWorkspaceException(PersonalWorkspaceException.Reason.STORAGE_CORRUPT));
        return new PersonalWorkspaceViews.FileDetailView(view(file),latest,versions,List.of(),List.of());
    }
    @Override public List<PersonalWorkspaceViews.VersionView> versions(Scope scope,String fileId){ file(scope,fileId); return dao.listVersions(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),fileId,100).stream().map(this::version).toList(); }
    @Override public PersonalWorkspaceViews.UploadView create(Scope scope,UploadCommand command){
        validateScope(scope); ValidUpload valid=validate(command); String requestHash=hash("CREATE",valid);
        PersonalWorkspaceWriteService.Claim claim=writes.claim(writeScope(scope),"CREATE",valid.key(),requestHash);
        if(!claim.claimed()) return completedUpload(scope,claim.operation());
        String fileId="pws_"+UUID.randomUUID().toString().replace("-","");
        try{
            PersonalWorkspaceStorage.StoredObject stored=storage.store(storageScope(scope),valid.content(),valid.mime()); long now=System.currentTimeMillis();
            PersonalWorkspaceFileEntity file=new PersonalWorkspaceFileEntity().setFileId(fileId).setOwnerJiacn(scope.ownerJiacn()).setSourceKind("UPLOAD").setDisplayName(valid.displayName()).setMediaFamily(family(valid.mime())).setState("ACTIVE").setMetadataRevision(1L).setLatestVersion(1).setCreatedAt(now);
            file.setTenantId(scope.tenantId()); file.setClientId(scope.clientId());
            PersonalWorkspaceVersionEntity version=new PersonalWorkspaceVersionEntity().setFileId(fileId).setOwnerJiacn(scope.ownerJiacn()).setVersion(1).setOriginalFilename(valid.filename()).setContentMimeType(valid.mime()).setByteLength(stored.byteLength()).setContentHash(stored.sha256()).setStorageUri(stored.storageUri()).setCreatedAt(now);
            version.setTenantId(scope.tenantId()); version.setClientId(scope.clientId());
            writes.completeCreate(writeScope(scope),claim.operation(),file,version); return new PersonalWorkspaceViews.UploadView(operation(claim.operation()),view(file),version(version));
        } catch (RuntimeException failure) { writes.fail(writeScope(scope),claim.operation(),reason(failure)); throw failure; }
    }
    @Override public PersonalWorkspaceViews.UploadView appendVersion(Scope scope,String fileId,UploadCommand command,int expectedPreviousVersion){
        PersonalWorkspaceFileEntity old=file(scope,fileId); if(!"ACTIVE".equals(old.getState())||expectedPreviousVersion<1) bad(); ValidUpload valid=validate(command); String requestHash=hash("APPEND",valid,fileId,String.valueOf(expectedPreviousVersion));
        PersonalWorkspaceWriteService.Claim claim=writes.claim(writeScope(scope),"APPEND",valid.key(),requestHash); if(!claim.claimed())return completedUpload(scope,claim.operation());
        try { PersonalWorkspaceStorage.StoredObject stored=storage.store(storageScope(scope),valid.content(),valid.mime()); int next=Math.addExact(expectedPreviousVersion,1); long now=System.currentTimeMillis();
            PersonalWorkspaceVersionEntity version=new PersonalWorkspaceVersionEntity().setFileId(fileId).setOwnerJiacn(scope.ownerJiacn()).setVersion(next).setOriginalFilename(valid.filename()).setContentMimeType(valid.mime()).setByteLength(stored.byteLength()).setContentHash(stored.sha256()).setStorageUri(stored.storageUri()).setCreatedAt(now);
            version.setTenantId(scope.tenantId()); version.setClientId(scope.clientId());
            PersonalWorkspaceFileEntity file=writes.completeAppend(writeScope(scope),claim.operation(),fileId,expectedPreviousVersion,version); return new PersonalWorkspaceViews.UploadView(operation(claim.operation()),view(file),version(version));
        } catch(RuntimeException failure){writes.fail(writeScope(scope),claim.operation(),reason(failure));throw failure;}
    }
    @Override public PersonalWorkspaceViews.FileView rename(Scope scope,String fileId,String displayName,String ifMatch,Idempotency idempotency){
        validateScope(scope); file(scope,fileId); text(displayName,255); String key=key(idempotency); PersonalWorkspaceWriteService.Claim c=writes.claim(writeScope(scope),"RENAME",key,hash("RENAME",fileId,displayName,ifMatch)); if(!c.claimed())return completedFile(scope,c.operation()); try{return view(writes.rename(writeScope(scope),c.operation(),fileId,displayName,ifMatch));}catch(RuntimeException f){writes.fail(writeScope(scope),c.operation(),reason(f));throw f;}}
    @Override public PersonalWorkspaceViews.FileView trash(Scope scope,String fileId,long impactRevision,boolean acknowledge,String ifMatch,Idempotency idempotency){
        validateScope(scope); file(scope,fileId); String key=key(idempotency); PersonalWorkspaceWriteService.Claim c=writes.claim(writeScope(scope),"TRASH",key,hash("TRASH",fileId,String.valueOf(impactRevision),String.valueOf(acknowledge),ifMatch)); if(!c.claimed())return completedFile(scope,c.operation()); try{return view(writes.trash(writeScope(scope),c.operation(),fileId,impactRevision,acknowledge,ifMatch));}catch(RuntimeException f){writes.fail(writeScope(scope),c.operation(),reason(f));throw f;}}
    @Override public PersonalWorkspaceViews.FileView restore(Scope scope,String fileId,String ifMatch,Idempotency idempotency){
        validateScope(scope); file(scope,fileId); String key=key(idempotency); PersonalWorkspaceWriteService.Claim c=writes.claim(writeScope(scope),"RESTORE",key,hash("RESTORE",fileId,ifMatch)); if(!c.claimed())return completedFile(scope,c.operation()); try{return view(writes.restore(writeScope(scope),c.operation(),fileId,ifMatch));}catch(RuntimeException f){writes.fail(writeScope(scope),c.operation(),reason(f));throw f;}}
    @Override public Content readContent(Scope scope,String fileId,int version){PersonalWorkspaceVersionEntity v=versionEntity(scope,fileId,version);PersonalWorkspaceStorage.StoredContent c=storage.read(storageScope(scope),v.getStorageUri(),v.getContentHash(),v.getByteLength(),v.getContentMimeType());return new Content(v.getOriginalFilename(),v.getContentMimeType(),c.content());}
    @Override public PersonalWorkspaceViews.PreviewView preview(Scope scope,String fileId,int version){return renderPreview(scope,fileId,version).view();}
    @Override public Content readPreviewPart(Scope scope,String fileId,int version,String partId){if(!PersonalWorkspacePreviewRenderer.CONTENT_PART_ID.equals(partId))throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.NOT_FOUND);PersonalWorkspacePreviewRenderer.RenderedPreview preview=renderPreview(scope,fileId,version);if(!"READY".equals(preview.view().state()))throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.UNSUPPORTED);PersonalWorkspaceViews.PreviewPart part=preview.view().parts().get(0);PersonalWorkspaceVersionEntity v=versionEntity(scope,fileId,version);return new Content(v.getOriginalFilename(),part.contentMimeType(),preview.bytes());}
    @Override public PersonalWorkspaceViews.UsageView usage(Scope scope,String fileId){
        PersonalWorkspaceFileEntity f=file(scope,fileId);
        List<PersonalWorkspaceViews.TaskReferenceView> references=taskLinks==null?List.of():taskLinks
                .listActiveByFile(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),fileId,100)
                .stream().map(link->new PersonalWorkspaceViews.TaskReferenceView(link.getTaskId(),
                        link.getRelationId(),link.getFileVersion(),link.getLinkRole(),
                        link.getRelationRevision(),link.getCreatedAt())).toList();
        List<Object> activeExecutions=executionDao==null?List.of():executionDao
                .listActiveExecutionIdsByFile(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),fileId,100)
                .stream().map(executionId -> (Object) new ActiveExecutionView(executionId)).toList();
        return new PersonalWorkspaceViews.UsageView(f.getMetadataRevision(),references,activeExecutions);
    }
    @Override public PersonalWorkspaceViews.OperationView operation(Scope scope,String operationId){validateScope(scope);PersonalWorkspaceOperationEntity o=dao.findOperation(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),operationId);if(o==null)throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.NOT_FOUND);return operation(o);}

    private PersonalWorkspacePreviewRenderer.RenderedPreview renderPreview(Scope scope,String fileId,int version){PersonalWorkspaceVersionEntity v=versionEntity(scope,fileId,version);Content source=readContent(scope,fileId,version);return previewRenderer.render(v.getContentMimeType(),source.bytes());}
    private PersonalWorkspaceViews.UploadView completedUpload(Scope s,PersonalWorkspaceOperationEntity o){if("PROCESSING".equals(o.getState()))throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.PROCESSING);if(!"COMMITTED".equals(o.getState()))throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.OPERATION_FAILED);PersonalWorkspaceFileEntity f=file(s,o.getFileId());PersonalWorkspaceVersionEntity v=versionEntity(s,o.getFileId(),o.getFileVersion());return new PersonalWorkspaceViews.UploadView(operation(o),view(f),version(v));}
    private PersonalWorkspaceViews.FileView completedFile(Scope s,PersonalWorkspaceOperationEntity o){if("PROCESSING".equals(o.getState()))throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.PROCESSING);if(!"COMMITTED".equals(o.getState()))throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.OPERATION_FAILED);return view(file(s,o.getFileId()));}
    private PersonalWorkspaceFileEntity file(Scope s,String id){validateScope(s);id(id,"fileId",100);PersonalWorkspaceFileEntity f=dao.findFile(s.tenantId(),s.clientId(),s.ownerJiacn(),id);if(f==null)throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.NOT_FOUND);return f;}
    private PersonalWorkspaceVersionEntity versionEntity(Scope s,String id,int n){file(s,id);if(n<1)bad();PersonalWorkspaceVersionEntity v=dao.findVersion(s.tenantId(),s.clientId(),s.ownerJiacn(),id,n);if(v==null)throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.NOT_FOUND);return v;}
    private PersonalWorkspaceViews.FileView view(PersonalWorkspaceFileEntity f){return new PersonalWorkspaceViews.FileView(f.getFileId(),f.getSourceKind(),f.getDisplayName(),f.getMediaFamily(),f.getState(),f.getMetadataRevision(),f.getLatestVersion(),f.getCreatedAt(),new PersonalWorkspaceViews.Capabilities("AVAILABLE","AVAILABLE","UNVERIFIED",previewCapability(f),"AVAILABLE"));}
    private static String previewCapability(PersonalWorkspaceFileEntity f){return Set.of("IMAGE","TEXT").contains(f.getMediaFamily())?"AVAILABLE":"UNSUPPORTED";}
    private PersonalWorkspaceViews.VersionView version(PersonalWorkspaceVersionEntity v){return new PersonalWorkspaceViews.VersionView(v.getFileId(),v.getVersion(),v.getOriginalFilename(),v.getContentMimeType(),v.getByteLength(),v.getContentHash(),v.getCreatedAt(),Set.of("image/png","image/jpeg","text/plain").contains(v.getContentMimeType())?"READY":"UNSUPPORTED");}
    private static PersonalWorkspaceViews.OperationView operation(PersonalWorkspaceOperationEntity o){return new PersonalWorkspaceViews.OperationView(o.getOperationId(),o.getState(),o.getFileId(),o.getFileVersion(),o.getErrorCode());}
    private static PersonalWorkspaceWriteService.Scope writeScope(Scope s){return new PersonalWorkspaceWriteService.Scope(s.tenantId(),s.clientId(),s.ownerJiacn());}
    private static PersonalWorkspaceStorage.Scope storageScope(Scope s){return new PersonalWorkspaceStorage.Scope(s.tenantId(),s.clientId(),s.ownerJiacn());}
    private static ValidUpload validate(UploadCommand c){if(c==null)bad();String key=key(c.idempotency());String mime=lower(c.contentMimeType());if(!MIME_TYPES.contains(mime))throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.UNSUPPORTED);String name=filename(c.originalFilename(),mime);String display=blankToNull(c.displayName());if(display==null)display=name;text(display,255);byte[] bytes=c.content();if(bytes==null||bytes.length==0)bad();return new ValidUpload(key,display,name,mime,bytes);}
    private static String filename(String raw,String mime){text(raw,255);String base=raw.replace('\\','/');if(base.contains("/"))bad();String ext=EXTENSIONS.get(mime);if(!base.toLowerCase(Locale.ROOT).endsWith(ext))throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.UNSUPPORTED);return base;}
    private static String family(String mime){if(mime.startsWith("image/"))return "IMAGE";if("text/plain".equals(mime))return "TEXT";if("application/pdf".equals(mime))return "PDF";if(mime.contains("spreadsheet"))return "SPREADSHEET";if(mime.contains("presentation"))return "PRESENTATION";return "DOCUMENT";}
    private static String key(Idempotency i){if(i==null)bad();id(i.key(),"Idempotency-Key",100);return i.key();}
    private static void validateScope(Scope s){if(s==null||!"0".equals(s.tenantId()))bad();id(s.clientId(),"clientId",50);id(s.ownerJiacn(),"owner",50);if("0".equals(s.ownerJiacn()))bad();}
    private static void id(String value,String name,int max){if(value==null||value.isBlank()||!value.equals(value.strip())||value.codePointCount(0,value.length())>max||value.chars().anyMatch(Character::isISOControl))bad();}
    private static void text(String value,int max){id(value,"text",max);}
    private static String lower(String v){return v==null?null:v.toLowerCase(Locale.ROOT);}
    private static String blankToNull(String value){return value==null||value.isBlank()?null:value;}
    private static String hash(String... values){try{MessageDigest d=MessageDigest.getInstance("SHA-256");for(String v:values){byte[]b=Objects.requireNonNullElse(v,"").getBytes(StandardCharsets.UTF_8);d.update(ByteBuffer.allocate(4).putInt(b.length).array());d.update(b);}return HexFormat.of().formatHex(d.digest());}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(bytes,"bytes")));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String hash(String type,ValidUpload v,String... rest){List<String> p=new ArrayList<>();p.add(type);p.add(v.key());p.add(v.displayName());p.add(v.filename());p.add(v.mime());p.add(hash(v.content()));p.addAll(List.of(rest));return hash(p.toArray(String[]::new));}
    private static String reason(RuntimeException f){return f instanceof PersonalWorkspaceException p?p.getReason().name():"STORAGE_UNAVAILABLE";}
    private static String cursor(PersonalWorkspaceFileEntity f){return Base64.getUrlEncoder().withoutPadding().encodeToString((f.getCreatedAt()+"\n"+f.getFileId()).getBytes(StandardCharsets.UTF_8));}
    private static Cursor parseCursor(String value){if(value==null||value.isBlank())return null;try{String s=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);String[]p=s.split("\\n",-1);if(p.length!=2)bad();long t=Long.parseLong(p[0]);id(p[1],"cursor",100);return new Cursor(t,p[1]);}catch(IllegalArgumentException e){bad();return null;}}
    private static void bad(){throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.BAD_REQUEST);}
    public record ActiveExecutionView(String executionId) { }
    private record ValidUpload(String key,String displayName,String filename,String mime,byte[] content) { }
    private record Cursor(long createdAt,String fileId) { }
}
