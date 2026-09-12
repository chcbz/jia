package cn.jia.core.interceptor;

import cn.jia.core.filter.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._-]{8,128}");
    private static final String TRACE_ID_ATTRIBUTE = HttpRequestLogInterceptor.class.getName() + ".traceId";

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
                    : new RequestLogState(nanoTime(), requestId(request));
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

        log.warn("http_request_complete request_id={} trace_id={} method={} route={} status={} duration_ms={} outcome={}",
                state.requestId, traceId(request), safeMethod(request.getMethod()), trustedRouteTemplate(request, handler), status,
                durationMillis, failed ? "ERROR" : "SLOW");
    }

    private static String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId instanceof String && SAFE_CORRELATION_ID.matcher((String) requestId).matches()
                ? (String) requestId
                : UUID.randomUUID().toString();
    }

    private static String traceId(HttpServletRequest request) {
        Object storedTraceId = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (storedTraceId instanceof String && SAFE_CORRELATION_ID.matcher((String) storedTraceId).matches()) {
            return (String) storedTraceId;
        }
        String traceId = MDC.get(RequestIdFilter.MDC_TRACE_ID_KEY);
        if (traceId == null) {
            traceId = MDC.get("traceId");
        }
        if (traceId != null && SAFE_CORRELATION_ID.matcher(traceId).matches()) {
            request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
            return traceId;
        }
        return null;
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
