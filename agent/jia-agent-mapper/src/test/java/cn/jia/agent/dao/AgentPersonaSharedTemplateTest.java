package cn.jia.agent.dao;

import cn.jia.agent.cache.AgentPersonaCatalogCache;
import cn.jia.agent.config.AgentPersonaTemplateAccess;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.mapper.AgentPersonaMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AgentPersonaSharedTemplateTest {
    @Test
    void allTemplateProjectionsPublishOnlyTheConfiguredTenantZeroSource() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:templates_"+UUID.randomUUID()+";MODE=MySQL");
             var s = c.createStatement()) {
            s.execute("CREATE TABLE agent_persona(id BIGINT,persona_code VARCHAR(50),rank_no INT,star_name VARCHAR(50),"
                    + "name VARCHAR(50),title VARCHAR(50),avatar VARCHAR(50),visual_config VARCHAR(50),abilities VARCHAR(50),"
                    + "personality VARCHAR(50),speaking_style VARCHAR(50),background VARCHAR(50),power INT,intelligence INT,"
                    + "leadership INT,active INT,system_agent INT,create_time BIGINT,update_time BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50))");
            s.execute("INSERT INTO agent_persona(persona_code,name,tenant_id,client_id,active) VALUES"
                    + "('published','published','0','public-source',1),('private','private','0','other-client',1),"
                    + "('foreign','foreign','2','public-source',1),('local','local','1','cyf-local',1),"
                    + "('null-public','null-public','0',NULL,1),('case','case','0','PUBLIC-SOURCE',1),"
                    + "('padded','padded','0','public-source ',1)");
            for (String method : List.of("selectFullProjection", "selectRuntimeProjection", "selectCatalogProjection")) {
                var annotation = AgentPersonaMapper.class.getMethod(method,String.class,String.class,String.class)
                        .getAnnotation(Select.class);
                String base = String.join(" ", annotation.value()).replace("#{tenantId}","'1'")
                        .replace("#{clientId}","'cyf-local'");
                assertEquals(List.of("local","null-public","published"), codes(s,base.replace("#{sharedClientId}","'public-source'")));
                assertEquals(List.of("local","null-public"), codes(s,base.replace("#{sharedClientId}","''")));
            }
        }
    }

    @Test
    void cacheAcceptsPublishedMetadataButNeverAnotherTenantOrUndeclaredClient() {
        var cache = new AgentPersonaCatalogCache();
        var row = persona("0","public-source");
        assertThrows(IllegalStateException.class,()->cache.get("1","cyf-local",()->List.of(row)));
        cache.setTemplateAccess(new AgentPersonaTemplateAccess("public-source"));
        assertEquals("wuyong",cache.get("1","cyf-local",()->List.of(row)).entries().getFirst().personaCode());
        assertThrows(IllegalStateException.class,()->cache.get("2","cyf-local",()->List.of(persona("2","public-source"))));
        assertThrows(IllegalStateException.class,()->cache.get("3","cyf-local",()->List.of(persona("0","other-client"))));
    }

    @Test
    void publicationIsDisabledByDefaultAndConfigurationIsExact() {
        assertFalse(new AgentPersonaTemplateAccess("").isPublishedTemplate("0","public-source"));
        var access = new AgentPersonaTemplateAccess("public-source");
        assertTrue(access.isPublishedTemplate("0","public-source"));
        assertFalse(access.isPublishedTemplate("1","public-source"));
        assertFalse(access.isPublishedTemplate("0","PUBLIC-SOURCE"));
        for(String value:List.of(" public-source","public-source ","a\n","x".repeat(51),"\ud800"))
            assertThrows(IllegalArgumentException.class,()->new AgentPersonaTemplateAccess(value));
    }

    private static List<String> codes(java.sql.Statement s,String query) throws Exception {
        List<String> codes = new ArrayList<>();
        try(var rows=s.executeQuery(query)) {while(rows.next()) codes.add(rows.getString("persona_code"));}
        return codes.stream().sorted().toList();
    }
    private static AgentPersonaEntity persona(String tenant,String client) {
        var row = new AgentPersonaEntity().setPersonaCode("wuyong").setName("吴用").setActive(true);
        row.setTenantId(tenant);row.setClientId(client);return row;
    }
}
