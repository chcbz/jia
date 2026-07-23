package cn.jia.chat.entity;

public final class AgentTaskThreadConstants {
    public static final String THREAD_TYPE_TEAM = "team";
    public static final String THREAD_KEY_TEAM = "team";
    public static final String THREAD_STATUS_ACTIVE = "active";
    public static final String CONVERSATION_TYPE = "juyiting";
    public static final String CONVERSATION_SCOPE_TYPE = "task_thread";

    private AgentTaskThreadConstants() {
    }

    public static boolean isTaskThreadConversation(ChatConversationEntity conversation) {
        return conversation != null
                && CONVERSATION_SCOPE_TYPE.equals(conversation.getConversationScopeType());
    }
}
