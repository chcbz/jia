package cn.jia.chat.handler.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Explicit D1 wire boundary; does not re-enable scalar coercion for other fields. */
public final class ChatRequestRevisionDeserializer extends ValueDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return context.reportInputMismatch(Long.class, "requestRevision must be a positive decimal string");
        }
        String value = parser.getString();
        if (!value.matches("[1-9][0-9]{0,18}")) {
            return context.reportInputMismatch(Long.class, "requestRevision must be a positive decimal string");
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException overflow) {
            return context.reportInputMismatch(Long.class, "requestRevision exceeds the supported range");
        }
    }
}
