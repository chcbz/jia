package cn.jia.base.filter;

import cn.jia.base.entity.LogEntity;
import cn.jia.base.service.LogService;
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

import java.io.IOException;
import java.util.Objects;

/** Captures a sanitized access-attempt audit and admits its DB write before downstream side effects. */
public class UriAccessLogFilter implements Filter {
    private final LogService logService;
    private final AuditDispatcher auditDispatcher;
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
        auditDispatcher.dispatch(() -> logService.persistLog(sanitizedAudit));
        filterChain.doFilter(requestWrapper, response);
    }

    @Override
    public void init(FilterConfig filterConfig) {
        String exclusionsParam = filterConfig.getInitParameter("exclusions");
        if (exclusionsParam != null) {
            exclusions = exclusionsParam.split(",");
        }
    }
}
