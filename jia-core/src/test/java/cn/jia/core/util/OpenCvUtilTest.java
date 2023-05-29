package cn.jia.core.util;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class OpenCvUtilTest {
    @Test
    void includeImage() {
        String path = this.getClass().getResource("/opencv").getPath();
        File sourceFile = new File(path + "/opencv_test.png");
        File templateFile = new File(path + "/opencv_test_m.png");
        boolean result = OpenCvUtil.includeImage(sourceFile, templateFile);
        assertTrue(result);

        File templateFile1 = new File(path + "/opencv_test_f.png");
        boolean result1 = OpenCvUtil.includeImage(sourceFile, templateFile1);
        assertFalse(result1);
    }

    @Test
    void tagMatchImage() {
        String path = this.getClass().getResource("/opencv").getPath();
        File sourceFile = new File(path + "/opencv_test.png");
        File templateFile = new File(path + "/opencv_test_m.png");
        File result = OpenCvUtil.tagMatchImage(sourceFile, templateFile);
        System.out.println(result.getAbsolutePath());
    }
}