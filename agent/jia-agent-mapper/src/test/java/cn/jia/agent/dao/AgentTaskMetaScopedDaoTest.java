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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void durableActiveWorkGuardCoversTaskMemberAndWorkItemAssignmentsInExactScope()
            throws Exception {
        Method method = AgentTaskMetaMapper.class.getDeclaredMethod(
                "findDurableActiveAssignmentByAgentForUpdate",
                String.class, String.class, String.class);
        String sql = normalize(String.join(" ", method.getAnnotation(Select.class).value()));

        assertTrue(sql.contains("select task.* from agent_task_meta task"), sql);
        assertTrue(sql.contains("task.tenant_id = #{tenantid}"), sql);
        assertTrue(sql.contains("task.client_id = #{clientid}"), sql);
        assertTrue(sql.contains("task.assigned_agent_id = #{agentid}"), sql);
        assertTrue(sql.contains("cast(task.assigned_agent_id as binary(400)) "), sql);
        assertTrue(sql.contains("cast(task.reward_status as binary) in ("), sql);
        String activeRoot = sql.substring(
                sql.indexOf("cast(task.reward_status as binary) in ("),
                sql.indexOf("task.assigned_agent_id = #{agentid}"));
        for (String status : new String[]{
                "open", "planning", "assigned", "running", "reviewing", "blocked"}) {
            assertTrue(activeRoot.contains("cast('" + status + "' as binary)"),
                    status + ": " + activeRoot);
        }
        for (String terminal : new String[]{"completed", "failed", "cancelled", "archived"}) {
            assertFalse(activeRoot.contains("cast('" + terminal + "' as binary)"),
                    terminal + ": " + activeRoot);
        }

        assertTrue(sql.contains("from agent_task_member member"), sql);
        assertTrue(sql.contains("member.agent_id = #{agentid}"), sql);
        assertTrue(sql.contains("member.task_id = task.task_id"), sql);
        assertTrue(sql.contains("cast(member.tenant_id as binary(200)) "), sql);
        assertTrue(sql.contains("cast(member.client_id as binary(200)) "), sql);
        String memberAssignment = sql.substring(
                sql.indexOf("from agent_task_member member"),
                sql.indexOf("or exists ( select 1 from agent_task_work_item"));
        for (String status : new String[]{"accepted", "working", "blocked"}) {
            assertTrue(memberAssignment.contains("cast('" + status + "' as binary)"),
                    status + ": " + memberAssignment);
        }

        assertTrue(sql.contains("from agent_task_work_item work_item"), sql);
        assertTrue(sql.contains("work_item.assignee_agent_id = #{agentid}"), sql);
        assertTrue(sql.contains("work_item.task_id = task.task_id"), sql);
        assertTrue(sql.contains("cast(work_item.tenant_id as binary(200)) "), sql);
        assertTrue(sql.contains("cast(work_item.client_id as binary(200)) "), sql);
        String workItemAssignment = sql.substring(
                sql.indexOf("from agent_task_work_item work_item"),
                sql.indexOf("order by task.task_id asc, task.id asc"));
        for (String status : new String[]{
                "pending", "ready", "claimed", "running", "blocked", "submitted"}) {
            assertTrue(workItemAssignment.contains("cast('" + status + "' as binary)"),
                    status + ": " + workItemAssignment);
        }

        assertTrue(sql.contains("order by task.task_id asc, task.id asc"), sql);
        assertTrue(sql.endsWith("limit 1 for update"), sql);
    }


    @Test
    void taskSearchSqlPushesExactScopeFiltersOrderingAndPaginationToDatabase()
            throws Exception {
        Method count = AgentTaskMetaMapper.class.getDeclaredMethod(
                "countSearchExactInScope", String.class, String.class,
                String.class, String.class, String.class, String.class, String.class);
        Method page = AgentTaskMetaMapper.class.getDeclaredMethod(
                "searchPageExactInScope", String.class, String.class,
                String.class, String.class, String.class, String.class, long.class, int.class, String.class);
        String countSql = normalize(String.join(" ", count.getAnnotation(Select.class).value()));
        String pageSql = normalize(String.join(" ", page.getAnnotation(Select.class).value()));

        for (String sql : List.of(countSql, pageSql)) {
            assertTrue(sql.contains("task.tenant_id = #{tenantid}"), sql);
            assertTrue(sql.contains("task.client_id = #{clientid}"), sql);
            assertTrue(sql.contains("task.owner_jiacn = #{ownerjiacn}"), sql);
            assertTrue(sql.contains("cast(task.tenant_id as binary(200)) "
                    + "= cast(#{tenantid} as binary(200))"), sql);
            assertTrue(sql.contains(
                    "octet_length(task.tenant_id) = octet_length(#{tenantid})"), sql);
            assertTrue(sql.contains("cast(task.client_id as binary(200)) "
                    + "= cast(#{clientid} as binary(200))"), sql);
            assertTrue(sql.contains(
                    "octet_length(task.client_id) = octet_length(#{clientid})"), sql);
            assertTrue(sql.contains("cast(task.owner_jiacn as binary(200)) "
                    + "= cast(#{ownerjiacn} as binary(200))"), sql);
            assertTrue(sql.contains(
                    "octet_length(task.owner_jiacn) = octet_length(#{ownerjiacn})"), sql);
            assertTrue(sql.contains("task.reward_status = #{status}"), sql);
            assertTrue(sql.contains("task.required_abilities like concat")
                    && sql.contains("#{ability}"), sql);
            assertTrue(sql.contains("lower(task.task_id) like"), sql);
            assertTrue(sql.contains("lower(coalesce(plan.name, '')) like"), sql);
            assertTrue(sql.contains("lower(coalesce(plan.description, '')) like"), sql);
            assertTrue(sql.contains("from agent_runtime runtime"), sql);
            assertFalse(sql.contains("tenant_id = '0'"), sql);
            assertFalse(sql.contains("tenant_id is null"), sql);
        }
        assertTrue(pageSql.contains(
                "order by task.update_time desc, task.task_id asc, task.id asc"), pageSql);
        assertTrue(pageSql.contains("limit #{limit} offset #{offset}"), pageSql);
        assertTrue(pageSql.contains("plan.jiacn = #{ownerjiacn}"), pageSql);
        assertTrue(pageSql.contains("plan.client_id = #{clientid}"), pageSql);
        assertFalse(pageSql.contains("plan.client_id is null"), pageSql);
        // Standard Juyi Hall task search is independent of the unpublished funding schema.
        assertTrue(pageSql.contains("0 as fundingpresent"), pageSql);
        assertFalse(pageSql.contains("agent_task_funding"), pageSql);
        assertFalse(pageSql.contains("payer_principal"), pageSql);
        assertFalse(pageSql.contains("idempotency"), pageSql);
        assertFalse(pageSql.contains("request_hash"), pageSql);
        assertFalse(pageSql.contains("transaction_id"), pageSql);

        Method fundedPage = AgentTaskMetaMapper.class.getDeclaredMethod(
                "searchPageWithFundingExactInScope", String.class, String.class,
                String.class, String.class, String.class, String.class, long.class, int.class, String.class);
        String fundedSql = normalize(String.join(" ", fundedPage.getAnnotation(Select.class).value()));
        assertTrue(fundedSql.contains("left join agent_task_funding funding"), fundedSql);
        assertFalse(fundedSql.contains("plan.client_id is null"), fundedSql);
        assertTrue(fundedSql.contains("funding.tenant_id = #{tenantid}"), fundedSql);
        assertTrue(fundedSql.contains("funding.client_id = #{clientid}"), fundedSql);
        assertTrue(fundedSql.contains("funding.owner_jiacn = #{ownerjiacn}"), fundedSql);
        assertTrue(fundedSql.contains("cast(funding.task_id as binary(400)) "
                + "= cast(task.task_id as binary(400))"), fundedSql);
        assertFalse(fundedSql.contains("payer_principal"), fundedSql);
        assertFalse(fundedSql.contains("idempotency"), fundedSql);
        assertFalse(fundedSql.contains("request_hash"), fundedSql);
        assertFalse(fundedSql.contains("transaction_id"), fundedSql);
    }

    @Test
    void taskStatusCountsUseSameExactScopeAndDatabaseAggregation() throws Exception {
        Method method = AgentTaskMetaMapper.class.getDeclaredMethod(
                "countSearchByStatusExactInScope", String.class, String.class,
                String.class, String.class, String.class, String.class);
        String sql = normalize(String.join(" ", method.getAnnotation(Select.class).value()));

        assertTrue(sql.contains("task.tenant_id = #{tenantid}"), sql);
        assertTrue(sql.contains("task.client_id = #{clientid}"), sql);
        assertTrue(sql.contains("task.owner_jiacn = #{ownerjiacn}"), sql);
        assertTrue(sql.contains("cast(task.tenant_id as binary(200))"), sql);
        assertTrue(sql.contains("octet_length(task.tenant_id)"), sql);
        assertTrue(sql.contains("cast(task.client_id as binary(200))"), sql);
        assertTrue(sql.contains("octet_length(task.client_id)"), sql);
        assertTrue(sql.contains("cast(task.owner_jiacn as binary(200))"), sql);
        assertTrue(sql.contains("octet_length(task.owner_jiacn)"), sql);
        assertTrue(sql.contains("task.required_abilities like concat")
                    && sql.contains("#{ability}"), sql);
        assertTrue(sql.contains("lower(coalesce(plan.name, '')) like"), sql);
        assertTrue(sql.contains("lower(task.task_id) like"), sql);
        assertTrue(sql.contains("as phase"), sql);
        assertTrue(sql.contains("agent_task_formal_delivery"), sql);
        assertTrue(sql.contains("count(*) as taskcount"), sql);
        assertTrue(sql.contains("group by classified.tenantid"), sql);
        assertTrue(sql.contains("#{status}"), sql); // DAO supplies explicit null, counts span phases.
        assertFalse(sql.contains("tenant_id = '0'"), sql);
    }

    @Test
    void phaseReadModelIsSharedByPageTotalAndCountsAndRejectsStaleSubmittedBatches() throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(AgentTaskMetaMapper.class);
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("tenantId", "0"); params.put("clientId", "Client-A"); params.put("ownerJiacn", "Owner-A");
        params.put("status", null); params.put("phase", "IN_PROGRESS"); params.put("keyword", "report");
        params.put("ability", "plan"); params.put("offset", 0L); params.put("limit", 30);
        for (String name : List.of("countSearchExactInScope", "searchPageExactInScope",
                "searchPageWithFundingExactInScope", "countSearchByStatusExactInScope")) {
            var bound = configuration.getMappedStatement(AgentTaskMetaMapper.class.getName() + "." + name)
                    .getBoundSql(params);
            String sql = normalize(bound.getSql());
            assertTrue(sql.contains("binary d.task_id = binary task.task_id"), sql);
            assertTrue(sql.contains("binary d.tenant_id = binary task.tenant_id"), sql);
            assertTrue(sql.contains("binary d.client_id = binary task.client_id"), sql);
            assertTrue(sql.contains("newer.revision > d.revision"), sql);
            assertTrue(sql.contains("then 'pending_acceptance'"), sql);
            assertTrue(sql.contains("else 'unknown' end"), sql);
            assertTrue(sql.contains("lower(coalesce(plan.description, '')) like"), sql);
            assertFalse(sql.contains("&gt;"), sql);
            assertFalse(sql.contains("${"), sql);
            long phaseBindings = bound.getParameterMappings().stream()
                    .filter(v -> "phase".equals(v.getProperty())).count();
            assertEquals(name.equals("countSearchByStatusExactInScope") ? 0 : 1, phaseBindings);
        }
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
    void daoPushesStrictOwnerScopePaginationAndBatchBoundsIntoMapper() throws Exception {
        AgentTaskMetaMapper mapper = mock(AgentTaskMetaMapper.class);
        when(mapper.selectByAgentInScope("tenant-a", "client-a", "agent-a", 500))
                .thenReturn(List.of());
        AgentTaskMetaDao dao = dao(mapper);

        assertEquals(List.of(), dao.findByAgentId(
                "tenant-a", "client-a", "agent-a", 9999));
        verify(mapper).selectByAgentInScope("tenant-a", "client-a", "agent-a", 500);

        when(mapper.countSearchExactInScope(
                "0", "client-a", "owner-a", "open", "planning", "reward", null))
                .thenReturn(2L);
        assertEquals(2L, dao.countSearch(
                "0", "client-a", "owner-a", "open", "planning", "reward", null));
        verify(mapper).countSearchExactInScope(
                "0", "client-a", "owner-a", "open", "planning", "reward", null);

        when(mapper.searchPageExactInScope(
                "0", "client-a", "owner-a", "open", "planning", "reward", 500L, 500, null))
                .thenReturn(List.of());
        assertEquals(List.of(), dao.searchPage(
                "0", "client-a", "owner-a", "open", "planning", "reward", 500L, 9999, null));
        verify(mapper).searchPageExactInScope(
                "0", "client-a", "owner-a", "open", "planning", "reward", 500L, 500, null);

        when(mapper.searchPageWithFundingExactInScope(
                "0", "client-a", "owner-a", "open", "planning", "reward", 500L, 500, null))
                .thenReturn(List.of());
        assertEquals(List.of(), dao.searchPageWithFunding(
                "0", "client-a", "owner-a", "open", "planning", "reward", 500L, 9999, null));
        verify(mapper).searchPageWithFundingExactInScope(
                "0", "client-a", "owner-a", "open", "planning", "reward", 500L, 500, null);

        dao.findSearchMembers("0", "client-a", "owner-a", List.of("task-a", "task-a"));
        verify(mapper).selectSearchMembersExactInScope(
                "0", "client-a", "owner-a", List.of("task-a"));
        dao.findSearchRuntimes("0", "client-a", "owner-a", List.of("agent-a", "agent-a"));
        verify(mapper).selectSearchRuntimesExactInOwnerScope(
                "0", "client-a", "owner-a", List.of("agent-a"));
        dao.countSearchByStatus("0", "client-a", "owner-a", "planning", "reward");
        verify(mapper).countSearchByStatusExactInScope(
                "0", "client-a", "owner-a", "planning", "reward", null);

        assertThrows(IllegalArgumentException.class,
                () -> dao.countSearch("tenant-a", "client-a", "owner-a", null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> dao.searchPage("0", "client-a", "0", null, null, null, 0, 20, null));
        assertThrows(IllegalArgumentException.class,
                () -> dao.countSearchByStatus("0", "client-a", " owner-a", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> dao.searchPage("0", "client-a", "owner-a", null, null, null, -1, 20, null));

        dao.reserveOpenTaskRoot("tenant-a", "client-a", "task-a", 1L);
        verify(mapper).reserveOpenTaskRoot("tenant-a", "client-a", "task-a", 1L);
        dao.rekeyReservedTaskRoot("tenant-a", "client-a", "task-a", "42", 2L);
        verify(mapper).rekeyReservedTaskRoot(
                "tenant-a", "client-a", "task-a", "42", 2L);
        dao.deleteReservedTaskRoot("tenant-a", "client-a", "task-a");
        verify(mapper).deleteReservedTaskRoot("tenant-a", "client-a", "task-a");
    }

    @Test
    void ownerRootObservationForwardsExactTenantButReservationRemainsClosed() throws Exception {
        var mapper=mock(AgentTaskMetaMapper.class); var dao=dao(mapper);
        dao.findByTaskIdForUpdateInOwnerScope("1","client","owner","task");
        verify(mapper).findExactByOwnerTaskScopeForUpdate("1","client","owner","task");
        for (String tenant:List.of(" 1","1 ","x".repeat(51)))
            assertThrows(IllegalArgumentException.class,()->dao.findByTaskIdForUpdateInOwnerScope(tenant,"client","owner","task"));
        assertThrows(IllegalArgumentException.class,()->dao.findByTaskIdForUpdateInOwnerScope("1","client","0","task"));
        assertThrows(IllegalArgumentException.class,()->dao.reserveOpenTaskRootInOwnerScope("1","client","owner","task",1000));
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
