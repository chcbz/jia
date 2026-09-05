package cn.jia.agent.common;

public final class AgentConstants {
    public static final String STATUS_ONLINE = "online";
    public static final String STATUS_BUSY = "busy";
    public static final String STATUS_OFFLINE = "offline";
    public static final String STATUS_ERROR = "error";

    public static final String BUILTIN_SONGJIANG_AGENT_ID = "builtin-songjiang";
    public static final String BUILTIN_SONGJIANG_PERSONA_CODE = "songjiang";
    public static final int BINDING_STATUS_PROVISIONED = 2;
    public static final int BINDING_STATUS_ACTIVE = 1;
    public static final int BINDING_STATUS_SUSPENDED = 0;
    public static final int BINDING_STATUS_INACTIVE = BINDING_STATUS_SUSPENDED;
    public static final int BINDING_STATUS_RETIRED = 3;

    public static final String IDENTITY_TYPE_OPAQUE = "OPAQUE";
    public static final String IDENTITY_TYPE_LEGACY_CANONICAL = "LEGACY_CANONICAL";
    public static final String IDENTITY_TYPE_SYSTEM = "SYSTEM";
    public static final String IDENTITY_STATUS_PROVISIONED = "PROVISIONED";
    public static final String IDENTITY_STATUS_ACTIVE = "ACTIVE";
    public static final String IDENTITY_STATUS_SUSPENDED = "SUSPENDED";
    public static final String IDENTITY_STATUS_RETIRED = "RETIRED";
    public static final String IDENTITY_ALIAS_TYPE_LEGACY_AGENT_ID = "LEGACY_AGENT_ID";
    public static final String IDENTITY_ALIAS_STATUS_ACTIVE = "ACTIVE";
    public static final String IDENTITY_ALIAS_STATUS_REVOKED = "REVOKED";

    public static final String TASK_TYPE_AGENT = "agent";
    public static final String TASK_STATUS_OPEN = "open";
    public static final String TASK_STATUS_ASSIGNED = "assigned";
    public static final String TASK_STATUS_RUNNING = "running";
    public static final String TASK_STATUS_COMPLETED = "completed";
    public static final String TASK_STATUS_FAILED = "failed";
    public static final String TASK_STATUS_CANCELLED = "cancelled";
    public static final String TASK_STATUS_ARCHIVED = "archived";

    private AgentConstants() {
    }
}
