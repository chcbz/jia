package cn.jia.chat.service;

import cn.jia.agent.service.AgentSelectedOutputFinalizationService;
import cn.jia.agent.service.AgentTaskArtifactContentService;
import cn.jia.agent.service.AgentTaskFormalDeliveryReadService;
import cn.jia.agent.service.SelectedOutputFinalizationDigest;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Fixed artifact retention is available only through a committed message-revision lineage. */
@Service
@ConditionalOnProperty(prefix="chat.delivery-review",name="enabled",havingValue="true")
public class ChatFormalDeliveryRetentionService {
    public record Lineage(ChatDeliveryRevisionService.BoundRevision revision,ChatDeliveryReviewContract.Receipt receipt) { }
    private final JdbcTemplate jdbc;
    private final ChatDeliberationDao dao;
    private final ChatTypedDeliberationStore typed;
    private final ObjectProvider<ChatActionFinalService> finals;
    private final AgentTaskFormalDeliveryReadService deliveries;
    private final AgentTaskArtifactContentService contents;
    public ChatFormalDeliveryRetentionService(JdbcTemplate jdbc,ChatDeliberationDao dao,ChatTypedDeliberationStore typed,
            ObjectProvider<ChatActionFinalService> finals,AgentTaskFormalDeliveryReadService deliveries,AgentTaskArtifactContentService contents) {
        this.jdbc=jdbc;this.dao=dao;this.typed=typed;this.finals=finals;this.deliveries=deliveries;this.contents=contents;
    }
    /** Follows explicit persisted ancestry only; never scans attachments or chooses a latest batch. */
    public Lineage lineage(ChatTypedDeliberationStore.Scope scope,String task,String request) {
        Set<String> seen=new HashSet<>();
        while(request!=null) {
            if(!seen.add(request))throw unavailable();
            var receipts=jdbc.query("""
                SELECT receipt_json,request_sha256,parent_outcome_id,parent_final_digest,source_request_id,source_message_id,target_artifact_ids_json,batch_sha256
                FROM chat_task_delivery_review_operation WHERE BINARY tenant_id=BINARY ?
                 AND BINARY owner_jiacn=BINARY ? AND BINARY client_id=BINARY ? AND BINARY task_id=BINARY ?
                 AND BINARY child_request_id=BINARY ? AND origin='MESSAGE_REVISION' AND state='COMMITTED'
                """,(r,n)->Map.of("receipt",r.getString(1),"digest",r.getString(2),"outcome",r.getString(3),"final",r.getString(4),
                        "request",r.getString(5),"message",r.getString(6),"targets",r.getString(7),"batch",r.getString(8)),
                        scope.tenantId(),scope.ownerJiacn(),scope.clientId(),task,request);
            if(receipts.size()>1)throw unavailable();
            if(receipts.size()==1) {
                var stored=receipts.getFirst();var receipt=ChatDeliveryReviewContract.receipt(stored.get("receipt"));
                if(!scope.conversationId().equals(receipt.conversationId()))throw unavailable();
                var parent=typed.findOutcomeByRequest(scope,receipt.sourceRequestId());if(parent==null || !task.equals(parent.taskId()))throw unavailable();
                var event=dao.findOutboxById(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),ChatDeliveryRevisionService.eventId(parent));
                var revision=finals.getObject().loadRevision(event);
                if(!revision.basis().deliveryId().equals(receipt.deliveryId()) || !"REQUEST_CHANGES".equals(receipt.decision())
                        || !ChatDeliveryRevisionService.digest(revision).equals(stored.get("digest")) || !parent.outcomeId().equals(stored.get("outcome"))
                        || !parent.finalDigest().equals(stored.get("final")) || !parent.requestId().equals(stored.get("request"))
                        || !revision.basis().sourceMessageId().equals(stored.get("message")) || !revision.basis().batchSha256().equals(stored.get("batch"))
                        || !ChatDeliveryReviewContract.json(revision.parent().validated().interactionOutcome().revision().targetArtifactIds())
                            .equals(CanonicalContextJson.write(cn.jia.core.util.JsonUtil.getMapper().readValue(stored.get("targets"),List.class))))throw unavailable();
                return new Lineage(revision,receipt);
            }
            var admission=typed.findAdmissionByRequest(scope,request);
            if(admission!=null && admission.parentOutcomeId()!=null) {
                if(!task.equals(admission.taskId()) || !scope.equals(admission.scope()))throw unavailable();
                var ancestor=typed.findOutcome(scope,admission.parentOutcomeId(),false);
                if(ancestor==null || !task.equals(ancestor.taskId()) || !scope.equals(ancestor.scope())
                        || admission.assignmentRevision()!=ancestor.assignmentRevision())throw unavailable();
                request=ancestor.requestId();continue;
            }
            var row=typed.findOutcomeByRequest(scope,request);if(row==null)return null;
            if(!task.equals(row.taskId()))throw unavailable();
            var turn=dao.findTurn(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),row.turnId());
            var snapshot=turn==null?null:dao.findSnapshot(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),turn.getSnapshotId());
            if(snapshot==null)throw unavailable();var facts=map(cn.jia.core.util.JsonUtil.getMapper().readValue(snapshot.getFactsManifestJson(),Map.class));
            var continuation=facts.get("actionContinuation");
            if(continuation instanceof Map<?,?> c && c.get("parentRequestId") instanceof String id) {request=id;continue;}
            var frozenAdmission=facts.get("typedDeliberationAdmission");
            if(frozenAdmission instanceof Map<?,?> a && a.get("deliveryParent") instanceof Map<?,?> p && p.get("outcomeId") instanceof String id) {
                var parent=typed.findOutcome(scope,id,false);if(parent==null || !Objects.equals(parent.finalDigest(),p.get("finalDigest")))throw unavailable();request=parent.requestId();continue;
            }
            return null;
        }
        return null;
    }
    /** Prepared outside task locks, then frozen into the fresh child admission. No public selector. */
    public List<Map<String,Object>> prepareRevisionSources(ChatDeliveryRevisionService.BoundRevision revision) {
        var scope=revision.parent().outcome().scope();var basis=revision.basis();
        var contextScope=new ChatTypedDeliberationContextService.Scope(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),scope.conversationId(),scope.conversationGeneration());
        var catalog=new ArrayList<Map<String,Object>>();
        for(var item:basis.items())if("RESULT".equals(item.displayRole())
                && revision.parent().validated().interactionOutcome().revision().targetArtifactIds().contains(item.artifactId())) {
            try(var content=contents.openContentForTaskOwner(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),basis.taskId(),item.artifactId(),Integer.parseInt(item.artifactVersion()))) {
            verifyContent(item,content);
            var identity=Map.<String,Object>of("kind","FORMAL_ARTIFACT","sourceDeliveryId",basis.deliveryId(),
                    "sourceArtifactId",item.artifactId(),"sourceArtifactVersion",item.artifactVersion(),
                    "sourceRequestId",basis.sourceRequestId(),"parentFinalDigest",revision.parent().outcome().finalDigest());
            var source=new LinkedHashMap<String,Object>();source.put("sourceKind","FORMAL_ARTIFACT");
            source.put("selectorKind","FORMAL_ARTIFACT");source.put("sourceIdentity",identity);
            String mime=content.contentMimeType();source.put("mediaType",mime.startsWith("image/")?"image":mime.startsWith("audio/")?"audio":mime.startsWith("text/")?"text":"file");
            source.put("contentMimeType",mime);source.put("contentHash",item.contentHash());source.put("byteLength",String.valueOf(content.byteLength()));
            source.put("parentRequestId",basis.sourceRequestId());source.put("parentStepId",null);
            source.put("sourceRefId",ChatTypedDeliberationContextService.sourceRefId(contextScope,source));
            catalog.add(Collections.unmodifiableMap(source));
            } catch(java.io.IOException failure) {throw unavailable();}
        }
        return List.copyOf(catalog);
    }
    /** Current ACL plus exact committed revision authority; never a browser-provided source. */
    ChatDeliveryRevisionContract.Item authorizeRevisionMaterial(
            cn.jia.agent.service.PersonalWorkspaceExecutionService.OwnerScope scope,String task,String conversation,long generation,Map<String,Object> input) {
        var identity=ChatSkillSourceAccessService.object(input.get("sourceIdentity"));
        if(!identity.keySet().equals(Set.of("kind","sourceDeliveryId","sourceArtifactId","sourceArtifactVersion","sourceRequestId","parentFinalDigest"))
                || !"FORMAL_ARTIFACT".equals(identity.get("kind")))throw unavailable();
        var s=new ChatTypedDeliberationStore.Scope(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),conversation,generation);
        var rows=jdbc.query("""
            SELECT child_request_id FROM chat_task_delivery_review_operation WHERE BINARY tenant_id=BINARY ?
             AND BINARY owner_jiacn=BINARY ? AND BINARY client_id=BINARY ? AND BINARY task_id=BINARY ?
             AND BINARY source_request_id=BINARY ? AND BINARY parent_final_digest=BINARY ?
             AND origin='MESSAGE_REVISION' AND state='COMMITTED'
            """,(r,n)->r.getString(1),scope.tenantId(),scope.ownerJiacn(),scope.clientId(),task,identity.get("sourceRequestId"),identity.get("parentFinalDigest"));
        if(rows.size()!=1)throw unavailable();var lineage=lineage(s,task,rows.getFirst());if(lineage==null)throw unavailable();
        var basis=lineage.revision().basis();if(!basis.deliveryId().equals(identity.get("sourceDeliveryId")))throw unavailable();
        var item=basis.items().stream().filter(x->"RESULT".equals(x.displayRole()) && x.artifactId().equals(identity.get("sourceArtifactId"))
                && x.artifactVersion().equals(identity.get("sourceArtifactVersion")) && x.contentHash().equals(input.get("sha256"))).findFirst().orElseThrow(ChatFormalDeliveryRetentionService::unavailable);
        var batch=deliveries.listForTaskOwner(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task).stream()
                .filter(x->basis.deliveryId().equals(x.getDeliveryId())).findFirst().orElseThrow(ChatFormalDeliveryRetentionService::unavailable);
        if(!"changes_requested".equals(batch.getState()) || !lineage.receipt().deliveryVersion().equals(String.valueOf(batch.getDeliveryVersion())))throw unavailable();
        if(batch.getItems().stream().noneMatch(x->item.artifactId().equals(x.getArtifactId()) && item.artifactVersion().equals(String.valueOf(x.getArtifactVersion()))
                && item.contentHash().equals(x.getContentHash())))throw unavailable();
        return item;
    }
    public cn.jia.agent.service.PersonalWorkspaceExecutionService.RuntimeDownload openRevisionMaterial(
            cn.jia.agent.service.PersonalWorkspaceExecutionService.OwnerScope scope,String task,String conversation,long generation,Map<String,Object> input) {
        var item=authorizeRevisionMaterial(scope,task,conversation,generation,input);
        var content=contents.openContentForTaskOwner(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task,item.artifactId(),Integer.parseInt(item.artifactVersion()));
        try {
            if(!item.artifactId().equals(content.artifactId())||!item.artifactVersion().equals(String.valueOf(content.artifactVersion()))
                    ||!item.contentHash().equals(content.sha256())||!content.contentMimeType().equals(input.get("contentMimeType"))
                    ||content.byteLength()!=ChatSkillSourceAccessService.decimal(input.get("byteLength")))throw unavailable();
            authorizeRevisionMaterial(scope,task,conversation,generation,input);
            return new cn.jia.agent.service.PersonalWorkspaceExecutionService.RuntimeDownload("formal-artifact",content.contentMimeType(),content.sha256(),content.byteLength(),content.content());
        } catch(RuntimeException | Error rejected) {
            try {content.close();} catch(java.io.IOException closeFailure) {rejected.addSuppressed(closeFailure);}
            throw rejected;
        }
    }

    private static void verifyContent(ChatDeliveryRevisionContract.Item item,cn.jia.agent.service.AgentTaskArtifactContentService.StreamContent content) {
        // The artifact boundary has already verified bytes; lineage checks only the fixed identity.
        if(content==null||!item.artifactId().equals(content.artifactId())||!item.artifactVersion().equals(String.valueOf(content.artifactVersion()))
                ||!item.contentHash().equals(content.sha256())||content.contentMimeType()==null)throw unavailable();
    }

    public void requireReplacementProof(ChatTypedDeliberationStore.Scope scope,String task,Lineage lineage,
            List<ChatSelectedOutputFinalizationStore.Selection> fresh) {
        if(lineage==null || fresh==null || fresh.isEmpty())throw unavailable();
        var parent=lineage.revision().parent().outcome();
        var targets=new HashSet<>(lineage.revision().parent().validated().interactionOutcome().revision().targetArtifactIds());
        var replaced=new HashSet<String>();
        for(var selection:fresh) {
            if(selection==null || selection.formalArtifactSource()!=null)throw unavailable();
            var producer=lineage(scope,task,selection.requestId());
            if(producer==null || !parent.outcomeId().equals(producer.revision().parent().outcome().outcomeId())
                    || !parent.finalDigest().equals(producer.revision().parent().outcome().finalDigest())
                    || !lineage.receipt().operationId().equals(producer.receipt().operationId()))throw unavailable();
            var admission=typed.findAdmissionByRequest(scope,selection.requestId());
            if(admission==null || !scope.equals(admission.scope()) || !task.equals(admission.taskId())
                    || parent.assignmentRevision()!=admission.assignmentRevision())throw unavailable();
            var inputs=ChatTypedDeliberationContextService.parseCatalog(admission.sourceCatalogJson()).stream()
                    .filter(x->"FORMAL_ARTIFACT".equals(x.get("sourceKind"))).toList();
            var evidence=new LinkedHashMap<String,Map<String,Object>>();
            for(var input:inputs) {
                var identity=ChatSkillSourceAccessService.object(input.get("sourceIdentity"));
                if(!parent.finalDigest().equals(identity.get("parentFinalDigest")) || !parent.requestId().equals(identity.get("sourceRequestId"))
                        || !lineage.revision().basis().deliveryId().equals(identity.get("sourceDeliveryId")))throw unavailable();
                String target=ChatSkillSourceAccessService.text(identity,"sourceArtifactId");
                var fixed=lineage.revision().basis().items().stream().filter(x->x.artifactId().equals(target) && "RESULT".equals(x.displayRole())).findFirst().orElseThrow(ChatFormalDeliveryRetentionService::unavailable);
                if(!fixed.artifactVersion().equals(identity.get("sourceArtifactVersion")) || !fixed.contentHash().equals(input.get("contentHash"))
                        || !targets.contains(target) || evidence.put(target,input)!=null)throw unavailable();
            }
            // More than one frozen target per output is not an item-level replacement proof.
            if(evidence.size()!=1)throw unavailable();var target=evidence.keySet().iterator().next();
            if(selection.messageSource()!=null && !((String)evidence.get(target).get("contentMimeType")).startsWith("text/"))throw unavailable();
            if(!replaced.add(target))throw unavailable();
        }
        if(!replaced.equals(targets))throw unavailable();
    }

    public List<ChatSelectedOutputFinalizationStore.Selection> retain(Lineage lineage,List<ChatSelectedOutputFinalizationStore.Selection> fresh) {
        if(lineage==null)return fresh;
        var basis=lineage.revision().basis();var targets=lineage.revision().parent().validated().interactionOutcome().revision().targetArtifactIds();
        var result=new ArrayList<ChatSelectedOutputFinalizationStore.Selection>();
        for(var item:basis.items())if("RESULT".equals(item.displayRole()) && !targets.contains(item.artifactId())) {
            var source=new SelectedOutputFinalizationDigest.FormalArtifactSource(basis.deliveryId(),item.artifactId(),Integer.parseInt(item.artifactVersion()));
            result.add(new ChatSelectedOutputFinalizationStore.Selection(null,null,null,item.contentHash(),"保留成果 "+item.artifactId(),item.purpose(),null,source));
        }
        result.addAll(fresh);return List.copyOf(result);
    }
    public AgentSelectedOutputFinalizationService.SourceOutput read(ChatSelectedOutputFinalizationService.Scope scope,String task,
            Lineage lineage,ChatSelectedOutputFinalizationService.Selection selection) {
        if(lineage==null || !task.equals(lineage.revision().basis().taskId()))throw unavailable();
        var parent=lineage.revision().parent().outcome();var owner=parent.scope();
        if(!scope.tenantId().equals(owner.tenantId()) || !scope.clientId().equals(owner.clientId()) || !scope.ownerJiacn().equals(owner.ownerJiacn()))throw unavailable();
        var formal=selection.formalArtifactSource();var basis=lineage.revision().basis();
        if(formal==null || !basis.deliveryId().equals(formal.sourceDeliveryId()))throw unavailable();
        var frozen=basis.items().stream().filter(i->"RESULT".equals(i.displayRole()) && i.artifactId().equals(formal.sourceArtifactId())
                && i.artifactVersion().equals(Integer.toString(formal.sourceArtifactVersion())) && i.contentHash().equals(selection.sha256())
                && i.purpose().equals(selection.purpose())).findFirst().orElseThrow(ChatFormalDeliveryRetentionService::unavailable);
        if(lineage.revision().parent().validated().interactionOutcome().revision().targetArtifactIds().contains(frozen.artifactId()))throw unavailable();
        var batch=deliveries.listForTaskOwner(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task).stream()
                .filter(d->basis.deliveryId().equals(d.getDeliveryId())).findFirst().orElseThrow(ChatFormalDeliveryRetentionService::unavailable);
        if(!"changes_requested".equals(batch.getState()) || !lineage.receipt().deliveryVersion().equals(String.valueOf(batch.getDeliveryVersion()))
                || batch.getItems().stream().noneMatch(i->frozen.artifactId().equals(i.getArtifactId())
                    && frozen.artifactVersion().equals(String.valueOf(i.getArtifactVersion())) && frozen.contentHash().equals(i.getContentHash())))throw unavailable();
        try(var content=contents.openContentForTaskOwner(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task,formal.sourceArtifactId(),formal.sourceArtifactVersion())) {
            verifyContent(frozen,content);
            return new AgentSelectedOutputFinalizationService.SourceOutput(null,null,null,null,null,selection.sha256(),content.contentMimeType(),content.byteLength(),
                    selection.title(),selection.purpose(),null,null,formal,
                    ()->openRetained(scope,task,formal,selection.sha256(),content.contentMimeType(),content.byteLength()));
        } catch(java.io.IOException failure) {throw unavailable();}
    }
    private java.io.InputStream openRetained(ChatSelectedOutputFinalizationService.Scope scope,String task,
            cn.jia.agent.service.SelectedOutputFinalizationDigest.FormalArtifactSource formal,String hash,String mime,long length) {
        var content=contents.openContentForTaskOwner(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task,formal.sourceArtifactId(),formal.sourceArtifactVersion());
        try {
            if(!formal.sourceArtifactId().equals(content.artifactId())||formal.sourceArtifactVersion()!=content.artifactVersion()
                    ||!hash.equals(content.sha256())||!mime.equals(content.contentMimeType())||length!=content.byteLength())throw unavailable();
            return content.content();
        } catch(RuntimeException | Error rejected) {
            try {content.close();} catch(java.io.IOException closeFailure) {rejected.addSuppressed(closeFailure);}
            throw rejected;
        }
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) {return (Map<String,Object>)value;}
    private static ChatDeliveryReviewContract.SourceUnavailable unavailable() {return new ChatDeliveryReviewContract.SourceUnavailable();}
}
