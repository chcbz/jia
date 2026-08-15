package cn.jia.agent.common;

import java.util.Set;

/**
 * C01 stable task event type classifications (§7.5).
 *
 * <p>Every append must pass {@link #requireKnown(String)}.
 * C01B will wire these into B03-B08 business write paths.
 * C01H will introduce HISTORICAL_BASELINE_IMPORTED for B09 migration.
 */
public final class TaskEventType {
    private TaskEventType() {
    }

    // ── Task lifecycle ──
    public static final String TASK_CREATED = "TASK_CREATED";
    public static final String TASK_ASSIGNED = "TASK_ASSIGNED";
    public static final String TASK_STARTED = "TASK_STARTED";
    public static final String TASK_BLOCKED = "TASK_BLOCKED";
    public static final String TASK_ARCHIVED = "TASK_ARCHIVED";
    public static final String TEAM_PROPOSED = "TEAM_PROPOSED";
    public static final String TASK_REVIEWING = "TASK_REVIEWING";
    public static final String TASK_COMPLETED = "TASK_COMPLETED";
    public static final String TASK_FAILED = "TASK_FAILED";
    public static final String TASK_CANCELLED = "TASK_CANCELLED";

    // ── Member lifecycle ──
    public static final String MEMBER_INVITED = "MEMBER_INVITED";
    public static final String MEMBER_ACCEPTED = "MEMBER_ACCEPTED";
    public static final String MEMBER_REJECTED = "MEMBER_REJECTED";
    public static final String MEMBER_BLOCKED = "MEMBER_BLOCKED";
    public static final String MEMBER_WORKING = "MEMBER_WORKING";
    public static final String MEMBER_DONE = "MEMBER_DONE";
    public static final String MEMBER_FAILED = "MEMBER_FAILED";
    public static final String MEMBER_LEFT = "MEMBER_LEFT";

    // ── Work item lifecycle ──
    public static final String WORK_ITEM_CREATED = "WORK_ITEM_CREATED";
    public static final String WORK_ITEM_READY = "WORK_ITEM_READY";
    public static final String WORK_ITEM_CLAIMED = "WORK_ITEM_CLAIMED";
    public static final String WORK_ITEM_STARTED = "WORK_ITEM_STARTED";
    public static final String WORK_ITEM_SUBMITTED = "WORK_ITEM_SUBMITTED";
    public static final String WORK_ITEM_COMPLETED = "WORK_ITEM_COMPLETED";
    public static final String WORK_ITEM_REQUEUED = "WORK_ITEM_REQUEUED";
    public static final String WORK_ITEM_BLOCKED = "WORK_ITEM_BLOCKED";
    public static final String WORK_ITEM_FAILED = "WORK_ITEM_FAILED";
    public static final String WORK_ITEM_CANCELLED = "WORK_ITEM_CANCELLED";
    public static final String WORK_ITEM_LEASE_RENEWED = "WORK_ITEM_LEASE_RENEWED";
    public static final String WORK_ITEM_LEASE_RELEASED = "WORK_ITEM_LEASE_RELEASED";

    // ── Communication ──
    public static final String PROGRESS_REPORTED = "PROGRESS_REPORTED";
    public static final String HELP_REQUESTED = "HELP_REQUESTED";
    public static final String REVIEW_REQUESTED = "REVIEW_REQUESTED";
    public static final String REQUEST_CREATED = "REQUEST_CREATED";
    public static final String REQUEST_ACKNOWLEDGED = "REQUEST_ACKNOWLEDGED";
    public static final String REQUEST_RESOLVED = "REQUEST_RESOLVED";
    public static final String REQUEST_REJECTED = "REQUEST_REJECTED";
    public static final String REQUEST_CANCELLED = "REQUEST_CANCELLED";

    // ── Task thread communication ──
    public static final String THREAD_CREATED = "THREAD_CREATED";
    public static final String MESSAGE_POSTED = "MESSAGE_POSTED";

