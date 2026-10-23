package cn.jia.agent.skill;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Consumes the pinned, unedited multi-component corpus, not separately invented DTO samples. */
class SkillManagementCodecTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static byte[] resource(String name) throws Exception {
        try(var in=Objects.requireNonNull(SkillManagementCodecTest.class.getResourceAsStream("/"+name))){return in.readAllBytes();}
    }
    static JsonNode examples() throws Exception{return JSON.readTree(resource("management-contract-v1.examples.json"));}
    @Test void originalFixtureBytesMatchSourceLock() throws Exception {
        var provenance=JSON.readTree(resource("source-sha256.json"));
        for(var it=provenance.get("files").fields();it.hasNext();) {
            var e=it.next();assertEquals(e.getValue().asText(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resource(e.getKey()))),e.getKey());
        }
        try(var in=SkillManagementCodec.class.getResourceAsStream("management-contract-v1.schema.json")) {
            assertArrayEquals(resource("management-contract-v1.schema.json"),Objects.requireNonNull(in).readAllBytes());
        }
    }
    @Test void allFrozenPositiveAndNegativeDtos() throws Exception {
        for(var c:examples().get("cases")) {
            String name=c.get("name").asText(),definition=c.get("definition").asText();
            byte[] raw=JSON.writeValueAsBytes(c.get("value"));
            if(c.get("valid").asBoolean())assertDoesNotThrow(()->SkillManagementCodec.read(raw,definition),name);
            else assertThrows(SkillMarketplaceException.class,()->SkillManagementCodec.read(raw,definition),name);
        }
    }
    @Test void definitionCachesNeverReuseAnotherDefinitionRoot() {
        // Interleave incompatible roots repeatedly, after other tests may have warmed the registry.
        for(int i=0;i<3;i++) {
            assertDoesNotThrow(()->SkillManagementCodec.validate("Id","a-resource"));
            assertDoesNotThrow(()->SkillManagementCodec.validate("Money",SkillManagementCodec.map("amountMinor","0","currency","SILVER")));
            assertThrows(SkillMarketplaceException.class,()->SkillManagementCodec.validate("Money","a-resource"));
            assertThrows(SkillMarketplaceException.class,()->SkillManagementCodec.validate("Id",SkillManagementCodec.map("amountMinor","0","currency","SILVER")));
        }
    }
    @Test void originalIllegalTokensCannotBeNormalizedAway() throws Exception {
        for(var c:examples().get("rawParserCases")) {
            var e=assertThrows(SkillMarketplaceException.class,()->SkillManagementCodec.read(c.get("raw").asText().getBytes(StandardCharsets.UTF_8),"Result"),c.get("name").asText());
            assertEquals(c.get("expected").asText(),e.code());
        }
        for(byte[] invalid:List.of(new byte[]{(byte)0xc3,0x28},"{\"x\":\"\\ud800\"}".getBytes(StandardCharsets.UTF_8)))
            assertThrows(SkillMarketplaceException.class,()->SkillManagementCodec.read(invalid,"Result"));
    }
    @Test void frozenCanonicalAndDomainDigestVectors() throws Exception {
        for(var c:examples().get("digestVectors")) {
            Object value=JSON.convertValue(c.get("value"),Object.class);
            assertEquals(c.get("canonicalUtf8").asText(),SkillManagementCodec.json(value));
            assertEquals(c.get("sha256").asText(),SkillManagementCodec.hash(c.get("domain").asText(),value));
        }
    }
    @Test void decodedResourceIdsAreNeverDecodedTwice() {
        for(String id:List.of("a/b","a\\b","a?b","a#b","a\nb"))assertThrows(SkillMarketplaceException.class,()->SkillManagementCodec.path(id));
        assertEquals("a%2fb",SkillManagementCodec.path("a%2fb"));
    }
}
