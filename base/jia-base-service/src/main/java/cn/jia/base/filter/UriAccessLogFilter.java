package cn.jia.base.filter;

import cn.jia.base.entity.LogEntity;
import cn.jia.base.service.LogService;
import cn.jia.core.audit.AuditAdmissionException;
import cn.jia.core.audit.AuditDispatcher;
import cn.jia.core.common.EsRequestWrapper;
import cn.jia.core.util.HttpUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Captures a sanitized access-attempt audit without making ordinary request availability depend on it. */
public class UriAccessLogFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(UriAccessLogFilter.class);

    private final LogService logService;
    private final AuditDispatcher auditDispatcher;
    private final AtomicLong rejectedAuditCount = new AtomicLong();
    private String[] exclusions;

    public UriAccessLogFilter(LogService logService, AuditDispatcher auditDispatcher) {
        this.logService = Objects.requireNonNull(logService, "logService");
        this.auditDispatcher = Objects.requireNonNull(auditDispatcher, "auditDispatcher");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)) {
            filterChain.doFilter(request, response);
            return;
        }
        String requestUri = httpRequest.getRequestURI().toLowerCase();
        if (HttpUtil.matchUrlPatterns(requestUri, exclusions)) {
            filterChain.doFilter(request, response);
            return;
        }

        EsRequestWrapper requestWrapper = new EsRequestWrapper(httpRequest);
        LogEntity sanitizedAudit = logService.captureLog(requestWrapper);
        try {
            auditDispatcher.dispatch(() -> logService.persistLog(sanitizedAudit));
        } catch (AuditAdmissionException rejected) {
            // Access telemetry is not a transaction/ACL/settlement audit. A full or closed
            // in-memory queue must not turn every normal request into a container ERROR dispatch.
            long rejectedCount = rejectedAuditCount.incrementAndGet();
            if (isPowerOfTwo(rejectedCount)) {
                log.warn("Access audit admission unavailable; continuing request without this telemetry, total_rejected={}",
                        rejectedCount);
            }
        }
        filterChain.doFilter(requestWrapper, response);
    }

    private static boolean isPowerOfTwo(long value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    @Override
    public void init(FilterConfig filterConfig) {
        String exclusionsParam = filterConfig.getInitParameter("exclusions");
        if (exclusionsParam != null) {
            exclusions = exclusionsParam.split(",");
        }
    }
}
