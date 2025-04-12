package cn.jia.base.config;

import cn.jia.core.interceptor.HttpRequestLogInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @author chc
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${http.request.log.enabled:true}")
public class HttpRequestLogConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HttpRequestLogInterceptor())
                .addPathPatterns("/**")          // 拦截所有路径
                .excludePathPatterns(            // 排除路径（按需配置）
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/actuator/**"
                );
    }
}