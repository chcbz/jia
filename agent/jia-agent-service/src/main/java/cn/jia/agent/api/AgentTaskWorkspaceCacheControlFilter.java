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

/** Adds no-store before the resource-server chain can emit workspace/SSE 401/403. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentTaskWorkspaceCacheControlFilter extends OncePerRequestFilter {
    private static final PathPattern EVENTS_PATH = PathPatternParser.defaultInstance.parse(
            "/agent/tasks/{taskId}/events");
    private static final PathPattern WORKSPACE_PATH = PathPatternParser.defaultInstance.parse(
            "/agent/tasks/{taskId}/workspace");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        PathContainer path = ServletRequestPathUtils.parse(request).pathWithinApplication();
        return !EVENTS_PATH.matches(path) && !WORKSPACE_PATH.matches(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL,
                AgentTaskWorkspaceController.CACHE_CONTROL_VALUE);
        filterChain.doFilter(request, response);
    }
}
