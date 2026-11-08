package cn.jia.agent.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Keep private skill responses non-cacheable, including errors rendered by common advice.
 * Match the selected Controller rather than duplicating its URL patterns. */
@Configuration(proxyBeanMethods = false)
public class SkillApiWebConfiguration implements WebMvcConfigurer, HandlerInterceptor {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method
                && (SkillMarketplaceController.class.isAssignableFrom(method.getBeanType())
                || SkillPackageController.class.isAssignableFrom(method.getBeanType()))) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        }
        return true;
    }
}
