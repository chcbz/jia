package cn.jia.agent.output.service;

import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OutputLeaseFixtureContractTest {
    private static final String SOURCE = "specs/agent-output-delivery/lease-fixtures.json";
    private static final String SHA256 =
            "a6be57af7411d4d1a14e158a0be86f5552e117cd19adbb0bc7abd96f8e94fdb4";

    @Test
    @SuppressWarnings("unchecked")
    void copiedPortableFixtureHasFrozenDigestAndStringVersions() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/output-delivery-lease-fixtures.json")) {
            assertNotNull(input);
            byte[] bytes = input.readAllBytes();
            assertEquals(SHA256, HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)), SOURCE);
            Map<String, Object> fixture = JsonUtil.getMapper().readValue(bytes, Map.class);
            List<Map<String, Object>> mutations =
                    (List<Map<String, Object>>) fixture.get("mutations");
            assertEquals(List.of("claim", "start", "heartbeat", "release"),
                    mutations.stream().map(row -> row.get("action")).toList());
            for (Map<String, Object> mutation : mutations) {
                Map<String, Object> request = (Map<String, Object>) mutation.get("request");
                assertEquals(String.class, request.get("expectedVersion").getClass());
            }
        }
    }
}
