package cn.jia.core.config;

import cn.jia.core.entity.JsonRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Only typed CYF requests use this parser; provider callbacks never opt in. */
@ControllerAdvice
public final class ApiRequestEnvelopeAdvice extends RequestBodyAdviceAdapter {
    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return parameter.getParameterType() == JsonRequest.class || parameter.hasParameterAnnotation(cn.jia.core.annotation.ApiJsonData.class);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type type,
            Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        byte[] bytes = input.getBody().readAllBytes();
        var decoded = ApiJsonInput.decode(bytes, input);
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw invalid("PROTOCOL_REQUEST_CONTEXT_REQUIRED", input);
        }
        ApiJsonInput.bind(attributes.getRequest(), decoded, input);
        byte[] selected = parameter.hasParameterAnnotation(cn.jia.core.annotation.ApiJsonData.class) ? decoded.data() : bytes;
        // Preserve the original bytes. In particular, do not rewrite nested signed objects.
        return new HttpInputMessage() {
            public HttpHeaders getHeaders() { return input.getHeaders(); }
            public InputStream getBody() { return new ByteArrayInputStream(selected); }
        };
    }

    @Override
    public Object handleEmptyBody(Object body, HttpInputMessage input, MethodParameter parameter, Type type,
            Class<? extends HttpMessageConverter<?>> converter) {
        throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
    }

    private HttpMessageNotReadableException invalid(String code, HttpInputMessage input) {
        return new HttpMessageNotReadableException(code, input);
    }
}
