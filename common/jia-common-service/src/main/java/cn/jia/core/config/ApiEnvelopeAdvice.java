package cn.jia.core.config;

import cn.jia.core.entity.JsonResult;
import cn.jia.core.filter.RequestIdFilter;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Enriches explicitly constructed envelopes; never guesses how to wrap an arbitrary body. */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class ApiEnvelopeAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter parameter, MediaType mediaType,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof JsonResult<?> result) || !(request instanceof ServletServerHttpRequest servlet)) return body;
        String requestId = RequestIdFilter.current(servlet.getServletRequest());
        if (result.getRequestId() != null && !requestId.equals(result.getRequestId())) {
            throw new IllegalStateException("PROTOCOL_RESPONSE_REQUEST_ID_MISMATCH");
        }
        result.setRequestId(requestId);
        response.getHeaders().set(RequestIdFilter.HEADER_NAME, requestId);
        return result;
    }
}
