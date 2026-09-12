package cn.jia.agent.dao;

import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentTaskWorkItemDependencyDaoTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String WORK = "work-1";

    @Test
    void taskGraphLockUsesExactScopeBinaryOrderBoundAndForUpdate() throws Exception {
        String sql = selectSql("selectTaskGraphForUpdate");

        assertTrue(sql.contains("tenant_id = #{tenantid}"), sql);
        assertTrue(sql.contains("client_id = #{clientid}"), sql);
        assertTrue(sql.contains("task_id = #{taskid}"), sql);
        for (String column : Arrays.asList("tenant_id", "client_id", "task_id")) {
            assertTrue(sql.contains("cast(" + column + " as binary)"), sql);
            assertTrue(sql.contains("octet_length(" + column + ")"), sql);
        }
        assertTrue(sql.contains("order by cast(work_item_id as binary), id"), sql);
        assertTrue(sql.contains("limit #{limit} for update"), sql);
    }

    @Test
    void readyCasUsesCompleteSnapshotExactScopePendingStatusNullLeaseAndVersion() throws Exception {
        String sql = updateSql("readyPendingByVersion");

        for (String column : Arrays.asList(
                "title", "description", "work_type", "required_abilities",
                "assignee_agent_id", "status", "priority", "required_item",
                "dependency_json", "lease_token", "lease_until", "attempt_count",
                "max_attempts", "result_artifact_id", "submitted_at", "completed_at")) {
            assertTrue(sql.contains(column + " = #{item."), column + " missing: " + sql);
        }
        for (String column : Arrays.asList(
                "tenant_id", "client_id", "task_id", "work_item_id", "status")) {
            assertTrue(sql.contains("cast(" + column + " as binary)"), sql);
            assertTrue(sql.contains("octet_length(" + column + ")"), sql);
        }
        assertTrue(sql.contains("status = 'pending'"), sql);
        assertTrue(sql.contains("lease_token is null"), sql);
        assertTrue(sql.contains("lease_until is null"), sql);
        assertTrue(sql.contains("version = #{expectedversion}"), sql);
        assertTrue(sql.contains("version = version + 1"), sql);
        assertTrue(sql.contains("update_time = #{changedat}"), sql);
    }

    @Test
    void daoDelegatesBoundedLockAndExactReadySnapshotWithoutReadBeforeWrite() {
        AgentTaskWorkItemMapper mapper = mock(AgentTaskWorkItemMapper.class);
        AgentTaskWorkItemDao dao = new AgentTaskWorkItemDaoImpl(mapper);
        AgentTaskWorkItemDTO snapshot = readySnapshot();

        dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 501);
        dao.readyPendingByVersion(TENANT, CLIENT, TASK, WORK, 7L, 9_000L, snapshot);

        verify(mapper).selectTaskGraphForUpdate(TENANT, CLIENT, TASK, 501);
        verify(mapper).readyPendingByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(7L),
                same(snapshot), eq(9_000L));
        verify(mapper, never()).selectOne(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void invalidGraphLimitOrNonReadySnapshotIsRejectedBeforeMapperAccess() {
        AgentTaskWorkItemMapper mapper = mock(AgentTaskWorkItemMapper.class);
        AgentTaskWorkItemDao dao = new AgentTaskWorkItemDaoImpl(mapper);

        assertThrows(IllegalArgumentException.class,
                () -> dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 502));
        AgentTaskWorkItemDTO pending = readySnapshot();
        pending.setStatus("pending");
        assertThrows(IllegalArgumentException.class,
                () -> dao.readyPendingByVersion(
                        TENANT, CLIENT, TASK, WORK, 7L, 9_000L, pending));

        verify(mapper, never()).selectTaskGraphForUpdate(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        verify(mapper, never()).readyPendingByVersion(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private AgentTaskWorkItemDTO readySnapshot() {
        AgentTaskWorkItemDTO item = new AgentTaskWorkItemDTO();
        item.setWorkItemId(WORK);
        item.setTaskId(TASK);
        item.setTitle("dependency");
        item.setWorkType("implementation");
        item.setStatus("ready");
        item.setPriority(1);
        item.setRequiredItem(true);
        item.setAttemptCount(0);
        item.setMaxAttempts(3);
        item.setVersion(7L);
        return item;
    }

    private String selectSql(String methodName) throws Exception {
        Method method = mapperMethod(methodName);
        return normalize(String.join(" ", method.getAnnotation(Select.class).value()));
    }

    private String updateSql(String methodName) throws Exception {
        Method method = mapperMethod(methodName);
        return normalize(String.join(" ", method.getAnnotation(Update.class).value()));
    }

    private Method mapperMethod(String methodName) {
        return Arrays.stream(AgentTaskWorkItemMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
