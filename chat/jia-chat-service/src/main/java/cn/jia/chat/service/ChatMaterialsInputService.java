package cn.jia.chat.service;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.handler.TypedDeliberationSessionRegistry;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Supplier;

/** Current owner/task/turn native-material authorization, within the original CHAT round. */
@Service
public class ChatMaterialsInputService {
    private ChatDispatchAuthorityService dispatchAuthority;
    @org.springframework.beans.factory.annotation.Autowired
    public void setDispatchAuthority(ChatDispatchAuthorityService value) { dispatchAuthority=value; }
    private final ChatDeliberationDao dao;
    private final ChatTypedDeliberationStore store;
    private final AgentTaskMutationTransaction tasks;
    private final TypedDeliberationSessionRegistry sessions;
    private final ChatSkillSourceAccessService sources;
    private final cn.jia.agent.service.AgentTaskExecutionGrantService grants;
    public ChatMaterialsInputService(ChatDeliberationDao dao,ChatTypedDeliberationStore store,AgentTaskMutationTransaction tasks,
            TypedDeliberationSessionRegistry sessions,ChatSkillSourceAccessService sources,cn.jia.agent.service.AgentTaskExecutionGrantService grants) {
        this.dao=dao;this.store=store;this.tasks=tasks;this.sessions=sessions;this.sources=sources;this.grants=grants;
    }
    /** Called under the HTTP boundary's native identity fence, before any task/grant lock. */
    public Prepared prepare(AgentRuntimeAuthentication.Scope runtime,String requestId,String turnId,String sourceId,String digest) {
        if(runtime==null||digest==null||!digest.matches("sha256:[0-9a-f]{64}"))throw missing();
        var turn=dispatchAuthority.incomingTurn(runtime.tenantId(),runtime.clientId(),runtime.ownerJiacn(),runtime.agentId(),turnId).turn();
        if(turn==null||!requestId.equals(turn.getRequestId())||!runtime.agentId().equals(turn.getTargetAgentId())||!"CHAT".equals(turn.getRoute()))throw missing();
        return new Prepared(turn,withAuthority(turn,digest,false,()->input(turn,sourceId)),sourceId,digest);
    }
    /** Storage I/O is invoked outside the identity/task transaction. */
    public PersonalWorkspaceExecutionService.RuntimeDownload read(Prepared prepared) {
        var turn=prepared.turn;
        var owner=new PersonalWorkspaceExecutionService.OwnerScope(turn.getTenantId(),turn.getClientId(),turn.getOwnerJiacn());
        return sources.open(owner,prepared.input.taskId(),turn.getConversationId(),turn.getConversationGeneration(),prepared.input.input());
    }
    /** Called under a fresh native identity fence before delivering the bytes. */
    public void revalidate(Prepared prepared) {
        withAuthority(prepared.turn,prepared.digest,false,()-> {
            if(!input(prepared.turn,prepared.sourceId).equals(prepared.input))throw missing();
            return null;
        });
    }
    /** Opaque, server-created selection; HTTP callers cannot construct or alter its authority. */
    public static final class Prepared {
        private final ChatTurnEntity turn;
        private final Input input;
        private final String sourceId;
        private final String digest;
        private Prepared(ChatTurnEntity turn,Input input,String sourceId,String digest) {
            this.turn=turn;this.input=input;this.sourceId=sourceId;this.digest=digest;
        }
    }
    public <T> T withFinalAuthority(ChatTurnEntity turn,Supplier<T> callback) {
        var facts=facts(turn);if(!facts.containsKey("materialsInput"))return callback.get();
        return withAuthority(turn,(String)((Map<?,?>)facts.get("materialsInput")).get("manifestDigest"),true,callback);
    }
    private <T> T withAuthority(ChatTurnEntity locator,String expectedDigest,boolean finalRead,Supplier<T> callback) {
        var scope=scope(locator);var admission=store.findAdmissionByRequest(scope,locator.getRequestId());if(admission==null)throw missing();
        return tasks.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),admission.taskId(),root-> {
            if(root==null||!locator.getTargetAgentId().equals(root.getAssignedAgentId()))throw missing();
            dispatchAuthority.forTurn(locator);
            sources.conversation(owner(scope),admission.taskId(),scope.conversationId(),scope.conversationGeneration());
            var request=dao.findRequest(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),locator.getRequestId());
            var turn=dao.findTurn(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),locator.getTurnId());
            if(request==null||turn==null||!"CHAT".equals(turn.getRoute())||!locator.getDispatchId().equals(turn.getDispatchId())
                    ||!locator.getSnapshotId().equals(turn.getSnapshotId())||!locator.getContextDigest().equals(turn.getContextDigest())
                    ||!locator.getRequestId().equals(turn.getRequestId())||!Objects.equals(admission.requestRevision(),turn.getRequestRevision())
                    ||!Objects.equals(admission.requestRevision(),request.getRequestRevision())||!Objects.equals(scope.conversationId(),request.getConversationId())
                    ||!Objects.equals(scope.conversationGeneration(),request.getConversationGeneration())
                    ||!Set.of("RECEIVED","QUEUED","DISPATCHED","STREAMING","UNKNOWN","RECOVERY_REQUIRED").contains(turn.getState())
                       &&!(finalRead&&Set.of("FINAL_PERSISTED","PUBLISHED").contains(turn.getState())))throw missing();
            var material=(Map<?,?>)facts(turn).get("materialsInput");if(material==null||!expectedDigest.equals(material.get("manifestDigest")))throw missing();
            var actual=ChatMaterialsInputContract.bind(nativeCandidates(admission,turn),scope.tenantId(),scope.ownerJiacn(),scope.clientId(),
                    scope.conversationId(),scope.conversationGeneration(),admission.taskId(),turn.getRequestId(),turn.getTurnId(),turn.getTargetAgentId());
            if(!CanonicalContextJson.write(actual).equals(CanonicalContextJson.write(material)))throw missing();
            // Revalidate every native source under the same root before a final. Storage reads stay outside locks.
            for(String id:ChatMaterialsInputContract.refs(material)) {var input=input(turn,id);sources.requireAuthorized(owner(scope),admission.taskId(),scope.conversationId(),scope.conversationGeneration(),input.input());}
            return callback.get();
        });
    }
    private Object nativeCandidates(ChatTypedDeliberationStore.Admission admission,ChatTurnEntity turn) {
        var registered=sessions.requireSingleReady(new TypedDeliberationSessionRegistry.Scope(turn.getTenantId(),dispatchAuthority.forTurn(turn).executorOwner(),turn.getClientId()),turn.getTargetAgentId());
        return ChatMaterialsInputContract.candidates(registered.frozenDeclaration(),ChatTypedDeliberationContextService.parseCatalog(admission.sourceCatalogJson()));
    }
    private record Input(String taskId,Map<String,Object> input) { }
    private Input input(ChatTurnEntity turn,String sourceId) {
        var admission=store.findAdmissionByRequest(scope(turn),turn.getRequestId());if(admission==null||!ChatMaterialsInputContract.refs(facts(turn).get("materialsInput")).contains(sourceId))throw missing();
        var selected=ChatTypedDeliberationContextService.parseCatalog(admission.sourceCatalogJson()).stream().filter(x->sourceId.equals(x.get("sourceRefId"))).toList();
        if(selected.size()!=1)throw missing();var source=selected.getFirst();String kind=(String)source.get("sourceKind");if("TASK_WORKSPACE_FILE".equals(kind))kind="TASK_LINKED_WORKSPACE_VERSION";
        return new Input(admission.taskId(),Map.of("sourceKind",kind,"sourceIdentity",source.get("sourceIdentity"),"inputRef",sourceId,
                "contentMimeType",source.get("contentMimeType"),"sha256",source.get("contentHash"),"byteLength",source.get("byteLength")));
    }
    private Map<String,Object> facts(ChatTurnEntity turn) {
        var snapshot=dao.findSnapshot(turn.getTenantId(),turn.getOwnerJiacn(),turn.getClientId(),turn.getSnapshotId());
        if(snapshot==null||!turn.getRequestId().equals(snapshot.getRequestId())||!turn.getTargetAgentId().equals(snapshot.getTargetAgentId())
                ||!turn.getConversationId().equals(snapshot.getConversationId())||!Objects.equals(turn.getConversationGeneration(),snapshot.getConversationGeneration())
                ||!turn.getContextDigest().equals(snapshot.getContextDigest()))throw missing();return ChatSkillSnapshotJson.object(snapshot.getFactsManifestJson());
    }
    private static ChatTypedDeliberationStore.Scope scope(ChatTurnEntity t) {return new ChatTypedDeliberationStore.Scope(t.getTenantId(),t.getOwnerJiacn(),t.getClientId(),t.getConversationId(),t.getConversationGeneration());}
    private static PersonalWorkspaceExecutionService.OwnerScope owner(ChatTypedDeliberationStore.Scope s) {return new PersonalWorkspaceExecutionService.OwnerScope(s.tenantId(),s.clientId(),s.ownerJiacn());}
    private static ChatDeliberationException missing() {return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Material input is unavailable");}
}
