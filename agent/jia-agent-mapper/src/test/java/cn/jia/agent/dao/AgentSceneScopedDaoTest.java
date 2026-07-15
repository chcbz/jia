package cn.jia.agent.dao;

import cn.jia.agent.dao.impl.AgentSceneEventDaoImpl;
import cn.jia.agent.dao.impl.AgentScenePhaseReportDaoImpl;
import cn.jia.agent.dao.impl.AgentSceneStateDaoImpl;
import cn.jia.agent.entity.AgentSceneEventEntity;
import cn.jia.agent.entity.AgentScenePhaseReportEntity;
import cn.jia.agent.entity.AgentSceneStateEntity;
import cn.jia.agent.mapper.AgentSceneEventMapper;
import cn.jia.agent.mapper.AgentScenePhaseReportMapper;
import cn.jia.agent.mapper.AgentSceneStateMapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
}
