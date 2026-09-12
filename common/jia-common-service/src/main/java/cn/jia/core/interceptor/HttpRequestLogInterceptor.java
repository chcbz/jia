package cn.jia.core.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Emits one bounded completion summary for slow or failed Spring MVC handler requests.
 * This interceptor deliberately does not observe request headers, query parameters, or bodies.
 */
@Slf4j
public class HttpRequestLogInterceptor implements HandlerInterceptor {
    static final String REQUEST_LOG_STATE_ATTRIBUTE = HttpRequestLogInterceptor.class.getName() + ".state";
    static final String REQUEST_CORRELATION_ID_ATTRIBUTE = HttpRequestLogInterceptor.class.getName() + ".requestId";

    private static final String UNMAPPED_ROUTE = "unmapped";
    private static final String UNKNOWN_METHOD = "UNKNOWN";
    private static final int MAX_ROUTE_LENGTH = 256;
    private static final Pattern SAFE_METHOD = Pattern.compile("[A-Z]{1,16}");

    private final long slowThresholdMillis;

    public HttpRequestLogInterceptor() {
        this(1000L);
    }

    public HttpRequestLogInterceptor(long slowThresholdMillis) {
        this.slowThresholdMillis = Math.max(0L, slowThresholdMillis);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        synchronized (request) {
            RequestLogState state = request.getAttribute(REQUEST_LOG_STATE_ATTRIBUTE) instanceof RequestLogState
                    ? (RequestLogState) request.getAttribute(REQUEST_LOG_STATE_ATTRIBUTE)
                    : new RequestLogState(nanoTime(), UUID.randomUUID().toString());
            request.setAttribute(REQUEST_LOG_STATE_ATTRIBUTE, state);
            request.setAttribute(REQUEST_CORRELATION_ID_ATTRIBUTE, state.requestId);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RequestLogState state;
        synchronized (request) {
            Object candidate = request.getAttribute(REQUEST_LOG_STATE_ATTRIBUTE);
            if (!(candidate instanceof RequestLogState)) {
                return;
            }
            state = (RequestLogState) candidate;
            if (state.completed) {
                return;
            }
            state.completed = true;
        }

        long durationMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nanoTime() - state.startNanos));
        int status = response.getStatus();
        boolean failed = ex != null || status >= 400;
        if (!failed && durationMillis < slowThresholdMillis) {
            return;
        }

        log.warn("http_request_complete request_id={} method={} route={} status={} duration_ms={} outcome={}",
                state.requestId, safeMethod(request.getMethod()), trustedRouteTemplate(request, handler), status,
                durationMillis, failed ? "ERROR" : "SLOW");
    }

    protected long nanoTime() {
        return System.nanoTime();
    }

    private String safeMethod(String method) {
        return method != null && SAFE_METHOD.matcher(method).matches() ? method : UNKNOWN_METHOD;
    }

    private String trustedRouteTemplate(HttpServletRequest request, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return UNMAPPED_ROUTE;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(pattern instanceof String)) {
            return UNMAPPED_ROUTE;
        }
        String route = (String) pattern;
        if (route.isEmpty() || route.length() > MAX_ROUTE_LENGTH || route.indexOf('\r') >= 0 || route.indexOf('\n') >= 0
                || route.indexOf('?') >= 0 || !route.startsWith("/")) {
            return UNMAPPED_ROUTE;
        }
        return route;
    }

    private static final class RequestLogState {
        private final long startNanos;
        private final String requestId;
        private boolean completed;

        private RequestLogState(long startNanos, String requestId) {
            this.startNanos = startNanos;
            this.requestId = requestId;
        }
    }
}
