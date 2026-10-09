package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Consumes only a trusted task bootstrap: rechecks the grant and binds one discussion per task. */
@Service
public class ChatBountyConversationService {
    private final ChatBountyBindingStore bindings;
    private final ChatConversationDao conversations;
    private final AgentTaskExecutionGrantService grants;

    public ChatBountyConversationService(ChatBountyBindingStore bindings,
            ChatConversationDao conversations, AgentTaskExecutionGrantService grants) {
        this.bindings=Objects.requireNonNull(bindings);
        this.conversations=Objects.requireNonNull(conversations);
        this.grants=Objects.requireNonNull(grants);
    }

    public record Discussion(String conversationId,long generation,boolean newlyCreated) { }

    /**
     * Grant verification precedes all Chat writes. Reservation, conversation and binding commit
     * as one DB transaction; replay never creates a second conversation or sends work to an Agent.
     */
    @Transactional(rollbackFor=Exception.class)
    public Discussion ensure(AgentTaskExecutionGrantService.Scope scope,String taskId,
            String grantId,long grantVersion,long assignmentRevision,String targetAgentId,
            String authorizedOperation,String taskTitle) {
        if (scope==null || !"0".equals(scope.tenantId())
                || !valid(scope.ownerJiacn(),50) || !valid(scope.clientId(),50)
                || !valid(taskId,100) || !valid(grantId,100) || !valid(targetAgentId,100)
                || !valid(authorizedOperation,40) || grantVersion<1 || assignmentRevision<0) {
            throw new IllegalArgumentException("Bounty bootstrap scope or authorization is invalid");
        }
        // No browser/model-provided grant or scope may bypass this server-side check.
        AgentTaskExecutionGrantService.Admission admitted=grants.admit(scope,taskId,
                grantId,grantVersion,assignmentRevision,targetAgentId,authorizedOperation,false);
        if (admitted==null || !grantId.equals(admitted.grantId())
                || admitted.grantVersion()!=grantVersion || admitted.assignmentRevision()!=assignmentRevision
                || !targetAgentId.equals(admitted.targetAgentId())) {
            throw new IllegalStateException("Bounty grant admission did not bind task and target");
        }
        var ownerScope=new ChatBountyBindingStore.Scope(scope.tenantId(),scope.ownerJiacn(),scope.clientId());
        long now=System.currentTimeMillis();
        bindings.reserve(ownerScope,taskId,assignmentRevision,now);
        var binding=bindings.lock(ownerScope,taskId);
        if (binding.assignmentRevision()>assignmentRevision)
            throw new IllegalStateException("Bounty discussion assignment is newer than bootstrap");
        boolean created=false;
        Long conversationId=binding.conversationId();
        if (conversationId==null) {
            List<Long> legacy=bindings.findExistingBountyConversationIds(ownerScope,taskId);
            if (legacy==null || legacy.size()>1)
                throw new IllegalStateException("Bounty task has ambiguous pre-existing discussions");
            if (legacy.isEmpty()) {
                ChatConversationEntity conversation=new ChatConversationEntity()
                        .setTitle(title(taskTitle,taskId)).setJiacn(scope.ownerJiacn())
                        .setConversationType("juyiting").setConversationScopeType("bounty")
                        .setConversationScopeKey("task:"+taskId).setTaskId(taskId)
                        .setTargetAgentIds(JsonUtil.toJson(List.of(targetAgentId)))
                        .setStatus(0).setLifecycleGeneration(1L);
                conversation.setTenantId(scope.tenantId());
                conversation.setClientId(scope.clientId());
                if (conversations.insert(conversation)!=1 || conversation.getId()==null)
                    throw new IllegalStateException("Bounty conversation was not persisted");
                conversationId=conversation.getId();
                created=true;
            } else conversationId=legacy.getFirst();
            if (bindings.attach(ownerScope,taskId,assignmentRevision,conversationId,now)!=1)
                throw new IllegalStateException("Bounty discussion binding changed concurrently");
        } else if (binding.assignmentRevision()!=assignmentRevision
                && bindings.advance(ownerScope,taskId,binding.assignmentRevision(),assignmentRevision,now)!=1) {
            throw new IllegalStateException("Bounty discussion assignment changed concurrently");
        }
        ChatConversationEntity conversation=conversations.lockScopedById(scope.ownerJiacn(),
                scope.clientId(),Long.toString(conversationId));
        if (conversation==null || !scope.tenantId().equals(conversation.getTenantId())
                || !"juyiting".equals(conversation.getConversationType())
                || !"bounty".equals(conversation.getConversationScopeType())
                || !("task:"+taskId).equals(conversation.getConversationScopeKey())
                || !taskId.equals(conversation.getTaskId())
                || conversation.getLifecycleGeneration()==null || conversation.getLifecycleGeneration()<1) {
            throw new IllegalStateException("Bounty discussion does not match its owner/task scope");
        }
        if (bindings.replaceAuthorizedTargets(ownerScope,taskId,conversationId,
                JsonUtil.toJson(List.of(targetAgentId)),now)!=1)
            throw new IllegalStateException("Bounty discussion target update failed");
        return new Discussion(Long.toString(conversationId),conversation.getLifecycleGeneration(),created);
    }

    private static String title(String requested,String taskId) {
        String value=requested==null?"":requested.strip();
        if (value.isEmpty()) value=taskId;
        StringBuilder result=new StringBuilder("悬赏议事 · ");
        value.codePoints().filter(ch->!Character.isISOControl(ch)).limit(72)
                .forEach(result::appendCodePoint);
        return result.toString();
    }
    private static boolean valid(String value,int max) {
        return value!=null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0,value.length())<=max
                && value.chars().noneMatch(Character::isISOControl);
    }
}
