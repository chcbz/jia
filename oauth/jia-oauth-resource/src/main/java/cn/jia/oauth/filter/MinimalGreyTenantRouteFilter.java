package cn.jia.oauth.filter;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.security.TenantClaimPolicy;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/** Fail-closed HTTP surface for the explicitly enabled fixed non-legacy tenant. */
public final class MinimalGreyTenantRouteFilter implements Filter {
    private static final Pattern AGENT_ID = Pattern.compile("(?:agt_[0-9a-f]{32}|builtin-songjiang)");
    private static final Pattern PERSONA_BIND = Pattern.compile("/agent/personas/[^/]{1,100}/bind");
    private static final Pattern INSTALLATION = Pattern.compile("/agent/runtime/v1/installations/[^/]{1,100}(?:/revoke)?");
    private static final Pattern RUNTIME_ACK = Pattern.compile("/agent/runtime/v1/commands/[^/]{1,100}/acks");
    private static final Set<String> CHAT_GET = Set.of("/chat/capabilities", "/chat/conversation/content",
            "/chat/conversation/events");
    private static final Set<String> CHAT_POST = Set.of("/chat/stream", "/chat/stop_stream",
            "/chat/conversation/list", "/chat/conversation/update");

    private final boolean enabled;
    private final String fixedTenantId;

    public MinimalGreyTenantRouteFilter(boolean enabled, String fixedTenantId) {
        this.enabled = enabled;
        this.fixedTenantId = enabled ? TenantClaimPolicy.exact(fixedTenantId) : null;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!enabled || !(request instanceof HttpServletRequest http)) {
            chain.doFilter(request, response);
            return;
        }
        String path = http.getRequestURI().substring(http.getContextPath().length());
        if (publicInfrastructureAllowed(http.getMethod(), path)
                || runtimeBootstrapAllowed(http.getMethod(), path)
                || fixedTenantId.equals(EsContextHolder.getContext().getTenantId())
                && allowed(http.getMethod(), path)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletResponse denied = (HttpServletResponse) response;
        denied.setStatus(HttpServletResponse.SC_FORBIDDEN);
        denied.setCharacterEncoding(StandardCharsets.UTF_8.name());
        denied.setContentType("application/json");
        denied.setHeader("Cache-Control", "private, no-store");
        denied.getWriter().write("{\"code\":\"FIXED_TENANT_ROUTE_UNSUPPORTED\",\"msg\":\"Route is not enabled for the minimal tenant\"}");
    }

    static boolean publicInfrastructureAllowed(String method, String path) {
        if ("OPTIONS".equals(method)) return true;
        if ("POST".equals(method) && "/login".equals(path)) return true;
        if ("GET".equals(method) && ("/login/index.html".equals(path)
                || path.matches("/login/(?:css|fonts|image|js)/[^?]+"))) return true;
        if (path.startsWith("/oauth2/") || path.startsWith("/.well-known/")) return true;
        if (("GET".equals(method) || "POST".equals(method)) && "/oauth/confirm_access".equals(path)) return true;
        return "GET".equals(method) && ("/favicon.ico".equals(path) || "/error".equals(path)
                || "/actuator/health".equals(path) || path.startsWith("/actuator/health/"));
    }

    static boolean runtimeBootstrapAllowed(String method, String path) {
        return "POST".equals(method) && Set.of("/agent/runtime/v1/enroll",
                "/agent/runtime/v1/session", "/agent/runtime/v1/heartbeat").contains(path);
    }

    static boolean allowed(String method, String path) {
        if ("GET".equals(method) && "/resource".equals(path)) return true;
        if ("GET".equals(method) && Set.of("/agent/map", "/agent/capabilities", "/agent/personas/catalog").contains(path)) return true;
        if ("POST".equals(method) && "/agent/roster".equals(path)) return true;
        if ("POST".equals(method) && PERSONA_BIND.matcher(path).matches()) return true;
        if (("GET".equals(method) || "PUT".equals(method)) && path.startsWith("/agent/")) {
            String tail = path.substring("/agent/".length());
            if ("PUT".equals(method) && tail.endsWith("/status")) tail = tail.substring(0, tail.length() - 7);
            if (AGENT_ID.matcher(tail).matches()) return true;
        }
        if ("POST".equals(method) && "/agent/runtime/v1/installations".equals(path)) return true;
        if (("GET".equals(method) || "POST".equals(method)) && INSTALLATION.matcher(path).matches()) return true;
        if ("POST".equals(method) && RUNTIME_ACK.matcher(path).matches()) return true;
        if ("GET".equals(method) && "/ws/agent/channel".equals(path)) return true;
        if ("DELETE".equals(method) && "/chat/conversation/delete".equals(path)) return true;
        if ("GET".equals(method) && (CHAT_GET.contains(path) || path.matches("/chat/(?:requests|turns)/[^/]{1,100}"))) return true;
        if ("POST".equals(method) && (CHAT_POST.contains(path)
                || path.matches("/chat/(?:requests|turns)/[^/]{1,100}/cancel"))) return true;
        return false;
    }
}
