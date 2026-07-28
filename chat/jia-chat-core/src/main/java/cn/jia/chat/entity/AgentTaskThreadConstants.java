package cn.jia.chat.entity;

public final class AgentTaskThreadConstants {
    public static final String THREAD_TYPE_TEAM = "team";
    public static final String THREAD_KEY_TEAM = "team";
    public static final String THREAD_STATUS_ACTIVE = "active";
    public static final String CONVERSATION_TYPE = "juyiting";
    public static final String CONVERSATION_SCOPE_TYPE = "task_thread";
    /** Explicitly excluded from the legacy jiacn-only long-term-memory pipeline. */
    public static final String MEMORY_SYNC_EXCLUDED = "EXCLUDED";

    private AgentTaskThreadConstants() {
    }

    public static boolean isTaskThreadConversation(ChatConversationEntity conversation) {
        return conversation != null
                && CONVERSATION_SCOPE_TYPE.equals(conversation.getConversationScopeType());
    }

    /** Detect canonical and corrupted case/whitespace/control-character forms of the reserved marker. */
    public static boolean hasTaskThreadMarkerEvidence(ChatConversationEntity conversation) {
        if (conversation == null || conversation.getConversationScopeType() == null) {
            return false;
        }
        String value = conversation.getConversationScopeType();
        String withoutControls = value.chars()
                .filter(character -> !Character.isISOControl(character))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        return CONVERSATION_SCOPE_TYPE.equalsIgnoreCase(withoutControls.strip());
    }
}
