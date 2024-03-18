package cn.jia.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZipUtilTest {

    @Test
    void unzip() {
        String ss = ZipUtil.zip("wegwegwegweg");
        assertEquals(ZipUtil.unzip(ss), "wegwegwegweg");
    }
}