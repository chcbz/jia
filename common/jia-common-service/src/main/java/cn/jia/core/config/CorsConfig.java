package cn.jia.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${cors.enabled:true}")
public class CorsConfig {
    @Value("${cors.allowed.origin.patterns:}")
    private String[] allowedOriginPatterns;
    @Value("${cors.allowed.methods:GET,POST,PUT,DELETE,OPTIONS}")
    private String[] allowedMethods;
    @Value("${cors.allowed.headers:Authorization,Content-Type,X-API-Key}")
    private String[] allowedHeaders;

    CorsConfiguration buildConfig() {
        List<String> origins = nonblank(allowedOriginPatterns);
        if (origins.contains("*")) {
            throw new IllegalStateException("Credentialed CORS cannot allow wildcard origins");
        }
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowedOriginPatterns(origins);
        corsConfiguration.setAllowedHeaders(nonblank(allowedHeaders));
        corsConfiguration.setAllowedMethods(nonblank(allowedMethods));
        corsConfiguration.setAllowCredentials(true);
        return corsConfiguration;
    }

    private static List<String> nonblank(String[] values) {
        return Arrays.stream(values == null ? new String[0] : values)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterFilterRegistrationBean() {
        FilterRegistrationBean<CorsFilter> registrationBean = new FilterRegistrationBean<>();
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildConfig());
        registrationBean.setFilter(new CorsFilter(source));
        registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registrationBean;
    }
}
