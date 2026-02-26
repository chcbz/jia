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

import java.util.List;

/**
 * 处理AJAX请求跨域的问题
 *
 * @author chcbz
 * @since 2018年4月24日 上午11:57:33
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${cors.enabled:true}")
public class CorsConfig {
    @Value("${cors.allowed.origin.patterns:*}")
    private String[] allowedOriginPatterns;
    @Value("${cors.allowed.methods:GET,POST,PUT,DELETE,OPTIONS}")
    private String[] allowedMethods;
    @Value("${cors.allowed.headers:*}")
    private String[] allowedHeaders;

    private CorsConfiguration buildConfig() {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowedOriginPatterns(List.of(allowedOriginPatterns));
        corsConfiguration.setAllowedHeaders(List.of(allowedHeaders));
        corsConfiguration.setAllowedMethods(List.of(allowedMethods));
        corsConfiguration.setAllowCredentials(true);
//        corsConfiguration.addExposedHeader(HttpHeaderConStant.X_TOTAL_COUNT);
        return corsConfiguration;
    }

    /**
     * 跨域过滤器
     *
     * @return 跨域过滤器
     */
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
