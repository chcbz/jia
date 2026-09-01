package cn.jia.chat.voice.config;

import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceErrorData;
import cn.jia.core.entity.JsonResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
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
            ObjectProvider<JwtDecoder> jwtDecoderProvider) throws Exception {
        JwtDecoder jwtDecoder = jwtDecoderProvider.getIfAvailable();
        http.securityMatcher("/chat/speech/**")
                .authorizeHttpRequests(authorize -> {
                    if (jwtDecoder == null) {
                        authorize.anyRequest().denyAll();
                    } else {
                        authorize.anyRequest().authenticated();
                    }
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                unauthorized(response, objectMapper))
                        .accessDeniedHandler((request, response, exception) ->
                                unauthorized(response, objectMapper)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(
                        new NullSecurityContextRepository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(AbstractHttpConfigurer::disable);
        if (jwtDecoder != null) {
            http.oauth2ResourceServer(oauth2 -> oauth2
                    .jwt(jwt -> jwt.decoder(jwtDecoder))
                    .authenticationEntryPoint((request, response, exception) ->
                            unauthorized(response, objectMapper)));
        }
        return http.build();
    }

    private static void unauthorized(HttpServletResponse response, ObjectMapper objectMapper)
            throws java.io.IOException {
        VoiceErrorCode error = VoiceErrorCode.UNAUTHORIZED;
        JsonResult<VoiceErrorData> body = new JsonResult<>(
                new VoiceErrorData(null), error.message(), error.code(), error.status().value());
        response.setStatus(error.status().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
