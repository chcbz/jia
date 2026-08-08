package cn.jia.agent.common;

/**
 * C01 stable task event type classifications.
 *
 * <p>Each event type is a SCREAMING_SNAKE_CASE constant.
 * C01B will wire these into B03-B08 business write paths.
 * C01H will introduce HISTORICAL_BASELINE_IMPORTED for B09 migration.
 */
public final class TaskEventType {
    private TaskEventType() {
    }

    // ── Task aggregate ──
    public static final String TASK_CREATED = "TASK_CREATED";
    public static final String TASK_STATUS_CHANGED = "TASK_STATUS_CHANGED";
    public static final String TASK_ASSIGNED = "TASK_ASSIGNED";
    public static final String TASK_COLLABORATION_MODE_CHANGED = "TASK_COLLABORATION_MODE_CHANGED";

    // ── Member aggregate ──
    public static final String MEMBER_JOINED = "MEMBER_JOINED";
    public static final String MEMBER_STATUS_CHANGED = "MEMBER_STATUS_CHANGED";
    public static final String MEMBER_REPORTED = "MEMBER_REPORTED";

    // ── Work item aggregate ──
    public static final String WORK_ITEM_CREATED = "WORK_ITEM_CREATED";
    public static final String WORK_ITEM_STATUS_CHANGED = "WORK_ITEM_STATUS_CHANGED";
    public static final String WORK_ITEM_CLAIMED = "WORK_ITEM_CLAIMED";
    public static final String WORK_ITEM_RESULT_SUBMITTED = "WORK_ITEM_RESULT_SUBMITTED";
    public static final String WORK_ITEM_RESULT_ACCEPTED = "WORK_ITEM_RESULT_ACCEPTED";

    // ── Request aggregate ──
    public static final String REQUEST_CREATED = "REQUEST_CREATED";
    public static final String REQUEST_RESOLVED = "REQUEST_RESOLVED";

    // ── Artifact aggregate ──
    public static final String ARTIFACT_PUBLISHED = "ARTIFACT_PUBLISHED";

    // ── Historical baseline (C01H) ──
    public static final String HISTORICAL_BASELINE_IMPORTED = "HISTORICAL_BASELINE_IMPORTED";

    /**
     * Aggregate type labels matching {@code agent_task_event.aggregate_type}.
     */
    public static final class Aggregate {
        private Aggregate() {
        }

        public static final String TASK = "task";
        public static final String MEMBER = "member";
        public static final String WORK_ITEM = "work_item";
        public static final String REQUEST = "request";
        public static final String ARTIFACT = "artifact";
    }

    /**
     * Validate that the given event type is a known constant.
     *
     * @return the validated value (fail-closed on unknown types)
     * @throws IllegalArgumentException if the type is unknown
     */
    public static String requireKnown(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        // Allow all known types plus the C01H baseline type
        return switch (eventType) {
            case TASK_CREATED, TASK_STATUS_CHANGED, TASK_ASSIGNED, TASK_COLLABORATION_MODE_CHANGED,
                 MEMBER_JOINED, MEMBER_STATUS_CHANGED, MEMBER_REPORTED,
                 WORK_ITEM_CREATED, WORK_ITEM_STATUS_CHANGED, WORK_ITEM_CLAIMED,
                 WORK_ITEM_RESULT_SUBMITTED, WORK_ITEM_RESULT_ACCEPTED,
                 REQUEST_CREATED, REQUEST_RESOLVED,
                 ARTIFACT_PUBLISHED, HISTORICAL_BASELINE_IMPORTED -> eventType;
            default ->
                throw new IllegalArgumentException("Unknown eventType: " + eventType);
        };
    }
}
