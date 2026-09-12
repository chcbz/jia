package cn.jia.core.redis;

import cn.jia.core.deadline.RequestDeadline;
import cn.jia.core.deadline.RequestDeadlineContext;
import cn.jia.core.deadline.SafeRequestTimeoutException;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RedisServiceDeadlineTest extends BaseMockTest {

    @AfterEach
    void clearsDeadlineContext() {
        assertEquals(false, RequestDeadlineContext.current().isPresent());
    }

    @Test
    void doesNotStartSynchronousRedisCommandAfterDeadlineExpires() {
        RedisTemplate<String, String> template = mock(RedisTemplate.class);
        RedisService service = new RedisService();
        ReflectionTestUtils.setField(service, "redisTemplate", template);

        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(0))) {
            SafeRequestTimeoutException exception = assertThrows(SafeRequestTimeoutException.class,
                    () -> service.get("session:1"));
            assertEquals(SafeRequestTimeoutException.Dependency.REDIS, exception.dependency());
            assertEquals(SafeRequestTimeoutException.WorkState.NOT_STARTED, exception.workState());
        }

        verifyNoInteractions(template);
    }
}
