package cn.jia.chat.serialization;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/** Serializes database identifiers as decimal JSON strings before they enter JavaScript. */
public final class ExactLongIdSerializer extends ValueSerializer<Long> {
    @Override
    public void serialize(Long value, JsonGenerator generator, SerializationContext context)
            throws JacksonException {
        generator.writeString(value.toString());
    }
}
