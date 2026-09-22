package cn.jia.agent.service.impl;

import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentExecutionReportEntity;
import cn.jia.agent.entity.AgentExecutionReportHeadEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.mapper.AgentExecutionReportMapper;
import cn.jia.agent.service.AgentExecutionReportService;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-transaction report inbox. The exact authoritative command/execution row is the first lock
 * and serializes head, receipt and terminal-state decisions for one command. No dispatch, socket
 * I/O or task mutation occurs in this service.
 */
@Service
public class AgentExecutionReportServiceImpl implements AgentExecutionReportService {
    static final String CODEX_RESULT = "CODEX_EXECUTION_RESULT";
    private static final Set<String> TYPES = Set.of(
            "work.progress", "work.heartbeat", "work.result", "help.request", "artifact.publish");
    private static final Set<String> REPORTABLE_DELIVERY_STATES = Set.of(
            "PUBLISHED", "CONSUMED", "SENT", "RECEIVED", "STARTED", "SUCCEEDED",
            "WAITING_AGENT", "RETRY", "FAILED");
    private final PersonalWorkspaceExecutionDao executions;
    private final AgentExecutionReportMapper reports;

    public AgentExecutionReportServiceImpl(PersonalWorkspaceExecutionDao executions,
            AgentExecutionReportMapper reports) {
        this.executions = executions;
        this.reports = reports;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportReceipt accept(RuntimeScope scope, ReportCommand command) {
        try {
            return acceptInTransaction(scope, command);
        } catch (Failure failure) {
            throw failure;
        } catch (DuplicateKeyException conflict) {
            throw new Failure(Reason.CONFLICT, conflict);
        } catch (DataIntegrityViolationException conflict) {
            throw new Failure(Reason.CONFLICT, conflict);
        } catch (ArithmeticException conflict) {
            throw new Failure(Reason.CONFLICT, conflict);
        } catch (DataAccessException unavailable) {
            throw new Failure(Reason.STORAGE_UNAVAILABLE, unavailable);
        }
    }

    private ReportReceipt acceptInTransaction(RuntimeScope scope, ReportCommand command) {
        validateScope(scope);
        if (command == null || !TYPES.contains(command.messageType())) invalid();
        exact(command.messageId(), 100);
        exact(command.commandId(), 100);
        exact(command.dispatchMessageId(), 100);
        String reportId = command.reportId() == null ? command.messageId() : command.reportId();
        exact(reportId, 100);

        Map<String, Object> payload = safePayload(command.messageType(), command.payload());
        boolean codexResult = "work.result".equals(command.messageType())
                && CODEX_RESULT.equals(payload.get("resultType"));
        if (codexResult && !reportId.equals(command.messageId())) invalid();

        CommandBinding binding;
        if (codexResult && command.executionRef() == null) {
            binding = lockCodexCommand(scope, command);
        } else {
            exact(command.executionRef(), 100);
            binding = lockWorkspaceExecution(scope, command, command.executionRef(), false);
        }
        String executionRef = binding.executionRef();
        long grantRevision = codexResult && command.grantRevision() == 0
                ? binding.grantRevision() : command.grantRevision();
        int attempt = codexResult && command.attempt() == 0
                ? binding.attempt() : command.attempt();
        String fencingToken = codexResult && command.fencingToken() == null
                ? binding.fencingToken() : command.fencingToken();
        if (grantRevision != binding.grantRevision() || attempt != binding.attempt()
                || !same(fencingToken, binding.fencingToken())) {
            notFound();
        }

        AgentExecutionReportHeadEntity head = reports.lockHead(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), scope.agentId(), command.commandId(), attempt);
        long now = System.currentTimeMillis();
        if (head == null) {
            head = new AgentExecutionReportHeadEntity()
                    .setOwnerJiacn(scope.ownerJiacn()).setAgentId(scope.agentId())
                    .setRuntimeInstanceId(scope.runtimeInstanceId())
                    .setExecutionRef(executionRef).setCommandId(command.commandId()).setAttempt(attempt)
                    .setLastSequence(0L).setCommittedVersion(0L)
                    .setTerminalReportId(null).setTerminalResultRef(null);
            head.setTenantId(scope.tenantId());
            head.setClientId(scope.clientId());
            head.setCreateTime(now);
            head.setUpdateTime(now);
            if (reports.insertHead(head) != 1 || head.getId() == null) unavailable();
        } else if (!same(head.getExecutionRef(), executionRef)
                || !same(head.getRuntimeInstanceId(), scope.runtimeInstanceId())) {
            notFound();
        }

        AgentExecutionReportEntity existing = reports.lockReport(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), scope.agentId(), reportId);
        long sequence = codexResult && command.sequence() == 0
                ? existing == null ? Math.addExact(head.getLastSequence(), 1L) : existing.getSequence()
                : command.sequence();
        long occurredAt = codexResult && command.occurredAt() == 0
                ? existing == null ? now : existing.getOccurredAt() : command.occurredAt();
        if (sequence < 1 || occurredAt < 1) invalid();

