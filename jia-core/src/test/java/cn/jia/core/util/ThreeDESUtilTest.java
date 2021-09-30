package cn.jia.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThreeDESUtilTest {
    @Test
    void decrypt3DES() {
        String s = "3XQgDrNlpjtzT4Orq8uSLQ==";
        assertEquals(ThreeDESUtil.Decrypt3DES(s), "li129125");
    }

    @Test
    void encrypt3DES() {
        String s = "li129125";
        assertEquals(ThreeDESUtil.Encrypt3DES(s), "3XQgDrNlpjtzT4Orq8uSLQ==");
    }
}