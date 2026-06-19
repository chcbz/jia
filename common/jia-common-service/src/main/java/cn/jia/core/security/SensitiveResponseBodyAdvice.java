package cn.jia.core.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@Slf4j
@ControllerAdvice
@EnableConfigurationProperties(SensitiveResponseProperties.class)
public class SensitiveResponseBodyAdvice implements ResponseBodyAdvice<Object> {
    private final SensitiveResponseProperties properties;

    public SensitiveResponseBodyAdvice(SensitiveResponseProperties properties) {
        this.properties = properties == null ? new SensitiveResponseProperties() : properties;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || !properties.isEnabled() || isAllowed(returnType, request) || !isJson(selectedContentType)
                || body instanceof CharSequence || body instanceof byte[]) {
            return body;
        }
        if (properties.isAuditEnabled() && properties.isAuditOnly()) {
            log.debug("Sensitive response sanitizer is running in audit-only mode");
        }
        return SensitiveDataSanitizer.sanitize(body, properties.toSanitizeConfig());
    }

    private boolean isAllowed(MethodParameter returnType, ServerHttpRequest request) {
        if (returnType != null && returnType.hasMethodAnnotation(AllowSensitiveOutput.class)) {
            return true;
        }
        if (request == null || properties.getAllowPaths().isEmpty()) {
            return false;
        }
        String path = request.getURI().getPath();
        return properties.getAllowPaths().stream().anyMatch(path::startsWith);
    }

    private boolean isJson(MediaType selectedContentType) {
        if (selectedContentType == null) {
            return true;
        }
        return MediaType.APPLICATION_JSON.includes(selectedContentType)
                || selectedContentType.getSubtype().contains("json");
    }
}
