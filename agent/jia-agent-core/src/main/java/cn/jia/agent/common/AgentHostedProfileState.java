package cn.jia.agent.common;

import java.util.Set;

public final class AgentHostedProfileState {
    public static final String PREPARED = "PREPARED";
    public static final String STAGED_DISABLED = "STAGED_DISABLED";
    public static final String FILE_ENABLED = "FILE_ENABLED";
    public static final String ACTIVE = "ACTIVE";
    public static final String SUSPENDING = "SUSPENDING";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String REPAIR_REQUIRED = "REPAIR_REQUIRED";
    public static final Set<String> VALUES = Set.of(
            PREPARED, STAGED_DISABLED, FILE_ENABLED, ACTIVE,
            SUSPENDING, SUSPENDED, REPAIR_REQUIRED);

    private AgentHostedProfileState() {}
}
