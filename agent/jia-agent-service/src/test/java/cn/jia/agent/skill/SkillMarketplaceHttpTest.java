package cn.jia.agent.skill;
import cn.jia.agent.hosting.HostingRentHttp;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SkillMarketplaceHttpTest {
    private static byte[] raw(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    @Test void decimalStringsPreserveValuesBeyondJavascriptIntegerRange() {
        var body=SkillMarketplaceHttp.body(raw("""
                {"productVersionId":"spv_1","targetAgentId":"agt_1","expectedAgentVersion":"9007199254740993"}
                """),false);
        assertEquals("9007199254740993",body.get("expectedAgentVersion"));
    }
    @Test void rejectsNumericDuplicateUnknownAndPaddedFields() {
        for(String s:List.of(
                "{\"productVersionId\":\"p\",\"targetAgentId\":\"a\",\"expectedAgentVersion\":1}",
                "{\"productVersionId\":\"p\",\"targetAgentId\":\"a\",\"expectedAgentVersion\":\"01\"}",
                "{\"productVersionId\":\"p\",\"targetAgentId\":\"a\",\"expectedAgentVersion\":\"1\",\"expectedAgentVersion\":\"1\"}",
                "{\"productVersionId\":\"p\",\"targetAgentId\":\"a \",\"expectedAgentVersion\":\"1\"}",
                "{\"productVersionId\":\"p\",\"targetAgentId\":\"a\",\"expectedAgentVersion\":\"1\",\"price\":\"0\"}"))
            assertThrows(SkillMarketplaceException.class,()->SkillMarketplaceHttp.body(raw(s),false));
    }
    @Test void explicitEmptyPermissionsAreRequiredEvenForFreeOrder() {
        String json="{\"quoteId\":\"q\",\"productVersionId\":\"p\",\"targetAgentId\":\"a\",\"expectedAgentVersion\":\"1\",\"expectedPriceMicro\":\"0\",\"approvedPermissions\":[]}";
        assertEquals("[]",SkillMarketplaceHttp.body(raw(json),true).get("approvedPermissions"));
        assertThrows(SkillMarketplaceException.class,()->SkillMarketplaceHttp.body(raw(json.replace("[]","null")),true));
        assertThrows(SkillMarketplaceException.class,()->SkillMarketplaceHttp.body(raw(json.replace("[]","[\"a\",\"a\"]")),true));
    }
    @Test void actorIsNeverReplacedWithLegacyTenantOwner() {
        var a=new HostingRentHttp.Actor("login-sub","legacy-owner","client");
        assertEquals("login-sub",a.principal().id()); assertEquals("legacy-owner",a.scope().tenantId());
    }
}
