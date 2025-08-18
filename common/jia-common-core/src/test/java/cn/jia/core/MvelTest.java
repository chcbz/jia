package cn.jia.core;

import org.junit.jupiter.api.Test;
import org.mvel2.MVEL;

import java.util.HashMap;
import java.util.Map;

public class MvelTest {
    @Test
    public void test() {
        Map<String, Object> map = new HashMap<>();
        map.put("a", 1);
        map.put("b", "2");
        Map<String, Object> map1 = new HashMap<>();
        map1.put("a", map);
        System.out.println(MVEL.eval("a.?c", map1));
    }
}
