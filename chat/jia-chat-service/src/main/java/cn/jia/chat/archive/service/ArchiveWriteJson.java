package cn.jia.chat.archive.service;

import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.core.entity.JsonResult;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class ArchiveWriteJson {
    private static final int MAX_REQUEST_BYTES = 65_536;
    private final ObjectMapper mapper;

    ArchiveWriteJson() {
        JsonFactory factory = new JsonFactory();
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper = new ObjectMapper(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
    }

    Parsed parse(byte[] bytes, Class<?> type) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_REQUEST_BYTES
                || startsWithBom(bytes)) invalidJson();
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            JsonNode node = mapper.readTree(bytes);
            if (node == null || !node.isObject()) invalidJson();
            validateNode(node);
            Object value = mapper.treeToValue(node, type);
            return new Parsed(value, canonical(node));
        } catch (CharacterCodingException failure) {
            throw invalidJsonException();
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof ArchivePersonalDataException personal) throw personal;
            throw invalidJsonException();
        }
    }

    byte[] canonicalNode(JsonNode node) {
        validateNode(node);
        return canonical(node);
    }

    JsonNode readCanonical(String json) {
        try { return mapper.readTree(json); }
        catch (IOException failure) { throw new IllegalStateException("Persisted canonical JSON is invalid", failure); }
    }

    byte[] success(Object data) {
        try { return mapper.writeValueAsBytes(JsonResult.success(data)); }
        catch (IOException failure) { throw new IllegalStateException("Unable to serialize archive response", failure); }
    }

    String writeCanonical(JsonNode node) {
        return new String(canonicalNode(node), StandardCharsets.UTF_8);
    }

    String canonicalValue(Object value) {
        return writeCanonical(mapper.valueToTree(value));
    }

    <T> T readValue(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (IOException failure) { throw new IllegalStateException("Persisted archive JSON is invalid", failure); }
    }

    String sha256(String method, String canonicalPath, byte[] canonicalJson) {
        byte[] prefix = (method + "\n" + canonicalPath + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] preimage = new byte[prefix.length + canonicalJson.length];
        System.arraycopy(prefix, 0, preimage, 0, prefix.length);
        System.arraycopy(canonicalJson, 0, preimage, prefix.length, canonicalJson.length);
        return ArchiveEtags.sha256(preimage);
    }

    private byte[] canonical(JsonNode node) {
        StringBuilder out = new StringBuilder();
        append(node, out);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void append(JsonNode node, StringBuilder out) {
        try {
            if (node.isObject()) {
                List<String> names = new ArrayList<>();
                node.fieldNames().forEachRemaining(names::add);
                names.sort(String::compareTo);
                out.append('{');
                for (int i = 0; i < names.size(); i++) {
                    if (i > 0) out.append(',');
                    String name = names.get(i);
                    out.append(mapper.writeValueAsString(name)).append(':');
                    append(node.get(name), out);
                }
                out.append('}');
            } else if (node.isArray()) {
                out.append('[');
                for (int i = 0; i < node.size(); i++) {
                    if (i > 0) out.append(',');
                    append(node.get(i), out);
                }
                out.append(']');
            } else if (node.isTextual()) {
                out.append(mapper.writeValueAsString(node.textValue()));
            } else if (node.isIntegralNumber()) {
                out.append(node.asText());
            } else if (node.isBoolean()) {
                out.append(node.booleanValue());
            } else if (node.isNull()) {
                out.append("null");
            } else invalidJson();
        } catch (IOException failure) {
            throw invalidJsonException();
        }
    }

    private void validateNode(JsonNode node) {
        if (node.isFloatingPointNumber() || node.isBinary() || node.isMissingNode()) invalidJson();
        if (node.isTextual()) validateUnicode(node.textValue());
        if (node.isContainerNode()) node.elements().forEachRemaining(this::validateNode);
        if (node.isObject()) node.fieldNames().forEachRemaining(this::validateUnicode);
    }

    private void validateUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) invalidJson();
            } else if (Character.isLowSurrogate(c)) invalidJson();
        }
    }

    private boolean startsWithBom(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf;
    }

    private void invalidJson() { throw invalidJsonException(); }
    private ArchivePersonalDataException invalidJsonException() {
        return new ArchivePersonalDataException(422, "INVALID_REQUEST_JSON", "Invalid archive mutation JSON");
    }

    record Parsed(Object value, byte[] canonicalJson) {
        Parsed { canonicalJson = canonicalJson.clone(); }
        @Override public byte[] canonicalJson() { return canonicalJson.clone(); }
        <T> T value(Class<T> type) { return type.cast(value); }
    }
}
