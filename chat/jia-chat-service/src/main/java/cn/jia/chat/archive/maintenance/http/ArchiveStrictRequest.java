package cn.jia.chat.archive.maintenance.http;

import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/** Reject extra identity/permission fields (including nested fields) and duplicate JSON keys. */
final class ArchiveStrictRequest {
    private ArchiveStrictRequest() { }

    static JsonNode tree(ObjectMapper mapper, byte[] body) {
        try {
            if (body == null || body.length == 0) throw invalid();
            JsonNode node = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(body);
            if (node == null || !node.isObject()) throw invalid();
            return node;
        } catch (IOException failure) {
            throw invalid();
        }
    }

    static <T> T read(ObjectMapper mapper, byte[] body, Class<T> type) {
        JsonNode node = tree(mapper, body);
        check(node, type);
        try {
            return mapper.treeToValue(node, type);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static void check(JsonNode node, Type type) {
        if (node == null || node.isNull()) return;
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == java.util.List.class) {
            if (!node.isArray()) throw invalid();
            for (JsonNode child : node) check(child, parameterized.getActualTypeArguments()[0]);
            return;
        }
        if (type instanceof Class<?> clazz && clazz.isRecord()) {
            if (!node.isObject()) throw invalid();
            Map<String, Type> allowed = Arrays.stream(clazz.getRecordComponents())
                    .collect(Collectors.toMap(RecordComponent::getName, RecordComponent::getGenericType));
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                Type childType = allowed.get(field.getKey());
                if (childType == null) throw invalid();
                check(field.getValue(), childType);
            }
        } else if (node.isContainerNode()) {
            throw invalid();
        }
    }

    private static ArchiveMaintenanceException invalid() {
        return new ArchiveMaintenanceException(400, "INVALID_REQUEST",
                "Archive request contains unknown, duplicate or invalid fields");
    }
}
