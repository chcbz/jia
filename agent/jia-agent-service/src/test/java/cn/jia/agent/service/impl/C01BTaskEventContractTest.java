package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frozen C01B E01-E22 production-entry and event contract audit. */
class C01BTaskEventContractTest {
    private static final Set<String> EVENT_TYPES = Set.of(
            "TASK_CREATED", "TASK_ASSIGNED", "TASK_STARTED", "TASK_BLOCKED",
            "TASK_ARCHIVED", "TEAM_PROPOSED", "TASK_REVIEWING", "TASK_COMPLETED",
            "TASK_FAILED", "TASK_CANCELLED", "MEMBER_INVITED", "MEMBER_ACCEPTED",
            "MEMBER_REJECTED", "MEMBER_BLOCKED", "MEMBER_WORKING", "MEMBER_DONE",
            "MEMBER_FAILED", "MEMBER_LEFT", "WORK_ITEM_CREATED", "WORK_ITEM_READY",
            "WORK_ITEM_CLAIMED", "WORK_ITEM_STARTED", "WORK_ITEM_SUBMITTED",
            "WORK_ITEM_COMPLETED", "WORK_ITEM_REQUEUED", "WORK_ITEM_BLOCKED",
            "WORK_ITEM_FAILED", "WORK_ITEM_CANCELLED", "WORK_ITEM_LEASE_RENEWED",
            "WORK_ITEM_LEASE_RELEASED", "PROGRESS_REPORTED", "HELP_REQUESTED",
            "REVIEW_REQUESTED", "REQUEST_CREATED", "REQUEST_ACKNOWLEDGED",
            "REQUEST_RESOLVED", "REQUEST_REJECTED", "REQUEST_CANCELLED",
            "THREAD_CREATED", "MESSAGE_POSTED", "ARTIFACT_PUBLISHED",
            "COMMAND_DELIVERY_FAILED", "HISTORICAL_BASELINE_IMPORTED");

    private static final Set<String> AGGREGATES = Set.of(
            "task", "member", "work_item", "request", "artifact", "thread", "message");

    private static final Set<String> PAYLOAD_KEYS = Set.of(
            "fromStatus", "toStatus", "status", "reasonCode", "expectedVersion",
            "resultVersion", "taskId", "taskType", "agentId", "memberId",
            "workItemId", "assigneeAgentId", "role", "attemptCount", "maxAttempts",
            "requestId", "requestType", "targetType", "targetId", "artifactId",
            "artifactType", "artifactVersion", "visibility", "threadId", "threadType",
            "conversationId", "messageId", "messageType", "senderAgentId", "noteId",
            "noteType", "contentByteLength", "contentSha256", "source", "decisionCode",
            "memberCount", "workItemCount", "completedWorkItemCount",
            "failedWorkItemCount", "createdAt", "updatedAt", "assignedAt", "startedAt",
            "completedAt", "acknowledgedAt", "resolvedAt", "cancelledAt", "publishedAt",
            "previousLeaseExpiresAt", "leaseExpiresAt");

    private static final Map<String, Integer> PRODUCTION_APPEND_COUNTS = Map.of(
            "agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentTaskStateServiceImpl.java", 1,
            "agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentWorkItemLeaseServiceImpl.java", 1,
            "agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentTaskAggregationServiceImpl.java", 1,
            "agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentTaskCollaborationServiceImpl.java", 2,
            "agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentWorkItemResultCommitServiceImpl.java", 1,
            "agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentLegacyTaskCompatibilityService.java", 5,
            "agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentServiceImpl.java", 3,
            "chat/jia-chat-service/src/main/java/cn/jia/chat/service/impl/AgentTaskThreadCreationTransaction.java", 2);

    private static final Set<String> ALLOWED_WRITER_IMPORTS = Set.of(
            "agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentTaskStateServiceImpl.java",
            "agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentWorkItemLeaseServiceImpl.java",
            "agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentTaskAggregationServiceImpl.java",
            "agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentTaskCollaborationServiceImpl.java",
            "agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentWorkItemResultCommitServiceImpl.java",
            "agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentLegacyTaskCompatibilityService.java",
            "agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentServiceImpl.java",
            "agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentTaskEventWriterImpl.java",
            "chat/jia-chat-service/src/main/java/cn/jia/chat/service/impl/AgentTaskThreadCreationTransaction.java");

    @Test
    void eventAggregatePayloadAndSchemaCatalogsAreExactAndConsistent() throws Exception {
        assertEquals(EVENT_TYPES, publicStringConstants(TaskEventType.class));
        assertEquals(AGGREGATES, publicStringConstants(TaskEventType.Aggregate.class));
        assertEquals(PAYLOAD_KEYS, publicStringConstants(TaskEventPayload.Key.class));

        EVENT_TYPES.forEach(type -> assertEquals(type, TaskEventType.requireKnown(type)));
        AGGREGATES.forEach(type -> assertEquals(type, TaskEventType.Aggregate.requireKnown(type)));
        PAYLOAD_KEYS.forEach(key -> assertTrue(TaskEventPayload.isAllowedKey(key), key));

        Path root = apiRoot();
        for (String schemaPath : List.of(
                "agent/jia-agent-mapper/src/main/resources/db/schema.sql",
                "agent/jia-agent-mapper/src/main/resources/db/task-event-schema.sql")) {
            String schema = Files.readString(root.resolve(schemaPath), StandardCharsets.UTF_8);
            assertTrue(schema.contains(
                    "Aggregate type: task/member/work_item/request/artifact/thread/message"));
            assertTrue(schema.contains(
                    "UNIQUE KEY uk_task_event_version (tenant_id, client_id, task_id, event_version)"));
            assertTrue(schema.contains(
                    "UNIQUE KEY uk_task_event_id (tenant_id, client_id, event_id)"));
        }
    }

