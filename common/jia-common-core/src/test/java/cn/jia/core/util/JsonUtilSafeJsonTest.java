package cn.jia.core.util;

import cn.jia.core.security.SensitiveSanitizeConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilSafeJsonTest {
    @Test
    void toSafeJsonSanitizesSensitiveFieldsWithoutChangingToJson() {
        Map<String, Object> payload = Map.of("name", "songjiang", "password", "raw-password");

        String rawJson = JsonUtil.toJson(payload);
        String safeJson = JsonUtil.toSafeJson(payload);

        assertTrue(rawJson.contains("raw-password"));
        assertFalse(safeJson.contains("raw-password"));
        assertTrue(safeJson.contains("\"password\":null"));
    }

    @Test
    void toSafeJsonSupportsCustomConfig() {
        SensitiveSanitizeConfig config = SensitiveSanitizeConfig.defaults();
        config.setMaskDisplayFields(true);

        String safeJson = JsonUtil.toSafeJson(Map.of("phone", "13800138000"), config);

        assertTrue(safeJson.contains("138****8000"));
    }
}