        byte[] semanticHash = semanticHash(scope, command, reportId, executionRef, grantRevision,
                attempt, fencingToken, sequence, occurredAt, payload);
        if (existing != null) {
            if (!MessageDigest.isEqual(semanticHash, existing.getSemanticHash())) conflict();
            return new ReportReceipt(existing.getReportId(), existing.getResultRef(),
                    Long.toString(existing.getCommittedVersion()), true);
        }
        if (head.getTerminalReportId() != null || sequence <= head.getLastSequence()) conflict();

        long committedVersion = Math.addExact(head.getCommittedVersion(), 1L);
        String resultRef = resultRef(scope, reportId, semanticHash);
        AgentExecutionReportEntity report = new AgentExecutionReportEntity()
                .setReportId(reportId).setOwnerJiacn(scope.ownerJiacn()).setAgentId(scope.agentId())
                .setRuntimeInstanceId(scope.runtimeInstanceId()).setMessageType(command.messageType())
                .setMessageId(command.messageId()).setCommandId(command.commandId())
                .setDispatchMessageId(command.dispatchMessageId()).setExecutionRef(executionRef)
                .setGrantRevision(grantRevision).setAttempt(attempt).setFencingToken(fencingToken)
                .setSequence(sequence).setOccurredAt(occurredAt).setSemanticHash(semanticHash)
                .setPayloadJson(json(payload).getBytes(StandardCharsets.UTF_8))
                .setResultRef(resultRef).setCommittedVersion(committedVersion);
        report.setTenantId(scope.tenantId());
        report.setClientId(scope.clientId());
        report.setCreateTime(now);
        report.setUpdateTime(now);
        if (reports.insertReport(report) != 1) unavailable();

