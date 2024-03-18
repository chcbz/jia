package cn.jia.core.service;

import cn.jia.base.dao.DictDao;
import cn.jia.base.entity.DictEntity;
import cn.jia.base.service.impl.DictServiceImpl;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DictServiceTest extends BaseMockTest {
    @Mock
    private DictDao baseDao;
    @Mock
    private RedisTemplate<String, List<DictEntity>> redisTemplate;
    @InjectMocks
    private DictServiceImpl dictService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(dictService, "baseDao", baseDao);
    }

    @Test
    void selectAll() {
        when(baseDao.selectAll()).thenReturn(Collections.singletonList(new DictEntity()));
        ValueOperations<String, List<DictEntity>> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        assertTrue(dictService.selectAll().size() > 0);
    }
}