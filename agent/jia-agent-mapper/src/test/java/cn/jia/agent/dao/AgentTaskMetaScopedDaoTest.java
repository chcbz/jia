package cn.jia.agent.dao;

import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.common.dao.BaseDaoImpl;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskMetaScopedDaoTest {
    @Test
    void rootReservationIsIdempotentAndInitializesDeterministicOpenState() throws Exception {
        Method method = AgentTaskMetaMapper.class.getDeclaredMethod(
                "reserveOpenTaskRoot", String.class, String.class, String.class, long.class);
        String sql = normalize(String.join(" ", method.getAnnotation(Insert.class).value()));

        assertTrue(sql.startsWith("insert ignore into agent_task_meta"), sql);
        assertTrue(sql.contains("(task_id, reward_status, collaboration_mode, risk_level, max_agents,"), sql);
        assertTrue(sql.contains("values (#{taskid}, 'open', 'single', 'low', 1,"), sql);
        assertTrue(sql.contains("0, 0, 0, #{tenantid}, #{clientid}, #{createtime}, #{createtime})"), sql);
    }

    @Test
    void reservedRootPlanIdBackfillAndCleanupAreByteExactAndStateGuarded() throws Exception {
        Method rekey = AgentTaskMetaMapper.class.getDeclaredMethod(
                "rekeyReservedTaskRoot", String.class, String.class,
                String.class, String.class, long.class);
        String rekeySql = normalize(String.join(" ", rekey.getAnnotation(Update.class).value()));
        assertTrue(rekeySql.startsWith("update agent_task_meta set task_id = #{finaltaskid}"), rekeySql);
        assertTrue(rekeySql.contains("cast(tenant_id as binary(200)) = cast(#{tenantid} as binary(200))"), rekeySql);
        assertTrue(rekeySql.contains("octet_length(tenant_id) = octet_length(#{tenantid})"), rekeySql);
        assertTrue(rekeySql.contains("cast(client_id as binary(200)) = cast(#{clientid} as binary(200))"), rekeySql);
        assertTrue(rekeySql.contains("octet_length(client_id) = octet_length(#{clientid})"), rekeySql);
        assertTrue(rekeySql.contains("substring(task_id, 1, 50)"), rekeySql);
        assertTrue(rekeySql.contains("substring(task_id, 51, 50)"), rekeySql);
        assertTrue(rekeySql.contains("octet_length(task_id) = octet_length(#{reservedtaskid})"), rekeySql);
        assertExactOpenGuard(rekeySql);
        assertTrue(rekeySql.contains("task_version = 0"), rekeySql);
        assertTrue(rekeySql.contains("current_event_version = 0"), rekeySql);

        Method delete = AgentTaskMetaMapper.class.getDeclaredMethod(
                "deleteReservedTaskRoot", String.class, String.class, String.class);
        String deleteSql = normalize(String.join(" ", delete.getAnnotation(Delete.class).value()));
        assertTrue(deleteSql.startsWith("delete from agent_task_meta"), deleteSql);
        assertTrue(deleteSql.contains("substring(task_id, 1, 50)"), deleteSql);
        assertTrue(deleteSql.contains("substring(task_id, 51, 50)"), deleteSql);
        assertTrue(deleteSql.contains("octet_length(task_id) = octet_length(#{reservedtaskid})"), deleteSql);
        assertExactOpenGuard(deleteSql);
        assertTrue(deleteSql.contains("task_version = 0"), deleteSql);
        assertTrue(deleteSql.contains("current_event_version = 0"), deleteSql);
    }

    @Test
    void taskRootLockAndLegacyFallbackAreByteExactScopedAndDeterministic() throws Exception {
        Method lock = AgentTaskMetaMapper.class.getDeclaredMethod(
                "findExactByTaskScopeForUpdate", String.class, String.class, String.class);
        String lockSql = normalize(String.join(" ", lock.getAnnotation(Select.class).value()));
        assertExactScope(lockSql);
        assertTrue(lockSql.contains("substring(task_id, 1, 50)"), lockSql);
        assertTrue(lockSql.contains("substring(task_id, 51, 50)"), lockSql);
        assertTrue(lockSql.contains("octet_length(task_id) = octet_length(#{taskid})"), lockSql);
        assertTrue(lockSql.endsWith("limit 1 for update"), lockSql);

        Method fallback = AgentTaskMetaMapper.class.getDeclaredMethod(
                "selectByAgentInScope", String.class, String.class, String.class, int.class);
        String fallbackSql = normalize(String.join(" ", fallback.getAnnotation(Select.class).value()));
        assertExactScope(fallbackSql);
        assertTrue(fallbackSql.contains("cast(assigned_agent_id as binary(400)) "
                + "= cast(#{agentid} as binary(400))"), fallbackSql);
        assertTrue(fallbackSql.contains(
                "octet_length(assigned_agent_id) = octet_length(#{agentid})"), fallbackSql);
        assertTrue(fallbackSql.contains("order by update_time desc, task_id asc, id asc"), fallbackSql);
        assertTrue(fallbackSql.endsWith("limit #{limit}"), fallbackSql);
    }


    @Test
    void workItemRootLockUsesByteExactScopeChildAndTaskJoinWithDeterministicOrder()
            throws Exception {
        Method method = AgentTaskMetaMapper.class.getDeclaredMethod(
                "findTaskRootByWorkItemForUpdate", String.class, String.class, String.class);
        String sql = normalize(String.join(" ", method.getAnnotation(Select.class).value()));

        assertTrue(sql.contains("parent.tenant_id = #{tenantid}"), sql);
        assertTrue(sql.contains("cast(parent.tenant_id as binary(200)) "
                + "= cast(#{tenantid} as binary(200))"), sql);
        assertTrue(sql.contains("octet_length(parent.tenant_id) = octet_length(#{tenantid})"), sql);
        assertTrue(sql.contains("parent.client_id = #{clientid}"), sql);
        assertTrue(sql.contains("cast(parent.client_id as binary(200)) "
                + "= cast(#{clientid} as binary(200))"), sql);
        assertTrue(sql.contains("octet_length(parent.client_id) = octet_length(#{clientid})"), sql);

        assertTrue(sql.contains("child.tenant_id = #{tenantid}"), sql);
        assertTrue(sql.contains("cast(child.tenant_id as binary(200)) "
                + "= cast(#{tenantid} as binary(200))"), sql);
        assertTrue(sql.contains("octet_length(child.tenant_id) = octet_length(#{tenantid})"), sql);
        assertTrue(sql.contains("child.client_id = #{clientid}"), sql);
        assertTrue(sql.contains("cast(child.client_id as binary(200)) "
                + "= cast(#{clientid} as binary(200))"), sql);
        assertTrue(sql.contains("octet_length(child.client_id) = octet_length(#{clientid})"), sql);
        assertTrue(sql.contains("child.tenant_id = parent.tenant_id"), sql);
        assertTrue(sql.contains("cast(child.tenant_id as binary(200)) "
                + "= cast(parent.tenant_id as binary(200))"), sql);
        assertTrue(sql.contains("octet_length(child.tenant_id) = octet_length(parent.tenant_id)"), sql);
        assertTrue(sql.contains("child.client_id = parent.client_id"), sql);
        assertTrue(sql.contains("cast(child.client_id as binary(200)) "
                + "= cast(parent.client_id as binary(200))"), sql);
        assertTrue(sql.contains("octet_length(child.client_id) = octet_length(parent.client_id)"), sql);

        assertTrue(sql.contains("child.work_item_id = #{workitemid}"), sql);
        assertTrue(sql.contains("substring(child.work_item_id, 1, 50)"), sql);
        assertTrue(sql.contains("substring(child.work_item_id, 51, 50)"), sql);
        assertTrue(sql.contains("octet_length(child.work_item_id) = octet_length(#{workitemid})"), sql);
        assertTrue(sql.contains("child.task_id = parent.task_id"), sql);
        assertTrue(sql.contains("substring(child.task_id, 1, 50)"), sql);
        assertTrue(sql.contains("substring(parent.task_id, 1, 50)"), sql);
        assertTrue(sql.contains("substring(child.task_id, 51, 50)"), sql);
        assertTrue(sql.contains("substring(parent.task_id, 51, 50)"), sql);
        assertTrue(sql.contains("octet_length(child.task_id) = octet_length(parent.task_id)"), sql);
        assertTrue(sql.contains("order by parent.task_id asc, parent.id asc"), sql);
        assertTrue(sql.endsWith("limit 1 for update"), sql);
    }

    @Test
    void daoPushesScopeAndBoundedLimitIntoMapper() throws Exception {
        AgentTaskMetaMapper mapper = mock(AgentTaskMetaMapper.class);
        when(mapper.selectByAgentInScope("tenant-a", "client-a", "agent-a", 500))
                .thenReturn(List.of());
        AgentTaskMetaDao dao = dao(mapper);

        assertEquals(List.of(), dao.findByAgentId(
                "tenant-a", "client-a", "agent-a", 9999));
        verify(mapper).selectByAgentInScope("tenant-a", "client-a", "agent-a", 500);

        dao.reserveOpenTaskRoot("tenant-a", "client-a", "task-a", 1L);
        verify(mapper).reserveOpenTaskRoot("tenant-a", "client-a", "task-a", 1L);
        dao.rekeyReservedTaskRoot("tenant-a", "client-a", "task-a", "42", 2L);
        verify(mapper).rekeyReservedTaskRoot(
                "tenant-a", "client-a", "task-a", "42", 2L);
        dao.deleteReservedTaskRoot("tenant-a", "client-a", "task-a");
        verify(mapper).deleteReservedTaskRoot("tenant-a", "client-a", "task-a");
    }

    private void assertExactOpenGuard(String sql) {
        assertTrue(sql.contains("reward_status = 'open'"), sql);
        assertTrue(sql.contains(
                "cast(reward_status as binary) = cast('open' as binary)"), sql);
        assertTrue(sql.contains(
                "octet_length(reward_status) = octet_length('open')"), sql);
    }

    private void assertExactScope(String sql) {
        assertTrue(sql.contains("tenant_id = #{tenantid}"), sql);
        assertTrue(sql.contains("client_id = #{clientid}"), sql);
        assertTrue(sql.contains("cast(tenant_id as binary(200)) = cast(#{tenantid} as binary(200))"), sql);
        assertTrue(sql.contains("octet_length(tenant_id) = octet_length(#{tenantid})"), sql);
        assertTrue(sql.contains("cast(client_id as binary(200)) = cast(#{clientid} as binary(200))"), sql);
        assertTrue(sql.contains("octet_length(client_id) = octet_length(#{clientid})"), sql);
    }

    private AgentTaskMetaDao dao(AgentTaskMetaMapper mapper) throws Exception {
        AgentTaskMetaDaoImpl dao = new AgentTaskMetaDaoImpl();
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(dao, mapper);
        return dao;
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
