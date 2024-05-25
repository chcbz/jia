package cn.jia.oauth.config;

import cn.jia.core.common.EsConstants;
import cn.jia.core.util.CollectionUtil;
import cn.jia.core.util.PasswordUtil;
import cn.jia.oauth.entity.OauthActionEntity;
import cn.jia.oauth.entity.OauthActionVO;
import cn.jia.oauth.service.ActionService;
import cn.jia.sms.common.SmsConstants;
import cn.jia.sms.entity.SmsCodeEntity;
import cn.jia.sms.service.SmsService;
import cn.jia.user.entity.AuthEntity;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.AuthService;
import cn.jia.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@EnableWebSecurity
@Configuration(proxyBeanMethods = false)
public class DefaultSecurityConfig {
    @Autowired(required = false)
    private UserService userService;
    @Autowired(required = false)
    private AuthService authService;
    @Autowired(required = false)
    private SmsService smsService;
    @Autowired
    private ActionService actionService;

    @Value("${oauth.password.secret:secret}")
    private String passwordSecret;

    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests((authorize) -> authorize
                        .requestMatchers(new AntPathRequestMatcher("/actuator/**"),
                                new AntPathRequestMatcher("/**/*.json"),
                                new AntPathRequestMatcher("/client/**/**")).permitAll()
                        .anyRequest().authenticated()
                )
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
//                .httpBasic(Customizer.withDefaults())
//				// Form login handles the redirect to the login page from the
//				// authorization server filter chain
                .formLogin(Customizer.withDefaults())
//                .oauth2Login((login) -> {
//                    login.failureHandler(new Oauth2FailureHandler());
//                })
//                .formLogin(v -> v.loginPage(""))
        ;

        return http.build();
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

}