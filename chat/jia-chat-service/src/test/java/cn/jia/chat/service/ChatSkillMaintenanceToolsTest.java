package cn.jia.chat.service;

import cn.jia.agent.api.SkillConversationToolAdapter;
import cn.jia.agent.skill.SkillMarketplaceException;
import cn.jia.agent.skill.SkillPackagePublicationService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatSkillMaintenanceToolsTest {
    private final SkillConversationToolAdapter service=mock(SkillConversationToolAdapter.class);
    private final Authentication principal=mock(Authentication.class);
    private final JsonMapper json=JsonMapper.builder().build();
    @Test void catalogDeclaresClosedSchemasAndUniqueToolNames() {
        var names=new HashSet<String>();
        for(var tool:cn.jia.agent.api.SkillConversationTools.catalog()) {
            assertTrue(names.add((String)tool.get("name")));
            var schema=(Map<?,?>)tool.get("inputSchema");
            assertEquals(false,schema.get("additionalProperties"));
            assertEquals(((Map<?,?>)schema.get("properties")).keySet(),new HashSet<>((List<?>)schema.get("required")));
        }
        assertEquals(6,names.size());
    }
    @Test void metadataUsesDecimalVersionWithoutInventingIdentityFields() {
        var command=new SkillPackagePublicationService.UpdateMetadata("p","标题","描述",9007199254740993L);
        var receipt=new SkillPackagePublicationService.MetadataReceipt("p","标题","描述",9007199254740994L,"UPDATED");
        when(service.updateMetadata(principal,"r","t",command)).thenReturn(receipt);
        assertSame(receipt,cn.jia.agent.api.SkillConversationTools.call(service,principal,"r","t","skill_update_metadata",json.readTree("""
            {"productId":"p","name":"标题","description":"描述","expectedProductVersion":"9007199254740993"}
            """)));
    }
    @Test void unknownToolsScopeInjectionAndNonDecimalVersionsCannotInvokeService() {
        for(String body:List.of("{\"productId\":\"p\",\"owner\":\"other\"}","{}","null"))
            assertThrows(SkillMarketplaceException.class,()->cn.jia.agent.api.SkillConversationTools.call(service,principal,"r","t","skill_read",json.readTree(body)));
        assertThrows(SkillMarketplaceException.class,()->cn.jia.agent.api.SkillConversationTools.call(service,principal,"r","t","shell",json.readTree("{}")));
        for(String version:List.of("1","\"-1\"","\"01\""))
            assertThrows(SkillMarketplaceException.class,()->cn.jia.agent.api.SkillConversationTools.call(service,principal,"r","t","skill_retire",json.readTree("{\"productId\":\"p\",\"expectedProductVersion\":"+version+"}")));
        assertThrows(SkillMarketplaceException.class,()->cn.jia.agent.api.SkillConversationTools.call(service,principal,"r","t","skill_list",json.readTree("{\"offset\":\"2147483648\",\"limit\":\"1\"}")));
        verifyNoInteractions(service);
    }
    @Test void publicationPassesActualFilesAndDoesNotTrustModelDigest() {
        cn.jia.agent.api.SkillConversationTools.call(service,principal,"r","t","skill_publish",json.readTree("""
            {"productId":"p","productVersionId":"v","name":"名称","description":"描述",
             "skillKey":"example","skillVersion":"1.0.0","priceWen":"0","approvedPermissionsManifest":"[]",
             "expectedProductVersion":"0","files":[{"path":"SKILL.md","content":"real content","encoding":"utf8"}]}
            """));
        verify(service).publish(eq(principal),eq("r"),eq("t"),argThat(c->c.packageSha256()==null&&c.manifestSha256()==null&&c.priceWen()==0),
                argThat(files->files.size()==1&&files.getFirst().content().equals("real content")));
    }
}
