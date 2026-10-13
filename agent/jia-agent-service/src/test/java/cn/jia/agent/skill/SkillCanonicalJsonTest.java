package cn.jia.agent.skill;
import cn.jia.agent.service.SkillCanonicalJson;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SkillCanonicalJsonTest {
    @Test void portableScalarsAndLexicalNumericKeys() {
        assertEquals("1",SkillCanonicalJson.write(1.0));assertEquals("0",SkillCanonicalJson.write(-0.0));assertEquals("5e-324",SkillCanonicalJson.write(Double.MIN_VALUE));
        assertEquals("1e-7",SkillCanonicalJson.write(1e-7));assertEquals("0.000001",SkillCanonicalJson.write(1e-6));
        assertEquals("{\"10\":true,\"2\":false,\"n\":1.25}",SkillCanonicalJson.write(Map.of("2",false,"10",true,"n",1.25)));
        assertEquals("\"\\ud800\"",SkillCanonicalJson.write("\ud800"));assertEquals("\"鸟🪶\\n\"",SkillCanonicalJson.write("鸟🪶\n"));
        for(var n:List.of(Double.NaN,Double.POSITIVE_INFINITY,9007199254740992L))assertThrows(IllegalArgumentException.class,()->SkillCanonicalJson.write(n));
    }
}
