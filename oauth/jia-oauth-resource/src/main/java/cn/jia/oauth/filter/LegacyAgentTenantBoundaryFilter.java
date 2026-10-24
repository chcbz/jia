package cn.jia.oauth.filter;

import cn.jia.oauth.security.TenantAdmissionPolicy;
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
import java.util.Objects;

/**
 * Fail-closed stopgap for resource-chain routes not yet migrated beyond tenant 0.
 * Installed only inside the OAuth resource-server chain, after bearer authentication.
 * Earlier dedicated runtime and voice chains require separate scope checks.
 */
public final class LegacyAgentTenantBoundaryFilter extends OncePerRequestFilter {
    private static final String FIXED_LOCAL_CLIENT_ID = "cyf-local";
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
    // Fixed local mode admits only controller methods whose tenant/owner scope is already explicit.
    // Tenant-zero hosting, funding, provider-consent, runtime and draft-submission routes stay closed.
    private static final List<PathPatternRequestMatcher> FIXED_TENANT_BUSINESS_PATHS = List.of(
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/chat/stream"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/chat/stop_stream"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/chat/conversation/delete"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/chat/conversation/content"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/chat/conversation/events"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/chat/conversation/list"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/chat/conversation/update"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/chat/library/search"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/chat/library/documents"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/chat/capabilities"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/chat/requests/{requestId}"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/chat/turns/{turnId}"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/chat/turns/{turnId}/cancel"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/chat/requests/{requestId}/cancel"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST,
                    "/chat/conversations/{conversationId}/interactions/discussion"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET,
                    "/chat/conversations/{conversationId}/requests/{requestId}/typed-outcome"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/agent/map"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/agent/roster"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/agent/personas/catalog"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/agent/hall/overview"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/agent/hall/items"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/agent/tasks/search"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/agent/tasks/status-counts"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/agent/tasks/{taskId}"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/agent/tasks/{taskId}/cancel"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/agent/tasks/creation-operations"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/agent/tasks/creation-operations/request"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/agent/tasks/creation-operations/v2"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/agent/tasks/creation-operations/v2/request"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/agent/hall/drafts"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/agent/hall/drafts"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/agent/hall/drafts/{draftId}"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.PUT, "/agent/hall/drafts/{draftId}"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/agent/hall/drafts/{draftId}/discard"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/agent/personal-workspace/files"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/agent/personal-workspace/files"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/agent/personal-workspace/files/{fileId}"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.PATCH, "/agent/personal-workspace/files/{fileId}"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET,
                    "/agent/personal-workspace/files/{fileId}/versions"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST,
                    "/agent/personal-workspace/files/{fileId}/versions"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET,
                    "/agent/personal-workspace/files/{fileId}/versions/{version}/content"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET,
                    "/agent/personal-workspace/files/{fileId}/versions/{version}/preview"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET,
                    "/agent/personal-workspace/files/{fileId}/versions/{version}/preview/parts/{partId}"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET,
                    "/agent/personal-workspace/files/{fileId}/usage"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST,
                    "/agent/personal-workspace/files/{fileId}/trash"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST,
                    "/agent/personal-workspace/files/{fileId}/restore"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET,
                    "/agent/personal-workspace/operations/{operationId}"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET,
                    "/agent/conversations/{conversationId}/file-links"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST,
                    "/agent/conversations/{conversationId}/file-links"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE,
                    "/agent/conversations/{conversationId}/file-links/{relationId}"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET,
                    "/agent/tasks/{taskId}/file-links"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST,
                    "/agent/tasks/{taskId}/file-links"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE,
                    "/agent/tasks/{taskId}/file-links/{relationId}"));
    private final boolean strict;
    private final TenantAdmissionPolicy admissionPolicy;

    public LegacyAgentTenantBoundaryFilter(boolean strict) {
        this.strict = strict;
        this.admissionPolicy = null;
    }

    public LegacyAgentTenantBoundaryFilter(TenantAdmissionPolicy admissionPolicy) {
        this.admissionPolicy = Objects.requireNonNull(admissionPolicy, "admissionPolicy");
        this.strict = admissionPolicy.requiresIsolatedResourceLane();
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
        boolean scopedConversationPath = SCOPED_CONVERSATION_PATHS.stream()
                .anyMatch(path -> path.matches(request));
        if (admissionPolicy != null && admissionPolicy.isFixedTenant()) {
            boolean fixedBusinessPath = FIXED_TENANT_BUSINESS_PATHS.stream()
                    .anyMatch(path -> path.matches(request));
            if (fixedBusinessPath) {
                try {
                    admissionPolicy.requireFixedUserTenant(jwt.getToken());
                    if (!FIXED_LOCAL_CLIENT_ID.equals(claims.get("client_id"))) {
                        throw new IllegalArgumentException("Token client is not admitted locally");
                    }
                } catch (RuntimeException ignored) {
                    // Fixed deployments admit only their signed canonical user scope.
                    response.setHeader("Cache-Control", "private, no-store");
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return;
                }
                chain.doFilter(request, response);
                return;
            }
            response.setHeader("Cache-Control", "private, no-store");
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        boolean legacyCentralScope = "0".equals(claims.get("tenant_id"))
                && "cyf".equals(claims.get("data_domain_id"));
        boolean scopedConversation = "cyf".equals(claims.get("data_domain_id"))
                && scopedConversationPath;
        if (legacyCentralScope || scopedConversation) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "private, no-store");
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
}
