package cn.jia.chat.service;

import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import org.springframework.stereotype.Service;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Chat owns lifecycle/dispatch admission; resource owners authorize the actual operation. */
@Service
public final class PrivateConversationToolAccessService implements PrivateConversationToolAccess {
    private final ChatDispatchAuthorityService dispatches;
    private final ChatDeliberationDao turns;
    private final ChatConversationDao conversations;
    public PrivateConversationToolAccessService(ChatDispatchAuthorityService dispatches,ChatDeliberationDao turns,ChatConversationDao conversations) {
        this.dispatches=dispatches;this.turns=turns;this.conversations=conversations;
    }
    @Override public <T> T withCurrentTurn(Subject subject,String requestId,String turnId,Supplier<T> operation) {
        var locator=turns.findTurn(subject.tenantId(),subject.ownerJiacn(),subject.clientId(),turnId);
        require(locator!=null);
        var conversation=conversations.lockScopedById(subject.tenantId(),subject.ownerJiacn(),subject.clientId(),locator.getConversationId());
        require(conversation!=null&&conversation.getDeletedAt()==null&&"private".equals(conversation.getConversationScopeType())
                &&Objects.equals(conversation.getLifecycleGeneration(),locator.getConversationGeneration()));
        var turn=turns.lockTurn(subject.tenantId(),subject.ownerJiacn(),subject.clientId(),turnId);
        require(turn!=null&&Objects.equals(requestId,turn.getRequestId())&&Objects.equals(subject.agentId(),turn.getTargetAgentId())
                &&Objects.equals(locator.getConversationId(),turn.getConversationId())
                &&Objects.equals(conversation.getLifecycleGeneration(),turn.getConversationGeneration())
                &&"CHAT".equals(turn.getRoute())&&Set.of("DISPATCHED","STREAMING").contains(turn.getState()));
        try {dispatches.incomingTurn(subject.tenantId(),subject.clientId(),subject.ownerJiacn(),subject.agentId(),turnId);}
        catch(ChatDeliberationException unavailable) {
            if(unavailable.reason()!=ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN)throw unavailable;
            throw new Denied();
        }
        var request=turns.findRequest(subject.tenantId(),subject.ownerJiacn(),subject.clientId(),requestId);
        require(request!=null&&Objects.equals(request.getRequestRevision(),turn.getRequestRevision()));
        return operation.get();
    }
    private static void require(boolean condition) {
        if(!condition)throw new Denied();
    }
}
