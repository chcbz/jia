package cn.jia.chat.service;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.agent.service.TaskLinkedMaterialReader;
import cn.jia.agent.service.SkillExecutionService;
import cn.jia.agent.service.SkillSourceAccessService;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.ChatConversationEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Chat-owned exact lineage/current-read adapter. No format-specific permission or fee branch. */
@Service
public final class ChatSkillSourceAccessService implements SkillSourceAccessService {
    private org.springframework.beans.factory.ObjectProvider<ChatFormalDeliveryRetentionService> formalSources;
    @org.springframework.beans.factory.annotation.Autowired
    public void setFormalSources(org.springframework.beans.factory.ObjectProvider<ChatFormalDeliveryRetentionService> value) {formalSources=value;}
    private ChatFormalDeliveryRetentionService formalSources() {
        var service=formalSources==null?null:formalSources.getIfAvailable();if(service==null)throw missing();return service;
    }
    private final ChatConversationDao conversations;
    private final JuyitingConversationScopeService scopes;
    private final ChatConversationArchiveStore archive;
    private final PersonalWorkspaceExecutionService files;
    private final SkillExecutionService skills;
    private final JdbcTemplate jdbc;
    private final TaskLinkedMaterialReader materials;
    public ChatSkillSourceAccessService(ChatConversationDao conversations,JuyitingConversationScopeService scopes,
            ChatConversationArchiveStore archive,@org.springframework.context.annotation.Lazy PersonalWorkspaceExecutionService files,
            @org.springframework.context.annotation.Lazy SkillExecutionService skills,JdbcTemplate jdbc,TaskLinkedMaterialReader materials) {
        this.conversations=conversations;this.scopes=scopes;this.archive=archive;this.files=files;this.skills=skills;this.jdbc=jdbc;this.materials=materials;
    }
    @Override public void requireAuthorized(PersonalWorkspaceExecutionService.OwnerScope scope,String task,
            String conversation,long generation,Map<String,Object> input) {
        String target=conversation(scope,task,conversation,generation);
        var identity=object(input.get("sourceIdentity"));String kind=(String)input.get("sourceKind");
        if("TASK_LINKED_WORKSPACE_VERSION".equals(kind)) {workspace(scope,task,identity,input);}
        else if("CURRENT_CONVERSATION_ASSET".equals(kind)) {
            var source=asset(scope,task,conversation,generation,target,identity);
            if(!source.contentMimeType().equals(input.get("contentMimeType"))||!source.sha256().equals(input.get("sha256"))
                    ||source.byteLength()!=decimal(input.get("byteLength"))||!identity.equals(assetIdentity(source)))throw missing();
        }else if("EXECUTION_RESULT".equals(kind)) {
            var content=result(scope,task,conversation,generation,identity);
            if(!"application/json".equals(input.get("contentMimeType"))||content.length!=decimal(input.get("byteLength"))
                    ||!sha(content).equals(input.get("sha256")))throw missing();
        }else if("FORMAL_ARTIFACT".equals(kind)) {
            formalSources().authorizeRevisionMaterial(scope,task,conversation,generation,input);
        }else throw missing();
    }
    @Override public PersonalWorkspaceExecutionService.RuntimeDownload open(PersonalWorkspaceExecutionService.OwnerScope scope,
            String task,String conversation,long generation,Map<String,Object> input) {
        requireAuthorized(scope,task,conversation,generation,input);
        var identity=object(input.get("sourceIdentity"));
        PersonalWorkspaceExecutionService.RuntimeDownload content;
        if("TASK_LINKED_WORKSPACE_VERSION".equals(input.get("sourceKind"))) {
            try {
                var stream=materials.open(new PersonalWorkspaceTaskLinkService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),
                        task,selection(identity),expected(input));
                content=new PersonalWorkspaceExecutionService.RuntimeDownload("material",text(input,"contentMimeType"),text(input,"sha256"),decimal(input.get("byteLength")),stream);
            } catch(TaskLinkedMaterialReader.Failure error) {throw materialFailure(error);}
        } else if("CURRENT_CONVERSATION_ASSET".equals(input.get("sourceKind"))) {
            var source=asset(scope,task,conversation,generation,conversation(scope,task,conversation,generation),identity);
            var output=files.openConversationOutput(scope,task,source.runId(),source.outputId());
            try {
                if(!source.executionId().equals(output.executionId())||!source.outputId().equals(output.outputId()))throw missing();
                content=new PersonalWorkspaceExecutionService.RuntimeDownload(output.originalFilename(),output.contentMimeType(),output.sha256(),output.byteLength(),output.content());
            } catch(RuntimeException | Error rejected) {
                try {output.close();} catch(java.io.IOException closeFailure) {rejected.addSuppressed(closeFailure);}
                throw rejected;
            }
        } else if("FORMAL_ARTIFACT".equals(input.get("sourceKind"))) {
            content=formalSources().openRevisionMaterial(scope,task,conversation,generation,input);
        } else {
            byte[] bytes=result(scope,task,conversation,generation,identity);
            content=new PersonalWorkspaceExecutionService.RuntimeDownload("execution-result.json","application/json",sha(bytes),bytes.length,new java.io.ByteArrayInputStream(bytes));
        }
        try {
            if(!content.contentMimeType().equals(input.get("contentMimeType"))||!content.sha256().equals(input.get("sha256"))
                    ||content.byteLength()!=decimal(input.get("byteLength")))throw conflict();
            requireAuthorized(scope,task,conversation,generation,input);
            return content;
        } catch(RuntimeException | Error rejected) {
            try {content.close();} catch(java.io.IOException closeFailure) {rejected.addSuppressed(closeFailure);}
            throw rejected;
        }
    }

    private void workspace(PersonalWorkspaceExecutionService.OwnerScope s,String task,Map<String,Object> identity,Map<String,Object> input) {
        var selection=selection(identity);
        try {
            var actual=materials.metadata(new PersonalWorkspaceTaskLinkService.Scope(s.tenantId(),s.clientId(),s.ownerJiacn()),task,selection);
            if(!actual.equals(expected(input)))throw missing();
        } catch (TaskLinkedMaterialReader.Failure error) { throw materialFailure(error); }
    }
    private static TaskLinkedMaterialReader.Selection selection(Map<String,Object> identity) {
        if(!identity.keySet().equals(Set.of("kind","fileId","version","purpose"))||!"TASK_LINKED_WORKSPACE_VERSION".equals(identity.get("kind"))
                ||!Set.of("INPUT","REFERENCE").contains(identity.get("purpose")))throw missing();
        long version=decimal(identity.get("version"));
        if(version<1||version>Integer.MAX_VALUE)throw missing();
        return new TaskLinkedMaterialReader.Selection(text(identity,"fileId"),(int)version,text(identity,"purpose"));
    }
    private static TaskLinkedMaterialReader.Metadata expected(Map<String,Object> input) {
        return new TaskLinkedMaterialReader.Metadata(text(input,"contentMimeType"),text(input,"sha256"),decimal(input.get("byteLength")));
    }
    private static ChatDeliberationException materialFailure(TaskLinkedMaterialReader.Failure error) {
        return switch(error.reason()) {
            case NOT_FOUND, METADATA_INVALID -> missing();
            case CONTENT_MISMATCH -> conflict();
            case UNAVAILABLE -> new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,
                    "Skill source storage is unavailable",error);
        };
    }
    public String conversation(PersonalWorkspaceExecutionService.OwnerScope s,String task,String id,long generation) {
        var c=conversations.findScopedById(s.ownerJiacn(),s.clientId(),id);
        if(c==null||c.getId()==null||!id.equals(Long.toString(c.getId()))||!s.tenantId().equals(c.getTenantId())
                ||!s.ownerJiacn().equals(c.getJiacn())||!s.clientId().equals(c.getClientId())||c.getDeletedAt()!=null
                ||!Objects.equals(generation,c.getLifecycleGeneration())||!task.equals(c.getTaskId())
                ||!"juyiting".equals(c.getConversationType())||!"bounty".equals(c.getConversationScopeType())
                ||!("task:"+task).equals(c.getConversationScopeKey()))throw missing();
        var targets=scopes.parsePersistedTargetAgentIds(c.getTargetAgentIds());if(targets.size()!=1)throw missing();return targets.getFirst();
    }
    ChatConversationArchiveStore.Source asset(PersonalWorkspaceExecutionService.OwnerScope s,String task,
            String conversation,long generation,String target,Map<String,Object> identity) {
        var source=archive.findAuthorizedSource(new ChatConversationArchiveStore.Scope(s.tenantId(),s.ownerJiacn(),s.clientId()),
                conversation,text(identity,"assetId"),decimal(identity.get("assetRevision")),target);
        if(source==null||!task.equals(source.taskId())||!conversation.equals(source.conversationId())||generation!=source.conversationGeneration())throw missing();
        return source;
    }
    ChatConversationArchiveStore.Source outputAsset(PersonalWorkspaceExecutionService.OwnerScope scope, String task,
            String conversationId, long generation, String target, Map<String,Object> output) {
        if (!target.equals(conversation(scope, task, conversationId, generation))) throw missing();
        var source = archive.findAuthorizedOutputSource(new ChatConversationArchiveStore.Scope(
                scope.tenantId(), scope.ownerJiacn(), scope.clientId()), conversationId, generation, task, target,
                text(output, "requestId"), text(output, "stepId"), text(output, "outputId"), text(output, "sha256"));
        if (source == null) throw new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,
                "上一版成果资料尚未就绪或已不可用，请稍后重试，或明确选择本次资料；不会改用最初上传资料。");
        if (!task.equals(source.taskId()) || !conversationId.equals(source.conversationId())
                || generation != source.conversationGeneration() || !output.get("requestId").equals(source.requestId())
                || !output.get("stepId").equals(source.stepId()) || !output.get("outputId").equals(source.outputId())
                || !output.get("sha256").equals(source.sha256())) throw missing();
        return source;
    }

    static Map<String,Object> assetIdentity(ChatConversationArchiveStore.Source s) {
        return Map.ofEntries(Map.entry("kind","CURRENT_CONVERSATION_ASSET"),Map.entry("conversationId",s.conversationId()),
                Map.entry("conversationGeneration",Long.toString(s.conversationGeneration())),Map.entry("assetId",s.assetId()),
                Map.entry("assetRevision",Long.toString(s.assetRevision())),Map.entry("producerRequestId",s.requestId()),
                Map.entry("producerRequestRevision",Long.toString(s.requestRevision())),Map.entry("producerStepId",s.stepId()),
                Map.entry("producerExecutionId",s.executionId()),Map.entry("producerRunId",s.runId()),Map.entry("producerOutputId",s.outputId()));
    }
    byte[] result(PersonalWorkspaceExecutionService.OwnerScope s,String task,String conversation,long generation,Map<String,Object> identity) {
        if(!identity.keySet().equals(Set.of("kind","executionId","resultDigest"))||!"EXECUTION_RESULT".equals(identity.get("kind")))throw missing();
        String execution=text(identity,"executionId"),expected=text(identity,"resultDigest");
        if(!expected.matches("[0-9a-f]{64}"))throw missing();
        // Bind producer to this accessible task conversation and a committed link; no self/uncommitted result.
        var rows=jdbc.queryForList("""
            SELECT e.execution_id FROM chat_step_execution_link l
            JOIN chat_interaction_step p ON BINARY p.step_id=BINARY l.step_id
              AND BINARY p.tenant_id=BINARY l.tenant_id AND BINARY p.client_id=BINARY l.client_id AND BINARY p.owner_jiacn=BINARY l.owner_jiacn
            JOIN agent_personal_workspace_execution e ON BINARY e.execution_id=BINARY l.execution_id
              AND BINARY e.tenant_id=BINARY l.tenant_id AND BINARY e.client_id=BINARY l.client_id AND BINARY e.owner_jiacn=BINARY l.owner_jiacn
            WHERE BINARY l.tenant_id=BINARY ? AND BINARY l.client_id=BINARY ? AND BINARY l.owner_jiacn=BINARY ?
              AND BINARY e.execution_id=BINARY ? AND BINARY p.task_id=BINARY ? AND BINARY e.task_id=BINARY ?
              AND BINARY p.conversation_id=BINARY ? AND BINARY e.conversation_id=BINARY ? AND p.conversation_generation=?
              AND p.kind='EXECUTE' AND e.execution_protocol_version IN (4,5) AND e.execution_state='RESULT_COMMITTED'
              AND p.state='RESULT_COMMITTED' AND l.state='RESULT_COMMITTED'
            """,s.tenantId(),s.clientId(),s.ownerJiacn(),execution,task,task,conversation,conversation,generation);
        if(rows.size()!=1)throw missing();
        var view=skills.result(s,execution);
        if(view==null||!execution.equals(view.executionId())||!"RESULT_COMMITTED".equals(view.state())
                ||!Set.of("ARCHIVING","COMPLETE").contains(view.phase())||view.resultManifest()==null)throw missing();
        var manifest=view.resultManifest();var preimage=new LinkedHashMap<>(manifest);preimage.remove("resultDigest");preimage.remove("manifestId");
        if(!expected.equals(manifest.get("resultDigest"))||!expected.equals(ChatSkillSnapshotJson.sha(preimage)))throw conflict();
        return ChatSkillSnapshotJson.write(manifest.get("data")).getBytes(StandardCharsets.UTF_8);
    }
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value) {if(!(value instanceof Map<?,?> m)||!m.keySet().stream().allMatch(String.class::isInstance))throw missing();return(Map<String,Object>)m;}
    static String text(Map<String,Object> m,String key){if(!(m.get(key)instanceof String s)||s.isBlank())throw missing();return s;}
    static long decimal(Object v){try {String s=String.valueOf(v);if(!s.matches("0|[1-9][0-9]*"))throw missing();return Long.parseLong(s);}catch(NumberFormatException e){throw missing();}}
    static String sha(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static ChatDeliberationException missing(){return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Skill source is unavailable");}
    private static ChatDeliberationException conflict(){return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,"Skill source digest changed");}
}
