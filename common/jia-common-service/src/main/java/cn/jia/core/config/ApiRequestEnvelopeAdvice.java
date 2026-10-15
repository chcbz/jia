package cn.jia.core.config;

import cn.jia.core.entity.JsonRequest;
import cn.jia.core.filter.RequestIdFilter;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;
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
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return parameter.getParameterType() == JsonRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type type,
            Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        byte[] bytes = input.getBody().readAllBytes();
        JsonNode root;
        try {
            root = mapper.readTree(bytes);
        } catch (RuntimeException exception) {
            throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
        }
        Set<String> fields = new HashSet<>();
        if (root != null && root.isObject()) fields.addAll(root.propertyNames());
        if (!fields.equals(Set.of("protocolVersion", "requestId", "data"))) throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
        if (!root.get("protocolVersion").isIntegralNumber() || !root.get("protocolVersion").canConvertToInt()
                || root.get("protocolVersion").intValue() != 1) throw invalid("PROTOCOL_UNSUPPORTED_VERSION", input);
        JsonNode id = root.get("requestId");
        if (!id.isTextual() || !RequestIdFilter.isValid(id.textValue())) throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw invalid("PROTOCOL_REQUEST_CONTEXT_REQUIRED", input);
        }
        var request = attributes.getRequest();
        String header = request.getHeader(RequestIdFilter.HEADER_NAME);
        if (header != null && !header.equals(id.textValue())) throw invalid("PROTOCOL_REQUEST_ID_MISMATCH", input);
        RequestIdFilter.accept(request, id.textValue());
        // Preserve the original bytes. In particular, do not rewrite nested signed objects.
        return new HttpInputMessage() {
            public HttpHeaders getHeaders() { return input.getHeaders(); }
            public InputStream getBody() { return new ByteArrayInputStream(bytes); }
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
