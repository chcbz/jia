package cn.jia.agent.output.service;

import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.dto.OutputContextDTO;
import cn.jia.agent.output.dto.OutputSourceDTO;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Keeps the portable test resource aligned with the root SDD fixture contract. */
class OutputDeliveryFixtureContractTest {
    private static final String SOURCE_FIXTURE = "specs/agent-output-delivery/fixtures.json";
    private static final String SOURCE_SHA256 =
            "453968ce85423aaf091e81d8600f2959fc44efca66793637927a06da54e48f6f";

    @Test
    @SuppressWarnings("unchecked")
    void outputContextDtoMatchesFrozenPortableFixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/output-delivery-fixtures.json")) {
            assertNotNull(input);
            byte[] bytes = input.readAllBytes();
            assertEquals(SOURCE_SHA256, HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)), SOURCE_FIXTURE);
            Map<String, Object> fixture = JsonUtil.getMapper().readValue(bytes, Map.class);
            Map<String, Object> expected = (Map<String, Object>) fixture.get("outputContext");
            OutputContextDTO context = new OutputContextDTO(
                    1,
                    "22222222222222222222222222222222",
                    new OutputSourceDTO(OutputConstants.SOURCE_TASK, "task-example"),
                    "52428800",
                    "209715200",
                    "outputs/22222222222222222222222222222222/manifest.json",
                    List.of(OutputConstants.CAPABILITY_HTTP_V1,
                            OutputConstants.CAPABILITY_OWNER_SHARE_V1));

            assertEquals(expected, JsonUtil.getMapper().convertValue(context, Map.class));
        }
    }
}
