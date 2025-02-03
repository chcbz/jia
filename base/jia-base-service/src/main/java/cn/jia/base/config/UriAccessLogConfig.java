package cn.jia.base.config;

import cn.jia.base.filter.UriAccessLogFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${jia.log.enabled:true}")
public class UriAccessLogConfig {
    @Bean
    public FilterRegistrationBean<UriAccessLogFilter> uriAccessLogFilter() {
        FilterRegistrationBean<UriAccessLogFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new UriAccessLogFilter()); // 这里是你自定义的 Filter
        registrationBean.addUrlPatterns("/*"); // 过滤 /api/* 路径的请求
        registrationBean.addInitParameter("exclusions", "*.js,*.gif,*.jpg,*.bmp,*.png,*.css,*.ico,/druid/*"); // 排除静态资源
        return registrationBean;
    }
}
