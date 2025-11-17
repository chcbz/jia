package cn.jia.oauth.filter;

import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.StringUtil;
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
import java.util.Optional;

public class EsSecurityContextFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        EsContext esContext = EsContextHolder.getContext();
        if (StringUtil.isNotEmpty(esContext.getJiacn())) {
            chain.doFilter(req, res);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            Jwt token = jwtAuthenticationToken.getToken();
            Optional.ofNullable(token.getClaim("jiacn")).map(String::valueOf).ifPresent(esContext::setJiacn);
            Optional.ofNullable(token.getClaim("appcn")).map(String::valueOf).ifPresent(esContext::setAppcn);
            Optional.ofNullable(token.getClaim("client_id")).map(String::valueOf).ifPresent(esContext::setClientId);
            Optional.ofNullable(token.getClaim("username")).map(String::valueOf).ifPresent(esContext::setUsername);
        }
        chain.doFilter(req, res);
    }
}