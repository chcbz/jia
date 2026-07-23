package cn.jia.agent.dao;

import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentTaskWorkItemLeaseDaoTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String WORK = "work-1";
    private static final String AGENT = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TOKEN = "lease-token";

    @BeforeAll
    static void initializeLambdaColumnMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "b04-lease-dao-test");
        TableInfoHelper.initTableInfo(assistant, AgentTaskWorkItemEntity.class);
    }

    @Test
    void expiredLeaseScanIsScopedBoundedAndDeterministicallyOrdered() {
        AgentTaskWorkItemMapper mapper = mock(AgentTaskWorkItemMapper.class);
        AgentTaskWorkItemDao dao = new AgentTaskWorkItemDaoImpl(mapper);

        dao.listExpiredLeases(TENANT, CLIENT, 1_000L, 9999);

        ArgumentCaptor<Wrapper<AgentTaskWorkItemEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        Wrapper<?> wrapper = captor.getValue();
        AbstractWrapper<?, ?, ?> actual = (AbstractWrapper<?, ?, ?>) wrapper;
        String sql = normalize(wrapper.getSqlSegment());
        assertTrue(sql.contains("tenant_id"), sql);
        assertTrue(sql.contains("client_id"), sql);
        assertTrue(sql.contains("status in"), sql);
        assertTrue(sql.contains("lease_until is not null"), sql);
        assertTrue(sql.contains("lease_until <="), sql);
        assertTrue(sql.contains("order by lease_until asc,work_item_id asc,id asc"), sql);
        assertTrue(sql.endsWith("limit 500"), sql);
        assertTrue(actual.getParamNameValuePairs().containsValue(TENANT));
        assertTrue(actual.getParamNameValuePairs().containsValue(CLIENT));
        assertTrue(actual.getParamNameValuePairs().containsValue(1_000L));
    }

    @Test
    void leaseCasSqlIncludesExactScopeTaskAgentTokenStatusTimeAndVersion() throws Exception {
        String claimUnassigned = sql("claimReadyUnassignedByVersion");
        assertScopedTaskWorkVersion(claimUnassigned);
        assertTrue(claimUnassigned.contains("status = 'ready'"), claimUnassigned);
        assertTrue(claimUnassigned.contains("assignee_agent_id is null"), claimUnassigned);
        assertTrue(claimUnassigned.contains("lease_token is null"), claimUnassigned);
        assertTrue(claimUnassigned.contains("lease_until is null"), claimUnassigned);

        String claimAssigned = sql("claimReadyAssignedByVersion");
        assertScopedTaskWorkVersion(claimAssigned);
        assertTrue(claimAssigned.contains("assignee_agent_id = #{expectedassigneeagentid}"), claimAssigned);

        String active = sql("updateActiveLeaseByVersion");
        assertScopedTaskWorkVersion(active);
        assertTrue(active.contains("assignee_agent_id = #{assigneeagentid}"), active);
        assertTrue(active.contains("lease_token = #{leasetoken}"), active);
        assertTrue(active.contains("status = #{expectedstatus}"), active);
        assertTrue(active.contains("lease_until = #{expectedleaseuntil}"), active);
        assertTrue(active.contains("lease_until > #{operationtime}"), active);

        String expiry = sql("expireLeaseByVersion");
        assertScopedTaskWorkVersion(expiry);
        assertTrue(expiry.contains("assignee_agent_id = #{assigneeagentid}"), expiry);
        assertTrue(expiry.contains("lease_token = #{leasetoken}"), expiry);
        assertTrue(expiry.contains("status = #{expectedstatus}"), expiry);
        assertTrue(expiry.contains("lease_until = #{expectedleaseuntil}"), expiry);
        assertTrue(expiry.contains("lease_until <= #{expiredatorbefore}"), expiry);
    }

    @Test
    void specializedLeaseDaosDelegateCompleteSnapshotsWithoutReadBeforeWrite() {
        AgentTaskWorkItemMapper mapper = mock(AgentTaskWorkItemMapper.class);
        AgentTaskWorkItemDao dao = new AgentTaskWorkItemDaoImpl(mapper);
        AgentTaskWorkItemDTO snapshot = completeSnapshot();

        dao.claimReadyByVersion(TENANT, CLIENT, TASK, WORK, null, 3L, snapshot);
        verify(mapper).claimReadyUnassignedByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(3L), same(snapshot), anyLong());

        dao.claimReadyByVersion(TENANT, CLIENT, TASK, WORK, AGENT, 4L, snapshot);
        verify(mapper).claimReadyAssignedByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(4L),
                same(snapshot), anyLong());

        dao.updateActiveLeaseByVersion(
                TENANT, CLIENT, TASK, WORK, AGENT, TOKEN, "running",
                2_000L, 5L, 1_000L, snapshot);
        verify(mapper).updateActiveLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(TOKEN),
                eq("running"), eq(2_000L), eq(5L), eq(1_000L), same(snapshot), anyLong());

        dao.expireLeaseByVersion(
                TENANT, CLIENT, TASK, WORK, AGENT, TOKEN, "running",
                2_000L, 5L, 2_001L, snapshot);
        verify(mapper).expireLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(TOKEN),
                eq("running"), eq(2_000L), eq(5L), eq(2_001L), same(snapshot), anyLong());
    }

    @Test
    void allLeaseCasUpdatesWriteCompleteSnapshotAndIncrementVersion() throws Exception {
        for (String methodName : Arrays.asList(
                "claimReadyUnassignedByVersion", "claimReadyAssignedByVersion",
                "updateActiveLeaseByVersion", "expireLeaseByVersion")) {
            String sql = sql(methodName);
            for (String column : Arrays.asList(
                    "title", "description", "work_type", "required_abilities",
                    "assignee_agent_id", "status", "priority", "required_item",
                    "dependency_json", "lease_token", "lease_until", "attempt_count",
                    "max_attempts", "result_artifact_id", "submitted_at", "completed_at")) {
                assertTrue(sql.contains(column + " = #{item."), methodName + ": " + column + " missing");
            }
            assertTrue(sql.contains("version = version + 1"), methodName);
        }
    }

    private void assertScopedTaskWorkVersion(String sql) {
        assertTrue(sql.contains("tenant_id = #{tenantid}"), sql);
        assertTrue(sql.contains("client_id = #{clientid}"), sql);
        assertTrue(sql.contains("task_id = #{taskid}"), sql);
        assertTrue(sql.contains("work_item_id = #{workitemid}"), sql);
        assertTrue(sql.contains("version = #{expectedversion}"), sql);
    }

    private String sql(String methodName) throws Exception {
        Method method = Arrays.stream(AgentTaskWorkItemMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        return normalize(String.join(" ", method.getAnnotation(Update.class).value()));
    }

    private AgentTaskWorkItemDTO completeSnapshot() {
        AgentTaskWorkItemDTO item = new AgentTaskWorkItemDTO();
        item.setWorkItemId(WORK);
        item.setTaskId(TASK);
        item.setTitle("B04");
        item.setWorkType("implementation");
        item.setStatus("claimed");
        item.setPriority(10);
        item.setRequiredItem(true);
        item.setAttemptCount(0);
        item.setMaxAttempts(3);
        return item;
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
