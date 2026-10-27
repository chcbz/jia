package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Creates only an empty, owner-scoped discussion. No bootstrap, dispatch or execution is emitted. */
@Service
public class ChatBountyTopicService {
    private final AgentTaskMutationTransaction tasks;
    private final ChatBountyBindingStore bindings;
    private final ChatConversationDao conversations;
    private final ChatBountyInteractionV3PreviewService contexts;

    public ChatBountyTopicService(AgentTaskMutationTransaction tasks, ChatBountyBindingStore bindings,
            ChatConversationDao conversations, ChatBountyInteractionV3PreviewService contexts) {
        this.tasks=Objects.requireNonNull(tasks);this.bindings=Objects.requireNonNull(bindings);
        this.conversations=Objects.requireNonNull(conversations);this.contexts=Objects.requireNonNull(contexts);
    }

    public record Receipt(String sourceConversationId, String sourceConversationGeneration,
            String conversationId, String conversationGeneration, String taskId, String targetAgentId) { }

    public Receipt create(String tenant, ServerResolvedSender sender, String sourceId,
            String operationKey, long expectedGeneration) {
        if(expectedGeneration<1) throw invalid();
        ChatConversationEntity source=source(tenant,sender,sourceId,operationKey);
        var scope=new ChatBountyBindingStore.Scope(tenant,sender.jiacn(),sender.clientId());
        return tasks.executeWithLockedTaskRootInOwnerScope(tenant,sender.clientId(),sender.jiacn(),
                source.getTaskId(),root->{
            bindings.lock(scope,source.getTaskId());
            var prior=bindings.findTopic(scope,source.getTaskId(),operationKey);
            if(prior!=null) {
                if(prior.sourceConversationId()!=source.getId() || prior.sourceGeneration()!=expectedGeneration)
                    throw conflict();
                return receipt(source.getTaskId(),prior);
            }
            // Recheck current grant, assignment, scope and source generation inside the task transaction.
            var context=contexts.context(tenant,sender,sourceId);
            if(!source.getTaskId().equals(context.taskId()) ||
                    !Long.toString(expectedGeneration).equals(context.conversationGeneration())) throw conflict();
            ChatConversationEntity topic=new ChatConversationEntity().setTitle("事项议事 · 新话头")
                    .setJiacn(sender.jiacn()).setConversationType("juyiting")
                    .setConversationScopeType("bounty").setConversationScopeKey("task:"+context.taskId())
                    .setTaskId(context.taskId()).setTargetAgentIds(JsonUtil.toJson(List.of(context.targetAgentId())))
                    .setLifecycleGeneration(1L).setStatus(0);
            topic.setTenantId(tenant);topic.setClientId(sender.clientId());
            if(conversations.insert(topic)!=1 || topic.getId()==null) throw persistence();
            var saved=new ChatBountyBindingStore.Topic(operationKey,source.getId(),expectedGeneration,
                    topic.getId(),1L,context.targetAgentId());
            bindings.insertTopic(scope,context.taskId(),saved,System.currentTimeMillis());
            return receipt(context.taskId(),saved);
        });
    }

    @Transactional(readOnly=true)
    public Receipt get(String tenant, ServerResolvedSender sender, String sourceId, String operationKey) {
        ChatConversationEntity source=source(tenant,sender,sourceId,operationKey);
        var topic=bindings.findTopic(new ChatBountyBindingStore.Scope(tenant,sender.jiacn(),sender.clientId()),
                source.getTaskId(),operationKey);
        if(topic==null || topic.sourceConversationId()!=source.getId()) throw unavailable();
        return receipt(source.getTaskId(),topic);
    }

    private ChatConversationEntity source(String tenant, ServerResolvedSender sender, String id, String key) {
        if(!"0".equals(tenant)||sender==null||!ServerResolvedSender.USER_TYPE.equals(sender.type())
                ||sender.jiacn()==null||sender.jiacn().isBlank()||sender.clientId()==null||sender.clientId().isBlank())throw unavailable();
        if(id==null||!id.matches("[1-9][0-9]{0,18}")||key==null||!key.matches("[A-Za-z0-9_-]{8,100}"))throw invalid();
        ChatConversationEntity source=conversations.findScopedById(sender.jiacn(),sender.clientId(),id);
        if(source==null||source.getId()==null||!id.equals(source.getId().toString())||source.getDeletedAt()!=null||
                !tenant.equals(source.getTenantId())||!sender.jiacn().equals(source.getJiacn())||
                !sender.clientId().equals(source.getClientId())||!"juyiting".equals(source.getConversationType())||
                !"bounty".equals(source.getConversationScopeType())||source.getTaskId()==null||
                !("task:"+source.getTaskId()).equals(source.getConversationScopeKey()))throw unavailable();
        return source;
    }
    private static Receipt receipt(String taskId, ChatBountyBindingStore.Topic topic) {
        return new Receipt(Long.toString(topic.sourceConversationId()),Long.toString(topic.sourceGeneration()),
                Long.toString(topic.conversationId()),Long.toString(topic.generation()),taskId,topic.targetAgentId());
    }
    private static ChatDeliberationException invalid(){return failure(ChatDeliberationException.Reason.INVALID_REQUEST,"Invalid topic creation request");}
    private static ChatDeliberationException conflict(){return failure(ChatDeliberationException.Reason.CONFLICT,"Topic source or assignment changed");}
    private static ChatDeliberationException unavailable(){return failure(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Topic is unavailable");}
    private static ChatDeliberationException persistence(){return failure(ChatDeliberationException.Reason.PERSISTENCE_ERROR,"Topic could not be persisted");}
    private static ChatDeliberationException failure(ChatDeliberationException.Reason reason,String message){return new ChatDeliberationException(reason,message);}
}
