package cn.jia.oauth.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Fail-closed stopgap for resource-chain routes not yet migrated beyond tenant 0.
 * Installed only inside the OAuth resource-server chain, after bearer authentication.
 * Earlier dedicated runtime and voice chains require separate scope checks.
 */
public final class LegacyAgentTenantBoundaryFilter extends OncePerRequestFilter {
    private static final PathPatternRequestMatcher UNSCOPED_CHAT_WEBSOCKET =
            PathPatternRequestMatcher.pathPattern("/ws/chat");
    // Match account-self operations by HTTP method as well as exact path.
    private static final List<PathPatternRequestMatcher> GLOBAL_ACCOUNT_PATHS = List.of(
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/user/my"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/user/me/sessions/revoke-all"));
    // Preserve the resource chain's explicitly public routes.
    private static final List<PathPatternRequestMatcher> PUBLIC_PATHS = List.of(
            PathPatternRequestMatcher.pathPattern("/wx/mp/checksignature"),
            PathPatternRequestMatcher.pathPattern("/dwz/view/**"));
    // Only these controller methods have completed tenant/domain/owner admission.
    private static final List<PathPatternRequestMatcher> SCOPED_CONVERSATION_PATHS = List.of(
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/chat/stream"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/chat/stop_stream"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/chat/conversation/delete"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/chat/conversation/content"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/chat/conversation/events"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/chat/conversation/list"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/chat/conversation/update"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/chat/library/search"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/chat/library/documents"));
    private final boolean strict;

    public LegacyAgentTenantBoundaryFilter(boolean strict) {
        this.strict = strict;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!strict || GLOBAL_ACCOUNT_PATHS.stream().anyMatch(path -> path.matches(request))
                || PUBLIC_PATHS.stream().anyMatch(path -> path.matches(request))) {
            chain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) {
            response.setHeader("Cache-Control", "private, no-store");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        // The old WebSocket handler accepts client-supplied conversation IDs and has
        // no authenticated owner/tenant scope. Deny its handshake even for tenant 0.
        if (UNSCOPED_CHAT_WEBSOCKET.matches(request)) {
            response.setHeader("Cache-Control", "private, no-store");
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        boolean legacyCentralScope = "0".equals(claims.get("tenant_id"))
                && "cyf".equals(claims.get("data_domain_id"));
        boolean scopedConversation = "cyf".equals(claims.get("data_domain_id"))
                && SCOPED_CONVERSATION_PATHS.stream().anyMatch(path -> path.matches(request));
        if (legacyCentralScope || scopedConversation) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "private, no-store");
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
}
