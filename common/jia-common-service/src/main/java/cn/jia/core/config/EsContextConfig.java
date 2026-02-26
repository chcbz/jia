package cn.jia.core.config;

import cn.jia.core.filter.EsContextFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 处理AJAX请求跨域的问题
 *
 * @author chcbz
 * @since 2018年4月24日 上午11:57:33
 */
@Configuration(proxyBeanMethods = false)
public class EsContextConfig {
    /**
     * 跨域过滤器
     *
     * @return 跨域过滤器
     */
    @Bean
    public FilterRegistrationBean<EsContextFilter> esContextFilter() {
        FilterRegistrationBean<EsContextFilter> filterRegistrationBean = new FilterRegistrationBean<>();
        filterRegistrationBean.setFilter(new EsContextFilter());
        filterRegistrationBean.addUrlPatterns("/*");
        filterRegistrationBean.setOrder(1);
        return filterRegistrationBean;
    }
}
