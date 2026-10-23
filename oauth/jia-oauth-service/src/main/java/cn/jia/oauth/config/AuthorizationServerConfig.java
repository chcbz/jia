package cn.jia.oauth.config;

import cn.jia.oauth.security.CentralSigningProperties;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.util.JsonUtil;
import cn.jia.oauth.entity.OauthClientEntity;
import cn.jia.oauth.service.ClientService;
import cn.jia.oauth.vomapper.RegisteredClientMapper;
import cn.jia.oauth.security.AccountSecurityTokenCustomizer;
import cn.jia.oauth.security.JdbcTenantAuthorizationRepository;
import cn.jia.oauth.security.TenantJdbcAuthorizationService;
import cn.jia.oauth.security.TenantAuthorizationRequestProvider;
import cn.jia.oauth.security.TenantConsentGrantProvider;
import cn.jia.oauth.security.TenantTokenGrantProvider;
import cn.jia.user.security.AccountSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.authentication.AuthenticationProvider;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OAuth2AuthorizationServerConfiguration.class)
public class AuthorizationServerConfig {
    @Autowired
    private ClientService clientService;
    @Autowired
    private AccountSecurityService accountSecurityService;

    @Autowired
    private JdbcTenantAuthorizationRepository tenantAuthorizationRepository;

    @ConditionalOnProperty(name = "cyf.oauth.authorization-server.enabled", havingValue = "true", matchIfMissing = true)
    @Bean
    @Order(0)
    public SecurityFilterChain tenantSelectionSecurityFilterChain(HttpSecurity http) {
        // This is deliberately outside the OAuth endpoints matcher: the AS chain
        // excludes endpoint CSRF checks, whereas selection is a browser mutation.
        http.securityMatcher("/oauth2/tenant-options", "/oauth2/tenant-selection")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .csrf(Customizer.withDefaults())
                .exceptionHandling(configurer -> configurer.authenticationEntryPoint(
                        new CustomAuthenticationEntryPoint("/login/index.html")));
        return http.build();
    }


    @ConditionalOnProperty(name = "cyf.oauth.authorization-server.enabled", havingValue = "true", matchIfMissing = true)
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http,
            JdbcOperations jdbc, OAuth2AuthorizationService authorizationService, RegisteredClientRepository clients) {
        // Spring Security reads the default request provider after this configurer's
        // callback to create its request prevalidator. Keep it in that list, but
        // replace it when registering providers in the runtime manager.
        http.objectPostProcessor(new ObjectPostProcessor<>() {
            @Override
            @SuppressWarnings("unchecked")
            public <O> O postProcess(O object) {
                if (object instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider request) {
                    return (O) new TenantAuthorizationRequestProvider(request, authorizationService);
                }
                return object;
            }
        });
        http.oauth2AuthorizationServer((authorizationServer) -> {
                    http.securityMatcher(authorizationServer.getEndpointsMatcher());
                    authorizationServer
                            .oidc(Customizer.withDefaults())    // Enable OpenID Connect 1.0
                            .authorizationEndpoint(endpointConfigurer -> endpointConfigurer
                                    .consentPage("/oauth2/tenant-options")
                                    .authenticationProviders(providers -> {
                                        for (int index = 0; index < providers.size(); index++) {
                                            AuthenticationProvider provider = providers.get(index);
                                            if (provider instanceof OAuth2AuthorizationConsentAuthenticationProvider) {
                                                providers.set(index, new TenantConsentGrantProvider(provider, authorizationService,
                                                        clients, tenantAuthorizationRepository, jdbc));
                                            }
                                        }
                                    }))
                            .tokenEndpoint(endpoint -> endpoint.authenticationProviders(providers -> {
                                for (int index = 0; index < providers.size(); index++) {
                                    AuthenticationProvider provider = providers.get(index);
                                    if (provider instanceof OAuth2AuthorizationCodeAuthenticationProvider
                                            || provider instanceof OAuth2RefreshTokenAuthenticationProvider) {
                                        providers.set(index, new TenantTokenGrantProvider(provider,
                                                authorizationService, tenantAuthorizationRepository, jdbc));
                                    }
                                }
                            }))
                    ;
                })
                .authorizeHttpRequests((authorize) -> authorize.anyRequest().authenticated())
                .exceptionHandling(configurer ->
                        configurer.defaultAuthenticationEntryPointFor((request, response, authException) -> {
                            log.warn(authException.getMessage(), authException);
                            JsonResult<Object> result = new JsonResult<>();
                            result.setMsg(authException.getMessage());
                            result.setCode(EsErrorConstants.UNAUTHORIZED.getCode());

                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.toString());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
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
    @ConditionalOnProperty(name = "cyf.oauth.authorization-server.enabled", havingValue = "true", matchIfMissing = true)
    public OAuth2AuthorizationService authorizationService(JdbcOperations jdbc,
                                                             RegisteredClientRepository registeredClients) {
        // Code and refresh state must survive instance restarts; a missing schema
        // fails closed instead of silently falling back to process-local memory.
        return new TenantJdbcAuthorizationService(jdbc, registeredClients);
    }

    @Bean
    @ConditionalOnProperty(name = "cyf.oauth.authorization-server.enabled", havingValue = "true", matchIfMissing = true)
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer(
            @Value("${cyf.security.legacy-single-tenant-id:0}") String canonicalTenantId) {
        return new AccountSecurityTokenCustomizer(accountSecurityService, tenantAuthorizationRepository, canonicalTenantId);
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
    @ConditionalOnMissingBean(CentralSigningProperties.class)
    @ConfigurationProperties(prefix = "cyf.oauth.signing")
    public CentralSigningProperties centralSigningProperties() {
        return new CentralSigningProperties();
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(CentralSigningProperties signing,
            @Value("${cyf.tenant.require-bound-identity:false}") boolean strictTenant,
            @Value("${cyf.oauth.authorization-server.enabled:true}") boolean enabled) {
        if (enabled && strictTenant && !signing.isEnabled()) {
            throw new IllegalStateException("A tenant-enabled authorization server requires persistent central signing");
        }
        if (signing.isEnabled()) {
            return AuthorizationServerSettings.builder().issuer(signing.validatedIssuer()).build();
        }
        return AuthorizationServerSettings.builder().build();
    }
}
