package cn.jia.base.config;

import cn.jia.base.filter.UriAccessLogFilter;
import cn.jia.base.service.LogService;
import cn.jia.core.audit.AuditDispatcher;
import cn.jia.core.audit.BoundedAuditDispatcher;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${jia.log.enabled:true}")
public class UriAccessLogConfig {
    static final String AUDIT_DISPATCHER_BEAN = "uriAccessAuditDispatcher";

    @Bean(name = AUDIT_DISPATCHER_BEAN, destroyMethod = "close")
    public BoundedAuditDispatcher uriAccessAuditDispatcher(
            @Value("${jia.log.async.queue-capacity:1024}") int queueCapacity,
            @Value("${jia.log.async.retry-initial-ms:100}") long retryInitialMillis,
            @Value("${jia.log.async.retry-max-ms:5000}") long retryMaxMillis,
            @Value("${jia.log.async.shutdown-wait-ms:5000}") long shutdownWaitMillis) {
        return new BoundedAuditDispatcher(queueCapacity, Duration.ofMillis(retryInitialMillis),
                Duration.ofMillis(retryMaxMillis), Duration.ofMillis(shutdownWaitMillis),
                "uri-access-audit-writer");
    }

    @Bean
    public FilterRegistrationBean<UriAccessLogFilter> uriAccessLogFilter(
            LogService logService,
            @Qualifier(AUDIT_DISPATCHER_BEAN) AuditDispatcher auditDispatcher) {
        FilterRegistrationBean<UriAccessLogFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new UriAccessLogFilter(logService, auditDispatcher));
        registrationBean.addUrlPatterns("/*");
        registrationBean.setDispatcherTypes(DispatcherType.REQUEST);
        registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        registrationBean.addInitParameter("exclusions", "*.js,*.gif,*.jpg,*.bmp,*.png,*.css,*.ico,/druid/*");
        return registrationBean;
    }
}
