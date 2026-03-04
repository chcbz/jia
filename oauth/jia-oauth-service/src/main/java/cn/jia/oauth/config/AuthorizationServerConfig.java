package cn.jia.oauth.config;

import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.util.JsonUtil;
import cn.jia.oauth.entity.OauthClientEntity;
import cn.jia.oauth.service.ClientService;
import cn.jia.oauth.vomapper.RegisteredClientMapper;
import cn.jia.user.entity.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OAuth2AuthorizationServerConfiguration.class)
public class AuthorizationServerConfig {
    @Autowired
    private ClientService clientService;

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) {
        http.oauth2AuthorizationServer((authorizationServer) -> {
                    http.securityMatcher(authorizationServer.getEndpointsMatcher());
                    authorizationServer
                            .oidc(Customizer.withDefaults())    // Enable OpenID Connect 1.0
                            .authorizationEndpoint(endpointConfigurer ->
                                    endpointConfigurer.consentPage("/oauth/confirm_access")
                            )
                    ;
                })
                .authorizeHttpRequests((authorize) -> authorize.anyRequest().authenticated())
                .exceptionHandling(configurer ->
                        configurer.defaultAuthenticationEntryPointFor((request, response, authException) -> {
                            log.warn(authException.getMessage(), authException);
                            JsonResult<Object> result = new JsonResult<>();
                            result.setMsg(authException.getMessage());
                            result.setCode(EsErrorConstants.UNAUTHORIZED.getCode());
                            result.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.toString());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setHeader("Access-Control-Allow-Origin", "*");
                            PrintWriter out = response.getWriter();
                            out.print(JsonUtil.toJson(result));
                        }, matcher -> MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(matcher.getContentType()))
                )
                .exceptionHandling((configurer) ->
                        configurer.defaultAuthenticationEntryPointFor(
                                new CustomAuthenticationEntryPoint("/login/index.html"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                        )
                )
        ;
        return http.build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository() {
        return new RegisteredClientRepository() {
            @Override
            public void save(RegisteredClient registeredClient) {
                clientService.upsert(RegisteredClientMapper.INSTANCE.toEntity(registeredClient));
            }

            @Override
            public RegisteredClient findById(String id) {
                OauthClientEntity clientEntity = clientService.get(id);
                if (clientEntity == null) {
                    return null;
                }
                EsContext context = EsContextHolder.getContext();
                context.setClientId(clientEntity.getClientId());
                context.setAppcn(clientEntity.getAppcn());
                return RegisteredClientMapper.INSTANCE.toVO(clientEntity);
            }

            @Override
            public RegisteredClient findByClientId(String clientId) {
                OauthClientEntity oauthClientEntity = new OauthClientEntity();
                oauthClientEntity.setClientId(clientId);
                OauthClientEntity clientEntity = clientService.findOne(oauthClientEntity);
                if (clientEntity == null) {
                    return null;
                }
                EsContext context = EsContextHolder.getContext();
                context.setClientId(clientEntity.getClientId());
                context.setAppcn(clientEntity.getAppcn());
                return RegisteredClientMapper.INSTANCE.toVO(clientEntity);
            }
        };
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            String clientId = context.getRegisteredClient().getClientId();
            context.getClaims().claim("client_id", clientId);
            if (context.getPrincipal() != null) {
                // 从CustomUserDetails中获取jiacn属性
                if (context.getPrincipal().getPrincipal() instanceof CustomUserDetails customUserDetails) {
                    if (customUserDetails.getJiacn() != null) {
                        context.getClaims().claim("jiacn", customUserDetails.getJiacn());
                        context.getClaims().claim("username", customUserDetails.getUsername());
                    }
                }
            }
        };
    }

    static class CustomAuthenticationEntryPoint extends LoginUrlAuthenticationEntryPoint {
        public CustomAuthenticationEntryPoint(String loginFormUrl) {
            super(loginFormUrl);
        }

        @Override
        protected String buildRedirectUrlToLoginPage(
                HttpServletRequest request,
                HttpServletResponse response,
                AuthenticationException authException) {
            return super.buildRedirectUrlToLoginPage(request, response, authException);
//            // 构建基础登录URL
//            String redirectUrl = super.buildRedirectUrlToLoginPage(
//                    request, response, authException);
//
//            // 保留所有原始查询参数
//            String queryString = request.getQueryString();
//            if (queryString != null && !queryString.isEmpty()) {
//                redirectUrl += "?" + queryString;
//            }
//
//            return redirectUrl;
        }
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
    }
}