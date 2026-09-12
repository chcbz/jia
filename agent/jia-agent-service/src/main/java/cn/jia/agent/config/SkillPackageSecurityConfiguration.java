package cn.jia.agent.config;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.user.security.AccountSecurityService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Narrow same-origin W10 package lane. No new credential scheme, query-string secrets, or JWT fallback. */
@Configuration(proxyBeanMethods=false)
public class SkillPackageSecurityConfiguration {
    @Bean @Order(1)
    public SecurityFilterChain skillPackageSecurityFilterChain(HttpSecurity http,ObjectProvider<ApiKeyService> keys,
            ObjectProvider<AccountSecurityService> accounts) throws Exception {
        http.securityMatcher("/internal/agent/skill-installations/*/package")
                .addFilterBefore(new PackageKeyFilter(keys,accounts),UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a->a.anyRequest().hasAuthority("ROLE_API_KEY"))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
    static final class PackageKeyFilter extends OncePerRequestFilter {
        private final ObjectProvider<ApiKeyService> keys;
        private final ObjectProvider<AccountSecurityService> accounts;
        PackageKeyFilter(ObjectProvider<ApiKeyService> keys,ObjectProvider<AccountSecurityService> accounts) { this.keys=keys;this.accounts=accounts; }
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
            OauthApiKeyEntity principal;
            try {
                var headers=Collections.list(request.getHeaders("X-API-Key"));
                if(!"GET".equals(request.getMethod()) || !request.getParameterMap().isEmpty() || headers.size()!=1
                        || headers.getFirst().isBlank() || headers.getFirst().length()>256 || keys.getIfAvailable()==null || accounts.getIfAvailable()==null) throw denied();
                var resolved=keys.getObject().findByApiKey(headers.getFirst());
                if(resolved==null || resolved.getId()==null) throw denied();
                var current=keys.getObject().get(resolved.getId());
                if(current==null || current.getApiKey()==null || !MessageDigest.isEqual(current.getApiKey().getBytes(StandardCharsets.UTF_8),headers.getFirst().getBytes(StandardCharsets.UTF_8))
                        || !Integer.valueOf(1).equals(current.getStatus()) || current.getExpireTime()!=null && current.getExpireTime()<=System.currentTimeMillis()
                        || current.getJiacn()==null || !current.getJiacn().equals(current.getTenantId()) || current.getClientId()==null) throw denied();
                var account=accounts.getObject().findUniqueByExactJiacn(current.getJiacn()).filter(a->a.isAuthenticatable()).orElseThrow(PackageKeyFilter::denied);
                if(!current.getJiacn().equals(account.jiacn())) throw denied();
                // Never put a secret-bearing entity into the security context or logging surface.
                principal=new OauthApiKeyEntity(); principal.setId(current.getId());principal.setJiacn(current.getJiacn());
                principal.setTenantId(current.getTenantId());principal.setClientId(current.getClientId());
                principal.setStatus(current.getStatus());principal.setExpireTime(current.getExpireTime());
            } catch(RuntimeException rejected) {
                response.setStatus(403);response.setHeader("Cache-Control","private, no-store");
                response.setContentType("application/json");response.getWriter().write("{\"code\":\"SKILL_DOWNLOAD_FORBIDDEN\",\"msg\":\"Package access unavailable\"}");return;
            }
            var previous=SecurityContextHolder.getContext();var context=SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,AuthorityUtils.createAuthorityList("ROLE_API_KEY")));
            SecurityContextHolder.setContext(context);
            try { chain.doFilter(request,response); } finally { SecurityContextHolder.setContext(previous); }
        }
        private static IllegalArgumentException denied() { return new IllegalArgumentException("package key denied"); }
    }
}
