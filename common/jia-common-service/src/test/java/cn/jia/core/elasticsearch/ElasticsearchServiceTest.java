package cn.jia.core.elasticsearch;

import cn.jia.core.deadline.RequestDeadline;
import cn.jia.core.deadline.RequestDeadlineContext;
import cn.jia.core.deadline.SafeRequestTimeoutException;
import cn.jia.test.BaseMockTest;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ElasticsearchServiceTest extends BaseMockTest {
    @Test
    void doesNotStartSearchWhenRemainingDeadlineCannotFitTheElasticsearchRequestBudget() {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchService service = service(client);

        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(2500))) {
            SafeRequestTimeoutException exception = assertThrows(SafeRequestTimeoutException.class,
                    () -> service.searchMatch("phrase", "content", "water", String.class));
            assertEquals(SafeRequestTimeoutException.Failure.REQUEST_DEADLINE_EXCEEDED, exception.failure());
            assertEquals(SafeRequestTimeoutException.Dependency.ELASTICSEARCH, exception.dependency());
        }

        verifyNoInteractions(client);
    }

    @Test
    void classifiesAReadSocketTimeoutAsSafeElasticsearchTimeoutWithoutRetry() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchService service = service(client);
        when(client.search(any(Function.class), eq(String.class))).thenThrow(new SocketTimeoutException("synthetic"));

        SafeRequestTimeoutException exception = assertThrows(SafeRequestTimeoutException.class,
                () -> service.searchMatch("phrase", "content", "water", String.class));

        assertEquals(SafeRequestTimeoutException.Failure.DEPENDENCY_TIMEOUT, exception.failure());
        assertEquals(SafeRequestTimeoutException.Dependency.ELASTICSEARCH, exception.dependency());
        assertEquals(SafeRequestTimeoutException.WorkState.SAFE_TO_CANCEL, exception.workState());
        assertFalse(exception.retryable());
    }

    @Test
    void preservesTheExistingDeleteFailureContractWhenItsOutcomeMayBeUnknown() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchService service = service(client);
        when(client.delete(any(Function.class))).thenThrow(new SocketTimeoutException("synthetic"));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.delete("phrase-1", "phrase"));

        assertEquals("Delete failed", exception.getMessage());
        assertEquals(SocketTimeoutException.class, exception.getCause().getClass());
    }

    @Test
    void classifiesAReadConnectionFailureAsUnavailableBeforeWork() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchService service = service(client);
        when(client.search(any(Function.class), eq(String.class))).thenThrow(new ConnectException("synthetic"));

        SafeRequestTimeoutException exception = assertThrows(SafeRequestTimeoutException.class,
                () -> service.searchMatch("phrase", "content", "water", String.class));

        assertEquals(SafeRequestTimeoutException.Failure.DEPENDENCY_UNAVAILABLE, exception.failure());
        assertEquals(SafeRequestTimeoutException.Dependency.ELASTICSEARCH, exception.dependency());
        assertEquals(SafeRequestTimeoutException.WorkState.NOT_STARTED, exception.workState());
        assertFalse(exception.retryable());
    }

    private ElasticsearchService service(ElasticsearchClient client) {
        ElasticsearchService service = new ElasticsearchService();
        ReflectionTestUtils.setField(service, "elasticsearchClient", client);
        ReflectionTestUtils.setField(service, "elasticsearchTimeouts", new ElasticsearchTimeouts(
                ElasticsearchTimeouts.DEFAULT_CONNECT_TIMEOUT, ElasticsearchTimeouts.DEFAULT_SOCKET_TIMEOUT,
                ElasticsearchTimeouts.DEFAULT_REQUEST_TIMEOUT, ElasticsearchTimeouts.DEFAULT_SAFETY_MARGIN));
        return service;
    }
}
