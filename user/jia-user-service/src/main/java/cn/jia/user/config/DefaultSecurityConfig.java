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
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
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
    @Autowired
    private AccountSecurityService accountSecurityService;
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
                    authorize.requestMatchers("/actuator", "/actuator/**",
                                    "/login/**", "/oauth/**", "/favicon.ico").permitAll()
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
        return username -> {
            try {
                UserEntity user;
                if (username.startsWith("wx-")) {
                    user = userService.findByOpenid(username.substring(3));
                } else if (username.startsWith("mb-")) {
                    user = userService.findByPhone(username.substring(3));
                } else {
                    user = userService.findByUsername(username);
                }

                AccountSecuritySnapshot account = currentAuthenticatableAccount(user);

                Collection<GrantedAuthority> grantedAuthorities = new ArrayList<>();
                List<PermsEntity> authList = permsService.findByUserId(account.userId());
                if (CollectionUtil.isNotNullOrEmpty(authList)) {
                    PermsVO actionQueryVO = new PermsVO();
                    actionQueryVO.setIdList(authList.stream().map(PermsEntity::getId).collect(Collectors.toList()));
                    List<PermsEntity> list = permsService.findList(actionQueryVO);
                    for (PermsEntity permission : list) {
                        if (EsConstants.PERMS_STATUS_ENABLE.equals(permission.getStatus())) {
                            grantedAuthorities.add(new SimpleGrantedAuthority(
                                    permission.getModule() + "-" + permission.getFunc()));
                        }
                    }
                }

                String password = user.getPassword();
                if (username.startsWith("wx-")) {
                    password = PasswordUtil.encode("wxpwd");
                } else if (username.startsWith("mb-")) {
                    if (smsService == null) {
                        throw authenticationFailed();
                    }
                    SmsCodeEntity code = smsService.selectSmsCodeNoUsed(username.substring(3),
                            SmsConstants.SMS_CODE_TYPE_LOGIN);
                    if (code != null) {
                        smsService.useSmsCode(code.getId());
                        password = PasswordUtil.encode(code.getSmsCode());
                    }
                }

                return new CustomUserDetails(account.userId(), account.jiacn(), account.authEpoch(),
                        username, password, grantedAuthorities);
            } catch (RuntimeException exception) {
                log.warn("Account authentication lookup failed");
                throw authenticationFailed();
            }
        };
    }

    private AccountSecuritySnapshot currentAuthenticatableAccount(UserEntity user) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            throw authenticationFailed();
        }
        AccountSecuritySnapshot account = accountSecurityService.findByUserId(user.getId())
                .filter(AccountSecuritySnapshot::isAuthenticatable)
                .filter(snapshot -> snapshot.jiacn().equals(user.getJiacn()))
                .orElseThrow(DefaultSecurityConfig::authenticationFailed);
        return account;
    }

    private static UsernameNotFoundException authenticationFailed() {
        return new UsernameNotFoundException("Authentication failed");
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
                if (!(authentication.getPrincipal() instanceof CustomUserDetails userDetails)) {
                    throw new ServletException("Authenticated principal is not a user account");
                }
                EsContext context = EsContextHolder.getContext();
                context.setUsername(userDetails.getUsername());
                context.setJiacn(userDetails.getJiacn());
                Cookie cookie = EsContextHolder.genCookie();
                response.addCookie(cookie);
                super.onAuthenticationSuccess(request, response, authentication);
            }
        };
    }
}
