package cn.jia.core.deadline;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Removes the reserved performance-deadline header and executes once, even when
 * the local observation budget has elapsed. Real network failures and caller
 * cancellation are not reclassified or retried: write outcomes may be unknown.
 */
public final class RequestDeadlineClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().remove(RequestDeadlinePropagation.HEADER_NAME);
        RequestDeadlinePropagation.writeCurrentHeaderBeforeNewWork(
                request.getHeaders()::set,
                SafeRequestTimeoutException.Dependency.HTTP);
        return execution.execute(request, body);
    }
}
