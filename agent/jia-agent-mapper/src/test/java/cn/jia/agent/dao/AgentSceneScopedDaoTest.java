package cn.jia.agent.dao;

import cn.jia.agent.dao.impl.AgentSceneEventDaoImpl;
import cn.jia.agent.dao.impl.AgentScenePhaseReportDaoImpl;
import cn.jia.agent.dao.impl.AgentSceneStateDaoImpl;
import cn.jia.agent.entity.AgentSceneEventEntity;
import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentScenePhaseReportEntity;
import cn.jia.agent.entity.AgentSceneStateEntity;
import cn.jia.agent.entity.AgentSceneStateDTO;
import cn.jia.agent.mapper.AgentSceneEventMapper;
import cn.jia.agent.mapper.AgentScenePhaseReportMapper;
import cn.jia.agent.mapper.AgentSceneStateMapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

class AgentSceneScopedDaoTest {
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "x", "y", "path", "coordinates", "frame", "frameIndex",
            "token", "credential", "rawModelResponse");

    @BeforeAll
    static void initializeLambdaColumnMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "scene-test"), AgentSceneStateEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "scene-test"), AgentSceneEventEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "scene-test"), AgentScenePhaseReportEntity.class);
    }

    @Test
    void daoContractsAlwaysStartWithTenantClientAndSceneScope() {
        for (Class<?> daoType : List.of(
                AgentSceneStateDao.class, AgentSceneEventDao.class, AgentScenePhaseReportDao.class)) {
            for (Method method : daoType.getDeclaredMethods()) {
                assertTrue(method.getParameterCount() >= 3, method.toString());
                assertEquals(String.class, method.getParameterTypes()[0], method.toString());
                assertEquals(String.class, method.getParameterTypes()[1], method.toString());
                assertEquals(String.class, method.getParameterTypes()[2], method.toString());
            }
        }
        Method eventInsert = Arrays.stream(AgentSceneEventDao.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("insert"))
                .findFirst().orElseThrow();
        assertEquals(AgentSceneEventDTO.class, eventInsert.getParameterTypes()[3]);
    }

    @Test
    void versionAllocationUsesAtomicScopedCounterAndDocumentsTransactionBoundary() throws Exception {
        Method allocate = AgentSceneEventMapper.class.getDeclaredMethod(
                "allocateNextVersion", String.class, String.class, String.class, long.class);
        String sql = normalizeSql(allocate.getAnnotation(Insert.class).value());

        assertTrue(sql.contains("insert into agent_scene_version"), sql);
        assertTrue(sql.contains("(tenant_id, client_id, scene_id, current_version"), sql);
        assertTrue(sql.contains("(#{tenantid}, #{clientid}, #{sceneid}, last_insert_id(1)"), sql);
        assertTrue(sql.contains("last_insert_id(1)"), sql);
        assertTrue(sql.contains("on duplicate key update"), sql);
        assertTrue(sql.contains("current_version = last_insert_id(current_version + 1)"), sql);
        assertFalse(sql.contains("select max("), sql);
        assertFalse(sql.contains("for update"), sql);

        Method selectAllocated = AgentSceneEventMapper.class.getDeclaredMethod("selectLastAllocatedVersion");
        String selectSql = normalizeSql(selectAllocated.getAnnotation(Select.class).value());
        assertEquals("select last_insert_id()", selectSql);
        assertThrows(NoSuchMethodException.class, () -> AgentSceneEventMapper.class.getDeclaredMethod(
                "selectLatestVersionForUpdate", String.class, String.class, String.class));

        Transactional transaction = AgentSceneEventDaoImpl.class.getMethod(
                "nextSceneVersion", String.class, String.class, String.class)
                .getAnnotation(Transactional.class);
        assertEquals(Propagation.MANDATORY, transaction.propagation());
    }

    @Test
    void phaseAndSceneScopeLocksUseCurrentForUpdateReadsWithoutIncrementing() throws Exception {
        Method reportLock = AgentScenePhaseReportMapper.class.getDeclaredMethod(
                "selectScopedForUpdate", String.class, String.class, String.class, String.class);
        String reportSql = normalizeSql(reportLock.getAnnotation(Select.class).value());
        assertTrue(reportSql.contains("from agent_scene_phase_report"), reportSql);
        assertTrue(reportSql.contains("tenant_id = #{tenantid}"), reportSql);
        assertTrue(reportSql.contains("client_id = #{clientid}"), reportSql);
        assertTrue(reportSql.contains("scene_id = #{sceneid}"), reportSql);
        assertTrue(reportSql.contains("report_id = #{reportid}"), reportSql);
        assertTrue(reportSql.trim().endsWith("for update"), reportSql);

        Method sceneLock = AgentSceneEventMapper.class.getDeclaredMethod(
                "ensureAndLockVersion", String.class, String.class, String.class, long.class);
        String sceneSql = normalizeSql(sceneLock.getAnnotation(Insert.class).value());
        assertTrue(sceneSql.contains("insert into agent_scene_version"), sceneSql);
        assertTrue(sceneSql.contains("on duplicate key update"), sceneSql);
        assertTrue(sceneSql.contains("current_version = current_version"), sceneSql);
        assertFalse(sceneSql.contains("current_version + 1"), sceneSql);
        assertFalse(sceneSql.contains("last_insert_id"), sceneSql);

        Transactional reportTransaction = AgentScenePhaseReportDaoImpl.class.getMethod(
                "findByReportIdForUpdate", String.class, String.class, String.class, String.class)
                .getAnnotation(Transactional.class);
        assertEquals(Propagation.MANDATORY, reportTransaction.propagation());
        Transactional sceneTransaction = AgentSceneEventDaoImpl.class.getMethod(
                "lockSceneVersionScope", String.class, String.class, String.class)
                .getAnnotation(Transactional.class);
        assertEquals(Propagation.MANDATORY, sceneTransaction.propagation());
    }

    @Test
    void stateUpsertUsesAtomicMonotonicDuplicateKeyUpdate() throws Exception {
        Method method = AgentSceneStateMapper.class.getDeclaredMethod(
                "upsertMonotonic", String.class, String.class, String.class, AgentSceneStateEntity.class);
        String sql = normalizeSql(method.getAnnotation(Insert.class).value());

        assertTrue(sql.contains("insert into agent_scene_state"), sql);
        assertTrue(sql.contains("on duplicate key update"), sql);
        assertTrue(sql.contains("values(state_version) > state_version"), sql);
        assertTrue(sql.contains("state_version = if("), sql);
        assertFalse(sql.contains("select max("), sql);
        for (String column : List.of(
                "persona_code", "behavior", "origin_region_id", "target_region_id",
                "related_type", "related_id", "phase", "started_at",
                "expected_arrival_at", "expires_at", "update_time", "state_version")) {
            assertTrue(sql.contains(column + " = if(values(state_version) > state_version"), column + ": " + sql);
        }
    }

    @Test
    void stateDaoUsesAtomicMapperResultForStaleAndConcurrentFirstWrites() {
        AgentSceneStateMapper mapper = mock(AgentSceneStateMapper.class);
        AgentSceneStateDao dao = new AgentSceneStateDaoImpl(mapper);
        AgentSceneStateEntity state = new AgentSceneStateEntity();
        state.setAgentId("agent-songjiang");
        state.setStateVersion(17L);
        when(mapper.upsertMonotonic("tenant-a", "client-a", "juyiting-main", state)).thenReturn(0);

        assertEquals(0, dao.upsert("tenant-a", "client-a", "juyiting-main", state));
        verify(mapper, never()).selectOne(any());
        verify(mapper).upsertMonotonic("tenant-a", "client-a", "juyiting-main", state);
        assertEquals("tenant-a", state.getTenantId());
        assertEquals("client-a", state.getClientId());
        assertEquals("juyiting-main", state.getSceneId());
    }

    @Test
    void nextSceneVersionUsesSameConnectionCounterAllocationPath() {
        AgentSceneEventMapper mapper = mock(AgentSceneEventMapper.class);
        when(mapper.allocateNextVersion(
                org.mockito.ArgumentMatchers.eq("tenant-a"),
                org.mockito.ArgumentMatchers.eq("client-a"),
                org.mockito.ArgumentMatchers.eq("juyiting-main"), anyLong())).thenReturn(1);
        when(mapper.selectLastAllocatedVersion()).thenReturn(42L);

        long next = new AgentSceneEventDaoImpl(mapper)
                .nextSceneVersion("tenant-a", "client-a", "juyiting-main");

        assertEquals(42L, next);
        InOrder allocationOrder = inOrder(mapper);
        allocationOrder.verify(mapper).allocateNextVersion(
                org.mockito.ArgumentMatchers.eq("tenant-a"),
                org.mockito.ArgumentMatchers.eq("client-a"),
                org.mockito.ArgumentMatchers.eq("juyiting-main"), anyLong());
        allocationOrder.verify(mapper).selectLastAllocatedVersion();
        verify(mapper, never()).selectOne(any());
    }

    @Test
    void nextSceneVersionRejectsBlankScopeBeforeCounterAccess() {
        AgentSceneEventMapper mapper = mock(AgentSceneEventMapper.class);

        assertThrows(IllegalArgumentException.class, () -> new AgentSceneEventDaoImpl(mapper)
                .nextSceneVersion("", "client-a", "juyiting-main"));

        verify(mapper, never()).allocateNextVersion(any(), any(), any(), anyLong());
        verify(mapper, never()).selectLastAllocatedVersion();
    }

    @Test
    void currentSceneVersionQueryUsesTheDurableScopedCounter() throws Exception {
        Method method = AgentSceneEventMapper.class.getDeclaredMethod(
                "selectCurrentVersion", String.class, String.class, String.class);
        String sql = normalizeSql(method.getAnnotation(Select.class).value());
        assertTrue(sql.contains("select current_version from agent_scene_version"), sql);
        assertTrue(sql.contains("tenant_id = #{tenantid}"), sql);
        assertTrue(sql.contains("client_id = #{clientid}"), sql);
        assertTrue(sql.contains("scene_id = #{sceneid}"), sql);

        AgentSceneEventMapper mapper = mock(AgentSceneEventMapper.class);
        when(mapper.selectCurrentVersion("tenant-a", "client-a", "juyiting-main")).thenReturn(88L);

        assertEquals(88L, new AgentSceneEventDaoImpl(mapper)
                .findCurrentSceneVersion("tenant-a", "client-a", "juyiting-main"));
        verify(mapper).selectCurrentVersion("tenant-a", "client-a", "juyiting-main");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void retainedEventBoundariesAndBacklogAreScopedAndOrdered() {
        AgentSceneEventMapper mapper = mock(AgentSceneEventMapper.class);
        AgentSceneEventDao dao = new AgentSceneEventDaoImpl(mapper);

        dao.findEarliestSceneVersion("tenant-a", "client-a", "juyiting-main");
        dao.findLatestSceneVersion("tenant-a", "client-a", "juyiting-main");
        dao.findAfterVersion("tenant-a", "client-a", "juyiting-main", 39L, 50);

        ArgumentCaptor<Wrapper<AgentSceneEventEntity>> boundaryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, org.mockito.Mockito.times(2)).selectOne(boundaryCaptor.capture());
        List<Wrapper<AgentSceneEventEntity>> boundaries = boundaryCaptor.getAllValues();
        assertScoped(boundaries.get(0), "tenant-a", "client-a", "juyiting-main");
        assertScoped(boundaries.get(1), "tenant-a", "client-a", "juyiting-main");
        assertTrue(boundaries.get(0).getSqlSegment().toLowerCase(Locale.ROOT)
                .contains("order by scene_version asc"));
        assertTrue(boundaries.get(1).getSqlSegment().toLowerCase(Locale.ROOT)
                .contains("order by scene_version desc"));

        ArgumentCaptor<Wrapper<AgentSceneEventEntity>> backlogCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(backlogCaptor.capture());
        String backlogSql = backlogCaptor.getValue().getSqlSegment().toLowerCase(Locale.ROOT);
        assertScoped(backlogCaptor.getValue(), "tenant-a", "client-a", "juyiting-main");
        assertTrue(backlogSql.contains("scene_version"), backlogSql);
        assertTrue(backlogSql.contains(">"), backlogSql);
        assertTrue(backlogSql.contains("order by scene_version asc"), backlogSql);
        assertTrue(backlogSql.contains("limit 50"), backlogSql);
    }

    @Test
    void eventInsertCopiesOnlyAllowlistedTypedFieldsAndDropsForbiddenNestedKeys() {
        AgentSceneEventMapper mapper = mock(AgentSceneEventMapper.class);
        AgentSceneEventDao dao = new AgentSceneEventDaoImpl(mapper);
        UnsafeEventDTO unsafe = new UnsafeEventDTO();
        unsafe.setSceneVersion(7L);
        unsafe.setEventType("state-upserted");
        unsafe.setOccurredAt(1234L);
        UnsafeStateDTO unsafeState = new UnsafeStateDTO();
        unsafeState.setAgentId("agent-songjiang");
        unsafeState.setPersonaCode("songjiang");
        unsafeState.setBehavior("moving");
        unsafeState.setTargetRegionId("council-table");
        unsafeState.setPhase("moving");
        unsafeState.setStateVersion(3L);
        unsafeState.setStartedAt(1000L);
        unsafe.setState(unsafeState);

        dao.insert("tenant-a", "client-a", "juyiting-main", unsafe);

        ArgumentCaptor<AgentSceneEventEntity> captor = ArgumentCaptor.forClass(AgentSceneEventEntity.class);
        verify(mapper).insert(captor.capture());
        AgentSceneEventEntity persisted = captor.getValue();
        String json = persisted.getEventJson().toLowerCase(Locale.ROOT);
        assertTrue(json.contains("agent-songjiang"), json);
        assertFalse(json.contains("hiddenpayload"), json);
        assertFalse(json.contains("path"), json);
        assertFalse(json.contains("credential"), json);
        assertFalse(json.contains("rawmodelresponse"), json);
        assertEquals("tenant-a", persisted.getTenantId());
        assertEquals("client-a", persisted.getClientId());
        assertEquals("juyiting-main", persisted.getSceneId());
    }

    @Test
    void sceneEntitiesContainSemanticStateOnly() {
        for (Class<?> entityType : List.of(
                AgentSceneStateEntity.class, AgentSceneEventEntity.class, AgentScenePhaseReportEntity.class)) {
            Set<String> fields = Arrays.stream(entityType.getDeclaredFields())
                    .map(Field::getName)
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(java.util.Collections.disjoint(fields, FORBIDDEN_FIELDS), entityType.getName());
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void stateEventAndReportQueriesBindEveryScopeDimension() {
        AgentSceneStateMapper stateMapper = mock(AgentSceneStateMapper.class);
        new AgentSceneStateDaoImpl(stateMapper)
                .findByAgent("tenant-a", "client-a", "juyiting-main", "agent-songjiang");
        ArgumentCaptor<Wrapper<AgentSceneStateEntity>> stateCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(stateMapper).selectOne(stateCaptor.capture());
        assertScoped(stateCaptor.getValue(), "tenant-a", "client-a", "juyiting-main");

        AgentSceneEventMapper eventMapper = mock(AgentSceneEventMapper.class);
        new AgentSceneEventDaoImpl(eventMapper)
                .findAfterVersion("tenant-b", "client-b", "juyiting-main", 12L, 50);
        ArgumentCaptor<Wrapper<AgentSceneEventEntity>> eventCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(eventMapper).selectList(eventCaptor.capture());
        assertScoped(eventCaptor.getValue(), "tenant-b", "client-b", "juyiting-main");

        AgentScenePhaseReportMapper reportMapper = mock(AgentScenePhaseReportMapper.class);
        new AgentScenePhaseReportDaoImpl(reportMapper)
                .findByReportId("tenant-c", "client-c", "juyiting-main", "report-1");
        ArgumentCaptor<Wrapper<AgentScenePhaseReportEntity>> reportCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(reportMapper).selectOne(reportCaptor.capture());
        assertScoped(reportCaptor.getValue(), "tenant-c", "client-c", "juyiting-main");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void phaseLockReadDelegatesToTheScopedCurrentMapperQuery() {
        AgentScenePhaseReportMapper mapper = mock(AgentScenePhaseReportMapper.class);
        AgentScenePhaseReportDao dao = new AgentScenePhaseReportDaoImpl(mapper);
        AgentScenePhaseReportEntity stored = new AgentScenePhaseReportEntity();
        when(mapper.selectScopedForUpdate("tenant-a", "client-a", "juyiting-main", "report-1"))
                .thenReturn(stored);

        AgentScenePhaseReportEntity result = dao.findByReportIdForUpdate(
                "tenant-a", "client-a", "juyiting-main", "report-1");

        assertEquals(stored, result);
        verify(mapper).selectScopedForUpdate("tenant-a", "client-a", "juyiting-main", "report-1");
    }

    @Test
    void sceneScopeLockEnsuresTheCounterRowWithoutAllocatingAVersion() {
        AgentSceneEventMapper mapper = mock(AgentSceneEventMapper.class);
        AgentSceneEventDao dao = new AgentSceneEventDaoImpl(mapper);

        dao.lockSceneVersionScope("tenant-a", "client-a", "juyiting-main");

        verify(mapper).ensureAndLockVersion(
                org.mockito.ArgumentMatchers.eq("tenant-a"),
                org.mockito.ArgumentMatchers.eq("client-a"),
                org.mockito.ArgumentMatchers.eq("juyiting-main"), anyLong());
        verify(mapper, never()).allocateNextVersion(any(), any(), any(), anyLong());
        verify(mapper, never()).selectLastAllocatedVersion();
    }

    @Test
    void blankScopeIsRejectedBeforeMapperAccess() {
        AgentSceneStateMapper mapper = mock(AgentSceneStateMapper.class);
        AgentSceneStateDao dao = new AgentSceneStateDaoImpl(mapper);

        assertThrows(IllegalArgumentException.class,
                () -> dao.findByAgent("", "client-a", "juyiting-main", "agent-songjiang"));
    }

    private void assertScoped(Wrapper<?> wrapper, String tenantId, String clientId, String sceneId) {
        String sql = wrapper.getSqlSegment().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("tenant_id"), sql);
        assertTrue(sql.contains("client_id"), sql);
        assertTrue(sql.contains("scene_id"), sql);
        assertFalse(sql.contains(" or "), sql);

        AbstractWrapper<?, ?, ?> abstractWrapper = (AbstractWrapper<?, ?, ?>) wrapper;
        assertTrue(abstractWrapper.getParamNameValuePairs().containsValue(tenantId));
        assertTrue(abstractWrapper.getParamNameValuePairs().containsValue(clientId));
        assertTrue(abstractWrapper.getParamNameValuePairs().containsValue(sceneId));
    }

    private String normalizeSql(String[] fragments) {
        return String.join(" ", fragments).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static class UnsafeEventDTO extends AgentSceneEventDTO {
        public Map<String, Object> getHiddenPayload() {
            return Map.of("nested", Map.of(
                    "path", List.of(1, 2, 3),
                    "credential", "secret",
                    "rawModelResponse", "private"));
        }
    }

    private static class UnsafeStateDTO extends AgentSceneStateDTO {
        public Map<String, Object> getHiddenPayload() {
            return Map.of("path", List.of("north", "east"));
        }
    }
}
