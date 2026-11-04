package cn.jia.chat.service;

import java.util.function.Supplier;

/** Current private CHAT admission, not permission to perform the callback's business operation.
 * The authenticated transport owns the surrounding native identity transaction; this adapter
 * joins it and holds the conversation/turn locks through the callback. */
public interface PrivateConversationToolAccess {
    final class Denied extends RuntimeException {
        public Denied() {super("PRIVATE_CONVERSATION_TOOL_FORBIDDEN");}
    }
    record Subject(String tenantId,String clientId,String ownerJiacn,String agentId) { }
    <T> T withCurrentTurn(Subject subject,String requestId,String turnId,Supplier<T> operation);
}
