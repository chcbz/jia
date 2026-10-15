package cn.jia.core.config;

import cn.jia.core.entity.JsonRequest;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Strict decoding applies only to explicit CYF request types, not OAuth/provider DTOs. */
@Configuration
public class ApiEnvelopeJsonConfig implements WebMvcConfigurer {
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : List.copyOf(converters)) {
            if (converter instanceof JacksonJsonHttpMessageConverter jackson) {
                converters.add(0, new RequestConverter(strictMapper(jackson.getMapper())));
                return;
            }
        }
    }

    static JsonMapper strictMapper(JsonMapper original) {
        return original.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .withCoercionConfig(LogicalType.Textual, config -> {
                    for (CoercionInputShape shape : List.of(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean)) {
                        config.setCoercion(shape, CoercionAction.Fail);
                    }
                }).build();
    }

    private static final class RequestConverter extends JacksonJsonHttpMessageConverter {
        RequestConverter(JsonMapper mapper) { super(mapper); }
        @Override
        public boolean canRead(Class<?> type, MediaType mediaType) {
            return type == JsonRequest.class && super.canRead(type, mediaType);
        }
        @Override
        public boolean canRead(ResolvableType type, MediaType mediaType) {
            return type.resolve() == JsonRequest.class && super.canRead(type, mediaType);
        }
        @Override
        public boolean canWrite(Class<?> type, MediaType mediaType) { return false; }
        @Override
        public boolean canWrite(ResolvableType type, Class<?> clazz, MediaType mediaType) { return false; }
    }
}
