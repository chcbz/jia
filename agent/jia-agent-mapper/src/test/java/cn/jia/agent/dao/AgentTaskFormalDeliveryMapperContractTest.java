package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskFormalDeliveryEntity;
import cn.jia.agent.mapper.AgentTaskFormalDeliveryMapper;
import cn.jia.agent.dao.impl.AgentTaskFormalDeliveryDaoImpl;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskFormalDeliveryMapperContractTest {

    @Test
    void lockAndReviewCasAreByteExactAndScoped() throws Exception {
        Method lock = AgentTaskFormalDeliveryMapper.class.getDeclaredMethod(
                "selectExactForUpdate", String.class, String.class, String.class);
        String lockSql = String.join("\n", lock.getAnnotation(Select.class).value());
        assertTrue(lockSql.contains("FROM agent_task_formal_delivery d"));
        assertTrue(lockSql.contains("FOR UPDATE"));
        assertTrue(lockSql.contains("CAST(d.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)"));
        assertTrue(lockSql.contains("OCTET_LENGTH(d.client_id)=OCTET_LENGTH(#{clientId})"));
        assertTrue(lockSql.contains("CAST(d.delivery_id AS BINARY)=CAST(#{deliveryId} AS BINARY)"));

        Method update = AgentTaskFormalDeliveryMapper.class.getDeclaredMethod("reviewByVersion",
                String.class, String.class, String.class, String.class, long.class, String.class,
                String.class, String.class, long.class, long.class);
        String updateSql = String.join("\n", update.getAnnotation(Update.class).value());
        assertTrue(updateSql.contains("AND state=#{expectedState} AND version=#{expectedVersion}"));
        assertTrue(updateSql.contains("version=#{resultVersion}"));
        assertTrue(updateSql.contains("CAST(state AS BINARY)=CAST(#{expectedState} AS BINARY)"));
    }

    @Test
    void insertsPinExactArtifactVersionsAndDoNotTruncateTextSummary() throws Exception {
        Method insert = AgentTaskFormalDeliveryMapper.class.getDeclaredMethod(
                "insertFormalDelivery", AgentTaskFormalDeliveryEntity.class);
        String insertSql = String.join("\n", insert.getAnnotation(Insert.class).value());
        assertTrue(insertSql.contains("manifest_artifact_id,manifest_artifact_version"));
        assertTrue(insertSql.contains("#{manifestArtifactId},#{manifestArtifactVersion}"));

        AtomicInteger inserts = new AtomicInteger();
        AgentTaskFormalDeliveryMapper mapper = mapper(inserts);
        AgentTaskFormalDeliveryDao dao = new AgentTaskFormalDeliveryDaoImpl(mapper);
        AgentTaskFormalDeliveryEntity delivery = new AgentTaskFormalDeliveryEntity()
                .setTaskId("task-1").setWorkItemId("work-1").setDeliveryId("delivery-1")
                .setRevision(1L).setProducerAgentId("agent-1").setRunId("run-1")
                .setSummary("x".repeat(101)).setState("submitted")
                .setManifestArtifactId("manifest-1").setManifestArtifactVersion(1)
                .setSubmittedAt(1L).setVersion(0L);

        assertDoesNotThrow(() -> dao.insert("tenant-1", "client-1", delivery));
        assertTrue(inserts.get() == 1);
    }

    @Test
    void migrationCandidateIsAdditiveAndNotStartupWired() throws Exception {
        Path root = apiRoot();
        Path ddlPath = root.resolve("agent/jia-agent-mapper/src/main/resources/db/agent-task-formal-delivery-r2.sql");
        String ddl = Files.readString(ddlPath, StandardCharsets.UTF_8).toLowerCase();
        assertTrue(ddl.contains("create table if not exists agent_task_formal_delivery"));
        assertTrue(ddl.contains("create table if not exists agent_task_formal_delivery_item"));
        assertTrue(ddl.contains("state in ('submitted', 'accepted', 'changes_requested')"));
        assertTrue(ddl.contains("unique key uk_formal_delivery_revision"));
        assertTrue(ddl.contains("unique key uk_formal_delivery_item_exact"));
        assertFalse(ddl.matches("(?s).*\\b(insert|update|delete|replace|truncate|alter|drop)\\b.*"));

        for (String startupSchema : java.util.List.of(
                "agent/jia-agent-mapper/src/main/resources/db/schema.sql",
                "agent/jia-agent-mapper/src/main/resources/db/task-collaboration-schema.sql")) {
            assertFalse(Files.readString(root.resolve(startupSchema), StandardCharsets.UTF_8)
                    .contains("agent_task_formal_delivery"));
        }
    }

    private static AgentTaskFormalDeliveryMapper mapper(AtomicInteger inserts) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("insertFormalDelivery")) {
                inserts.incrementAndGet();
                return 1;
            }
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            return null;
        };
        return (AgentTaskFormalDeliveryMapper) Proxy.newProxyInstance(
                AgentTaskFormalDeliveryMapper.class.getClassLoader(),
                new Class<?>[] {AgentTaskFormalDeliveryMapper.class}, handler);
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
