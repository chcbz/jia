package cn.jia.oauth.filter;

import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
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
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken
                && authentication.isAuthenticated()) {
            overwriteFromJwt(EsContextHolder.getContext(), jwtAuthenticationToken.getToken());
        }
        chain.doFilter(req, res);
    }

    private static void overwriteFromJwt(EsContext context, Jwt jwt) {
        context.setJiacn(stringClaim(jwt, "jiacn"));
        context.setAppcn(stringClaim(jwt, "appcn"));
        context.setClientId(stringClaim(jwt, "client_id"));
        context.setUsername(stringClaim(jwt, "username"));
    }

    private static String stringClaim(Jwt jwt, String name) {
        Object claim = jwt.getClaims().get(name);
        return claim instanceof String value ? value : null;
    }
}
