package cn.jia.agent.dao;

import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.agent.mapper.AgentPersonaMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeBatchMapperContractTest {
    @Test
    void runtimeMetadataCannotUseAnUnscopedWholeTableProjection() {
        assertFalse(java.util.Arrays.stream(AgentPersonaMapper.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("selectRuntimeProjection")));
    }

    @Test
    void personaCatalogMetadataAndUserOverlayAreTwoBoundedByteExactQueries() throws Exception {
        String catalog = selectSql(AgentPersonaMapper.class, "selectCatalogProjection");
        assertTenantLineIgnored(AgentPersonaMapper.class, "selectCatalogProjection");
        assertTrue(catalog.startsWith("select persona_code, rank_no, star_name, name, title, avatar,"));
        assertTrue(catalog.contains("where active = 1"));
        assertFalse(catalog.contains("tenant_id = '0'"));
        assertTrue(catalog.contains("tenant_id = #{tenantid}"));
        assertExactParameter(catalog, "tenant_id", "tenantid");
        assertExactParameter(catalog, "client_id", "clientid");
        assertFalse(catalog.contains("select *"));
        assertFalse(catalog.contains("binding_id"));
        assertFalse(catalog.contains("owner_jiacn"));

        String scopedPersona = selectSql(AgentPersonaMapper.class, "selectCatalogPersona");
        assertTenantLineIgnored(AgentPersonaMapper.class, "selectCatalogPersona");
        assertTrue(scopedPersona.contains("where active = 1"));
        assertExactParameter(scopedPersona, "persona_code", "personacode");
        assertExactParameter(scopedPersona, "tenant_id", "tenantid");
        assertExactParameter(scopedPersona, "client_id", "clientid");
        assertTrue(scopedPersona.contains("client_id is null"));
        assertTrue(scopedPersona.contains("limit 1"));

        String overlay = selectSql(AgentPersonaBindingMapper.class, "findCatalogOverlay");
        assertTenantLineIgnored(AgentPersonaBindingMapper.class, "findCatalogOverlay");
        assertTrue(overlay.contains("from agent_persona_binding b"));
        assertTrue(overlay.contains("left join agent_persona p"));
        assertTrue(overlay.contains("p.active = 1"));
        assertExactParameter(overlay, "p.tenant_id", "tenantid");
        assertExactParameter(overlay, "p.client_id", "clientid");
        assertTrue(overlay.contains("#{tenantid} <> '0' or p.persona_code is not null"));
        assertTrue(overlay.contains("left join agent_identity_registry i on i.binding_id = b.id"));
        assertTrue(overlay.contains("i.tenant_id = b.tenant_id"));
        assertTrue(overlay.contains("cast(i.tenant_id as binary) = cast(b.tenant_id as binary)"));
        assertTrue(overlay.contains("octet_length(i.tenant_id) = octet_length(b.tenant_id)"));
        assertTrue(overlay.contains("i.client_id = b.client_id"));
        assertTrue(overlay.contains("cast(i.client_id as binary) = cast(b.client_id as binary)"));
        assertTrue(overlay.contains("octet_length(i.client_id) = octet_length(b.client_id)"));
        assertTrue(overlay.contains("i.owner_jiacn = b.owner_jiacn"));
        assertTrue(overlay.contains("cast(i.owner_jiacn as binary) = cast(b.owner_jiacn as binary)"));
        assertTrue(overlay.contains("octet_length(i.owner_jiacn) = octet_length(b.owner_jiacn)"));
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
        assertTrue(overlay.contains("r.tenant_id = b.tenant_id"));
        assertTrue(overlay.contains("cast(r.tenant_id as binary) = cast(b.tenant_id as binary)"));
        assertTrue(overlay.contains("r.client_id = b.client_id"));
        assertTrue(overlay.contains("cast(r.client_id as binary) = cast(b.client_id as binary)"));
        assertTrue(overlay.contains("r.owner_jiacn = b.owner_jiacn"));
        assertTrue(overlay.contains("cast(r.owner_jiacn as binary) = cast(b.owner_jiacn as binary)"));
        assertTrue(overlay.contains("r.binding_id = b.id"));
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

    private static void assertTenantLineIgnored(Class<?> mapperType, String methodName) throws Exception {
        Method method = java.util.Arrays.stream(mapperType.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(methodName));
        InterceptorIgnore ignored = method.getAnnotation(InterceptorIgnore.class);
        assertTrue(ignored != null && "true".equals(ignored.tenantLine()), methodName);
    }

    private static void assertExactParameter(String sql, String column, String parameter) {
        assertTrue(sql.contains("cast(" + column + " as binary) = cast(#{" + parameter + "} as binary)"), column);
        assertTrue(sql.contains("octet_length(" + column + ") = octet_length(#{" + parameter + "})"), column);
    }
}
