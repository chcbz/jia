package cn.jia.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThreeDesUtilTest {
    @Test
    void decrypt3DES() {
        String s = "3XQgDrNlpjtzT4Orq8uSLQ==";
        assertEquals(ThreeDesUtil.decrypt3Des(s), "li129125");
    }

    @Test
    void encrypt3DES() {
        String s = "li129125";
        assertEquals(ThreeDesUtil.encrypt3Des(s), "3XQgDrNlpjtzT4Orq8uSLQ==");
    }
}