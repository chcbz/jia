package cn.jia.core.util;

import org.junit.jupiter.api.Test;

class Base64UtilTest {

    @Test
    void encode() {
        System.out.println(Base64Util.encode("jia_client:secret"));
    }
}