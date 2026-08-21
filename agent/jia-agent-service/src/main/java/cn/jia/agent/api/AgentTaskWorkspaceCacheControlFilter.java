package cn.jia.agent.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Adds no-store before the resource-server chain can emit workspace/SSE 401/403. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentTaskWorkspaceCacheControlFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return !(path.startsWith("/agent/tasks/")
                && (path.endsWith("/workspace") || path.endsWith("/events")));
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
