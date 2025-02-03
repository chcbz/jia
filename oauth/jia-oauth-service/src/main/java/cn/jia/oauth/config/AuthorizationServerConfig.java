package cn.jia.oauth.config;

import cn.jia.core.entity.JsonResult;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.util.JsonUtil;
import cn.jia.oauth.entity.OauthClientEntity;
import cn.jia.oauth.service.ClientService;
import cn.jia.oauth.vomapper.RegisteredClientMapper;
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
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.web.SecurityFilterChain;

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
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        //针对 Spring Authorization Server 最佳实践配置
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                .oidc(Customizer.withDefaults())    // Enable OpenID Connect 1.0
                .authorizationEndpoint(endpointConfigurer ->
                        endpointConfigurer.consentPage("/oauth/confirm_access"));

        http.exceptionHandling(configurer ->
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
//                .exceptionHandling((configurer) ->
//                        configurer.defaultAuthenticationEntryPointFor(
//                                new LoginUrlAuthenticationEntryPoint("/oauth/login"),
//                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
//                        )
//                )
                // Accept access tokens for User Info and/or Client Registration
                .oauth2ResourceServer((resourceServer) -> resourceServer
                        .jwt(Customizer.withDefaults()));

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
                return RegisteredClientMapper.INSTANCE.toVO(clientService.get(id));
            }

            @Override
            public RegisteredClient findByClientId(String clientId) {
                OauthClientEntity oauthClientEntity = new OauthClientEntity();
                oauthClientEntity.setClientId(clientId);
                return RegisteredClientMapper.INSTANCE.toVO(clientService.findOne(oauthClientEntity));
            }
        };
    }

//    /**
//     * 默认发放令牌
//     *
//     * @return 令牌
//     */
//    @Bean
//    public JWKSource<SecurityContext> jwkSource() {
//        KeyPair keyPair = generateRsaKey();
//        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
//        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
//        RSAKey rsaKey = new RSAKey.Builder(publicKey)
//                .privateKey(privateKey)
//                .keyID(UUID.randomUUID().toString())
//                .build();
//        JWKSet jwkSet = new JWKSet(rsaKey);
//        return new ImmutableJWKSet<>(jwkSet);
//    }
//
//    private static KeyPair generateRsaKey() {
//        KeyPair keyPair;
//        try {
//            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
//            keyPairGenerator.initialize(2048);
//            keyPair = keyPairGenerator.generateKeyPair();
//        } catch (Exception ex) {
//            throw new IllegalStateException(ex);
//        }
//        return keyPair;
//    }
//
//    @Bean
//    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
//        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
//    }
//
//    @Bean
//    public AuthorizationServerSettings authorizationServerSettings() {
//        return AuthorizationServerSettings.builder().build();
//    }
}