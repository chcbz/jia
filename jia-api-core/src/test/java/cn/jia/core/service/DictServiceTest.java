package cn.jia.core.service;

import cn.jia.test.BaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

class DictServiceTest extends BaseTest {
    @Autowired
    private DictService dictService;

    @Test
    void selectAll() {
        assertTrue(dictService.selectAll().size() > 0);
    }
}