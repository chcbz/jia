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

    public static final long MAX_SCOPE_BYTES = 1_073_741_824L;
    public static final int MAX_BINDING_UPLOADS = 2;
    public static final int MAX_SCOPE_UPLOADS = 8;
    public static final long UPLOAD_SESSION_MILLIS = 86_400_000L;
    public static final long WRITER_LEASE_MILLIS = 300_000L;
    public static final long UPLOAD_HARD_DEADLINE_MILLIS = 600_000L;
    public static final long JOB_LEASE_MILLIS = 60_000L;

    public static final String UPLOAD_CREATED = "CREATED";
    public static final String UPLOAD_UPLOADING = "UPLOADING";
    public static final String UPLOAD_VERIFYING = "VERIFYING";
    public static final String UPLOAD_READY = "READY";
    public static final String UPLOAD_REJECTED = "REJECTED";
    public static final String UPLOAD_EXPIRED = "EXPIRED";

    private OutputConstants() {
    }
}