        boolean terminal = "work.result".equals(command.messageType());
        String terminalReportId = terminal ? reportId : null;
        String terminalResultRef = terminal ? resultRef : null;
        if (reports.advanceHead(head, scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                scope.agentId(), command.commandId(), attempt, sequence, committedVersion,
                terminalReportId, terminalResultRef, now) != 1) {
            conflict();
        }
        return new ReportReceipt(reportId, resultRef, Long.toString(committedVersion), false);
    }

    private CommandBinding lockCodexCommand(RuntimeScope scope, ReportCommand command) {
        AgentCommandDeliveryEntity delivery = reports.lockTransportDelivery(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), command.commandId());
        if (delivery != null) {
            if (!visibleDelivery(scope, command, delivery)) notFound();
            int attempt = delivery.getActiveAttempt();
            return new CommandBinding(transportExecutionRef(scope, command.commandId(),
                    command.dispatchMessageId(), attempt),
                    attempt, attempt, delivery.getActiveMessageId());
        }
        PersonalWorkspaceExecutionEntity execution = reports.lockExecutionByCommand(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), scope.agentId(),
                command.commandId(), command.dispatchMessageId());
        return workspaceBinding(scope, command, execution, true);
    }

    private CommandBinding lockWorkspaceExecution(RuntimeScope scope, ReportCommand command,
            String executionRef, boolean codexResult) {
        PersonalWorkspaceExecutionEntity execution = executions.lock(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), executionRef);
        return workspaceBinding(scope, command, execution, codexResult);
    }

    private CommandBinding workspaceBinding(RuntimeScope scope, ReportCommand command,
            PersonalWorkspaceExecutionEntity execution, boolean codexResult) {
        if (!visibleExecution(scope, execution)) notFound();
        String executionRef = execution.getExecutionId();
        Long revision = execution.getGrantRevision();
        if (revision == null || revision < 1
                || !same(command.commandId(), commandId(executionRef))
                || !same(command.dispatchMessageId(), dispatchMessageId(executionRef))
                || (!codexResult && !same(command.executionRef(), executionRef))) {
            notFound();
        }
        return new CommandBinding(executionRef, revision, 1, Long.toString(revision));
    }

    private static boolean visibleDelivery(RuntimeScope scope, ReportCommand command,
            AgentCommandDeliveryEntity delivery) {
        Integer attempt = delivery.getActiveAttempt();
        byte[] payload = delivery.getCommandPayload();
        byte[] storedHash = delivery.getCommandPayloadHash();
        return same(scope.tenantId(), delivery.getTenantId())
                && same(scope.clientId(), delivery.getClientId())
                && same(scope.ownerJiacn(), delivery.getOwnerJiacn())
                && same(scope.agentId(), delivery.getTargetAgentId())
                && same(command.commandId(), delivery.getCommandId())
                && same(command.dispatchMessageId(), delivery.getActiveMessageId())
                && validText(delivery.getCommandType(), 64)
                && REPORTABLE_DELIVERY_STATES.contains(delivery.getStatus())
                && attempt != null && attempt > 0
                && same(attempt, delivery.getAttemptCount())
                && delivery.getVersion() != null && delivery.getVersion() >= 0
                && payload != null && storedHash != null && storedHash.length == 32
                && MessageDigest.isEqual(sha256(payload), storedHash);
    }

    private static boolean visibleExecution(RuntimeScope scope, PersonalWorkspaceExecutionEntity execution) {
        if (execution == null || !same(scope.tenantId(), execution.getTenantId())
                || !same(scope.clientId(), execution.getClientId())
                || !same(scope.ownerJiacn(), execution.getOwnerJiacn())
                || !same(scope.agentId(), execution.getTargetAgentId())) return false;
        String state = execution.getExecutionState();
        return "QUEUED".equals(state) || "OUTPUT_STAGED".equals(state)
                || "OUTPUT_COMMITTED".equals(state);
    }

    private static Map<String, Object> safePayload(String type, Map<String, Object> raw) {
        Map<String, Object> source = raw == null ? Map.of() : raw;
        return switch (type) {
            case "work.progress" -> project(source, Set.of("status", "summary", "percent"), map -> {
                boundedText(map, "status", 32, true);
                boundedText(map, "summary", 1000, false);
                Integer percent = exactInteger(map.get("percent"));
                if (map.containsKey("percent") && (percent == null || percent < 0 || percent > 100)) invalid();
            });
            case "work.heartbeat" -> project(source, Set.of("status"), map ->
                    boundedText(map, "status", 32, true));
            case "work.result" -> resultPayload(source);
            case "help.request" -> project(source, Set.of("reasonCode", "summary"), map -> {
                boundedText(map, "reasonCode", 64, true);
                boundedText(map, "summary", 1000, false);
            });
            case "artifact.publish" -> project(source,
                    Set.of("artifactId", "artifactVersion", "stageRef", "sha256", "byteLength", "contentType"),
                    map -> {
                        boundedText(map, "artifactId", 100, true);
                        boundedText(map, "artifactVersion", 16, true);
                        boundedText(map, "stageRef", 100, true);
                        digest(map, "sha256");
                        decimal(map, "byteLength");
                        boundedText(map, "contentType", 127, true);
                    });
            default -> throw new Failure(Reason.INVALID_REQUEST);
        };
    }

    private static Map<String, Object> resultPayload(Map<String, Object> source) {
        if (CODEX_RESULT.equals(source.get("resultType"))) {
            return project(source, Set.of("resultType", "status", "exitCode"), map -> {
                if (!CODEX_RESULT.equals(map.get("resultType"))) invalid();
                if (!("SUCCEEDED".equals(map.get("status")) || "FAILED".equals(map.get("status")))) invalid();
                if (exactInteger(map.get("exitCode")) == null) invalid();
            });
        }
        return project(source, Set.of("outcome", "exitCode", "failureCode", "summary",
                "manifestDigest", "outputStageRefs"), map -> {
            Object outcome = map.get("outcome");
            if (!("SUCCEEDED".equals(outcome) || "FAILED".equals(outcome)
                    || "CANCELLED".equals(outcome))) invalid();
            if (map.containsKey("exitCode") && exactInteger(map.get("exitCode")) == null) invalid();
            boundedText(map, "failureCode", 64, false);
            boundedText(map, "summary", 1000, false);
            if (map.containsKey("manifestDigest")) digest(map, "manifestDigest");
            if (map.containsKey("outputStageRefs")) {
                Object refs = map.get("outputStageRefs");
                if (!(refs instanceof List<?> list) || list.size() > 32) invalid();
                for (Object value : list) if (!(value instanceof String text) || !validText(text, 100)) invalid();
            }
        });
    }

    private static Map<String, Object> project(Map<String, Object> source, Set<String> allowed,
            java.util.function.Consumer<Map<String, Object>> validator) {
        if (!allowed.containsAll(source.keySet())) invalid();
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), immutableValue(entry.getValue())));
        validator.accept(result);
        return Map.copyOf(result);
    }

    private static Object immutableValue(Object value) {
        if (value == null) invalid();
        if (value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) return value;
        if (value instanceof List<?> list) return List.copyOf(list);
        invalid();
        return null;
    }

    private static void validateScope(RuntimeScope scope) {
        if (scope == null || !"0".equals(scope.tenantId())) invalid();
        exact(scope.clientId(), 50);
        exact(scope.ownerJiacn(), 50);
        exact(scope.agentId(), 100);
        exact(scope.runtimeInstanceId(), 100);
        if ("0".equals(scope.ownerJiacn()) || scope.agentId().equals(scope.runtimeInstanceId())) invalid();
    }

    private static void boundedText(Map<String, Object> map, String key, int max, boolean required) {
        Object value = map.get(key);
        if (value == null && !required) return;
        if (!(value instanceof String text) || !validText(text, max)) invalid();
    }

    private static void digest(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || !text.matches("[0-9a-f]{64}")) invalid();
    }

    private static void decimal(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || !text.matches("0|[1-9][0-9]{0,15}")) invalid();
    }

    private static Integer exactInteger(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer
                ? ((Number) value).intValue() : null;
    }

    private static void exact(String value, int max) {
        if (!validText(value, max)) invalid();
    }

    private static boolean validText(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= max
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static byte[] semanticHash(RuntimeScope scope, ReportCommand command, String reportId,
            String executionRef, long grantRevision, int attempt, String fencingToken,
            long sequence, long occurredAt, Map<String, Object> payload) {
        String canonical = String.join("\n", scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                scope.agentId(), scope.runtimeInstanceId(), command.messageType(), command.messageId(),
                reportId, command.commandId(), command.dispatchMessageId(), executionRef,
                Long.toString(grantRevision), Integer.toString(attempt), fencingToken,
                Long.toString(sequence), Long.toString(occurredAt), json(payload));
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String resultRef(RuntimeScope scope, String reportId, byte[] semanticHash) {
        byte[] value = (scope.tenantId() + "\n" + scope.clientId() + "\n" + scope.ownerJiacn()
                + "\n" + scope.agentId() + "\n" + reportId + "\n"
                + HexFormat.of().formatHex(semanticHash)).getBytes(StandardCharsets.UTF_8);
        return "rr_" + HexFormat.of().formatHex(sha256(value));
    }

    static String commandId(String executionRef) {
        return "pwe_cmd_" + HexFormat.of().formatHex(sha256(
                ("command\n" + executionRef).getBytes(StandardCharsets.UTF_8)));
    }

    static String dispatchMessageId(String executionRef) {
        return "pwe_msg_" + HexFormat.of().formatHex(sha256(
                ("message\n" + executionRef).getBytes(StandardCharsets.UTF_8)));
    }

    static String transportExecutionRef(RuntimeScope scope, String commandId,
            String dispatchMessageId, int attempt) {
        byte[] value = String.join("\n", "delivery", scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), scope.agentId(), commandId, dispatchMessageId,
                Integer.toString(attempt)).getBytes(StandardCharsets.UTF_8);
        return "delivery_" + HexFormat.of().formatHex(sha256(value));
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String json(Map<String, Object> map) {
        StringBuilder out = new StringBuilder("{");
        List<Map.Entry<String, Object>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) out.append(',');
            string(out, entries.get(i).getKey());
            out.append(':');
            jsonValue(out, entries.get(i).getValue());
        }
        return out.append('}').toString();
    }

    private static void jsonValue(StringBuilder out, Object value) {
        if (value == null) out.append("null");
        else if (value instanceof String text) string(out, text);
        else if (value instanceof Boolean || value instanceof Number) out.append(value);
        else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                jsonValue(out, list.get(i));
            }
            out.append(']');
        } else invalid();
    }

    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static boolean same(Object left, Object right) { return left != null && left.equals(right); }
    private static void invalid() { throw new Failure(Reason.INVALID_REQUEST); }
    private static void notFound() { throw new Failure(Reason.NOT_FOUND); }
    private static void conflict() { throw new Failure(Reason.CONFLICT); }
    private static void unavailable() { throw new Failure(Reason.STORAGE_UNAVAILABLE); }

    private record CommandBinding(String executionRef, long grantRevision, int attempt,
            String fencingToken) { }
}
