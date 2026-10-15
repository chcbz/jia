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

    /** Stream the envelope; only data consumes the caller's existing business-body budget.
     * Compact mode folds JSON whitespace outside strings, never string bytes or scalar tokens.
     * This overload is for bounded self-owned readers, not canonical raw snapshot readers.
     */
    public static byte[] readData(HttpServletRequest request, int dataBudget, boolean compact) throws IOException {
        if (dataBudget < 1) throw new IllegalArgumentException("Positive data budget required");
        var input = new ServletServerHttpRequest(request);
        try (var stream = new java.io.PushbackInputStream(request.getInputStream(), 1)) {
            if (nextNonWhitespace(stream) != '{') throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
            var keys = new java.util.HashSet<String>();
            String requestId = null; byte[] data = null; boolean versionValid = false;
            while (true) {
                int first = nextNonWhitespace(stream);
                if (first != '"') throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
                var keyNode = JSON.readTree(readValue(stream, first, Integer.MAX_VALUE, false, input));
                if (!keyNode.isString()) throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
                String key = keyNode.asString();
                if (!Set.of("protocolVersion", "requestId", "data").contains(key) || !keys.add(key)
                        || nextNonWhitespace(stream) != ':') throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
                byte[] value = readValue(stream, nextNonWhitespace(stream),
                        "data".equals(key) ? dataBudget : Integer.MAX_VALUE, "data".equals(key) && compact, input);
                var node = JSON.readTree(value); // validates duplicate keys and malformed/trailing tokens within each value
                if (node == null) throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
                if ("data".equals(key)) data = value;
                else if ("requestId".equals(key)) {
                    if (!node.isString() || !RequestIdFilter.isValid(node.asString())) throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
                    requestId = node.asString();
                } else {
                    if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() != 1)
                        throw invalid("PROTOCOL_UNSUPPORTED_VERSION", input);
                    versionValid = true;
                }
                int separator = nextNonWhitespace(stream);
                if (separator == '}') break;
                if (separator != ',') throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
            }
            if (!versionValid || requestId == null || data == null || keys.size() != 3 || nextNonWhitespace(stream) != -1)
                throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
            var decoded = new Decoded(requestId, data); bind(request, decoded, input); return data;
        } catch (HttpMessageNotReadableException failure) { throw failure; }
        catch (RuntimeException failure) { throw invalid("PROTOCOL_INVALID_ENVELOPE", input); }
    }
    private static boolean whitespace(int value) { return value == ' ' || value == '\t' || value == '\r' || value == '\n'; }
    private static int nextNonWhitespace(java.io.InputStream in) throws IOException {
        int value; do { value = in.read(); } while (whitespace(value)); return value;
    }
    private static byte[] readValue(java.io.PushbackInputStream in, int first, int budget,
            boolean compact, HttpInputMessage input) throws IOException {
        if (first < 0 || first == ',' || first == '}' || first == ']') throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
        var out = new java.io.ByteArrayOutputStream(Math.min(budget, 8192));
        boolean quoted = false, escaped = false, pendingSpace = false;
        boolean container = first == '{' || first == '['; boolean string = first == '"'; int depth = 0, value = first;
        while (value >= 0) {
            if (!quoted && !container && !string && (whitespace(value) || value == ',' || value == '}')) { in.unread(value); break; }
            if (compact && !quoted && whitespace(value)) { pendingSpace = true; value = in.read(); continue; }
            if (pendingSpace) { writeBudget(out, ' ', budget, input); pendingSpace = false; }
            writeBudget(out, value, budget, input);
            if (quoted) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') { quoted = false; if (string) break; }
            } else if (value == '"') quoted = true;
            else if (value == '{' || value == '[') depth++;
            else if (value == '}' || value == ']') { if (--depth == 0 && container) break; }
            value = in.read();
        }
        if (out.size() == 0 || quoted || depth != 0) throw invalid("PROTOCOL_INVALID_ENVELOPE", input);
        return out.toByteArray();
    }
    private static void writeBudget(java.io.ByteArrayOutputStream out, int value, int budget, HttpInputMessage input) {
        if (out.size() >= budget) throw invalid("PROTOCOL_INVALID_ENVELOPE", input); out.write(value);
    }

    private static HttpMessageNotReadableException invalid(String code, HttpInputMessage input) {
        return new HttpMessageNotReadableException(code, input);
    }
}
