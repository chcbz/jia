package cn.jia.agent.dao;

import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.common.dao.BaseDaoImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentTaskMetaStateDaoTest {
    @BeforeAll
    static void initializeLambdaColumnMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "task-meta-state-test");
        TableInfoHelper.initTableInfo(assistant, AgentTaskMetaEntity.class);
    }

    @Test
    void scopedTaskLookupDelegatesToByteExactMapperMethods() throws Exception {
        AgentTaskMetaMapper mapper = mock(AgentTaskMetaMapper.class);
        AgentTaskMetaDao dao = dao(mapper);

        dao.findByTaskId("tenant-a", "client-a", "task-1");
        dao.findByTaskIdForUpdate("tenant-a", "client-a", "task-1");

        verify(mapper).findExactByTaskScope("tenant-a", "client-a", "task-1");
        verify(mapper).findExactByTaskScopeForUpdate("tenant-a", "client-a", "task-1");
        verify(mapper, never()).selectOne(any());
    }

    @Test
    void taskStatusMapperUsesScopedTaskVersionCasAndAtomicIncrement() throws Exception {
        Method method = AgentTaskMetaMapper.class.getDeclaredMethod(
                "updateStatusByVersion", String.class, String.class, String.class, long.class,
                String.class, Long.class, Long.class, String.class, long.class);
        String sql = normalize(String.join(" ", method.getAnnotation(Update.class).value()));

        assertTrue(sql.contains("where tenant_id = #{tenantid}"), sql);
        assertTrue(sql.contains("and client_id = #{clientid}"), sql);
        assertTrue(sql.contains("and task_id = #{taskid}"), sql);
        assertTrue(sql.contains("and task_version = #{expectedversion}"), sql);
        assertTrue(sql.contains("task_version = task_version + 1"), sql);
    }

    @Test
    void taskStatusDaoDelegatesScopeAndExpectedVersionWithoutReadBeforeWrite() throws Exception {
        AgentTaskMetaMapper mapper = mock(AgentTaskMetaMapper.class);
        AgentTaskMetaDao dao = dao(mapper);

        dao.updateStatusByVersion(
                "tenant-a", "client-a", "task-1", 7L,
                "running", 1_000L, null, null);

        verify(mapper).updateStatusByVersion(
                eq("tenant-a"), eq("client-a"), eq("task-1"), eq(7L), eq("running"),
                eq(1_000L), eq(null), eq(null), anyLong());
        verify(mapper, never()).selectOne(any());
    }

    @Test
    void blankScopeIsRejectedBeforeTaskMetaMapperAccess() throws Exception {
        AgentTaskMetaMapper mapper = mock(AgentTaskMetaMapper.class);
        AgentTaskMetaDao dao = dao(mapper);

        assertThrows(IllegalArgumentException.class,
                () -> dao.findByTaskId("", "client-a", "task-1"));

        verify(mapper, never()).selectOne(any());
        verify(mapper, never()).updateStatusByVersion(
                any(), any(), any(), anyLong(), any(), any(), any(), any(), anyLong());
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
