package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskFormalDeliveryDecisionDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import cn.jia.agent.service.AgentTaskFormalDeliveryDecisionService;
import cn.jia.agent.service.AgentTaskFormalDeliveryReadService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.service.ChatDeliveryReviewContract.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Independent bean: root -> operation -> formal delivery/work item; decision and receipt commit together. */
@Service
public class ChatTaskDeliveryReviewTransaction {
    private final AgentTaskMutationTransaction mutations;
    private final ChatTaskDeliveryReviewStore store;
    private final AgentTaskFormalDeliveryReadService deliveries;
    private final AgentTaskFormalDeliveryDecisionService decisions;
    private final ChatTaskDeliveryReviewReadService reads;
    public ChatTaskDeliveryReviewTransaction(AgentTaskMutationTransaction mutations, ChatTaskDeliveryReviewStore store,
            AgentTaskFormalDeliveryReadService deliveries, AgentTaskFormalDeliveryDecisionService decisions, ChatTaskDeliveryReviewReadService reads) {
        this.mutations = Objects.requireNonNull(mutations); this.store = Objects.requireNonNull(store);
        this.deliveries = Objects.requireNonNull(deliveries); this.decisions = Objects.requireNonNull(decisions); this.reads = Objects.requireNonNull(reads);
    }
    @Transactional(rollbackFor = Exception.class)
    public ChatTaskDeliveryReviewStore.Operation admit(Scope s, String task, String key, Accept command) {
        // Authorize the actual owner before retaining any caller-controlled operation.
        return mutations.executeWithLockedTaskRootInOwnerScope(s.tenantId(), s.clientId(), s.ownerJiacn(), task,
                root -> store.admit(s, task, key, command));
    }
    @Transactional(rollbackFor = Exception.class)
    public Receipt commit(Scope s, String task, String operationId, Accept command) {
        return mutations.executeWithLockedTaskRootInOwnerScope(s.tenantId(), s.clientId(), s.ownerJiacn(), task, root -> {
            var operation = store.require(s, task, operationId, true);
            ChatTaskDeliveryReviewStore.requireDigest(operation, ChatDeliveryReviewContract.requestDigest(command));
            if (!"PROCESSING".equals(operation.state())) return ChatTaskDeliveryReviewStore.committedReceipt(operation);
            if (!"reviewing".equals(root.getRewardStatus()) || !Long.valueOf(command.taskVersion()).equals(root.getTaskVersion())
                    || store.assignmentRevision(s, task) != command.assignmentRevision()) throw new Conflict();
            List<AgentTaskFormalDeliveryViewDTO> batches = deliveries.listForTaskOwner(s.tenantId(), s.clientId(), s.ownerJiacn(), task);
            List<AgentTaskFormalDeliveryViewDTO> submitted = batches.stream().filter(v -> "submitted".equals(v.getState())).toList();
            if (submitted.size() != 1) throw new Conflict();
            var batch = submitted.getFirst();
            if (!command.deliveryId().equals(batch.getDeliveryId())
                    || !Long.valueOf(command.deliveryVersion()).equals(batch.getDeliveryVersion())
                    || !command.batchSha256().equals(ChatDeliveryReviewContract.batchDigest(task, batch))) throw new Conflict();
            if (!ChatTaskDeliveryReviewReadService.sourcesComplete(reads.sourceItems(s, task, batch))) throw new SourceUnavailable();
            var decision = new AgentTaskFormalDeliveryDecisionDTO();
            decision.setDeliveryId(command.deliveryId()); decision.setExpectedTaskVersion(command.taskVersion());
            decision.setExpectedDeliveryVersion(command.deliveryVersion()); decision.setDecision("accepted");
            var result = decisions.decide(s.tenantId(), s.clientId(), task, s.ownerJiacn(), decision);
            if (!"accepted".equals(result.getState()) || !command.deliveryId().equals(result.getDeliveryId())
                    || result.getTaskVersion() == null || result.getDeliveryVersion() == null) throw new Conflict();
            var receipt = new Receipt(1, operationId, task, command.deliveryId(), "ACCEPT", "COMMITTED",
                    Long.toString(Math.addExact(operation.stateVersion(), 1)), "COMPLETED", result.getTaskVersion().toString(),
                    "ACCEPTED", result.getDeliveryVersion().toString(), null, null, null, null, false);
            return store.settle(s, operation, receipt);
        });
    }
    @Transactional(rollbackFor = Exception.class)
    public Receipt reject(Scope s, String task, String operationId, Accept command, String code) {
        return mutations.executeWithLockedTaskRootInOwnerScope(s.tenantId(), s.clientId(), s.ownerJiacn(), task, root -> {
            var operation = store.require(s, task, operationId, true);
            ChatTaskDeliveryReviewStore.requireDigest(operation, ChatDeliveryReviewContract.requestDigest(command));
            if (!"PROCESSING".equals(operation.state())) return ChatTaskDeliveryReviewStore.committedReceipt(operation);
            var batch = deliveries.listForTaskOwner(s.tenantId(), s.clientId(), s.ownerJiacn(), task).stream()
                    .filter(v -> command.deliveryId().equals(v.getDeliveryId())).findFirst().orElseThrow(Conflict::new);
            var receipt = new Receipt(1, operationId, task, command.deliveryId(), "ACCEPT", "REJECTED",
                    Long.toString(Math.addExact(operation.stateVersion(), 1)), root.getRewardStatus().toUpperCase(java.util.Locale.ROOT),
                    root.getTaskVersion().toString(), batch.getState().toUpperCase(java.util.Locale.ROOT), batch.getDeliveryVersion().toString(),
                    null, null, null, code, false);
            return store.settle(s, operation, receipt);
        });
    }
}
