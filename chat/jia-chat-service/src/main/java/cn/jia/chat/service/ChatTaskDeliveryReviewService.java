package cn.jia.chat.service;

import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.chat.service.ChatDeliveryReviewContract.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** No automatic acceptance; only an explicit, persisted, batch-bound user command reaches this service. */
@Service
@ConditionalOnProperty(prefix = "chat.delivery-review", name = "enabled", havingValue = "true")
public final class ChatTaskDeliveryReviewService {
    private final ChatTaskDeliveryReviewStore store;
    private final ChatTaskDeliveryReviewTransaction transactions;
    public ChatTaskDeliveryReviewService(ChatTaskDeliveryReviewStore store, ChatTaskDeliveryReviewTransaction transactions) {
        this.store = Objects.requireNonNull(store); this.transactions = Objects.requireNonNull(transactions);
    }
    public Receipt accept(Scope scope, String task, String key, Accept command) {
        ChatDeliveryReviewContract.id(task, 100); ChatDeliveryReviewContract.key(key); Objects.requireNonNull(command);
        var operation = transactions.admit(scope, task, key, command);
        if (!"PROCESSING".equals(operation.state())) return ChatDeliveryReviewContract.receipt(operation.receiptJson());
        try { return transactions.commit(scope, task, operation.operationId(), command); }
        catch (Conflict conflict) { return transactions.reject(scope, task, operation.operationId(), command, "DELIVERY_REVIEW_CONFLICT"); }
        catch (AgentTaskCollaborationException rejected) {
            String code = switch (rejected.getReason()) {
                case INVALID_TRANSITION, VERSION_CONFLICT -> "DELIVERY_REVIEW_CONFLICT";
                case FORBIDDEN -> "DELIVERY_REVIEW_FORBIDDEN";
                case NOT_FOUND -> "DELIVERY_REVIEW_NOT_FOUND";
                default -> null;
            };
            if (code == null) throw rejected;
            return transactions.reject(scope, task, operation.operationId(), command, code);
        }
        // A transient storage/transport failure rolls back decide+receipt, retaining PROCESSING.
        // The same key/body POST can resume it; a GET below never executes this path.
    }
    public Receipt byKey(Scope scope, String task, String key) {
        ChatDeliveryReviewContract.id(task, 100); ChatDeliveryReviewContract.key(key);
        var operation = store.byKey(scope, task, key, false); if (operation == null) throw new Missing(); return receipt(scope, operation);
    }
    public Receipt byOperation(Scope scope, String task, String operationId) {
        ChatDeliveryReviewContract.id(task, 100); ChatDeliveryReviewContract.id(operationId, 100);
        return receipt(scope, store.require(scope, task, operationId, false));
    }
    private Receipt receipt(Scope scope, ChatTaskDeliveryReviewStore.Operation operation) {
        if (!"PROCESSING".equals(operation.state())) return ChatDeliveryReviewContract.receipt(operation.receiptJson());
        return store.progress(scope, operation);
    }
}
