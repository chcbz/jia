package cn.jia.chat.voice.config;

import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceErrorData;
import cn.jia.core.entity.JsonResult;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(SecurityFilterChain.class)
public class VoiceSecurityConfiguration {
    @Bean
    @Order(1)
    public SecurityFilterChain voiceSecurityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            ObjectProvider<JwtDecoder> jwtDecoderProvider,
            @Value("${cyf.tenant.require-bound-identity:false}") boolean strictTenant) throws Exception {
        JwtDecoder jwtDecoder = jwtDecoderProvider.getIfAvailable();
        http.securityMatcher("/chat/speech/**")
                .authorizeHttpRequests(authorize -> {
                    if (jwtDecoder == null) {
                        authorize.anyRequest().denyAll();
                    } else if (strictTenant) {
                        // Voice has its own higher-priority security chain and a legacy
                        // client/owner cache namespace; it cannot inherit the chat route gate.
                        authorize.anyRequest().access((authentication, request) -> {
                            if (!(authentication.get() instanceof JwtAuthenticationToken jwt)) {
                                return new AuthorizationDecision(false);
                            }
                            var claims = jwt.getToken().getClaims();
                            return new AuthorizationDecision("0".equals(claims.get("tenant_id"))
                                    && "cyf".equals(claims.get("data_domain_id")));
                        });
                    } else {
                        authorize.anyRequest().authenticated();
                    }
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                unauthorized(request, response, objectMapper))
                        .accessDeniedHandler((request, response, exception) ->
                                unauthorized(request, response, objectMapper)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(
                        new NullSecurityContextRepository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(AbstractHttpConfigurer::disable);
        if (jwtDecoder != null) {
            http.oauth2ResourceServer(oauth2 -> oauth2
                    .jwt(jwt -> jwt.decoder(jwtDecoder))
                    .authenticationEntryPoint((request, response, exception) ->
                            unauthorized(request, response, objectMapper)));
        }
        return http.build();
    }

    private static void unauthorized(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response, ObjectMapper objectMapper)
            throws java.io.IOException {
        VoiceErrorCode error = VoiceErrorCode.UNAUTHORIZED;
        JsonResult<VoiceErrorData> body = new JsonResult<>(
                new VoiceErrorData(null), error.message(), error.code());
        body.setRequestId(cn.jia.core.filter.RequestIdFilter.current(request));
        response.setHeader(cn.jia.core.filter.RequestIdFilter.HEADER_NAME, body.getRequestId());
        response.setStatus(error.status().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
