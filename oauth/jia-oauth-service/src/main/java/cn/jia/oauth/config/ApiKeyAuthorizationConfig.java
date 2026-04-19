package cn.jia.oauth.config;

import cn.jia.core.common.EsConstants;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.oauth.entity.ApiKeyAuthToken;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class ApiKeyAuthorizationConfig {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY_PARAM = "api_key";

    private final ApiKeyService apiKeyService;

    @Bean
    @Order(99)
    public SecurityFilterChain apiKeySecurityFilterChain(HttpSecurity http) {
        http.securityMatcher("/api/**")
                .addFilterBefore(apiKeyAuthFilter(), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(configurer -> configurer
                        .defaultAuthenticationEntryPointFor(this::handleUnauthorized,
                                matcher -> MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(matcher.getContentType()))
                );
        return http.build();
    }

    private void handleUnauthorized(HttpServletRequest request, HttpServletResponse response,
                                    AuthenticationException authException) throws IOException {
        log.warn(authException.getMessage(), authException);
        JsonResult<Object> result = new JsonResult<>();
        result.setMsg(authException.getMessage());
        result.setCode(EsErrorConstants.UNAUTHORIZED.getCode());
        result.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Access-Control-Allow-Origin", "*");
        PrintWriter out = response.getWriter();
        out.print(JsonUtil.toJson(result));
    }

    private Filter apiKeyAuthFilter() {
        AbstractAuthenticationProcessingFilter filter = new AbstractAuthenticationProcessingFilter(
                PathPatternRequestMatcher.withDefaults().matcher("/api/**")) {
            @Override
            public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
                    throws AuthenticationException {
                Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
                if (existingAuth != null && existingAuth.isAuthenticated()) {
                    return null;
                }

                String apiKey = request.getHeader(API_KEY_HEADER);
                if (StringUtil.isEmpty(apiKey)) {
                    apiKey = request.getParameter(API_KEY_PARAM);
                }
                if (StringUtil.isEmpty(apiKey)) {
                    return null;
                }

                ApiKeyAuthToken authRequest = new ApiKeyAuthToken(apiKey);
                authRequest.setDetails(authenticationDetailsSource.buildDetails(request));
                return getAuthenticationManager().authenticate(authRequest);
            }

            @Override
            protected void successfulAuthentication(HttpServletRequest request, HttpServletResponse response,
                                                    FilterChain chain, Authentication authResult)
                    throws IOException, ServletException {
                if (authResult.getPrincipal() instanceof OauthApiKeyEntity principal) {
                    EsContext context = EsContextHolder.getContext();
                    context.setClientId(principal.getClientId());
                    context.setJiacn(principal.getJiacn());
                }
                SecurityContextHolder.getContext().setAuthentication(authResult);
                chain.doFilter(request, response);
            }
        };

        filter.setAuthenticationManager(new ProviderManager(apiKeyAuthProvider()));
        return filter;
    }

    private AuthenticationProvider apiKeyAuthProvider() {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication authentication) throws AuthenticationException {
                if (!supports(authentication.getClass())) {
                    return null;
                }
                ApiKeyAuthToken authToken = (ApiKeyAuthToken) authentication;
                String apiKey = authToken.getApiKey();

                OauthApiKeyEntity apiKeyEntity = apiKeyService.findByApiKey(apiKey);
                if (apiKeyEntity == null) {
                    throw new BadCredentialsException("无效的API Key");
                }
                if (!EsConstants.COMMON_YES.equals(apiKeyEntity.getStatus())) {
                    throw new BadCredentialsException("API Key已禁用");
                }
                if (apiKeyEntity.getExpireTime() != null &&
                        DateUtil.genDate(apiKeyEntity.getExpireTime()).before(new Date())) {
                    throw new BadCredentialsException("API Key已过期");
                }

                ApiKeyAuthToken result = new ApiKeyAuthToken(
                        apiKeyEntity,
                        AuthorityUtils.createAuthorityList("ROLE_API_KEY")
                );
                result.setDetails(authToken.getDetails());
                log.debug("API Key认证成功，clientId: {}, jiacn: {}, keyName: {}",
                        apiKeyEntity.getClientId(), apiKeyEntity.getJiacn(), apiKeyEntity.getKeyName());
                return result;
            }

            @Override
            public boolean supports(Class<?> authentication) {
                return ApiKeyAuthToken.class.isAssignableFrom(authentication);
            }
        };
    }
}