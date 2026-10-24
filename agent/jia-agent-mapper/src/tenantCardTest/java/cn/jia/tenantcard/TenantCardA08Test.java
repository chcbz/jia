package cn.jia.tenantcard;

import cn.jia.agent.dao.impl.AgentTaskCreationOperationDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskNoteDaoImpl;
import cn.jia.agent.entity.AgentTaskCreationOperationEntity;
import cn.jia.agent.mapper.AgentTaskCreationOperationMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskNoteMapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantCardA08Test {
    @Test
    void tenantOneOwnerScopedDaosKeepExactTenantClientOwnerPredicates() throws Exception {
        AgentTaskMetaMapper metaMapper = mock(AgentTaskMetaMapper.class);
        AgentTaskMetaDaoImpl metaDao = inject(new AgentTaskMetaDaoImpl(), metaMapper);
        when(metaMapper.countSearchExactInScope("1", "client-1", "owner-1",
                null, null, null)).thenReturn(0L);

        assertDoesNotThrow(() -> metaDao.countSearch(
                "1", "client-1", "owner-1", null, null, null));
        verify(metaMapper).countSearchExactInScope(
                "1", "client-1", "owner-1", null, null, null);

        AgentTaskNoteMapper noteMapper = mock(AgentTaskNoteMapper.class);
        AgentTaskNoteDaoImpl noteDao = inject(new AgentTaskNoteDaoImpl(), noteMapper);
        when(noteMapper.selectList(any())).thenReturn(java.util.List.of());
        assertDoesNotThrow(() -> noteDao.findByTaskIdInOwnerScope(
                "1", "client-1", "owner-1", "task-1"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<cn.jia.agent.entity.AgentTaskNoteEntity>> queryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(noteMapper).selectList(queryCaptor.capture());
        String sql = queryCaptor.getValue().getExpression().getNormal().getSqlSegment();
        for (String column : java.util.List.of(
                "tenant_id", "client_id", "owner_jiacn", "task_id")) {
            assertTrue(sql.contains("CAST(" + column + " AS BINARY) = CAST("), sql);
            assertTrue(sql.contains("OCTET_LENGTH(" + column + ") = OCTET_LENGTH("), sql);
        }
        var values = queryCaptor.getValue().getParamNameValuePairs().values();
        for (String value : java.util.List.of(
                "1", "client-1", "owner-1", "task-1")) {
            assertEquals(2L, values.stream().filter(value::equals).count());
        }
        assertThrows(IllegalArgumentException.class, () -> noteDao.findByTaskIdInOwnerScope(
                "1", "client-1", "0", "task-1"));
        assertThrows(IllegalArgumentException.class, () -> noteDao.findByTaskIdInOwnerScope(
                "1", "client-1\0", "owner-1", "task-1"));
        assertThrows(IllegalArgumentException.class, () -> noteDao.findByTaskIdInOwnerScope(
                "1", "client-1", "owner-1", "task-1 "));
    }

    @Test
    void malformedTenantFailsClosedBeforeCreationOperationSql() {
        AgentTaskCreationOperationMapper mapper = mock(AgentTaskCreationOperationMapper.class);
        AgentTaskCreationOperationDaoImpl dao = new AgentTaskCreationOperationDaoImpl(mapper);
        AgentTaskCreationOperationEntity row = new AgentTaskCreationOperationEntity();
        when(mapper.select("1", "client-1", "owner-1", "idem-1")).thenReturn(row);

        assertDoesNotThrow(() -> dao.find("1", "client-1", "owner-1", "idem-1"));
        verify(mapper).select("1", "client-1", "owner-1", "idem-1");
        assertThrows(IllegalArgumentException.class,
                () -> dao.find(" 1", "client-1", "owner-1", "idem-1"));
    }

    @Test
    void everyTaskPlanLeftJoinFencesTenantInsideOnClause() {
        for (String methodName : java.util.List.of(
                "countSearchExactInScope", "searchPageExactInScope",
                "searchPageWithFundingExactInScope")) {
            Method method = Arrays.stream(AgentTaskMetaMapper.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst().orElseThrow();
            String sql = String.join("\n", method.getAnnotation(Select.class).value());
            int join = sql.indexOf("LEFT JOIN task_plan plan");
            int where = sql.indexOf("WHERE task.tenant_id", join);
            String onClause = sql.substring(join, where);
            assertTrue(onClause.contains("plan.tenant_id = #{tenantId}"), methodName);
            assertTrue(onClause.contains("CAST(plan.tenant_id AS BINARY(200))"), methodName);
            assertTrue(onClause.contains("OCTET_LENGTH(plan.tenant_id)"), methodName);
            assertTrue(onClause.contains("plan.client_id = #{clientId}"), methodName);
            assertTrue(onClause.contains("plan.jiacn = #{ownerJiacn}"), methodName);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T inject(T dao, BaseMapper<?> mapper) throws Exception {
        Class<?> type = dao.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("baseMapper");
                field.setAccessible(true);
                field.set(dao, mapper);
                return dao;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new IllegalStateException("baseMapper field unavailable");
    }
}
