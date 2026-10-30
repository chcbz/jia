package cn.jia.oauth.filter;

import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.core.security.TenantClaimPolicy;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;

public class EsSecurityContextFilter implements Filter {
    private final FixedTenantAdmissionPolicy tenantAdmission;

    public EsSecurityContextFilter(FixedTenantAdmissionPolicy tenantAdmission) {
        this.tenantAdmission = java.util.Objects.requireNonNull(tenantAdmission);
    }
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken
                && authentication.isAuthenticated()) {
            overwriteFromJwt(EsContextHolder.getContext(), jwtAuthenticationToken.getToken());
        }
        chain.doFilter(req, res);
    }

    private void overwriteFromJwt(EsContext context, Jwt jwt) throws ServletException {
        String tenant;
        try {
            tenant = TenantClaimPolicy.resolve(jwt.getClaims());
        } catch (IllegalArgumentException invalid) {
            context.setTenantId(null);
            throw new ServletException("Authenticated token has invalid tenant scope", invalid);
        }
        String clientId = stringClaim(jwt, "client_id");
        try {
            tenantAdmission.requireAdmitted(tenant, clientId);
        } catch (IllegalArgumentException denied) {
            context.setTenantId(null);
            throw new ServletException("Authenticated token tenant/client scope is not admitted", denied);
        }
        context.setTenantId(tenant);
        context.setJiacn(stringClaim(jwt, "jiacn"));
        context.setAppcn(stringClaim(jwt, "appcn"));
        context.setClientId(clientId);
        context.setUsername(stringClaim(jwt, "username"));
    }

    private static String stringClaim(Jwt jwt, String name) {
        Object claim = jwt.getClaims().get(name);
        return claim instanceof String value ? value : null;
    }
}
