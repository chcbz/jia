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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

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
    void versionAllocationUsesScopedLatestRowLockAndDocumentsTransactionBoundary() throws Exception {
        Method method = AgentSceneEventMapper.class.getDeclaredMethod(
                "selectLatestVersionForUpdate", String.class, String.class, String.class);
        String sql = normalizeSql(method.getAnnotation(Select.class).value());

        assertTrue(sql.contains("tenant_id = #{tenantid}"), sql);
        assertTrue(sql.contains("client_id = #{clientid}"), sql);
        assertTrue(sql.contains("scene_id = #{sceneid}"), sql);
        assertTrue(sql.contains("order by scene_version desc"), sql);
        assertTrue(sql.contains("limit 1 for update"), sql);
        Transactional transaction = AgentSceneEventDaoImpl.class.getMethod(
                "nextSceneVersion", String.class, String.class, String.class)
                .getAnnotation(Transactional.class);
        assertEquals(Propagation.MANDATORY, transaction.propagation());
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
    void nextSceneVersionUsesScopedLockingMapperPath() {
        AgentSceneEventMapper mapper = mock(AgentSceneEventMapper.class);
        when(mapper.selectLatestVersionForUpdate("tenant-a", "client-a", "juyiting-main")).thenReturn(41L);

        long next = new AgentSceneEventDaoImpl(mapper)
                .nextSceneVersion("tenant-a", "client-a", "juyiting-main");

        assertEquals(42L, next);
        verify(mapper).selectLatestVersionForUpdate("tenant-a", "client-a", "juyiting-main");
        verify(mapper, never()).selectOne(any());
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
