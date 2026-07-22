package cn.jia.agent.common;

import java.util.Locale;
import java.util.Set;

/**
 * Canonical Agent Protocol v1 message and command names.
 *
 * <p>Only {@link #TYPE_COMMAND_DISPATCH} is an execution trigger. Task events
 * describe facts that already happened and must never be interpreted as an
 * execution request.</p>
 */
public final class AgentProtocolConstants {
    public static final int LEGACY_VERSION = 0;
    public static final int VERSION_1 = 1;

    public static final String CATEGORY_CONTROL = "control";
    public static final String CATEGORY_CHAT = "chat";
    public static final String CATEGORY_COMMAND = "command";
    public static final String CATEGORY_PROGRESS = "progress";
    public static final String CATEGORY_RESULT = "result";
    public static final String CATEGORY_EVENT = "event";

    public static final String TYPE_PROTOCOL_HELLO = "protocol.hello";
    public static final String TYPE_PROTOCOL_ERROR = "protocol.error";
    public static final String TYPE_PING = "ping";
    public static final String TYPE_PONG = "pong";
    public static final String TYPE_CHAT_STREAM = "chat.stream";
    public static final String TYPE_CHAT_STOP = "chat.stop";
    public static final String TYPE_AGENT_REGISTER = "agent.register";
    public static final String TYPE_AGENT_PRESENCE = "agent.presence";
    public static final String TYPE_CAPABILITY_LOOKUP = "capability.lookup";
    public static final String TYPE_TASK_ASSIGN_LEGACY = "task.assign";

    public static final String TYPE_CHAT_MESSAGE = "chat.message";
    public static final String TYPE_CHAT_MESSAGE_DELTA = "chat.message.delta";
    public static final String TYPE_COMMAND_DISPATCH = "command.dispatch";
    public static final String TYPE_COMMAND_ACK = "command.ack";
    public static final String TYPE_WORK_PROGRESS = "work.progress";
    public static final String TYPE_WORK_HEARTBEAT = "work.heartbeat";
    public static final String TYPE_WORK_RESULT = "work.result";
    public static final String TYPE_HELP_REQUEST = "help.request";
    public static final String TYPE_ARTIFACT_PUBLISH = "artifact.publish";
    public static final String TYPE_TASK_EVENT = "task.event";

    public static final String LEGACY_AGENT_DIRECT_MESSAGE = "agent_direct_message";
    public static final String LEGACY_AGENT_ACTION = "agent.action";
    public static final String LEGACY_TASK_EVENT = "task_event";
    public static final String LEGACY_TASK_REPORT = "task.report";
    public static final String LEGACY_TASK_REPORT_ALIAS = "task_report";
    public static final String LEGACY_CODEX_RESULT = "codex.result";

    public static final String COMMAND_TASK_INVITE = "TASK_INVITE";
    public static final String COMMAND_WORK_ITEM_EXECUTE = "WORK_ITEM_EXECUTE";
    public static final String COMMAND_WORK_ITEM_RESUME = "WORK_ITEM_RESUME";
    public static final String COMMAND_WORK_ITEM_CANCEL = "WORK_ITEM_CANCEL";
    public static final String COMMAND_REQUEST_RESPOND = "REQUEST_RESPOND";
    public static final String COMMAND_REVIEW_EXECUTE = "REVIEW_EXECUTE";
    public static final String COMMAND_CONTEXT_REFRESH = "CONTEXT_REFRESH";

    private static final Set<String> CANONICAL_TYPES = Set.of(
            TYPE_PROTOCOL_HELLO, TYPE_PROTOCOL_ERROR, TYPE_PING, TYPE_PONG,
            TYPE_CHAT_STREAM, TYPE_CHAT_STOP, TYPE_AGENT_REGISTER, TYPE_AGENT_PRESENCE,
            TYPE_CAPABILITY_LOOKUP, TYPE_TASK_ASSIGN_LEGACY, TYPE_CHAT_MESSAGE,
            TYPE_CHAT_MESSAGE_DELTA, TYPE_COMMAND_DISPATCH, TYPE_COMMAND_ACK,
            TYPE_WORK_PROGRESS, TYPE_WORK_HEARTBEAT, TYPE_WORK_RESULT,
            TYPE_HELP_REQUEST, TYPE_ARTIFACT_PUBLISH, TYPE_TASK_EVENT);

    private AgentProtocolConstants() {
    }

    public static boolean isCanonicalType(String type) {
        return type != null && CANONICAL_TYPES.contains(type);
    }

    public static boolean isExecutionTrigger(String type) {
        return TYPE_COMMAND_DISPATCH.equals(type);
    }

    public static String categoryOf(String type) {
        if (TYPE_CHAT_MESSAGE.equals(type) || TYPE_CHAT_MESSAGE_DELTA.equals(type)) {
            return CATEGORY_CHAT;
        }
        if (TYPE_COMMAND_DISPATCH.equals(type) || TYPE_COMMAND_ACK.equals(type)) {
            return CATEGORY_COMMAND;
        }
        if (TYPE_WORK_PROGRESS.equals(type) || TYPE_WORK_HEARTBEAT.equals(type)
                || TYPE_HELP_REQUEST.equals(type)) {
            return CATEGORY_PROGRESS;
        }
        if (TYPE_WORK_RESULT.equals(type) || TYPE_ARTIFACT_PUBLISH.equals(type)) {
            return CATEGORY_RESULT;
        }
        if (TYPE_TASK_EVENT.equals(type)) {
            return CATEGORY_EVENT;
        }
        return CATEGORY_CONTROL;
    }

    /** Maps the current action vocabulary to a bounded Protocol v1 command type. */
    public static String commandTypeForLegacyAction(String actionType) {
        String normalized = actionType == null ? "" : actionType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "task_briefing", "task_invite" -> COMMAND_TASK_INVITE;
            case "work_item_execute", "execute" -> COMMAND_WORK_ITEM_EXECUTE;
            case "work_item_resume", "resume" -> COMMAND_WORK_ITEM_RESUME;
            case "work_item_cancel", "cancel" -> COMMAND_WORK_ITEM_CANCEL;
            case "ask_help", "request_respond", "respond_request" -> COMMAND_REQUEST_RESPOND;
            case "review", "review_execute" -> COMMAND_REVIEW_EXECUTE;
            default -> COMMAND_CONTEXT_REFRESH;
        };
    }
}
