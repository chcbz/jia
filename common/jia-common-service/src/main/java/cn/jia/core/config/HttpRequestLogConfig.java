package cn.jia.core.config;

import cn.jia.core.interceptor.HttpRequestLogInterceptor;
import org.springframework.beans.factory.annotation.Value;
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
    private final long slowThresholdMillis;

    public HttpRequestLogConfig(@Value("${http.request.log.slow-threshold-ms:1000}") long slowThresholdMillis) {
        this.slowThresholdMillis = Math.max(0L, slowThresholdMillis);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HttpRequestLogInterceptor(slowThresholdMillis))
                .addPathPatterns("/**")          // 拦截所有路径
                .excludePathPatterns(            // 排除路径（按需配置）
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/actuator/**"
                );
    }
}
