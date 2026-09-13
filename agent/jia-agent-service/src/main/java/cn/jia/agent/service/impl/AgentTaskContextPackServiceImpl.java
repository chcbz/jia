package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskContextPackDao;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeViewDTO;
import cn.jia.agent.entity.AgentTaskContextPackDTO;
import cn.jia.agent.entity.AgentTaskContextPackTaskSourceRow;
import cn.jia.agent.entity.AgentTaskWorkspaceDTO;
import cn.jia.agent.exception.AgentTaskContextPackException;
import cn.jia.agent.exception.AgentTaskWorkspaceException;
import cn.jia.agent.service.AgentTaskArtifactOutcomeService;
import cn.jia.agent.service.AgentTaskContextPackConversationSource;
import cn.jia.agent.service.AgentTaskContextPackService;
import cn.jia.agent.service.AgentTaskWorkspaceService;
import cn.jia.core.util.JsonUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Generates one deterministic Context Pack from a single repeatable-read snapshot.
 * Lock order is not applicable: F01 performs no locking and no writes.
 */
@Named
public class AgentTaskContextPackServiceImpl implements AgentTaskContextPackService {
    static final String SCHEMA_VERSION = "f01-context-pack-v1";
    static final int MAX_MEMBERS = 100;
    static final int MAX_WORK_ITEMS = 25;
    static final int MAX_ARTIFACTS = 50;
    static final int MAX_REQUESTS = 25;
    static final int MAX_DEPENDENCIES_PER_ITEM = 25;
    static final int MAX_DEPENDENCY_JSON_UTF8_BYTES = 64 * 1024;
    static final int MAX_TEXT_UTF8_BYTES = 2048;
    static final String REDACTED_TEXT = "[REDACTED_SENSITIVE_TEXT]";

    private static final String AVAILABLE = "AVAILABLE";
    private static final String TRUNCATED = "TRUNCATED";
    private static final String UNAVAILABLE = "UNAVAILABLE";
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(authorization\\s*:|bearer\\s+[\\p{Alnum}._~+/=-]{8,}"
                    + "|(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|secret|token)\\s*[:=]"
                    + "|-----BEGIN [A-Z ]*PRIVATE KEY-----"
                    + "|(?:https?|mysql|postgres(?:ql)?)://[^\\s/:]+:[^\\s/@]+@"
                    + "|\\bsk-[A-Za-z0-9_-]{12,})");
    private static final Comparator<String> UTF8_ORDER =
            AgentTaskContextPackServiceImpl::compareUtf8;

    private final AgentTaskWorkspaceService workspaceService;
    private final AgentTaskArtifactOutcomeService outcomeService;
    private final AgentTaskContextPackDao contextPackDao;
    private final Optional<AgentTaskContextPackConversationSource> conversationSource;

    @Inject
    public AgentTaskContextPackServiceImpl(
            AgentTaskWorkspaceService workspaceService,
            AgentTaskArtifactOutcomeService outcomeService,
            AgentTaskContextPackDao contextPackDao,
            Optional<AgentTaskContextPackConversationSource> conversationSource) {
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.outcomeService = Objects.requireNonNull(outcomeService, "outcomeService");
        this.contextPackDao = Objects.requireNonNull(contextPackDao, "contextPackDao");
        this.conversationSource = Objects.requireNonNull(
                conversationSource, "conversationSource");
    }

