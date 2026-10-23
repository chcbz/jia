package cn.jia.core.entity;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class JsonEnvelopeSerializationTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test void successAlwaysContainsExactlyFiveFieldsIncludingNullData() throws Exception {
        JsonResult<Object> result = JsonResult.success();
        result.setRequestId("req_test_1");
        var wire = mapper.readTree(mapper.writeValueAsBytes(result));
        Set<String> keys = new HashSet<>();
        keys.addAll(wire.propertyNames());
        assertEquals(Set.of("protocolVersion", "requestId", "code", "msg", "data"), keys);
        assertEquals(1, wire.get("protocolVersion").intValue());
        assertTrue(wire.get("data").isNull());
        assertEquals("E0", wire.get("code").textValue());
    }

    @Test void pageMetadataIsNestedAndTotalDoesNotLosePrecision() throws Exception {
        var result = JsonResultPage.page(List.of("a"), 2, 100, 9007199254740993L);
        result.setRequestId("req_test_1");
        var wire = mapper.readTree(mapper.writeValueAsBytes(result));
        assertEquals("9007199254740993", wire.at("/data/total").textValue());
        assertEquals(100, wire.at("/data/pageSize").intValue());
        assertEquals(2, wire.at("/data/pageNum").intValue());
        assertFalse(wire.has("total"));
        assertFalse(wire.has("status"));
    }

    @Test void pageSizeKeepsExistingNormalization() {
        var page = new JsonRequestPage<>();
        page.setPageSize(1000); assertEquals(100, page.getPageSize());
        page.setPageSize(0); assertEquals(1, page.getPageSize());
        page.setPageSize(null); assertEquals(100, page.getPageSize());
    }

    @Test void requestRejectsUnsupportedVersionAndUnsafeCorrelation() {
        assertThrows(IllegalArgumentException.class, () -> new JsonRequest<>(2, "r", null));
        assertThrows(IllegalArgumentException.class, () -> new JsonRequest<>(1, "r\nx", null));
        assertDoesNotThrow(() -> new JsonRequest<>(1, "r", null));
    }
    @Test void actualSerializerAgreesWithSharedCrossLanguageFixtures() throws Exception {
        var fixture = mapper.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "../../docs/interface-catalog/contracts/http-envelope-fixtures.json")));
        var examples = java.util.Map.<String, JsonResult<?>>of(
                "empty-success", JsonResult.success(),
                "large-total-as-string", JsonResultPage.page(List.of(), 1, 100, 9007199254740993L),
                "business-data", JsonResult.success(java.util.Map.of("nested", java.util.Map.of("data", "business data, not another envelope"))),
                "forbidden", JsonResult.failure("E403", "Forbidden"));
        for (var item : fixture.get("cases")) {
            JsonResult<?> actual = examples.get(item.get("name").asString());
            if (actual == null) continue;
            actual.setRequestId("req_test_1");
            assertEquals(mapper.readTree(item.get("text").asString()), mapper.readTree(mapper.writeValueAsBytes(actual)));
        }
    }

}
