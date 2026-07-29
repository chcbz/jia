package cn.jia.agent.dao;

import cn.jia.agent.dao.impl.AgentTaskArtifactDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMemberDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskRequestDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.entity.AgentTaskArtifactDTO;
import cn.jia.agent.entity.AgentTaskArtifactEntity;
import cn.jia.agent.entity.AgentTaskMemberDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskRequestDTO;
import cn.jia.agent.entity.AgentTaskRequestEntity;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.mapper.AgentTaskArtifactMapper;
import cn.jia.agent.mapper.AgentTaskMemberMapper;
import cn.jia.agent.mapper.AgentTaskRequestMapper;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentTaskCollaborationDaoTest {
    @BeforeAll
    static void initializeLambdaColumnMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "task-collaboration-test");
        for (Class<?> entity : List.of(
                AgentTaskMemberEntity.class, AgentTaskWorkItemEntity.class,
                AgentTaskRequestEntity.class, AgentTaskArtifactEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @Test
    void everyCollaborationDaoMethodStartsWithTenantAndClientScope() {
        for (Class<?> dao : List.of(
                AgentTaskMemberDao.class, AgentTaskWorkItemDao.class,
                AgentTaskRequestDao.class, AgentTaskArtifactDao.class)) {
            for (Method method : dao.getDeclaredMethods()) {
                assertTrue(method.getParameterCount() >= 2, method.toString());
                assertEquals(String.class, method.getParameterTypes()[0], method.toString());
                assertEquals(String.class, method.getParameterTypes()[1], method.toString());
            }
            assertEquals(0, dao.getInterfaces().length, dao.getName());
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void memberQueriesBindScopeAndUseDeterministicOrdering() {
        AgentTaskMemberMapper mapper = mock(AgentTaskMemberMapper.class);
        AgentTaskMemberDao dao = new AgentTaskMemberDaoImpl(mapper);

        dao.findByTaskAndAgent("tenant-a", "client-a", "task-1", "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        dao.findByTaskAndAgentForUpdate(
                "tenant-a", "client-a", "task-1", "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        dao.listByTask("tenant-a", "client-a", "task-1");
        dao.listByAgent("tenant-a", "client-a", "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "working", 50);

        verify(mapper).findExactByTaskAndAgent(
                "tenant-a", "client-a", "task-1", "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        verify(mapper).findExactByTaskAndAgentForUpdate(
                "tenant-a", "client-a", "task-1", "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        verify(mapper, never()).selectOne(any());

        ArgumentCaptor<Wrapper<AgentTaskMemberEntity>> lists = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, org.mockito.Mockito.times(2)).selectList(lists.capture());
        for (Wrapper<?> wrapper : lists.getAllValues()) {
            assertScoped(wrapper, "tenant-a", "client-a");
        }
        String taskSql = normalize(lists.getAllValues().get(0).getSqlSegment());
        assertTrue(taskSql.contains("order by member_role asc,agent_id asc,id asc"), taskSql);
        String agentSql = normalize(lists.getAllValues().get(1).getSqlSegment());
        assertTrue(agentSql.contains("order by update_time desc,task_id asc,id asc"), agentSql);
        assertTrue(agentSql.endsWith("limit 50"), agentSql);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void workItemAndRequestListsAreScopedBoundedAndDeterministicallyOrdered() {
        AgentTaskWorkItemMapper workMapper = mock(AgentTaskWorkItemMapper.class);
        new AgentTaskWorkItemDaoImpl(workMapper)
                .listByTask("tenant-a", "client-a", "task-1", "ready", 9999);
        ArgumentCaptor<Wrapper<AgentTaskWorkItemEntity>> work = ArgumentCaptor.forClass(Wrapper.class);
        verify(workMapper).selectList(work.capture());
        assertScoped(work.getValue(), "tenant-a", "client-a");
        assertValues(work.getValue(), "task-1", "ready");
        String workSql = normalize(work.getValue().getSqlSegment());
        assertTrue(workSql.contains("order by priority desc,create_time asc,work_item_id asc,id asc"), workSql);
        assertTrue(workSql.endsWith("limit 500"), workSql);

        AgentTaskRequestMapper requestMapper = mock(AgentTaskRequestMapper.class);
        new AgentTaskRequestDaoImpl(requestMapper).listByTarget(
                "tenant-b", "client-b", "task-1", "agent", "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "open", 20);
        ArgumentCaptor<Wrapper<AgentTaskRequestEntity>> request = ArgumentCaptor.forClass(Wrapper.class);
        verify(requestMapper).selectList(request.capture());
        assertScoped(request.getValue(), "tenant-b", "client-b");
        assertValues(request.getValue(), "agent", "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "open");
        String requestSql = normalize(request.getValue().getSqlSegment());
        assertTrue(requestSql.contains(
                "order by priority desc,due_at asc,create_time asc,request_id asc,id asc"), requestSql);
        assertTrue(requestSql.endsWith("limit 20"), requestSql);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void requestWorkItemAndStatusPredicatesPrecedeDeterministicLimit() {
        AgentTaskRequestMapper mapper = mock(AgentTaskRequestMapper.class);
        new AgentTaskRequestDaoImpl(mapper).listByTask(
                "tenant-a", "client-a", "task-1", "open", "work-501", 2);

        ArgumentCaptor<Wrapper<AgentTaskRequestEntity>> capture = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(capture.capture());
        Wrapper<AgentTaskRequestEntity> wrapper = capture.getValue();
        assertScoped(wrapper, "tenant-a", "client-a");
        assertValues(wrapper, "task-1", "open", "work-501");
        String sql = normalize(wrapper.getSqlSegment());
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("work_item_id"), sql);
        assertTrue(sql.contains("order by priority desc,create_time asc,request_id asc,id asc"), sql);
        assertTrue(sql.endsWith("limit 2"), sql);
        assertTrue(sql.indexOf("work_item_id") < sql.indexOf("order by"), sql);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void artifactVisibilityAclPredicatesPrecedeDeterministicLimit() {
        AgentTaskArtifactMapper mapper = mock(AgentTaskArtifactMapper.class);
        new AgentTaskArtifactDaoImpl(mapper).listVisibleByTask(
                "tenant-a", "client-a", "task-1", "work-1",
                "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", true, false, 3);

        ArgumentCaptor<Wrapper<AgentTaskArtifactEntity>> capture = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(capture.capture());
        Wrapper<AgentTaskArtifactEntity> wrapper = capture.getValue();
        assertScoped(wrapper, "tenant-a", "client-a");
        assertValues(wrapper, "task-1", "work-1", "task_members", "reviewer", "private",
                "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        String sql = normalize(wrapper.getSqlSegment());
        assertTrue(sql.contains("visibility in"), sql);
        assertTrue(sql.contains("producer_agent_id"), sql);
        assertTrue(sql.contains("work_item_id"), sql);
        assertTrue(sql.contains(
                "order by created_at desc,artifact_id asc,artifact_version desc,id desc"), sql);
        assertTrue(sql.endsWith("limit 3"), sql);
        assertTrue(sql.indexOf("visibility") < sql.indexOf("order by"), sql);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void artifactAccessPreservesLogicalVersionsWithinScope() {
        AgentTaskArtifactMapper mapper = mock(AgentTaskArtifactMapper.class);
        AgentTaskArtifactDao dao = new AgentTaskArtifactDaoImpl(mapper);

        AgentTaskArtifactDTO versionOne = artifact(1);
        AgentTaskArtifactDTO versionTwo = artifact(2);
        dao.insert("tenant-a", "client-a", versionOne);
        dao.insert("tenant-a", "client-a", versionTwo);
        dao.findVersion("tenant-a", "client-a", "task-1", "artifact-1", 1);
        dao.findLatestVersion("tenant-a", "client-a", "task-1", "artifact-1");
        dao.listVersions("tenant-a", "client-a", "task-1", "artifact-1");

        ArgumentCaptor<AgentTaskArtifactEntity> inserts = ArgumentCaptor.forClass(AgentTaskArtifactEntity.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(inserts.capture());
        assertEquals(List.of(1, 2), inserts.getAllValues().stream()
                .map(AgentTaskArtifactEntity::getArtifactVersion).toList());
        for (AgentTaskArtifactEntity entity : inserts.getAllValues()) {
            assertEquals("artifact-1", entity.getArtifactId());
            assertEquals("tenant-a", entity.getTenantId());
            assertEquals("client-a", entity.getClientId());
        }

        ArgumentCaptor<Wrapper<AgentTaskArtifactEntity>> one = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, org.mockito.Mockito.times(2)).selectOne(one.capture());
        assertValues(one.getAllValues().get(0), "artifact-1", 1);
        String latestSql = normalize(one.getAllValues().get(1).getSqlSegment());
        assertTrue(latestSql.contains("order by artifact_version desc,id desc"), latestSql);

        ArgumentCaptor<Wrapper<AgentTaskArtifactEntity>> versions = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(versions.capture());
        assertScoped(versions.getValue(), "tenant-a", "client-a");
        assertTrue(normalize(versions.getValue().getSqlSegment())
                .contains("order by artifact_version desc,id desc"));
    }

    @Test
    void versionedMapperUpdatesUseFullScopeAndAtomicCasIncrement() throws Exception {
        assertCasSql(AgentTaskMemberMapper.class, "task_id = #{taskid}", "agent_id = #{agentid}");
        assertCasSql(AgentTaskWorkItemMapper.class, "work_item_id = #{workitemid}");
        assertCasSql(AgentTaskRequestMapper.class, "task_id = #{taskid}", "request_id = #{requestid}");
    }

    @Test
    void versionedDaosDelegateExpectedVersionWithoutReadBeforeWrite() {
        AgentTaskMemberMapper memberMapper = mock(AgentTaskMemberMapper.class);
        AgentTaskMemberDTO member = member();
        new AgentTaskMemberDaoImpl(memberMapper).updateByVersion(
                "tenant-a", "client-a", "task-1", "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 7L, member);
        verify(memberMapper).updateByVersion(
                org.mockito.ArgumentMatchers.eq("tenant-a"),
                org.mockito.ArgumentMatchers.eq("client-a"),
                org.mockito.ArgumentMatchers.eq("task-1"),
                org.mockito.ArgumentMatchers.eq("agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.same(member), anyLong());
        verify(memberMapper, never()).selectOne(any());

        AgentTaskWorkItemMapper workItemMapper = mock(AgentTaskWorkItemMapper.class);
        AgentTaskWorkItemDTO item = workItem();
        new AgentTaskWorkItemDaoImpl(workItemMapper).updateByVersion(
                "tenant-b", "client-b", "work-1", 8L, item);
        verify(workItemMapper).updateByVersion(
                org.mockito.ArgumentMatchers.eq("tenant-b"),
                org.mockito.ArgumentMatchers.eq("client-b"),
                org.mockito.ArgumentMatchers.eq("work-1"),
                org.mockito.ArgumentMatchers.eq(8L),
                org.mockito.ArgumentMatchers.same(item), anyLong());
        verify(workItemMapper, never()).selectOne(any());

        AgentTaskRequestMapper requestMapper = mock(AgentTaskRequestMapper.class);
        AgentTaskRequestDTO request = request();
        new AgentTaskRequestDaoImpl(requestMapper).updateByVersion(
                "tenant-c", "client-c", "task-1", "request-1", 9L, request);
        verify(requestMapper).updateByVersion(
                org.mockito.ArgumentMatchers.eq("tenant-c"),
                org.mockito.ArgumentMatchers.eq("client-c"),
                org.mockito.ArgumentMatchers.eq("task-1"),
                org.mockito.ArgumentMatchers.eq("request-1"),
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.same(request), anyLong());
        verify(requestMapper, never()).selectOne(any());
    }

    @Test
    void negativeExpectedVersionIsRejectedBeforeCasMapperAccess() {
        AgentTaskMemberMapper memberMapper = mock(AgentTaskMemberMapper.class);
        assertThrows(IllegalArgumentException.class, () -> new AgentTaskMemberDaoImpl(memberMapper).updateByVersion(
                "tenant-a", "client-a", "task-1", "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", -1L, member()));
        verify(memberMapper, never()).updateByVersion(any(), any(), any(), any(), anyLong(), any(), anyLong());

        AgentTaskWorkItemMapper workItemMapper = mock(AgentTaskWorkItemMapper.class);
        assertThrows(IllegalArgumentException.class, () -> new AgentTaskWorkItemDaoImpl(workItemMapper).updateByVersion(
                "tenant-a", "client-a", "work-1", -1L, workItem()));
        verify(workItemMapper, never()).updateByVersion(any(), any(), any(), anyLong(), any(), anyLong());

        AgentTaskRequestMapper requestMapper = mock(AgentTaskRequestMapper.class);
        assertThrows(IllegalArgumentException.class, () -> new AgentTaskRequestDaoImpl(requestMapper).updateByVersion(
                "tenant-a", "client-a", "task-1", "request-1", -1L, request()));
        verify(requestMapper, never()).updateByVersion(any(), any(), any(), any(), anyLong(), any(), anyLong());
    }

    @Test
    void blankScopeIsRejectedBeforeMapperAccess() {
        AgentTaskWorkItemMapper mapper = mock(AgentTaskWorkItemMapper.class);
        AgentTaskWorkItemDao dao = new AgentTaskWorkItemDaoImpl(mapper);

        assertThrows(IllegalArgumentException.class,
                () -> dao.findByWorkItemId("", "client-a", "work-1"));

        verify(mapper, never()).selectOne(any());
    }

    @Test
    void entitiesAndTaskMetaCoverTheB01ColumnsWithoutIdentityOrTransportKeys() {
        assertFields(AgentTaskMemberEntity.class,
                "taskId", "agentId", "memberRole", "memberStatus", "assignmentSource", "version");
        assertFields(AgentTaskWorkItemEntity.class,
                "workItemId", "taskId", "assigneeAgentId", "requiredItem", "submittedAt", "completedAt", "version");
        assertFields(AgentTaskRequestEntity.class,
                "requestId", "taskId", "requesterAgentId", "targetType", "targetId", "version");
        assertFields(AgentTaskArtifactEntity.class,
                "artifactId", "artifactVersion", "taskId", "producerAgentId", "createdAt");
        assertFields(AgentTaskMetaEntity.class,
                "collaborationMode", "riskLevel", "maxAgents", "coordinatorAgentId",
                "reviewRequired", "taskVersion", "currentEventVersion");

        for (Class<?> entity : List.of(
                AgentTaskMemberEntity.class, AgentTaskWorkItemEntity.class,
                AgentTaskRequestEntity.class, AgentTaskArtifactEntity.class)) {
            Set<String> fields = Arrays.stream(entity.getDeclaredFields())
                    .map(Field::getName).collect(java.util.stream.Collectors.toSet());
            assertFalse(fields.contains("personaCode"), entity.getName());
            assertFalse(fields.contains("runtimeInstanceId"), entity.getName());
            assertFalse(fields.contains("webSocketSessionId"), entity.getName());
        }
    }

    private void assertCasSql(Class<?> mapperType, String... businessKeys) throws Exception {
        Method method = Arrays.stream(mapperType.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("updateByVersion"))
                .findFirst().orElseThrow();
        String sql = normalize(String.join(" ", method.getAnnotation(Update.class).value()));
        assertTrue(sql.contains("where tenant_id = #{tenantid}"), sql);
        assertTrue(sql.contains("and client_id = #{clientid}"), sql);
        for (String businessKey : businessKeys) {
            assertTrue(sql.contains("and " + businessKey), sql);
        }
        assertTrue(sql.contains("and version = #{expectedversion}"), sql);
        assertTrue(sql.contains("version = version + 1"), sql);
    }

    private void assertFields(Class<?> type, String... expectedFields) {
        Set<String> fields = Arrays.stream(type.getDeclaredFields())
                .map(Field::getName).collect(java.util.stream.Collectors.toSet());
        for (String field : expectedFields) {
            assertTrue(fields.contains(field), type.getSimpleName() + "." + field);
        }
    }

    private void assertScoped(Wrapper<?> wrapper, String tenantId, String clientId) {
        assertValues(wrapper, tenantId, clientId);
        String sql = normalize(wrapper.getSqlSegment());
        assertTrue(sql.contains("tenant_id"), sql);
        assertTrue(sql.contains("client_id"), sql);
    }

    private void assertValues(Wrapper<?> wrapper, Object... values) {
        wrapper.getSqlSegment();
        AbstractWrapper<?, ?, ?> actual = (AbstractWrapper<?, ?, ?>) wrapper;
        for (Object value : values) {
            assertTrue(actual.getParamNameValuePairs().containsValue(value),
                    value + " missing from " + actual.getParamNameValuePairs());
        }
    }

    private AgentTaskMemberDTO member() {
        AgentTaskMemberDTO member = new AgentTaskMemberDTO();
        member.setMemberRole("worker");
        member.setMemberStatus("working");
        member.setAssignmentSource("manual");
        return member;
    }

    private AgentTaskWorkItemDTO workItem() {
        AgentTaskWorkItemDTO item = new AgentTaskWorkItemDTO();
        item.setTitle("Implement collaboration DAO");
        item.setWorkType("implementation");
        item.setStatus("running");
        item.setPriority(10);
        item.setRequiredItem(true);
        item.setAttemptCount(1);
        item.setMaxAttempts(3);
        return item;
    }

    private AgentTaskRequestDTO request() {
        AgentTaskRequestDTO request = new AgentTaskRequestDTO();
        request.setRequesterAgentId("agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        request.setTargetType("agent");
        request.setTargetId("agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        request.setRequestType("review");
        request.setStatus("open");
        request.setPriority(10);
        request.setTitle("Review request");
        request.setDescription("Please review the collaboration DAO changes");
        return request;
    }

    private AgentTaskArtifactDTO artifact(int version) {
        AgentTaskArtifactDTO artifact = new AgentTaskArtifactDTO();
        artifact.setArtifactId("artifact-1");
        artifact.setTaskId("task-1");
        artifact.setProducerAgentId("agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        artifact.setArtifactType("document");
        artifact.setTitle("Design v" + version);
        artifact.setContent("content-v" + version);
        artifact.setArtifactVersion(version);
        artifact.setCreatedAt(1_000L + version);
        return artifact;
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
