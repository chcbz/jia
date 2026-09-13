package cn.jia.core.deadline;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Propagates only the common request deadline to the shared {@code RestTemplate}.
 *
 * <p>The reserved header is replaced rather than appended, so caller-supplied or stale values cannot extend the
 * current request budget. Exhaustion fails before {@link ClientHttpRequestExecution#execute(HttpRequest, byte[])};
 * failures after the network call starts are deliberately not reclassified because a write outcome may be unknown.
 * This interceptor never retries.</p>
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
