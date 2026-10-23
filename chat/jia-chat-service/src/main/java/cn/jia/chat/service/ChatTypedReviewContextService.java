package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/** Called before the first dispatch snapshot is persisted, within USER/request admission's transaction. */
@Service
@ConditionalOnProperty(prefix="chat.delivery-review",name="enabled",havingValue="true")
public class ChatTypedReviewContextService {
    private final ChatTaskDeliveryReviewReadService reads;
    private final ChatTypedReviewContextStore contexts;
    private final ChatTypedDeliberationStore typed;
    public ChatTypedReviewContextService(ChatTaskDeliveryReviewReadService reads,ChatTypedReviewContextStore contexts,
            ChatTypedDeliberationStore typed) {this.reads=reads;this.contexts=contexts;this.typed=typed;}

    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public ChatDeliveryRevisionContract.Basis freeze(ChatTypedDeliberationStore.Scope scope,String task,String taskVersion,
            String request,long revision,long userMessage,String clarificationParentOutcome,long now) {
        var current=reads.read(new ChatDeliveryReviewContract.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),task);
        if(!task.equals(current.taskId()) || !taskVersion.equals(current.taskVersion()))throw stale();
        var batch=current.currentDelivery();
        ChatDeliveryRevisionContract.Basis basis=null;
        if(batch!=null && "SUBMITTED".equals(batch.state())) {
            if(!"REVIEWING".equals(current.taskState()))throw stale();
            // A pending operation/unavailable source can restrict writes, but must not silently drop
            // the existing batch or prevent an ordinary question from preserving its review context.
            basis=new ChatDeliveryRevisionContract.Basis(task,current.taskVersion(),batch.deliveryId(),batch.deliveryVersion(),
                    batch.batchSha256(),current.assignmentRevision(),Long.toString(userMessage),request,batch.items().stream()
                    .map(i->new ChatDeliveryRevisionContract.Item(i.artifactId(),i.artifactVersion(),i.contentHash(),i.purpose(),i.displayRole())).toList());
        }
        if(clarificationParentOutcome!=null) {
            var parent=typed.findOutcome(scope,clarificationParentOutcome,false);
            if(parent==null || !scope.equals(parent.scope()) || !task.equals(parent.taskId()) || !"CLARIFY".equals(parent.kind()))throw stale();
            var inherited=contexts.require(scope,parent.requestId(),parent.requestRevision());
            // Inherit the exact batch, not the latest one. Only USER/request provenance belongs to the new reply.
            if(!sameBatch(inherited,basis))throw stale();
        }
        contexts.insert(scope,request,revision,basis,now);
        return basis;
    }
    @Transactional(readOnly=true)
    public void requireActionAllowed(ChatActionFinalService.BoundAction action) {
        var row=action.outcome();var basis=contexts.require(row.scope(),row.requestId(),row.requestRevision());
        // Current EXECUTE_SKILL has no trusted read-only classification. Never rely on the Agent's claim.
        if(basis!=null)throw new ChatActionDispatchService.Rejected();
    }
    private static boolean sameBatch(ChatDeliveryRevisionContract.Basis a,ChatDeliveryRevisionContract.Basis b) {
        if(a==null || b==null)return a==b;
        return a.taskId().equals(b.taskId()) && a.taskVersion().equals(b.taskVersion()) && a.deliveryId().equals(b.deliveryId())
                && a.deliveryVersion().equals(b.deliveryVersion()) && a.assignmentRevision().equals(b.assignmentRevision())
                && a.batchSha256().equals(b.batchSha256()) && new java.util.HashSet<>(a.items()).equals(new java.util.HashSet<>(b.items()));
    }
    private static ChatDeliberationException stale() {return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,"Delivery review basis changed");}
}
