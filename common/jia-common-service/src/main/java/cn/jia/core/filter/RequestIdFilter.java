package cn.jia.core.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Establishes a bounded request correlation ID at the servlet boundary.
 * It deliberately reads only {@value #HEADER_NAME}; query strings, bodies, and other headers are not inspected.
 */
public final class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    public static final String MDC_REQUEST_ID_KEY = "request_id";
    public static final String MDC_TRACE_ID_KEY = "trace_id";

    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,128}");

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
            response.setHeader(HEADER_NAME, requestId);
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

    private static boolean isValid(String value) {
        return value != null && VALID_REQUEST_ID.matcher(value).matches();
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
