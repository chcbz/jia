package cn.jia.chat.deliberation;

public final class ChatDeliberationStates {
    public static final String RECEIVED = "RECEIVED";
    public static final String QUEUED = "QUEUED";
    public static final String DISPATCHED = "DISPATCHED";
    public static final String STREAMING = "STREAMING";
    public static final String FINAL_PERSISTED = "FINAL_PERSISTED";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String RECOVERY_REQUIRED = "RECOVERY_REQUIRED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String RUNNING = "RUNNING";
    public static final String PARTIAL = "PARTIAL";
    public static final String COMPLETED = "COMPLETED";

    private ChatDeliberationStates() { }
}
