package cn.jia.user.config;

import cn.jia.core.common.EsConstants;
import cn.jia.core.config.SpringContextHolder;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.util.CollectionUtil;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.PasswordUtil;
import cn.jia.sms.common.SmsConstants;
import cn.jia.sms.entity.SmsCodeEntity;
import cn.jia.sms.service.SmsService;
import cn.jia.user.entity.CustomUserDetails;
import cn.jia.user.entity.PermsEntity;
import cn.jia.user.entity.PermsVO;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.PermsService;
import cn.jia.user.service.UserService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@EnableWebSecurity
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(HttpSecurity.class)
public class DefaultSecurityConfig {
    @Autowired
    private UserService userService;
    @Autowired
    private PermsService permsService;
    @Autowired(required = false)
    private SmsService smsService;

    @Bean
    @ConfigurationProperties(prefix = "user.permit")
    public UserPermitProperties userPermitProperties() {
        return new UserPermitProperties();
    }

    @Getter
    @Setter
    @ToString
    static class UserPermitProperties {
        private List<String> ignoreUris = new ArrayList<>();
    }

    @Bean
    @Order(100)
    @DependsOn("userPermitProperties")
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) {
        http.authorizeHttpRequests((authorize) -> {
                    List<String> ignoreUris = SpringContextHolder.getBean(UserPermitProperties.class).getIgnoreUris();
                    if (CollectionUtil.isNotNullOrEmpty(ignoreUris)) {
                        authorize.requestMatchers(ignoreUris.toArray(new String[0])).permitAll();
                    }
                    authorize.requestMatchers("/login/**", "/oauth/**", "/favicon.ico").permitAll()
                            .anyRequest().authenticated();
                })
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptionHandling ->
                        exceptionHandling.defaultAuthenticationEntryPointFor((request, response, authException) -> {
                            log.warn(authException.getMessage(), authException);
                            JsonResult<Object> result = new JsonResult<>();
                            result.setMsg(authException.getMessage());
                            result.setCode(EsErrorConstants.UNAUTHORIZED.getCode());
                            result.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.toString());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            PrintWriter out = response.getWriter();
                            out.print(JsonUtil.toJson(result));
                        }, matcher -> MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(matcher.getContentType()))
                )
//                .httpBasic(Customizer.withDefaults())
                .formLogin(v -> v.loginPage("/login/index.html").loginProcessingUrl("/login")
                        .successHandler(authenticationSuccessHandler()))
        ;
        return http.build();
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
                List<PermsEntity> authList = permsService.findByUserId(user.getId());
                if (CollectionUtil.isNotNullOrEmpty(authList)) {
                    PermsVO actionQueryVO = new PermsVO();
                    actionQueryVO.setIdList(authList.stream().map(PermsEntity::getId).collect(Collectors.toList()));
                    List<PermsEntity> list = permsService.findList(actionQueryVO);
                    for (PermsEntity p : list) {
                        if (EsConstants.PERMS_STATUS_ENABLE.equals(p.getStatus())) {
                            GrantedAuthority grantedAuthority =
                                    new SimpleGrantedAuthority(p.getModule() + "-" + p.getFunc());
                            grantedAuthorities.add(grantedAuthority);
                        }
                    }
                    //设置登录用户所属clientId
//					redisTemplate.opsForValue().set("clientId_" + username, org.getClientId());
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

                return new CustomUserDetails(user.getJiacn(), username, password, grantedAuthorities);
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

    private AuthenticationSuccessHandler authenticationSuccessHandler() {
        return new SavedRequestAwareAuthenticationSuccessHandler() {
            @Override
            public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                                Authentication authentication) throws ServletException, IOException {
                if (request.getParameter("redirect_uri") != null) {
                    super.setTargetUrlParameter("redirect_uri");
                }
                String username = authentication.getName();
                UserEntity user;
                if (username.startsWith("wx-")) { //微信登录
                    user = userService.findByOpenid(username.substring(3));
                } else if (username.startsWith("mb-")) {
                    user = userService.findByPhone(username.substring(3));
                } else {
                    user = userService.findByUsername(username);
                }
                EsContext context = EsContextHolder.getContext();
                context.setUsername(username);
                context.setJiacn(user.getJiacn());
                Cookie cookie = EsContextHolder.genCookie();
                response.addCookie(cookie);
                super.onAuthenticationSuccess(request, response, authentication);
            }
        };
    }
}