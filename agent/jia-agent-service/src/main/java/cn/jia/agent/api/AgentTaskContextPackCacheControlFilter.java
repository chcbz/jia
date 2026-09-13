package cn.jia.agent.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;

/** Adds no-store before authentication can emit a Context Pack 401/403 response. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentTaskContextPackCacheControlFilter extends OncePerRequestFilter {
    private static final PathPattern CONTEXT_PACK_PATH =
            PathPatternParser.defaultInstance.parse("/agent/tasks/{taskId}/context-pack");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        PathContainer path = ServletRequestPathUtils.parse(request).pathWithinApplication();
        return !CONTEXT_PACK_PATH.matches(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL,
                AgentTaskContextPackController.CACHE_CONTROL_VALUE);
        filterChain.doFilter(request, response);
    }
}
