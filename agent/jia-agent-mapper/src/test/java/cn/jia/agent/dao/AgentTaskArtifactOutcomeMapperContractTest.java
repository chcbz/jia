package cn.jia.agent.dao;

import cn.jia.agent.mapper.AgentTaskArtifactOutcomeMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskArtifactOutcomeMapperContractTest {

    @Test
    void authoritativeQueryFiltersAcceptedAndAclBeforeOrderAndLimit() throws Exception {
        Method method = AgentTaskArtifactOutcomeMapper.class.getDeclaredMethod(
                "selectAuthoritativeAccepted", String.class, String.class, String.class,
                String.class, String.class, boolean.class, boolean.class, int.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        int accepted = sql.indexOf("o.outcome_state = 'accepted'");
        int visibility = sql.indexOf("a.visibility = 'task_members'");
        int order = sql.indexOf("ORDER BY o.decided_at DESC");
        int limit = sql.indexOf("LIMIT #{limit}");
        assertTrue(accepted >= 0 && accepted < visibility && visibility < order && order < limit);
        assertTrue(sql.contains("CAST(o.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)"));
        assertTrue(sql.contains("CAST(o.client_id AS BINARY) = CAST(#{clientId} AS BINARY)"));
        assertTrue(sql.contains("CAST(o.task_id AS BINARY) = CAST(#{taskId} AS BINARY)"));
        assertTrue(sql.contains("CAST(a.artifact_id AS BINARY) = CAST(o.artifact_id AS BINARY)"));
        assertTrue(sql.contains("a.producer_agent_id = #{actorAgentId}"));
        assertTrue(sql.contains("#{reviewerAccess} = TRUE"));
        assertTrue(sql.contains("#{coordinatorAccess} = TRUE"));
        assertFalse(sql.toLowerCase().contains("a.content,"));
        assertFalse(sql.toLowerCase().contains("storage_uri"));
        assertFalse(sql.toLowerCase().contains("metadata_json"));
    }

    @Test
    void lockingAndCasQueriesUseExactScopeAndExactArtifactIdentity() throws Exception {
        Method lock = AgentTaskArtifactOutcomeMapper.class.getDeclaredMethod(
                "selectExactForUpdate", String.class, String.class, String.class,
                String.class, int.class);
        String lockSql = String.join("\n", lock.getAnnotation(Select.class).value());
        assertTrue(lockSql.contains("FOR UPDATE"));
        assertTrue(lockSql.contains(
                "CAST(o.artifact_id AS BINARY) = CAST(#{artifactId} AS BINARY)"));
        assertTrue(lockSql.contains(
                "OCTET_LENGTH(o.artifact_id) = OCTET_LENGTH(#{artifactId})"));

        Method decision = AgentTaskArtifactOutcomeMapper.class.getDeclaredMethod(
                "selectDecisionForUpdate", String.class, String.class,
                String.class, String.class);
        String decisionSql = String.join("\n", decision.getAnnotation(Select.class).value());
        assertTrue(decisionSql.contains("FROM agent_task_artifact_outcome_decision d"));
        assertTrue(decisionSql.contains("FOR UPDATE"));
        assertTrue(decisionSql.contains(
                "CAST(d.decision_id AS BINARY) = CAST(#{decisionId} AS BINARY)"));

        Method insertDecision = AgentTaskArtifactOutcomeMapper.class.getDeclaredMethod(
                "insertDecision",
                cn.jia.agent.entity.AgentTaskArtifactOutcomeDecisionEntity.class);
        String insertDecisionSql = String.join("\n",
                insertDecision.getAnnotation(Insert.class).value());
        assertTrue(insertDecisionSql.contains(
                "INSERT INTO agent_task_artifact_outcome_decision"));
        assertTrue(insertDecisionSql.contains("#{acceptedOutcomeVersion}"));

        Method update = java.util.Arrays.stream(
                        AgentTaskArtifactOutcomeMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("updateByVersion"))
                .findFirst().orElseThrow();
        String updateSql = String.join("\n", update.getAnnotation(Update.class).value());
        assertTrue(updateSql.contains("AND outcome_state = #{expectedState}"));
        assertTrue(updateSql.contains("AND version = #{expectedVersion}"));
        assertTrue(updateSql.contains(
                "CAST(outcome_state AS BINARY) = CAST(#{expectedState} AS BINARY)"));
        assertTrue(updateSql.contains("version = #{resultVersion}"));
    }

    @Test
    void migrationCandidateIsAdditiveDdlOnlyAndIsNotStartupWired() throws Exception {
        Path root = apiRoot();
        Path migration = root.resolve(
                "agent/jia-agent-mapper/src/main/resources/db/agent-task-artifact-outcome-f06.sql");
        String ddl = Files.readString(migration, StandardCharsets.UTF_8);
        String lower = ddl.toLowerCase();

        assertTrue(lower.contains("create table if not exists agent_task_artifact_outcome"));
        assertTrue(lower.contains("outcome_state in ('accepted', 'superseded')"));
        assertTrue(lower.contains("unique key uk_artifact_outcome_version"));
        assertTrue(lower.contains(
                "create table if not exists agent_task_artifact_outcome_decision"));
        assertTrue(lower.contains("unique key uk_artifact_outcome_decision"));
        assertTrue(lower.contains("accepted_outcome_version"));
        assertTrue(lower.contains("decision_digest"));
        assertTrue(lower.contains("check (artifact_version >= 1 and version >= 1 and decided_at > 0)"));
        assertFalse(lower.matches("(?s).*\\b(insert|update|delete|replace|truncate)\\b.*"));

        for (String startupSchema : java.util.List.of(
                "agent/jia-agent-mapper/src/main/resources/db/schema.sql",
                "agent/jia-agent-mapper/src/main/resources/db/task-collaboration-schema.sql")) {
            assertFalse(Files.readString(root.resolve(startupSchema), StandardCharsets.UTF_8)
                    .contains("agent_task_artifact_outcome"));
        }
        try (var sources = Files.walk(root.resolve("agent"))) {
            assertFalse(sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .map(path -> read(path))
                    .anyMatch(source -> source.contains("agent-task-artifact-outcome-f06.sql")));
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }

    private static Path apiRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))
                    && Files.isDirectory(candidate.resolve("agent"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate API root from " + current);
    }
}
