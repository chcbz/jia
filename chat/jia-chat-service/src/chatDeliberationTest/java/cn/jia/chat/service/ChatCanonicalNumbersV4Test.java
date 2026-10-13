package cn.jia.chat.service;
import org.junit.jupiter.api.Test;
import cn.jia.core.util.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ChatCanonicalNumbersV4Test {
    @Test void sharedJavaScriptNumberVectors() throws Exception {
        try(var in=getClass().getResourceAsStream("/contracts/canonical-numbers-v4.json")) {
            var fixture=JsonUtil.getMapper().readValue(new String(in.readAllBytes(),StandardCharsets.UTF_8),Map.class);
            for(Object item:(List<?>)fixture.get("cases")) {var row=(Map<?,?>)item;String actual=CanonicalContextJson.write(row.get("value"));
                assertEquals(row.get("canonical"),actual,row.toString());assertEquals(row.get("sha256"),ChatSkillSourceAccessService.sha(actual.getBytes(StandardCharsets.UTF_8)));}
        }
        assertEquals("0",CanonicalContextJson.write(-0.0));
        for(var invalid:List.of(Double.NaN,Double.POSITIVE_INFINITY,9007199254740992d))assertThrows(IllegalArgumentException.class,()->CanonicalContextJson.write(invalid));
    }
}