    @Test
    void e01ToE19EntryPointsUseOnlyServiceLayerWriterSitesWithoutDuplicateAppendSites()
            throws Exception {
        Path root = apiRoot();
        Map<String, Integer> actualAppendCounts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : PRODUCTION_APPEND_COUNTS.entrySet()) {
            String source = Files.readString(root.resolve(entry.getKey()), StandardCharsets.UTF_8);
            assertTrue(source.contains("AgentTaskMutationTransaction"), entry.getKey());
            int count = occurrences(source, "eventWriter.append(")
                    + occurrences(source, "taskEventWriter.append(");
            actualAppendCounts.put(entry.getKey(), count);
        }
        assertEquals(PRODUCTION_APPEND_COUNTS, actualAppendCounts);

        Set<String> writerImports = new TreeSet<>();
        for (String top : List.of("agent", "chat")) {
            try (var paths = Files.walk(root.resolve(top))) {
                paths.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> path.toString().contains("/src/main/java/"))
                        .forEach(path -> collectWriterImport(root, path, writerImports));
            }
        }
        assertEquals(ALLOWED_WRITER_IMPORTS, writerImports);
        assertTrue(writerImports.stream().noneMatch(path -> path.contains("/api/")));
        assertTrue(writerImports.stream().noneMatch(path -> path.contains("/handler/")));
        assertTrue(writerImports.stream().noneMatch(path -> path.contains("/tool/")));
        assertTrue(writerImports.stream().noneMatch(path -> path.toLowerCase().contains("publisher")));
    }

    @Test
    void fixtureManifestIsSortedUniqueCompleteAndRestrictedToAssignedModules()
            throws Exception {
        Path root = apiRoot();
        Path manifest = root.resolve(
                "agent/jia-agent-service/src/test/resources/c01b/fixture-files.txt");
        List<String> entries = Files.readAllLines(manifest, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank() && !line.stripLeading().startsWith("#"))
                .toList();
        assertEquals(new TreeSet<>(entries).stream().toList(), entries);
        assertEquals(entries.size(), new TreeSet<>(entries).size());
        for (String entry : entries) {
            assertTrue(entry.startsWith("agent/") || entry.startsWith("chat/"), entry);
            assertTrue(Files.isRegularFile(root.resolve(entry)), entry);
        }
        Path script = root.resolve(
                "agent/jia-agent-service/src/test/resources/c01b/compute-fixture-digest.sh");
        assertTrue(Files.isExecutable(script));
        String scriptText = Files.readString(script, StandardCharsets.UTF_8);
        assertTrue(scriptText.contains("printf '%s\\0%s\\n'"));
        assertTrue(scriptText.contains("^[0-9a-f]{64}$"));
    }

    @Test
    void frozenCanonicalMappingsHaveNoSynonymousEventForOneMutation() {
        assertEquals("TASK_STARTED", AgentTaskMutationEventSupport.taskEvent("running"));
        assertEquals("TASK_BLOCKED", AgentTaskMutationEventSupport.taskEvent("blocked"));
        assertEquals("MEMBER_WORKING", AgentTaskMutationEventSupport.memberEvent("working"));
        assertEquals("MEMBER_DONE", AgentTaskMutationEventSupport.memberEvent("done"));
        assertEquals("WORK_ITEM_STARTED", AgentTaskMutationEventSupport.workItemEvent("running"));
        assertEquals("WORK_ITEM_FAILED", AgentTaskMutationEventSupport.workItemEvent("failed"));

        List<List<String>> fixedSequences = List.of(
                List.of(TaskEventType.WORK_ITEM_LEASE_RELEASED),
                List.of(TaskEventType.WORK_ITEM_REQUEUED),
                List.of(TaskEventType.WORK_ITEM_FAILED),
                List.of(TaskEventType.ARTIFACT_PUBLISHED, TaskEventType.WORK_ITEM_SUBMITTED),
                List.of(TaskEventType.THREAD_CREATED, TaskEventType.MESSAGE_POSTED));
        fixedSequences.forEach(sequence ->
                assertEquals(sequence.size(), new TreeSet<>(sequence).size(), sequence.toString()));
        assertFalse(fixedSequences.get(0).contains(TaskEventType.WORK_ITEM_REQUEUED));
        assertFalse(fixedSequences.get(0).contains(TaskEventType.WORK_ITEM_FAILED));
    }

    private Set<String> publicStringConstants(Class<?> type) throws IllegalAccessException {
        Set<String> values = new TreeSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() == String.class
                    && Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers())) {
                values.add((String) field.get(null));
            }
        }
        return values;
    }

    private void collectWriterImport(Path root, Path path, Set<String> writerImports) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (source.contains("import cn.jia.agent.service.AgentTaskEventWriter;")) {
                writerImports.add(root.relativize(path).toString());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to audit " + path, e);
        }
    }

    private int occurrences(String source, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private Path apiRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))
                    && Files.isDirectory(candidate.resolve("agent"))
                    && Files.isDirectory(candidate.resolve("chat"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate API worktree root from " + current);
    }
}
