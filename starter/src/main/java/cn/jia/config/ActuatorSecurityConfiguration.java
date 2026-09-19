package cn.jia.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * Gives the loopback health endpoint its own highest-priority chain. The general
 * application rule retains an exact health-only fallback, while this explicit chain
 * keeps lifecycle health checks outside user/OAuth chain selection.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(SecurityFilterChain.class)
public class ActuatorSecurityConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(ActuatorSecurityConfiguration::selectsActuatorHealth)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()));
        return http.build();
    }

    static boolean selectsActuatorHealth(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (requestUri == null || contextPath == null || !requestUri.startsWith(contextPath)) {
            return false;
        }
        String path = requestUri.substring(contextPath.length());
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }
}
