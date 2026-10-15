package cn.jia.core.config;

import cn.jia.core.entity.JsonRequest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.lang.reflect.Type;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Strict decoding applies only to explicit CYF request types, not OAuth/provider DTOs. */
@Configuration
public class ApiEnvelopeJsonConfig implements WebMvcConfigurer {
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : List.copyOf(converters)) {
            if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
                converters.add(0, new RequestConverter(strictMapper(jackson.getObjectMapper())));
                return;
            }
        }
    }

    static ObjectMapper strictMapper(ObjectMapper original) {
        ObjectMapper mapper = original.copy()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        for (CoercionInputShape shape : List.of(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean)) {
            mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        }
        return mapper;
    }

    private static final class RequestConverter extends MappingJackson2HttpMessageConverter {
        RequestConverter(ObjectMapper mapper) { super(mapper); }
        @Override
        public boolean canRead(Class<?> type, MediaType mediaType) {
            return type == JsonRequest.class && super.canRead(type, mediaType);
        }
        @Override
        public boolean canRead(Type type, Class<?> context, MediaType mediaType) {
            JavaType resolved = getJavaType(type, context);
            return resolved.getRawClass() == JsonRequest.class && super.canRead(type, context, mediaType);
        }
        @Override
        public boolean canWrite(Class<?> type, MediaType mediaType) { return false; }
        @Override
        public boolean canWrite(Type type, Class<?> clazz, MediaType mediaType) { return false; }
    }
}
