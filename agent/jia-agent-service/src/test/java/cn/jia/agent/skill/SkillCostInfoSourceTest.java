package cn.jia.agent.skill;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
class SkillCostInfoSourceTest {
    @TempDir Path root;
    static final JsonMapper JSON=new JsonMapper();
    static final String MANIFEST="{\"skillKey\":\"image-edit\",\"skillVersion\":\"1.0.0\",\"requirements\":{\"providerAlias\":\"image\"},\"costInfo\":{\"mode\":\"UNKNOWN\"}}";
    String config(String overrides){return "{\"schemaVersion\":1,\"subjects\":{\"agent\":{\"providerAliases\":{\"image\":\"bound\"}}},\"costInfoOverrides\":"+overrides+"}";}
    String fee(String amount){return "{\"mode\":\"FIXED\",\"amount\":\""+amount+"\",\"informationRevision\":\"2\",\"source\":\"operator\"}";}
    @Test void exactSubjectBindingPrecedesVersion(){var source=new SkillCostInfoSource(root.resolve("none").toString());var config=JSON.readTree(config("{\"image-edit@1.0.0\":"+fee("1")+",\"image-edit@1.0.0#bound\":"+fee("2")+"}"));assertEquals("2",JSON.readTree(source.decorate(config,"agent",MANIFEST)).path("costInfo").path("amount").asText());assertEquals("1",JSON.readTree(source.decorate(config,"other",MANIFEST)).path("costInfo").path("amount").asText());}
    @Test void absentOverrideRetainsDefault(){var source=new SkillCostInfoSource(root.resolve("none").toString());assertEquals(MANIFEST,source.decorate(JSON.readTree(config("{}")),"agent",MANIFEST));}
    @Test void invalidFeeIsUnknownNotFailure(){var source=new SkillCostInfoSource(root.resolve("none").toString());assertEquals("UNKNOWN",JSON.readTree(source.decorate(JSON.readTree(config("{\"image-edit@1.0.0\":"+fee("-1")+"}")),"agent",MANIFEST)).path("costInfo").path("mode").asText());}
    @Test void rereadDoesNotCachePriceOrRequireBindingRevision() throws Exception {Path f=root.resolve("bindings.json");var source=new SkillCostInfoSource(f.toString());Files.writeString(f,config("{\"image-edit@1.0.0\":"+fee("1")+"}"));String first=source.decorate(source.read(),"agent",MANIFEST);Files.writeString(f,config("{\"image-edit@1.0.0\":"+fee("9")+"}"));String next=source.decorate(source.read(),"agent",MANIFEST);assertEquals("1",JSON.readTree(first).path("costInfo").path("amount").asText());assertEquals("9",JSON.readTree(next).path("costInfo").path("amount").asText());assertEquals(JSON.readTree(first).path("requirements"),JSON.readTree(next).path("requirements"));}
    @Test void missingMalformedDuplicateAndSymlinkAreInformationalOnly() throws Exception {Path f=root.resolve("bindings.json");var source=new SkillCostInfoSource(f.toString());assertTrue(source.read().isEmpty());Files.writeString(f,"{\"schemaVersion\":1,\"schemaVersion\":2}");assertTrue(source.read().isEmpty());Files.delete(f);Path target=root.resolve("target");Files.writeString(target,config("{}"));Files.createSymbolicLink(f,target);assertTrue(source.read().isEmpty());}
}
