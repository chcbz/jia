package cn.jia.core.deadline;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.function.LongSupplier;

/**
 * Establishes a request deadline context in shadow mode without terminating, interrupting, or timing out requests.
 * The only request header it reads is {@link RequestDeadlinePropagation#HEADER_NAME}.
 */
public final class RequestDeadlineFilter extends OncePerRequestFilter {
    public static final String DEADLINE_ATTRIBUTE = RequestDeadlineFilter.class.getName() + ".deadline";
    public static final String EXHAUSTED_ATTRIBUTE = RequestDeadlineFilter.class.getName() + ".exhausted";
    public static final String MODE_ATTRIBUTE = RequestDeadlineFilter.class.getName() + ".mode";
    public static final String SHADOW_MODE = "shadow";

    static final long MAX_SERVER_BUDGET_MILLIS = 60_000;
    private static final int MAX_HEADER_LENGTH = 10;

    private final long serverBudgetMillis;
    private final LongSupplier monotonicClock;

    public RequestDeadlineFilter(long serverBudgetMillis) {
        this(serverBudgetMillis, System::nanoTime);
    }

    RequestDeadlineFilter(long serverBudgetMillis, LongSupplier monotonicClock) {
        if (serverBudgetMillis <= 0 || serverBudgetMillis > MAX_SERVER_BUDGET_MILLIS) {
            throw new IllegalArgumentException(
                    "serverBudgetMillis must be between 1 and " + MAX_SERVER_BUDGET_MILLIS);
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException("monotonicClock must not be null");
        }
        this.serverBudgetMillis = serverBudgetMillis;
        this.monotonicClock = monotonicClock;
    }

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
        RequestDeadline deadline = requestDeadline(request);
        request.setAttribute(MODE_ATTRIBUTE, SHADOW_MODE);
        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(deadline)) {
            filterChain.doFilter(request, response);
        } finally {
            request.setAttribute(EXHAUSTED_ATTRIBUTE, deadline.isExpired());
        }
    }

    private RequestDeadline requestDeadline(HttpServletRequest request) {
        Object existing = request.getAttribute(DEADLINE_ATTRIBUTE);
        if (existing instanceof RequestDeadline deadline) {
            return deadline;
        }
        long budgetMillis = incomingBudgetMillis(request.getHeader(RequestDeadlinePropagation.HEADER_NAME));
        RequestDeadline deadline = RequestDeadline.start(budgetMillis, monotonicClock);
        request.setAttribute(DEADLINE_ATTRIBUTE, deadline);
        return deadline;
    }

    private long incomingBudgetMillis(String headerValue) {
        if (headerValue == null || headerValue.isEmpty() || headerValue.length() > MAX_HEADER_LENGTH) {
            return serverBudgetMillis;
        }
        long parsed = 0;
        for (int index = 0; index < headerValue.length(); index++) {
            char current = headerValue.charAt(index);
            if (current < '0' || current > '9') {
                return serverBudgetMillis;
            }
            int digit = current - '0';
            if (parsed > (Long.MAX_VALUE - digit) / 10) {
                return serverBudgetMillis;
            }
            parsed = (parsed * 10) + digit;
        }
        return Math.min(parsed, serverBudgetMillis);
    }
}
