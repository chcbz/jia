package cn.jia.agent.config;

import cn.jia.agent.api.OutputHttpEnvelope;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
@Conditional(OutputDeliveryEnabledCondition.class)
public class OutputReadSecurityConfiguration {
    @Bean
    @Order(1)
    public SecurityFilterChain outputReadSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(OutputReadSecurityConfiguration::matches)
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(OutputReadSecurityConfiguration::unauthorized))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(OutputReadSecurityConfiguration::unauthorized))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context
                        .securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    static boolean matches(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) return false;
        String path = request.getRequestURI();
        return "/agent/output-capabilities".equals(path)
                || path.matches("/agent/tasks/[^/]+/artifacts(?:/[^/]+/versions(?:/[^/]+(?:/download)?)?)?")
                || path.matches("/chat/conversations/[^/]+/outputs(?:/[^/]+/versions(?:/[^/]+(?:/download)?)?)?");
    }

    private static void unauthorized(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException failure) throws IOException {
        OutputHttpEnvelope.writeError(request, response, "OUTPUT_AUTH_UNAUTHORIZED",
                "Output access is unavailable", 401, false);
    }
}
