package cn.jia.agent.output;

import java.util.List;
import java.util.Set;

/** Frozen R1 output-delivery vocabulary shared by HTTP and Agent Protocol. */
public final class OutputConstants {
    public static final String SOURCE_CONVERSATION = "CONVERSATION";
    public static final String SOURCE_TASK = "TASK";

    public static final String RUN_ACTIVE = "ACTIVE";
    public static final String RUN_RESULT_SUBMITTED = "RESULT_SUBMITTED";
    public static final String RUN_CLOSED = "CLOSED";
    public static final String RUN_REVOKED = "REVOKED";

    public static final String OWNERSHIP_ACTIVE = "ACTIVE";
    public static final String OWNERSHIP_REVOKED = "REVOKED";

    public static final String CAPABILITY_HTTP_V1 = "output.http.v1";
    public static final String CAPABILITY_OWNER_SHARE_V1 = "task.owner-share.v1";
    public static final String CAPABILITY_DELIVERY_HTTP_V1 = "task.delivery-http.v1";
    public static final Set<String> R1_REGISTERED_CAPABILITIES = Set.of(
            CAPABILITY_HTTP_V1, CAPABILITY_OWNER_SHARE_V1);

    public static final String OP_UPLOAD = "upload";
    public static final String OP_PUBLISH = "publish";
    public static final String OP_STATUS = "status";
    public static final List<String> R1_TICKET_OPERATIONS = List.of(OP_UPLOAD, OP_PUBLISH, OP_STATUS);

    public static final long CAPABILITY_FRESHNESS_MILLIS = 90_000L;
    public static final long RUN_RECOVERY_MILLIS = 86_400_000L;
    public static final long TICKET_TTL_MILLIS = 900_000L;
    public static final long DEFAULT_MAX_FILE_BYTES = 52_428_800L;
    public static final long DEFAULT_MAX_RUN_BYTES = 209_715_200L;
    public static final int DEFAULT_MAX_FILES = 100;
    public static final int AUTH_REQUESTS_PER_MINUTE = 60;
    public static final String TICKET_AUDIENCE = "output-api";

    private OutputConstants() {
    }
}
