package cn.jia.core.service;

import cn.jia.base.dao.DictDao;
import cn.jia.base.entity.DictEntity;
import cn.jia.base.service.impl.DictServiceImpl;
import cn.jia.core.redis.RedisService;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

class DictServiceTest extends BaseMockTest {
    @Mock
    private DictDao baseDao;
    @Mock
    private RedisService redisService;
    @InjectMocks
    private DictServiceImpl dictService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(dictService, "baseDao", baseDao);
    }

    @Test
    void selectAll() {
        when(baseDao.selectAll()).thenReturn(Collections.singletonList(new DictEntity()));
        when(redisService.get(any())).thenReturn("[]");
        doNothing().when(redisService).set(any(), any(), any(), any());
        assertTrue(dictService.selectAll().size() > 0);
    }
}