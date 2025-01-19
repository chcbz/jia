package cn.jia.oauth.config;

import cn.jia.core.config.SpringContextHolder;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.util.CollectionUtil;
import cn.jia.core.util.JsonUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
import org.springframework.security.web.SecurityFilterChain;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@EnableWebSecurity
@Configuration(proxyBeanMethods = false)
public class DefaultSecurityConfig {
    @Bean
    @ConfigurationProperties(prefix = "oauth.permit")
    public OauthPermitProperties oauthPermitProperties() {
        return new OauthPermitProperties();
    }

    @Getter
    @Setter
    @ToString
    static class OauthPermitProperties {
        private List<String> uris = new ArrayList<>();
    }

    @Bean
    @Order(2)
    @DependsOn("oauthPermitProperties")
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests((authorize) -> {
                    List<String> ignoreUris = SpringContextHolder.getBean(OauthPermitProperties.class).getUris();
                    if (CollectionUtil.isNotNullOrEmpty(ignoreUris)) {
                        authorize.requestMatchers(ignoreUris.toArray(new String[0])).permitAll();
                    }
                    authorize.requestMatchers("/oauth/**").permitAll()
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
                            response.setHeader("Access-Control-Allow-Origin", "*");
                            PrintWriter out = response.getWriter();
                            out.print(JsonUtil.toJson(result));
                        }, matcher -> MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(matcher.getContentType()))
                )
//                .httpBasic(Customizer.withDefaults())
//				// Form login handles the redirect to the login page from the
//				// authorization server filter chain
//                .formLogin(Customizer.withDefaults())
//                .oauth2Login((login) -> {
//                    login.failureHandler(new Oauth2FailureHandler());
//                })
                .formLogin(v -> v.loginPage("/oauth/login"))
        ;
        return http.build();
    }
}