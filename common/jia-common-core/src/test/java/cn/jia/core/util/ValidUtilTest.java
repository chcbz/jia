package cn.jia.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValidUtilTest {

    @Test
    void isDate() {
        assertTrue(ValidUtil.isDate("2020-11-12 23:11:12"));
    }
}