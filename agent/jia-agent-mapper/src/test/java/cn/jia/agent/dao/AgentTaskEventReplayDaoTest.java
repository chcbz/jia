package cn.jia.agent.dao;

import cn.jia.agent.dao.impl.AgentTaskEventDaoImpl;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.test.BaseMockTest;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentTaskEventReplayDaoTest extends BaseMockTest {
    @Mock
    AgentTaskEventMapper mapper;

    AgentTaskEventDaoImpl dao;

    @BeforeEach
    void setUp() throws Exception {
        dao = new AgentTaskEventDaoImpl();
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(dao, mapper);
    }

    @Test
    void boundedExclusivePageDelegatesLimitAndRejectsEveryUnboundedShape() {
        List<AgentTaskEventEntity> expected = List.of(new AgentTaskEventEntity());
        when(mapper.findExactByTaskScopeAfterVersion(
                "Tenant-A", "client-a", "caf\u00e9", 7L, 25)).thenReturn(expected);

        assertEquals(expected, dao.findAfterVersion(
                "Tenant-A", "client-a", "caf\u00e9", 7L, 25));
        verify(mapper).findExactByTaskScopeAfterVersion(
                "Tenant-A", "client-a", "caf\u00e9", 7L, 25);

        assertThrows(IllegalArgumentException.class,
                () -> dao.findAfterVersion("tenant", "client", "task", -1L, 1));
        assertThrows(IllegalArgumentException.class,
                () -> dao.findAfterVersion("tenant", "client", "task", 0L, 0));
        assertThrows(IllegalArgumentException.class,
                () -> dao.findAfterVersion("tenant", "client", "task", 0L,
                        AgentTaskEventDao.MAX_REPLAY_PAGE_SIZE + 1));
        assertFalse(java.util.Arrays.stream(AgentTaskEventDao.class.getMethods())
                .anyMatch(method -> method.getName().equals("findAfterVersion")
                        && method.getParameterCount() == 4));
        assertFalse(java.util.Arrays.stream(AgentTaskEventDao.class.getMethods())
                .anyMatch(method -> method.getName().equals("findByTaskScope")));
        assertFalse(java.util.Arrays.stream(AgentTaskEventMapper.class.getMethods())
                .anyMatch(method -> method.getName().equals("findExactByTaskScope")));
    }

    @Test
    void currentAndEarliestVersionUseExactScopeDelegates() {
        when(mapper.findExactCurrentEventVersion("tenant", "client", "task")).thenReturn(9L);
        when(mapper.findExactEarliestEventVersion("tenant", "client", "task")).thenReturn(3L);

        assertEquals(9L, dao.findCurrentVersion("tenant", "client", "task"));
        assertEquals(3L, dao.findEarliestVersion("tenant", "client", "task"));
        verify(mapper).findExactCurrentEventVersion("tenant", "client", "task");
        verify(mapper).findExactEarliestEventVersion("tenant", "client", "task");
    }

    @Test
    void invalidExactScopesFailBeforeMapper() {
        assertThrows(IllegalArgumentException.class,
                () -> dao.findCurrentVersion(" tenant", "client", "task"));
        assertThrows(IllegalArgumentException.class,
                () -> dao.findEarliestVersion("tenant", "client", "task "));
        assertThrows(IllegalArgumentException.class,
                () -> dao.findAfterVersion("tenant", "client\n", "task", 0L, 1));
        verifyNoInteractions(mapper);
    }

    @Test
    void replaySqlIsAscendingLimitedExclusiveAndByteExactForEveryScopeQuery()
            throws Exception {
        String pageSql = sql(AgentTaskEventMapper.class.getMethod(
                "findExactByTaskScopeAfterVersion",
                String.class, String.class, String.class, long.class, int.class));
        assertTrue(pageSql.contains("event_version > #{afterversion}"));
        assertTrue(pageSql.contains("order by event_version asc"));
        assertTrue(pageSql.contains("limit #{limit}"));
        assertByteExactScope(pageSql);

        String currentSql = sql(AgentTaskEventMapper.class.getMethod(
                "findExactCurrentEventVersion", String.class, String.class, String.class));
        assertTrue(currentSql.contains("current_event_version"));
        assertByteExactScope(currentSql);

        String earliestSql = sql(AgentTaskEventMapper.class.getMethod(
                "findExactEarliestEventVersion", String.class, String.class, String.class));
        assertTrue(earliestSql.contains("min(event_version)"));
        assertByteExactScope(earliestSql);
    }

    private static String sql(Method method) {
        Select select = method.getAnnotation(Select.class);
        return String.join(" ", select.value())
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static void assertByteExactScope(String sql) {
        for (String field : List.of("tenant_id", "client_id", "task_id")) {
            assertTrue(sql.contains("octet_length(" + field + ")"), sql);
        }
        assertTrue(sql.contains("cast(tenant_id as binary"), sql);
        assertTrue(sql.contains("cast(#{tenantid} as binary"), sql);
        assertTrue(sql.contains("cast(client_id as binary"), sql);
        assertTrue(sql.contains("cast(#{clientid} as binary"), sql);
        assertTrue(sql.contains("cast(task_id as binary")
                || sql.contains("cast(substring(task_id"), sql);
        assertTrue(sql.contains("cast(#{taskid} as binary")
                || sql.contains("cast(substring(#{taskid}"), sql);
    }
}
