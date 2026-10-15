package cn.jia.core.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Establishes a bounded request correlation ID at the servlet boundary.
 * It deliberately reads only {@value #HEADER_NAME}; query strings, bodies, and other headers are not inspected.
 */
public final class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    public static final String MDC_REQUEST_ID_KEY = "request_id";
    public static final String MDC_TRACE_ID_KEY = "trace_id";


    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = requestId(request);
        response.setHeader(HEADER_NAME, requestId);

        String previousRequestId = MDC.get(MDC_REQUEST_ID_KEY);
        String previousTraceId = MDC.get(MDC_TRACE_ID_KEY);
        MDC.put(MDC_REQUEST_ID_KEY, requestId);
        String traceId = availableTraceId();
        if (traceId != null) {
            MDC.put(MDC_TRACE_ID_KEY, traceId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            response.setHeader(HEADER_NAME, current(request));
            restoreMdcValue(MDC_REQUEST_ID_KEY, previousRequestId);
            restoreMdcValue(MDC_TRACE_ID_KEY, previousTraceId);
        }
    }

    private String requestId(HttpServletRequest request) {
        Object stored = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (stored instanceof String && isValid((String) stored)) {
            return (String) stored;
        }
        String incoming = request.getHeader(HEADER_NAME);
        String requestId = isValid(incoming) ? incoming : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        return requestId;
    }

    public static boolean isValid(String value) {
        return value != null && !value.isBlank() && value.codePoints().noneMatch(Character::isISOControl);
    }

    /** Reuses the same correlation on normal, error and asynchronous dispatch. */
    public static String current(HttpServletRequest request) {
        Object stored = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (stored instanceof String value && isValid(value)) return value;
        String supplied = request.getHeader(HEADER_NAME);
        String value = isValid(supplied) ? supplied : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, value);
        return value;
    }

    public static void accept(HttpServletRequest request, String value) {
        if (!isValid(value)) throw new IllegalArgumentException("PROTOCOL_INVALID_ENVELOPE");
        request.setAttribute(REQUEST_ID_ATTRIBUTE, value);
        MDC.put(MDC_REQUEST_ID_KEY, value);
    }

    private static String availableTraceId() {
        String traceId = MDC.get(MDC_TRACE_ID_KEY);
        return traceId != null ? traceId : MDC.get("traceId");
    }

    private static void restoreMdcValue(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }
}
