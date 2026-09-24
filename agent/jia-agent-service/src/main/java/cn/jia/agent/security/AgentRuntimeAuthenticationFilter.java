package cn.jia.agent.security;

import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;

/** Closed method/path lane. Runtime credentials cannot fall through to user/OAuth APIs. */
public final class AgentRuntimeAuthenticationFilter extends OncePerRequestFilter {
    private final AgentRuntimeAuthenticationService service;
    public AgentRuntimeAuthenticationFilter(AgentRuntimeAuthenticationService service) { this.service = service; }

    public static boolean selectsRuntimeCredentialLane(HttpServletRequest request) {
        // One X-Agent-Id is also the established API-key websocket identity header. It is not,
        // by itself, proof that the caller is presenting an AgentRuntime credential. Ambiguous
        // duplicates remain fail-closed instead of falling through to a first-value consumer.
        return request.getHeader("X-Agent-Runtime-Id") != null
                || Collections.list(request.getHeaders("X-Agent-Id")).size() > 1
                || Collections.list(request.getHeaders("Authorization")).stream()
                .anyMatch(value -> value.toLowerCase(Locale.ROOT).startsWith("agentruntime"));
    }

    public static boolean allowed(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // No matrix parameters, encoded separators, dot-segments or alternate dispatch paths.
        String id = "[A-Za-z0-9][A-Za-z0-9._:-]{0,99}";
        return "GET".equals(request.getMethod()) && path.equals("/internal/agent/tasks/workspace-executions/commands")
                || "GET".equals(request.getMethod()) && path.matches("/agent/tasks/" + id + "/context-pack")
                || "POST".equals(request.getMethod()) && path.matches("/agent/tasks/" + id
                + "/work-items/" + id + "/reassignments/" + id + "/lease(?:/start|/heartbeat)?")
                || "GET".equals(request.getMethod()) && path.matches("/internal/agent/tasks/" + id
                + "/runs/" + id + "/inputs(?:/" + id + "/content)?")
                || "POST".equals(request.getMethod()) && path.matches("/internal/agent/tasks/" + id
                + "/runs/" + id + "/outputs/" + id + "/content")
                || "POST".equals(request.getMethod()) && path.matches("/internal/agent/tasks/" + id
                + "/runs/" + id + "/output-commits/" + id)
                || "POST".equals(request.getMethod()) && path.matches("/internal/agent/tasks/" + id
                + "/runs/" + id + "/(?:failure|start)");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "private, no-store");
        final AgentRuntimeAuthentication authentication;
        try {
            if (!allowed(request) || request.getHeader("Origin") != null) {
                reject(response, 403); return; // native-only; no browser credential exposure/CORS lane
            }
            String authorization = single(request, "Authorization");
            if (!authorization.matches("AgentRuntime [0-9a-f]{32}")) throw AgentRuntimeAuthenticationService.denied();
            authentication = service.authenticate(single(request, "X-Agent-Id"),
                    single(request, "X-Agent-Runtime-Id"), authorization.substring(13));
        } catch (RuntimeException ignored) { reject(response, 401); return; }
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        var previousScope = EsContextHolder.getContext();
        var trustedScope = new EsContext();
        trustedScope.setJiacn(authentication.getPrincipal().ownerJiacn());
        trustedScope.setClientId(authentication.getPrincipal().clientId());
        EsContextHolder.setContext(trustedScope); // never inherit client cookies as domain scope
        try { chain.doFilter(request, response); } finally {
            EsContextHolder.setContext(previousScope);
            SecurityContextHolder.setContext(previous);
        }
    }
    private static String single(HttpServletRequest request, String header) {
        var values = Collections.list(request.getHeaders(header));
        if (values.size() != 1) throw AgentRuntimeAuthenticationService.denied();
        return values.getFirst();
    }
    private static void reject(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"AGENT_RUNTIME_UNAUTHENTICATED\"}");
    }
}