    // ── Artifact ──
    public static final String ARTIFACT_PUBLISHED = "ARTIFACT_PUBLISHED";

    // ── Infrastructure ──
    public static final String COMMAND_DELIVERY_FAILED = "COMMAND_DELIVERY_FAILED";

    // ── Historical baseline (C01H) ──
    public static final String HISTORICAL_BASELINE_IMPORTED = "HISTORICAL_BASELINE_IMPORTED";

    private static final Set<String> KNOWN_TYPES = Set.of(
            TASK_CREATED, TASK_ASSIGNED, TASK_STARTED, TASK_BLOCKED, TASK_ARCHIVED,
            TEAM_PROPOSED, TASK_REVIEWING, TASK_COMPLETED, TASK_FAILED, TASK_CANCELLED,
            MEMBER_INVITED, MEMBER_ACCEPTED, MEMBER_REJECTED, MEMBER_BLOCKED,
            MEMBER_WORKING, MEMBER_DONE, MEMBER_FAILED, MEMBER_LEFT,
            WORK_ITEM_CREATED, WORK_ITEM_READY, WORK_ITEM_CLAIMED, WORK_ITEM_STARTED,
            WORK_ITEM_SUBMITTED, WORK_ITEM_COMPLETED, WORK_ITEM_REQUEUED,
            WORK_ITEM_BLOCKED, WORK_ITEM_FAILED, WORK_ITEM_CANCELLED,
            WORK_ITEM_LEASE_RENEWED, WORK_ITEM_LEASE_RELEASED,
            PROGRESS_REPORTED, HELP_REQUESTED, REVIEW_REQUESTED,
            REQUEST_CREATED, REQUEST_ACKNOWLEDGED, REQUEST_RESOLVED,
            REQUEST_REJECTED, REQUEST_CANCELLED, THREAD_CREATED, MESSAGE_POSTED,
            ARTIFACT_PUBLISHED,
            COMMAND_DELIVERY_FAILED,
            HISTORICAL_BASELINE_IMPORTED
    );

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
        public static final String THREAD = "thread";
        public static final String MESSAGE = "message";

        private static final Set<String> KNOWN = Set.of(
                TASK, MEMBER, WORK_ITEM, REQUEST, ARTIFACT, THREAD, MESSAGE);

        public static String requireKnown(String aggregateType) {
            if (aggregateType == null || !KNOWN.contains(aggregateType)) {
                throw new IllegalArgumentException(
                        "Unknown aggregateType: " + aggregateType
                        + "; expected one of " + KNOWN);
            }
            return aggregateType;
        }
    }

    /**
     * Actor type classifications matching {@code agent_task_event.actor_type}.
     */
    public static final class ActorType {
        private ActorType() {
        }

        public static final String AGENT = "agent";
        public static final String ROLE = "role";
        public static final String SYSTEM = "system";

        private static final Set<String> KNOWN = Set.of(AGENT, ROLE, SYSTEM);

        /**
         * Validate that the given actor type is a known constant (fail-closed).
         *
         * @return the validated value
         * @throws IllegalArgumentException if null or unknown
         */
        public static String requireKnown(String actorType) {
            if (actorType == null || !KNOWN.contains(actorType)) {
                throw new IllegalArgumentException(
                        "Unknown actorType: " + actorType
                        + "; expected one of " + KNOWN);
            }
            return actorType;
        }
    }


    /**
     * Validate that the given event type is a known constant (fail-closed).
     *
     * @return the validated value
     * @throws IllegalArgumentException if the type is null, blank, or unknown
     */
    public static String requireKnown(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (!KNOWN_TYPES.contains(eventType)) {
            throw new IllegalArgumentException(
                    "Unknown eventType: " + eventType
                    + "; expected one of " + KNOWN_TYPES);
        }
        return eventType;
    }
}
