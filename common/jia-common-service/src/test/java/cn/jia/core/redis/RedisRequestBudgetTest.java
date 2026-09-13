package cn.jia.core.redis;

import cn.jia.core.deadline.RequestDeadline;
import cn.jia.core.deadline.RequestDeadlineContext;
import cn.jia.core.deadline.SafeRequestTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisRequestBudgetTest {

    @AfterEach
    void clearsDeadlineContext() {
        assertEquals(false, RequestDeadlineContext.current().isPresent());
    }

    @Test
    void preservesBoundedRedisClientTimeoutOutsideARequest() {
        assertEquals(RedisRequestBudget.COMMAND_TIMEOUT_MILLIS, RedisRequestBudget.requireCommandBudget());
    }

    @Test
    void rejectsExpiredRequestBeforeRedisWorkStarts() {
        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(0))) {
            SafeRequestTimeoutException exception = assertThrows(SafeRequestTimeoutException.class,
                    RedisRequestBudget::requireCommandBudget);
            assertEquals(SafeRequestTimeoutException.Failure.REQUEST_DEADLINE_EXCEEDED, exception.failure());
            assertEquals(SafeRequestTimeoutException.Dependency.REDIS, exception.dependency());
            assertEquals(SafeRequestTimeoutException.WorkState.NOT_STARTED, exception.workState());
        }
    }

    @Test
    void rejectsPartialBudgetInsteadOfStartingACommandThatCanOutliveIt() {
        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(1_000))) {
            SafeRequestTimeoutException exception = assertThrows(SafeRequestTimeoutException.class,
                    RedisRequestBudget::requireCommandBudget);
            assertEquals(SafeRequestTimeoutException.Dependency.REDIS, exception.dependency());
            assertEquals(SafeRequestTimeoutException.WorkState.NOT_STARTED, exception.workState());
        }
    }
}
