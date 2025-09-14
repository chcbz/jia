package cn.jia.oauth.config;

import cn.jia.core.config.SpringContextHolder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;

/**
 * OAuth2 资源服务器配置类
 * 用于配置资源服务器的安全策略，包括JWT验证、权限控制等
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OAuth2ResourceServerConfigurer.class)
public class ResourceServerConfig {

    @Bean
    @ConfigurationProperties(prefix = "oauth.resource")
    public OauthResourceProperties oauthResourceProperties() {
        return new OauthResourceProperties();
    }

    @Getter
    @Setter
    @ToString
    static class OauthResourceProperties {
        private List<String> uris = new ArrayList<>();
    }

    /**
     * 配置资源服务器的安全过滤链
     *
     * @param http HttpSecurity对象，用于配置安全策略
     * @return SecurityFilterChain 安全过滤链
     * @throws Exception 配置过程中可能抛出的异常
     */
    @Bean
    @Order(2)
    public SecurityFilterChain resourceServerSecurityFilterChain(HttpSecurity http) throws Exception {
        List<String> resourceUris = SpringContextHolder.getBean(OauthResourceProperties.class).getUris();
        http
                .securityMatcher(resourceUris.toArray(new String[0]))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .csrf(AbstractHttpConfigurer::disable)
        ;
        return http.build();
    }
}