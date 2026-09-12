package cn.jia.core.deadline;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.Locale;

/** Registers deadline context after request-id correlation. This first releasable slice is intentionally shadow-only. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "jia.performance.deadline", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RequestDeadlineConfig {
    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 3;

    @Bean
    public FilterRegistrationBean<RequestDeadlineFilter> requestDeadlineFilter(
            @Value("${jia.performance.deadline.mode:shadow}") String mode,
            @Value("${jia.performance.deadline.budget-ms:3000}") long budgetMillis) {
        String normalizedMode = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (!RequestDeadlineFilter.SHADOW_MODE.equals(normalizedMode)) {
            throw new IllegalArgumentException(
                    "jia.performance.deadline.mode must remain shadow in the PERF-A03 first release slice");
        }
        FilterRegistrationBean<RequestDeadlineFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestDeadlineFilter(budgetMillis));
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(FILTER_ORDER);
        return registration;
    }
}
