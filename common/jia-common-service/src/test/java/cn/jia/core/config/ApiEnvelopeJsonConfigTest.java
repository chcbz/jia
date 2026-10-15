package cn.jia.core.config;

import cn.jia.core.entity.JsonRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiEnvelopeJsonConfigTest {
    record Input(String id, int count) {}
    private final JsonMapper original = JsonMapper.builder().build();
    private final ObjectMapper strict = ApiEnvelopeJsonConfig.strictMapper(original);

    @Test void typedRequestRetainsGenericType() throws Exception {
        var type = strict.getTypeFactory().constructParametricType(JsonRequest.class, Input.class);
        JsonRequest<Input> result = strict.readValue("{\"protocolVersion\":1,\"requestId\":\"r\",\"data\":{\"id\":\"x\",\"count\":1}}", type);
        assertEquals(new Input("x", 1), result.data());
    }

    @Test void rejectsCoercionDuplicatesUnknownFieldsAndTrailingJson() {
        var type = strict.getTypeFactory().constructParametricType(JsonRequest.class, Input.class);
        for (String data : new String[]{"{\"id\":1,\"count\":1}", "{\"id\":\"x\",\"count\":\"1\"}",
                "{\"id\":\"x\",\"count\":1.5}", "{\"id\":\"x\",\"count\":1,\"other\":1}",
                "{\"id\":\"x\",\"id\":\"y\",\"count\":1}"}) {
            assertThrows(Exception.class, () -> strict.readValue("{\"protocolVersion\":1,\"requestId\":\"r\",\"data\":" + data + "}", type));
        }
        assertThrows(Exception.class, () -> strict.readValue("{\"protocolVersion\":1,\"requestId\":\"r\",\"data\":null} {}", type));
    }

    @Test void doesNotChangeProviderMapper() throws Exception {
        assertEquals(1, original.readValue("{\"id\":\"x\",\"count\":\"1\"}", Input.class).count());
    }
}