    /** Transaction starts before the first ACL/data read; all collaborators join it. */
    @Override
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.REPEATABLE_READ,
            readOnly = true, rollbackFor = Exception.class)
    public AgentTaskContextPackDTO generate(
            String tenantId, String clientId, String taskId,
            String actorAgentId, String expectedVersion) {
        try {
            AgentTaskWorkspaceDTO workspace = workspaceService.snapshot(
                    tenantId, clientId, taskId, actorAgentId);
            requireWorkspace(workspace, taskId);
            requireExpectedVersion(expectedVersion, workspace.getCurrentVersion());

            AgentTaskContextPackDTO pack = new AgentTaskContextPackDTO();
            pack.setSchemaVersion(SCHEMA_VERSION);
            pack.setProvenance(provenance(
                    tenantId, clientId, taskId, actorAgentId, workspace));
            pack.setTaskDescription(taskDescription(
                    tenantId, clientId, taskId));
            pack.setMembers(members(workspace));
            pack.setWorkItems(workItems(workspace));
            AgentTaskContextPackDTO.ArtifactsSection authoritativeArtifacts = artifacts(
                    taskId, outcomeService.listAuthoritativeAccepted(
                            tenantId, clientId, taskId, actorAgentId, null, MAX_ARTIFACTS + 1));
            pack.setAuthoritativeArtifacts(authoritativeArtifacts);
            pack.setOpenRequests(requests(workspace));
            pack.setRecentEvents(events(workspace, authoritativeArtifacts));
            pack.setConversation(conversation(
                    tenantId, clientId, taskId, actorAgentId));
            pack.setDigestAlgorithm("SHA-256");
            pack.setDigest(digest(pack));
            return pack;
        } catch (AgentTaskContextPackException exception) {
            throw exception;
        } catch (AgentTaskWorkspaceException exception) {
            if (exception.getReason()
                    == AgentTaskWorkspaceException.Reason.NOT_FOUND_OR_FORBIDDEN) {
                throw failure(AgentTaskContextPackException.Reason.NOT_FOUND_OR_FORBIDDEN,
                        exception);
            }
            throw unavailable(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private static AgentTaskContextPackDTO.Provenance provenance(
            String tenantId, String clientId, String taskId, String actorAgentId,
            AgentTaskWorkspaceDTO workspace) {
        AgentTaskContextPackDTO.Provenance value = new AgentTaskContextPackDTO.Provenance();
        value.setTenantId(tenantId);
        value.setClientId(clientId);
        value.setTaskId(taskId);
        value.setActorAgentId(actorAgentId);
        value.setTaskVersion(workspace.getTask().getVersion());
        value.setCurrentEventVersion(workspace.getCurrentVersion());
        return value;
    }

    private AgentTaskContextPackDTO.TaskDescriptionSection taskDescription(
            String tenantId, String clientId, String taskId) {
        AgentTaskContextPackDTO.TaskDescriptionSection section =
                new AgentTaskContextPackDTO.TaskDescriptionSection();
        AgentTaskContextPackTaskSourceRow row =
                contextPackDao.findTaskDescription(tenantId, clientId, taskId);
        if (row == null) {
            section.setStatus(UNAVAILABLE);
            section.setReason(taskId.matches("[0-9]+")
                    ? "TASK_PLAN_SOURCE_NOT_FOUND" : "TASK_PLAN_SOURCE_NON_NUMERIC");
            return section;
        }
        if (!tenantId.equals(row.getTenantId()) || !clientId.equals(row.getClientId())
                || !taskId.equals(row.getTaskId()) || row.getPlanId() == null
                || row.getPlanId() <= 0) {
            throw unavailable(null);
        }
        AgentTaskContextPackDTO.SafeText title = safeText(row.getTitle());
        AgentTaskContextPackDTO.SafeText description = safeText(row.getDescription());
        section.setTitle(title);
        section.setDescription(description);
        boolean truncated = title.isTruncated() || description.isTruncated();
        section.setTruncated(truncated);
        section.setStatus(truncated ? TRUNCATED : AVAILABLE);
        if (row.getTitle() == null && row.getDescription() == null) {
            section.setReason("TASK_PLAN_TEXT_EMPTY");
        }
        return section;
    }

    private static AgentTaskContextPackDTO.MembersSection members(
            AgentTaskWorkspaceDTO workspace) {
        AgentTaskContextPackDTO.MembersSection section =
                new AgentTaskContextPackDTO.MembersSection();
        List<AgentTaskWorkspaceDTO.Member> ordered = new ArrayList<>(
                required(workspace.getMembers()));
        ordered.sort(Comparator.comparing(AgentTaskWorkspaceDTO.Member::getRole, UTF8_ORDER)
                .thenComparing(AgentTaskWorkspaceDTO.Member::getAgentId, UTF8_ORDER));
        boolean bounded = ordered.size() > MAX_MEMBERS;
        List<AgentTaskContextPackDTO.Member> items = new ArrayList<>();
        for (AgentTaskWorkspaceDTO.Member source : ordered.subList(
                0, Math.min(MAX_MEMBERS, ordered.size()))) {
            AgentTaskContextPackDTO.Member item = new AgentTaskContextPackDTO.Member();
            item.setAgentId(source.getAgentId());
            item.setRole(source.getRole());
            item.setStatus(source.getStatus());
            item.setVersion(source.getVersion());
            items.add(item);
        }
        section.setItems(List.copyOf(items));
        section.setTruncated(bounded);
        section.setStatus(bounded ? TRUNCATED : AVAILABLE);
        if (bounded) {
            section.setReason("MEMBER_LIMIT_REACHED");
        }
        return section;
    }

    private static AgentTaskContextPackDTO.WorkItemsSection workItems(
            AgentTaskWorkspaceDTO workspace) {
        AgentTaskContextPackDTO.WorkItemsSection section =
                new AgentTaskContextPackDTO.WorkItemsSection();
        List<AgentTaskContextPackDTO.WorkItem> items = new ArrayList<>();
        List<AgentTaskWorkspaceDTO.WorkItem> ordered = new ArrayList<>(
                required(workspace.getWorkItems()));
        ordered.sort(Comparator
                .comparing(AgentTaskWorkspaceDTO.WorkItem::getPriority,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AgentTaskWorkspaceDTO.WorkItem::getWorkItemId, UTF8_ORDER));
        boolean bounded = ordered.size() > MAX_WORK_ITEMS;
        boolean truncated = bounded;
        try {
            for (AgentTaskWorkspaceDTO.WorkItem source : ordered.subList(
                    0, Math.min(MAX_WORK_ITEMS, ordered.size()))) {
                AgentTaskContextPackDTO.WorkItem item = new AgentTaskContextPackDTO.WorkItem();
                item.setWorkItemId(source.getWorkItemId());
                item.setTitle(safeText(source.getTitle()));
                item.setDescription(source.getDescription() == null
                        ? null : safeText(source.getDescription()));
                item.setWorkType(source.getWorkType());
                item.setAssigneeAgentId(source.getAssigneeAgentId());
                item.setStatus(source.getStatus());
                item.setPriority(source.getPriority());
                item.setRequiredItem(source.getRequiredItem());
                Dependencies dependencies = dependencyIds(source.getDependencyJson());
                item.setDependencyIds(dependencies.ids());
                item.setDependenciesTruncated(dependencies.truncated());
                item.setVersion(source.getVersion());
                truncated |= item.getTitle().isTruncated()
                        || item.getDescription() != null && item.getDescription().isTruncated()
                        || item.isDependenciesTruncated();
                items.add(item);
            }
        } catch (IllegalArgumentException exception) {
            section.setStatus(UNAVAILABLE);
            section.setReason("DEPENDENCY_DATA_INVALID");
            section.setItems(List.of());
            return section;
        }
        section.setItems(List.copyOf(items));
        section.setTruncated(truncated);
        section.setStatus(truncated ? TRUNCATED : AVAILABLE);
        if (bounded) {
            section.setReason("WORK_ITEM_LIMIT_REACHED");
        } else if (items.stream().anyMatch(AgentTaskContextPackDTO.WorkItem::isDependenciesTruncated)) {
            section.setReason("DEPENDENCY_LIMIT_REACHED");
        }
        return section;
    }

    private static AgentTaskContextPackDTO.ArtifactsSection artifacts(
            String taskId, List<AgentTaskArtifactOutcomeViewDTO> sourceRows) {
        AgentTaskContextPackDTO.ArtifactsSection section =
                new AgentTaskContextPackDTO.ArtifactsSection();
        List<AgentTaskArtifactOutcomeViewDTO> ordered = new ArrayList<>(required(sourceRows));
        ordered.sort(Comparator
                .comparing(AgentTaskArtifactOutcomeViewDTO::getDecidedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AgentTaskArtifactOutcomeViewDTO::getArtifactId,
                        Comparator.nullsLast(UTF8_ORDER))
                .thenComparing(AgentTaskArtifactOutcomeViewDTO::getArtifactVersion,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        List<AgentTaskContextPackDTO.Artifact> items = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        boolean truncatedText = false;
        boolean bounded = ordered.size() > MAX_ARTIFACTS;
        for (AgentTaskArtifactOutcomeViewDTO source : ordered) {
            if (source == null || !taskId.equals(source.getTaskId())
                    || !"accepted".equals(source.getOutcomeState())
                    || source.getArtifactVersion() == null || source.getArtifactVersion() < 1
                    || source.getOutcomeVersion() == null || source.getOutcomeVersion() < 1
                    || source.getContentHash() == null
                    || !source.getContentHash().matches("[0-9a-f]{64}")
                    || !unique.add(source.getArtifactId() + "\u0000"
                            + source.getArtifactVersion())) {
                throw unavailable(null);
            }
            if (items.size() >= MAX_ARTIFACTS) {
                continue;
            }
            AgentTaskContextPackDTO.Artifact item = new AgentTaskContextPackDTO.Artifact();
            item.setArtifactId(source.getArtifactId());
            item.setWorkItemId(source.getWorkItemId());
            item.setProducerAgentId(source.getProducerAgentId());
            item.setArtifactType(source.getArtifactType());
            item.setTitle(safeText(source.getTitle()));
            item.setContentHash(source.getContentHash());
            item.setArtifactVersion(Integer.toString(source.getArtifactVersion()));
            item.setVisibility(source.getVisibility());
            item.setCreatedAt(decimal(source.getCreatedAt()));
            item.setOutcomeState(source.getOutcomeState());
            item.setOutcomeVersion(decimal(source.getOutcomeVersion()));
            item.setDecisionId(source.getDecisionId());
            item.setDecidedByAgentId(source.getDecidedByAgentId());
            item.setDecidedAt(decimal(source.getDecidedAt()));
            truncatedText |= item.getTitle().isTruncated();
            items.add(item);
        }
        section.setItems(List.copyOf(items));
        section.setTruncated(truncatedText || bounded);
        section.setStatus(truncatedText || bounded ? TRUNCATED : AVAILABLE);
        if (bounded) {
            section.setReason("AUTHORITATIVE_ARTIFACT_LIMIT_REACHED");
        }
        return section;
    }

    private static AgentTaskContextPackDTO.RequestsSection requests(
            AgentTaskWorkspaceDTO workspace) {
        AgentTaskContextPackDTO.RequestsSection section =
                new AgentTaskContextPackDTO.RequestsSection();
        List<AgentTaskWorkspaceDTO.Request> ordered = new ArrayList<>(
                required(workspace.getOpenRequests()));
        ordered.sort(Comparator
                .comparing(AgentTaskWorkspaceDTO.Request::getPriority,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AgentTaskWorkspaceDTO.Request::getRequestId, UTF8_ORDER));
        boolean bounded = ordered.size() > MAX_REQUESTS;
        List<AgentTaskContextPackDTO.Request> items = new ArrayList<>();
        boolean truncated = bounded;
        for (AgentTaskWorkspaceDTO.Request source : ordered.subList(
                0, Math.min(MAX_REQUESTS, ordered.size()))) {
            AgentTaskContextPackDTO.Request item = new AgentTaskContextPackDTO.Request();
            item.setRequestId(source.getRequestId());
            item.setWorkItemId(source.getWorkItemId());
            item.setRequesterAgentId(source.getRequesterAgentId());
            item.setTargetType(source.getTargetType());
            item.setTargetId(source.getTargetId());
            item.setRequestType(source.getRequestType());
            item.setStatus(source.getStatus());
            item.setPriority(source.getPriority());
            item.setTitle(safeText(source.getTitle()));
            item.setDescription(safeText(source.getDescription()));
            item.setDueAt(source.getDueAt());
            item.setVersion(source.getVersion());
            truncated |= item.getTitle().isTruncated() || item.getDescription().isTruncated();
            items.add(item);
        }
        section.setItems(List.copyOf(items));
        section.setTruncated(truncated);
        section.setStatus(truncated ? TRUNCATED : AVAILABLE);
        if (bounded) {
            section.setReason("REQUEST_LIMIT_REACHED");
        }
        return section;
    }

    private static AgentTaskContextPackDTO.EventsSection events(
            AgentTaskWorkspaceDTO workspace,
            AgentTaskContextPackDTO.ArtifactsSection authoritativeArtifacts) {
        AgentTaskContextPackDTO.EventsSection section =
                new AgentTaskContextPackDTO.EventsSection();
        Set<String> acceptedArtifactIds = new HashSet<>();
        for (AgentTaskContextPackDTO.Artifact artifact : authoritativeArtifacts.getItems()) {
            acceptedArtifactIds.add(artifact.getArtifactId());
        }
        boolean policyFiltered = false;
        List<AgentTaskContextPackDTO.Event> items = new ArrayList<>();
        for (AgentTaskWorkspaceDTO.Event source : required(workspace.getRecentEvents())) {
            if ("artifact".equals(source.getAggregateType())
                    && (!"ARTIFACT_ACCEPTED".equals(source.getEventType())
                    || !acceptedArtifactIds.contains(source.getAggregateId()))) {
                policyFiltered = true;
                continue;
            }
            AgentTaskContextPackDTO.Event item = new AgentTaskContextPackDTO.Event();
            item.setVersion(source.getVersion());
            item.setRedacted(source.getRedacted());
            item.setEventType(source.getEventType());
            item.setActorType(source.getActorType());
            item.setActorId(source.getActorId());
            item.setAggregateType(source.getAggregateType());
            item.setAggregateId(source.getAggregateId());
            item.setOccurredAt(source.getOccurredAt());
            items.add(item);
        }
        items.sort(Comparator.comparingLong(value -> parseCanonicalDecimal(value.getVersion())));
        section.setItems(List.copyOf(items));
        section.setTruncated(workspace.isTimelineTruncated());
        section.setStatus(workspace.isTimelineTruncated() ? TRUNCATED : AVAILABLE);
        if (workspace.isTimelineTruncated()) {
            section.setReason(policyFiltered
                    ? "RECENT_EVENT_WINDOW_AND_ACCEPTED_ARTIFACT_FILTER"
                    : "RECENT_EVENT_WINDOW");
        } else if (policyFiltered) {
            section.setReason("ACCEPTED_ARTIFACT_EVENT_FILTER");
        }
        return section;
    }

    private AgentTaskContextPackDTO.ConversationSection conversation(
            String tenantId, String clientId, String taskId, String actorAgentId) {
        AgentTaskContextPackDTO.ConversationSection section =
                new AgentTaskContextPackDTO.ConversationSection();
        section.setContentOmitted(true);
        if (conversationSource.isEmpty()) {
            section.setStatus(UNAVAILABLE);
            section.setReason("CONVERSATION_SOURCE_NOT_INSTALLED");
            return section;
        }
        AgentTaskContextPackConversationSource.ConversationReference reference;
        try {
            reference = conversationSource.get().findReference(
                    tenantId, clientId, taskId, actorAgentId);
        } catch (RuntimeException exception) {
            section.setStatus(UNAVAILABLE);
            section.setReason("CONVERSATION_SOURCE_UNAVAILABLE");
            return section;
        }
        if (reference == null || !reference.available()) {
            section.setStatus(UNAVAILABLE);
            section.setReason(reference == null || reference.reason() == null
                    ? "CONVERSATION_REFERENCE_UNAVAILABLE" : safeReason(reference.reason()));
            return section;
        }
        if (!validId(reference.conversationId(), 100)
                || reference.recentMessageCount() == null
                || reference.recentMessageCount() < 0
                || reference.latestMessageAt() != null && reference.latestMessageAt() < 0) {
            section.setStatus(UNAVAILABLE);
            section.setReason("CONVERSATION_REFERENCE_INVALID");
            return section;
        }
        section.setConversationId(reference.conversationId());
        section.setRecentMessageCount(reference.recentMessageCount());
        section.setLatestMessageAt(decimalNullable(reference.latestMessageAt()));
        section.setTruncated(reference.truncated());
        section.setStatus(reference.truncated() ? TRUNCATED : AVAILABLE);
        if (reference.truncated()) {
            section.setReason("RECENT_CONVERSATION_WINDOW");
        }
        return section;
    }

    private static Dependencies dependencyIds(String json) {
        if (json == null) {
            return new Dependencies(List.of(), false);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_DEPENDENCY_JSON_UTF8_BYTES) {
            throw new IllegalArgumentException("invalid dependency data");
        }
        final List<?> raw;
        try {
            raw = JsonUtil.getMapper().readValue(json, List.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid dependency data");
        }
        List<String> result = new ArrayList<>(raw.size());
        Set<String> unique = new HashSet<>();
        for (Object value : raw) {
            if (!(value instanceof String id) || !validId(id, 100) || !unique.add(id)) {
                throw new IllegalArgumentException("invalid dependency data");
            }
            result.add(id);
        }
        result.sort(UTF8_ORDER);
        boolean truncated = result.size() > MAX_DEPENDENCIES_PER_ITEM;
        return new Dependencies(List.copyOf(result.subList(
                0, Math.min(MAX_DEPENDENCIES_PER_ITEM, result.size()))), truncated);
    }

    private static AgentTaskContextPackDTO.SafeText safeText(String source) {
        AgentTaskContextPackDTO.SafeText result = new AgentTaskContextPackDTO.SafeText();
        if (source == null) {
            result.setValue(null);
            return result;
        }
        if (SENSITIVE.matcher(source).find()) {
            result.setValue(REDACTED_TEXT);
            result.setRedacted(true);
            return result;
        }
        StringBuilder cleaned = new StringBuilder(source.length());
        source.codePoints().forEach(codePoint -> cleaned.appendCodePoint(
                Character.isISOControl(codePoint) ? ' ' : codePoint));
        String value = truncateUtf8(cleaned.toString(), MAX_TEXT_UTF8_BYTES);
        result.setValue(value);
        result.setTruncated(!value.equals(cleaned.toString()));
        return result;
    }

    private static String truncateUtf8(String value, int maxBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String unit = new String(Character.toChars(codePoint));
            int unitBytes = unit.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + unitBytes > maxBytes) {
                break;
            }
            result.append(unit);
            bytes += unitBytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static String digest(AgentTaskContextPackDTO pack) {
        try {
            byte[] canonical = JsonUtil.getMapper().writeValueAsBytes(pack);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    private static void requireWorkspace(AgentTaskWorkspaceDTO workspace, String taskId) {
        if (workspace == null || workspace.getTask() == null
                || !taskId.equals(workspace.getTask().getTaskId())
                || workspace.getTask().getVersion() == null
                || workspace.getCurrentVersion() == null) {
            throw unavailable(null);
        }
        parseCanonicalDecimal(workspace.getTask().getVersion());
        parseCanonicalDecimal(workspace.getCurrentVersion());
    }

    private static void requireExpectedVersion(String expected, String actual) {
        if (expected == null) {
            return;
        }
        parseCanonicalDecimal(expected);
        if (!expected.equals(actual)) {
            throw failure(AgentTaskContextPackException.Reason.STALE_VERSION, null);
        }
    }

    private static long parseCanonicalDecimal(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) {
            throw unavailable(null);
        }
        try {
            long parsed = Long.parseLong(value);
            if (!Long.toString(parsed).equals(value)) {
                throw unavailable(null);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw unavailable(exception);
        }
    }

    private static String decimal(Long value) {
        if (value == null || value < 0) {
            throw unavailable(null);
        }
        return Long.toString(value);
    }

    private static String decimalNullable(Long value) {
        return value == null ? null : decimal(value);
    }

    private static String safeReason(String reason) {
        return reason.matches("[A-Z0-9_]{1,80}")
                ? reason : "CONVERSATION_REFERENCE_UNAVAILABLE";
    }

    private static boolean validId(String value, int maxCodePoints) {
        return value != null && !value.isEmpty() && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= maxCodePoints
                && !isPadding(value.codePointAt(0))
                && !isPadding(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static int compareUtf8(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(a.length, b.length);
        for (int index = 0; index < length; index++) {
            int compared = Integer.compare(
                    Byte.toUnsignedInt(a[index]), Byte.toUnsignedInt(b[index]));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static <T> List<T> required(List<T> values) {
        if (values == null) {
            throw unavailable(null);
        }
        return values;
    }

    private record Dependencies(List<String> ids, boolean truncated) {
    }

    private static AgentTaskContextPackException unavailable(Throwable cause) {
        return failure(AgentTaskContextPackException.Reason.CONTEXT_UNAVAILABLE, cause);
    }

    private static AgentTaskContextPackException failure(
            AgentTaskContextPackException.Reason reason, Throwable cause) {
        return cause == null ? new AgentTaskContextPackException(reason)
                : new AgentTaskContextPackException(reason, cause);
    }
}
