package cn.jia.agent.entity;

/** Target/lane-only scheduling hint; historical class name, never command or authorization. */
public record NativeConversationWorkAvailable(String tenantId, String clientId, String agentId, String lane) {
    public NativeConversationWorkAvailable(String tenantId,String clientId,String agentId) {
        this(tenantId,clientId,agentId,"NATIVE_CONVERSATION");
    }
}
