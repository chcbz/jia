package cn.jia.agent.dao;

import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskWorkItemParentGateDaoTest {
    @Test
    void insertSqlAtomicallyLocksScopedParentAndAllowsOnlyCanonicalNonTerminalStates()
            throws Exception {
        Method method = Arrays.stream(AgentTaskWorkItemMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("insertIfParentNonTerminal"))
                .findFirst().orElseThrow();
        String sql = normalize(String.join(" ", method.getAnnotation(Insert.class).value()));

        assertTrue(sql.startsWith("insert into agent_task_work_item"), sql);
        assertTrue(sql.contains(" select "), sql);
        assertTrue(sql.contains("from agent_task_meta parent"), sql);
        assertTrue(sql.contains("parent.tenant_id = #{tenantid}"), sql);
        assertTrue(sql.contains("parent.client_id = #{clientid}"), sql);
        assertTrue(sql.contains("parent.task_id = #{item.taskid}"), sql);
        assertTrue(sql.contains("cast(parent.tenant_id as binary(200)) "
                + "= cast(#{tenantid} as binary(200))"), sql);
        assertTrue(sql.contains("octet_length(parent.tenant_id) = octet_length(#{tenantid})"), sql);
        assertTrue(sql.contains("cast(parent.client_id as binary(200)) "
                + "= cast(#{clientid} as binary(200))"), sql);
        assertTrue(sql.contains("octet_length(parent.client_id) = octet_length(#{clientid})"), sql);
        assertTrue(sql.contains("cast(substring(parent.task_id, 1, 50) as binary(200))"), sql);
        assertTrue(sql.contains("cast(substring(parent.task_id, 51, 50) as binary(200))"), sql);
        assertTrue(sql.contains("octet_length(parent.task_id) = octet_length(#{item.taskid})"), sql);
        for (String nonTerminal : new String[]{"open", "planning", "assigned", "running", "reviewing", "blocked"}) {
            assertTrue(sql.contains("cast(parent.reward_status as binary(80)) "
                    + "= cast('" + nonTerminal + "' as binary(80))"), sql);
            assertTrue(sql.contains("octet_length(parent.reward_status) "
                    + "= octet_length('" + nonTerminal + "')"), sql);
        }
        assertTrue(sql.endsWith("for update"), sql);
        for (String terminal : new String[]{"completed", "failed", "cancelled", "archived"}) {
            assertTrue(!sql.contains("'" + terminal + "'"), sql);
        }
    }

    @Test
    void daoPreservesB02DefaultsAndUsesOnlyAtomicParentGate() {
        AgentTaskWorkItemMapper mapper = mock(AgentTaskWorkItemMapper.class);
        when(mapper.insertIfParentNonTerminal(eq("tenant-a"), eq("client-a"), any()))
                .thenReturn(1);
        AgentTaskWorkItemDao dao = new AgentTaskWorkItemDaoImpl(mapper);
        AgentTaskWorkItemDTO item = new AgentTaskWorkItemDTO();
        item.setWorkItemId("work-1");
        item.setTaskId("task-1");
        item.setTitle("Implement B05");
        item.setWorkType("implementation");
        item.setStatus("ready");

        assertEquals(1, dao.insert("tenant-a", "client-a", item));

        ArgumentCaptor<AgentTaskWorkItemEntity> entity =
                ArgumentCaptor.forClass(AgentTaskWorkItemEntity.class);
        verify(mapper).insertIfParentNonTerminal(
                eq("tenant-a"), eq("client-a"), entity.capture());
        verify(mapper, never()).insert(any(AgentTaskWorkItemEntity.class));
        AgentTaskWorkItemEntity inserted = entity.getValue();
        assertEquals("tenant-a", inserted.getTenantId());
        assertEquals("client-a", inserted.getClientId());
        assertEquals("task-1", inserted.getTaskId());
        assertEquals(0, inserted.getPriority());
        assertEquals(true, inserted.getRequiredItem());
        assertEquals(0, inserted.getAttemptCount());
        assertEquals(3, inserted.getMaxAttempts());
        assertEquals(0L, inserted.getVersion());
        assertNotNull(inserted.getCreateTime());
        assertNotNull(inserted.getUpdateTime());
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
