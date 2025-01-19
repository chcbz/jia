package cn.jia.oauth.config;

import cn.jia.core.common.EsConstants;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.util.CollectionUtil;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.PasswordUtil;
import cn.jia.oauth.entity.OauthActionEntity;
import cn.jia.oauth.entity.OauthActionVO;
import cn.jia.oauth.entity.OauthClientEntity;
import cn.jia.oauth.service.ActionService;
import cn.jia.oauth.service.ClientService;
import cn.jia.oauth.vomapper.RegisteredClientMapper;
import cn.jia.sms.common.SmsConstants;
import cn.jia.sms.entity.SmsCodeEntity;
import cn.jia.sms.service.SmsService;
import cn.jia.user.entity.AuthEntity;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.AuthService;
import cn.jia.user.service.UserService;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OAuth2AuthorizationServerConfiguration.class)
public class AuthorizationServerConfig {
    @Autowired
    private ClientService clientService;
    @Autowired
    private ActionService actionService;
    @Autowired(required = false)
    private UserService userService;
    @Autowired(required = false)
    private AuthService authService;
    @Autowired(required = false)
    private SmsService smsService;

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

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return PasswordUtil.encode(rawPassword);
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return PasswordUtil.validatePassword(rawPassword, encodedPassword);
            }
        };
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return new UserDetailsService() {
            /**
             * 根据用户名获取登录用户信息
             *
             * @param username 用户名
             * @return 用户详情
             * @throws UsernameNotFoundException 异常
             */
            @Override
            public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
                UserEntity user;
                if (username.startsWith("wx-")) { //微信登录
                    user = userService.findByOpenid(username.substring(3));
                } else if (username.startsWith("mb-")) {
                    user = userService.findByPhone(username.substring(3));
                } else {
                    user = userService.findByUsername(username);
                }

                if (user == null) {
                    throw new UsernameNotFoundException("用户名：" + username + "不存在！");
                }

                // 获取用户的所有权限并且SpringSecurity需要的集合
                Collection<GrantedAuthority> grantedAuthorities = new ArrayList<>();
                List<AuthEntity> authList = authService.findByUserId(user.getId());
                if (CollectionUtil.isNotNullOrEmpty(authList)) {
                    OauthActionVO actionQueryVO = new OauthActionVO();
                    actionQueryVO.setIdList(authList.stream().map(AuthEntity::getPermsId).collect(Collectors.toList()));
                    List<OauthActionEntity> list = actionService.findList(actionQueryVO);
                    for (OauthActionEntity p : list) {
                        if (EsConstants.PERMS_STATUS_ENABLE.equals(p.getStatus())) {
                            GrantedAuthority grantedAuthority =
                                    new SimpleGrantedAuthority(p.getModule() + "-" + p.getFunc());
                            grantedAuthorities.add(grantedAuthority);
                        }
                    }
                    //设置登录用户所属clientId
//                    redisTemplate.opsForValue().set("clientId_" + username, org.getClientId());
                }

                String password = user.getPassword();
                //微信登录的话采用特定密码进行验证
                if (username.startsWith("wx-")) {
                    password = PasswordUtil.encode("wxpwd");
                } else if (username.startsWith("mb-")) {
                    SmsCodeEntity code = smsService.selectSmsCodeNoUsed(username.substring(3),
                            SmsConstants.SMS_CODE_TYPE_LOGIN);
                    if (code != null) {
                        smsService.useSmsCode(code.getId());
                        password = PasswordUtil.encode(code.getSmsCode());
                    }
                }

                return new User(username, password, grantedAuthorities);
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

//    @Bean
//    public AuthorizationServerSettings authorizationServerSettings() {
//        return AuthorizationServerSettings.builder().build();
//    }
}