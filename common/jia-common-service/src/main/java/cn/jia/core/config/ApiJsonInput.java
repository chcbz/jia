package cn.jia.core.config;

import cn.jia.core.filter.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Explicit Q decoder for typed and raw-JSON boundaries. It preserves original data bytes. */
public final class ApiJsonInput {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public record Decoded(String requestId, byte[] data) { }
    private ApiJsonInput() { }

    public static Decoded decode(byte[] bytes, HttpInputMessage input) {
        try {
            var root = JSON.readTree(bytes);
            if (root == null || !root.isObject() || !Set.copyOf(root.propertyNames()).equals(Set.of("protocolVersion", "requestId", "data"))) {
                throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
            }
            var version = root.get("protocolVersion");
            if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1) {
                throw invalid("PROTOCOL_UNSUPPORTED_VERSION", input);
            }
            var id = root.get("requestId");
            if (!id.isString() || !RequestIdFilter.isValid(id.asString())) throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
            try (var parser = JSON.createParser(bytes)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String key = parser.currentName();
                    parser.nextToken();
                    int start = Math.toIntExact(parser.currentTokenLocation().getByteOffset());
                    parser.skipChildren();
                    parser.finishToken();
                    int end = Math.toIntExact(parser.currentLocation().getByteOffset());
                    if ("data".equals(key)) return new Decoded(id.asString(), Arrays.copyOfRange(bytes, start, end));
                }
            }
            throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
        } catch (HttpMessageNotReadableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
        }
    }

    public static void bind(HttpServletRequest request, Decoded decoded, HttpInputMessage input) {
        String header = request.getHeader(RequestIdFilter.HEADER_NAME);
        if (header != null && !header.equals(decoded.requestId())) throw invalid("PROTOCOL_REQUEST_ID_MISMATCH", input);
        RequestIdFilter.accept(request, decoded.requestId());
    }

    /** For explicitly self-owned servlet JSON readers; existing data budgets are enforced by the caller. */
    public static byte[] readData(HttpServletRequest request) throws IOException {
        var input = new ServletServerHttpRequest(request);
        var decoded = decode(request.getInputStream().readAllBytes(), input);
        bind(request, decoded, input);
        return decoded.data();
    }

    private static HttpMessageNotReadableException invalid(String code, HttpInputMessage input) {
        return new HttpMessageNotReadableException(code, input);
    }
}
