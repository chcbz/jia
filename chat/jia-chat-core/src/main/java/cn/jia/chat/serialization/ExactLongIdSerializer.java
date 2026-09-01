package cn.jia.chat.serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/** Serializes database identifiers as decimal JSON strings before they enter JavaScript. */
public final class ExactLongIdSerializer extends JsonSerializer<Long> {
    @Override
    public void serialize(Long value, JsonGenerator generator, SerializerProvider serializers)
            throws IOException {
        generator.writeString(value.toString());
    }
}
