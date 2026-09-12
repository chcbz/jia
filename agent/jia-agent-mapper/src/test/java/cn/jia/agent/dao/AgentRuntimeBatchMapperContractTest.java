package cn.jia.agent.dao;

import cn.jia.agent.mapper.AgentPersonaBindingMapper;
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
    void personaCatalogMetadataAndUserOverlayAreTwoBoundedByteExactQueries() throws Exception {
        String catalog = selectSql(AgentPersonaMapper.class, "selectCatalogProjection");
        assertTrue(catalog.startsWith("select persona_code, rank_no, star_name, name, title, avatar,"));
        assertTrue(catalog.contains("where active = 1"));
        assertTrue(catalog.contains("tenant_id = '0'"));
        assertExactParameter(catalog, "tenant_id", "tenantid");
        assertExactParameter(catalog, "client_id", "clientid");
        assertFalse(catalog.contains("select *"));
        assertFalse(catalog.contains("binding_id"));
        assertFalse(catalog.contains("owner_jiacn"));

        String overlay = selectSql(AgentPersonaBindingMapper.class, "findCatalogOverlay");
        assertTrue(overlay.contains("from agent_persona_binding b"));
        assertTrue(overlay.contains("inner join agent_persona p"));
        assertTrue(overlay.contains("cast(p.persona_code as binary) = cast(b.persona_code as binary)"));
        assertTrue(overlay.contains("p.active = 1"));
        assertExactParameter(overlay, "p.tenant_id", "tenantid");
        assertExactParameter(overlay, "p.client_id", "clientid");
        assertTrue(overlay.contains("left join agent_identity_registry i on i.binding_id = b.id"));
        assertTrue(overlay.contains("left join agent_runtime r"));
        assertTrue(overlay.contains("exists ( select 1 from agent_identity_alias a"));
        assertExactParameter(overlay, "b.tenant_id", "tenantid");
        assertExactParameter(overlay, "b.client_id", "clientid");
        assertExactParameter(overlay, "b.owner_jiacn", "ownerjiacn");
        assertExactParameter(overlay, "a.tenant_id", "tenantid");
        assertExactParameter(overlay, "a.client_id", "clientid");
        assertExactParameter(overlay, "a.owner_jiacn", "ownerjiacn");
        assertTrue(overlay.contains("cast(b.agent_id as binary) = cast(i.canonical_agent_id as binary)"));
        assertTrue(overlay.contains("cast(r.agent_id as binary) = cast(i.canonical_agent_id as binary)"));
        assertFalse(overlay.contains("select *"));
        assertFalse(overlay.contains("${"));
        assertFalse(overlay.contains("/agent/active"));
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
        assertFalse(sql.contains("for update"));
        assertFalse(sql.contains("token_hash"));
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
