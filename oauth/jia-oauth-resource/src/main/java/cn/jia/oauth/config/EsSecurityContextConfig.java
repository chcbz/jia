package cn.jia.oauth.config;

import cn.jia.oauth.filter.EsSecurityContextFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/**
 * 安全上下文配置类，用于配置和注册安全相关的过滤器
 *
 * @author chcbz
 * @since 2018年4月24日 上午11:57:33
 */
@Configuration(proxyBeanMethods = false)
public class EsSecurityContextConfig {
    /**
     * 创建并配置安全上下文过滤器的注册Bean
     * 该过滤器用于处理请求中的安全上下文信息
     *
     * @return 配置好的过滤器注册Bean
     */
    @Bean
    public FilterRegistrationBean<EsSecurityContextFilter> esSecurityContextFilter(
            @Value("${cyf.security.legacy-single-tenant-id:0}") String legacySingleTenantId) {
        FilterRegistrationBean<EsSecurityContextFilter> filterRegistrationBean = new FilterRegistrationBean<>();
        filterRegistrationBean.setFilter(new EsSecurityContextFilter(legacySingleTenantId));
        filterRegistrationBean.addUrlPatterns("/*");
        filterRegistrationBean.setOrder(2);
        return filterRegistrationBean;
    }
}
