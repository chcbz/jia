package cn.jia.agent.config;

import cn.jia.agent.security.AgentRuntimeAuthenticationFilter;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration(proxyBeanMethods = false)
public class AgentRuntimeSecurityConfiguration {
    /**
     * Runtime v1 bootstrap and lifecycle credentials are verified by their controller/service,
     * not by the general OAuth resource-server chain. Installation administration deliberately
     * remains outside this lane so it still requires the caller's scoped JWT.
     */
    @Bean @Order(0)
    public SecurityFilterChain agentRuntimeV1ClientSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(AgentRuntimeSecurityConfiguration::selectsRuntimeV1ClientLane)
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(c -> c.requestCache(new NullRequestCache()));
        return http.build();
    }

    @Bean @Order(1)
    public SecurityFilterChain agentRuntimeSecurityFilterChain(HttpSecurity http,
            AgentRuntimeAuthenticationService service) throws Exception {
        http.securityMatcher(AgentRuntimeAuthenticationFilter::selectsRuntimeCredentialLane)
                .addFilterBefore(new AgentRuntimeAuthenticationFilter(service), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().hasAuthority("AGENT_RUNTIME_NARROW"))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(c -> c.requestCache(new NullRequestCache()));
        return http.build();
    }

    public static boolean selectsRuntimeV1ClientLane(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return false;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("/agent/runtime/v1/enroll".equals(path)
                || "/agent/runtime/v1/session".equals(path)
                || "/agent/runtime/v1/heartbeat".equals(path)) return true;
        return path.matches("/agent/runtime/v1/commands/[A-Za-z0-9][A-Za-z0-9._:-]{0,99}/acks");
    }
}
