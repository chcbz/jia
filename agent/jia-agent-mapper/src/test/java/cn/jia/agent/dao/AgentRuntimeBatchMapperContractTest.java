package cn.jia.agent.dao;

import cn.jia.agent.mapper.AgentPersonaMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeBatchMapperContractTest {
    @Test
    void personaRuntimeProjectionIsOneBoundedColumnOnlyQuery() throws Exception {
        String sql = selectSql(AgentPersonaMapper.class, "selectRuntimeProjection");

        assertTrue(sql.startsWith("select persona_code, rank_no, star_name, name, title, avatar, visual_config,"));
        assertTrue(sql.contains("abilities, power, intelligence, leadership, system_agent from agent_persona"));
        assertFalse(sql.contains("select *"));
        assertFalse(sql.contains("where"));
    }

    @Test
    void taskStatsUseOneForeachAggregateWithByteExactTripleGrouping() throws Exception {
        String sql = selectSql(AgentTaskMetaMapper.class, "selectStatsByAgentScopes");

        assertTrue(sql.contains("count(*) as taskcount"));
        assertTrue(sql.contains("sum(case when cast(task.reward_status as binary) = cast('completed' as binary)"));
        assertTrue(sql.contains("sum(case when cast(task.reward_status as binary) = cast('failed' as binary)"));
        assertTrue(sql.contains("<foreach collection=\"scopes\" item=\"scope\" open=\"(\" separator=\" or \" close=\")\">"));
        assertExactParameter(sql, "task.tenant_id", "scope.tenantid");
        assertExactParameter(sql, "task.client_id", "scope.clientid");
        assertExactParameter(sql, "task.assigned_agent_id", "scope.agentid");
        assertTrue(sql.contains("group by task.tenant_id, cast(task.tenant_id as binary), octet_length(task.tenant_id), task.client_id, cast(task.client_id as binary), octet_length(task.client_id), task.assigned_agent_id, cast(task.assigned_agent_id as binary), octet_length(task.assigned_agent_id)"));
        assertFalse(sql.contains("${"));
        assertFalse(sql.contains("/agent/active"));
    }

    private static String selectSql(Class<?> mapperType, String methodName) throws Exception {
        Method method = java.util.Arrays.stream(mapperType.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(methodName));
        Select select = method.getAnnotation(Select.class);
        if (select == null) throw new IllegalStateException(methodName + " is not @Select");
        return String.join(" ", select.value()).replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }

    private static void assertExactParameter(String sql, String column, String parameter) {
        assertTrue(sql.contains("cast(" + column + " as binary) = cast(#{" + parameter + "} as binary)"), column);
        assertTrue(sql.contains("octet_length(" + column + ") = octet_length(#{" + parameter + "})"), column);
    }
}
