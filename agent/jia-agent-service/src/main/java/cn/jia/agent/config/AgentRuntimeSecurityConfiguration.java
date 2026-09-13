package cn.jia.agent.config;

import cn.jia.agent.security.AgentRuntimeAuthenticationFilter;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
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
    @Bean @Order(0)
    public SecurityFilterChain agentRuntimeSecurityFilterChain(HttpSecurity http,
            AgentRuntimeAuthenticationService service) throws Exception {
        http.securityMatcher(AgentRuntimeAuthenticationFilter::selects)
                .addFilterBefore(new AgentRuntimeAuthenticationFilter(service), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().hasAuthority("AGENT_RUNTIME_NARROW"))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(c -> c.requestCache(new NullRequestCache()));
        return http.build();
    }
}
