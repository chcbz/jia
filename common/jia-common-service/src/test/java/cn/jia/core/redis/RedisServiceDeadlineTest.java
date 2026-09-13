package cn.jia.core.redis;

import cn.jia.core.deadline.RequestDeadline;
import cn.jia.core.deadline.RequestDeadlineContext;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisServiceDeadlineTest extends BaseMockTest {

    @AfterEach
    void clearsDeadlineContext() {
        assertEquals(false, RequestDeadlineContext.current().isPresent());
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiredPerformanceDeadlineDoesNotRefuseRedisWork() {
        RedisTemplate<String, String> template = mock(RedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.get("session:1")).thenReturn("available");
        RedisService service = new RedisService();
        ReflectionTestUtils.setField(service, "redisTemplate", template);

        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(0))) {
            assertEquals("available", service.get("session:1"));
        }

        verify(values).get("session:1");
    }
}
