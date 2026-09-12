package cn.jia.core.elasticsearch;

import cn.jia.core.deadline.RequestDeadline;
import cn.jia.core.deadline.RequestDeadlineContext;
import cn.jia.core.deadline.SafeRequestTimeoutException;
import cn.jia.core.config.ElasticsearchConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElasticsearchTimeoutsTest {
    @Test
    void defaultsReserveAnEndToEndRequestBudget() {
        ElasticsearchTimeouts timeouts = defaults();

        assertEquals(Duration.ofMillis(500), timeouts.getConnectTimeout());
        assertEquals(Duration.ofMillis(1750), timeouts.getSocketTimeout());
        assertEquals(Duration.ofMillis(2500), timeouts.getRequestTimeout());
        assertEquals(Duration.ofMillis(100), timeouts.getSafetyMargin());
        assertDoesNotThrow(timeouts::requireFullRequestBudgetBeforeNewOperation);
    }

    @Test
    void configurationCreatesTheSameValidatedBudgetUsedByTheClient() {
        ElasticsearchTimeouts timeouts = new ElasticsearchConfig().elasticsearchTimeouts(Duration.ofMillis(500),
                Duration.ofMillis(1750), Duration.ofMillis(2500), Duration.ofMillis(100));

        assertEquals(Duration.ofMillis(2500), timeouts.getRequestTimeout());
    }

    @Test
    void rejectsPhaseTimeoutsThatExceedTheRequestBudget() {
        assertThrows(IllegalArgumentException.class, () -> new ElasticsearchTimeouts(Duration.ofMillis(1000),
                Duration.ofMillis(2000), Duration.ofMillis(2500), Duration.ofMillis(100)));
    }

    @Test
    void doesNotStartAnOperationWhenTheRemainingRequestDeadlineCannotFitItsFullBudget() {
        ElasticsearchTimeouts timeouts = defaults();

        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(2500))) {
            SafeRequestTimeoutException exception = assertThrows(SafeRequestTimeoutException.class,
                    timeouts::requireFullRequestBudgetBeforeNewOperation);
            assertEquals(SafeRequestTimeoutException.Failure.REQUEST_DEADLINE_EXCEEDED, exception.failure());
            assertEquals(SafeRequestTimeoutException.Dependency.ELASTICSEARCH, exception.dependency());
            assertEquals(SafeRequestTimeoutException.WorkState.NOT_STARTED, exception.workState());
            assertEquals(false, exception.retryable());
        }
    }

    private ElasticsearchTimeouts defaults() {
        return new ElasticsearchTimeouts(ElasticsearchTimeouts.DEFAULT_CONNECT_TIMEOUT,
                ElasticsearchTimeouts.DEFAULT_SOCKET_TIMEOUT, ElasticsearchTimeouts.DEFAULT_REQUEST_TIMEOUT,
                ElasticsearchTimeouts.DEFAULT_SAFETY_MARGIN);
    }
}
