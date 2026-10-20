package cn.jia.core.security;

import cn.jia.core.entity.JsonResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SensitiveResponseBodyAdviceTest {

    @Test
    void sanitizesRestResponseBodyByDefault() throws Exception {
        SensitiveResponseBodyAdvice advice = new SensitiveResponseBodyAdvice(new SensitiveResponseProperties());
        JsonResult<Map<String, Object>> body = JsonResult.success(Map.of("name", "songjiang", "password", "raw-password"));

        Object result = advice.beforeBodyWrite(body, methodParameter("normal"), MediaType.APPLICATION_JSON,
                null, null, null);

        Map<?, ?> resultMap = (Map<?, ?>) result;
        Map<?, ?> data = (Map<?, ?>) resultMap.get("data");
        assertEquals("songjiang", data.get("name"));
        assertNull(data.get("password"));
        assertEquals("raw-password", body.getData().get("password"));
    }

    @Test
    void preservesHallRefsAndActionsWhileRedactingNestedSecrets() throws Exception {
        SensitiveResponseBodyAdvice advice = new SensitiveResponseBodyAdvice(new SensitiveResponseProperties());
        Map<String, Object> item = Map.of("ref", Map.of("type", "TASK", "id", "458"),
                "status", Map.of("value", "open"), "allowedActions", java.util.List.of("OPEN_TASK"),
                "details", Map.of("password", "do-not-expose"));
        JsonResult<?> body = JsonResult.success(Map.of("sections", Map.of("recent", Map.of(
                "partitions", Map.of("task", Map.of("items", java.util.List.of(item)))))));
        Map<?, ?> result = (Map<?, ?>) advice.beforeBodyWrite(body, methodParameter("normal"),
                MediaType.APPLICATION_JSON, null, null, null);
        for (String key : java.util.List.of("data", "sections", "recent", "partitions", "task")) {
            result = (Map<?, ?>) result.get(key);
        }
        Map<?, ?> actual = (Map<?, ?>) ((java.util.List<?>) result.get("items")).getFirst();
        assertEquals(item.get("ref"), actual.get("ref"));
        assertEquals(item.get("status"), actual.get("status"));
        assertEquals(item.get("allowedActions"), actual.get("allowedActions"));
        assertNull(((Map<?, ?>) actual.get("details")).get("password"));
    }

    @Test
    void sanitizedQueuedProtocolEventsRetainClosedRootAndRedactPayload() throws Exception {
        var event = cn.jia.core.entity.ProtocolMessage.event("request", "command.dispatch", "message",
                Map.of("instruction", "safe", "password", "secret"));
        var reply = cn.jia.core.entity.ProtocolMessage.reply(event, "command.receipt", "reply", "E0", "ok", null);
        var body = JsonResult.success(Map.of("items", java.util.List.of(event, reply)));
        var advice = new SensitiveResponseBodyAdvice(new SensitiveResponseProperties());
        Object sanitized = advice.beforeBodyWrite(body, methodParameter("normal"), MediaType.APPLICATION_JSON,
                null, null, null);
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var items = mapper.readTree(mapper.writeValueAsString(sanitized)).path("data").path("items");
        assertEquals(5, items.get(0).size());
        org.junit.jupiter.api.Assertions.assertFalse(items.get(0).has("correlationId"));
        org.junit.jupiter.api.Assertions.assertFalse(items.get(0).has("code"));
        org.junit.jupiter.api.Assertions.assertFalse(items.get(0).has("msg"));
        org.junit.jupiter.api.Assertions.assertTrue(items.get(0).path("data").path("password").isNull());
        assertEquals("safe", items.get(0).path("data").path("instruction").asText());
        assertEquals(8, items.get(1).size());
        assertEquals("message", items.get(1).path("correlationId").asText());
        org.junit.jupiter.api.Assertions.assertTrue(items.get(1).has("data"));
        org.junit.jupiter.api.Assertions.assertTrue(items.get(1).path("data").isNull());
        assertEquals("secret", event.data().get("password"));
    }

    @Test
    void leavesBodyUnchangedWhenDisabled() throws Exception {
        SensitiveResponseProperties properties = new SensitiveResponseProperties();
        properties.setEnabled(false);
        SensitiveResponseBodyAdvice advice = new SensitiveResponseBodyAdvice(properties);
        JsonResult<Map<String, Object>> body = JsonResult.success(Map.of("password", "raw-password"));

        Object result = advice.beforeBodyWrite(body, methodParameter("normal"), MediaType.APPLICATION_JSON,
                null, null, null);

        assertSame(body, result);
    }

    @Test
    void leavesSensitiveValuesInAuditOnlyMode() throws Exception {
        SensitiveResponseProperties properties = new SensitiveResponseProperties();
        properties.setAuditOnly(true);
        SensitiveResponseBodyAdvice advice = new SensitiveResponseBodyAdvice(properties);
        JsonResult<Map<String, Object>> body = JsonResult.success(Map.of("password", "raw-password"));

        Map<?, ?> result = (Map<?, ?>) advice.beforeBodyWrite(body, methodParameter("normal"), MediaType.APPLICATION_JSON,
                null, null, null);

        assertEquals("raw-password", ((Map<?, ?>) result.get("data")).get("password"));
    }

    @Test
    void allowsMethodLevelSensitiveOutput() throws Exception {
        SensitiveResponseBodyAdvice advice = new SensitiveResponseBodyAdvice(new SensitiveResponseProperties());
        JsonResult<Map<String, Object>> body = JsonResult.success(Map.of("accessToken", "issued-token"));

        Object result = advice.beforeBodyWrite(body, methodParameter("allowed"), MediaType.APPLICATION_JSON,
                null, null, null);

        assertSame(body, result);
    }

    @Test
    void ignoresNonJsonResponses() throws Exception {
        SensitiveResponseBodyAdvice advice = new SensitiveResponseBodyAdvice(new SensitiveResponseProperties());
        String body = "password=raw-password";

        Object result = advice.beforeBodyWrite(body, methodParameter("normal"), MediaType.TEXT_PLAIN,
                null, null, null);

        assertSame(body, result);
    }

    @Test
    void queueEventSurvivesActualHttpAdviceAndJacksonConverter() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new QueueController())
                .setControllerAdvice(new SensitiveResponseBodyAdvice(new SensitiveResponseProperties()))
                .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter())
                .build();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/queue"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var item = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(response.getContentAsString()).path("data").path("items").get(0);
        assertEquals(5, item.size());
        assertEquals("command.dispatch", item.path("messageType").asText());
        org.junit.jupiter.api.Assertions.assertTrue(item.path("data").path("password").isNull());
        org.junit.jupiter.api.Assertions.assertFalse(item.has("correlationId"));
    }

    @org.springframework.web.bind.annotation.RestController
    static class QueueController {
        @org.springframework.web.bind.annotation.GetMapping("/queue")
        public JsonResult<?> queue() {
            return JsonResult.success(Map.of("items", java.util.List.of(
                    cn.jia.core.entity.ProtocolMessage.event("request", "command.dispatch", "message",
                            Map.of("password", "secret", "instruction", "safe")))));
        }
    }

    private MethodParameter methodParameter(String methodName) throws NoSuchMethodException {
        Method method = SampleController.class.getDeclaredMethod(methodName);
        return new MethodParameter(method, -1);
    }

    static class SampleController {
        JsonResult<Map<String, Object>> normal() {
            return JsonResult.success();
        }

        @AllowSensitiveOutput(reason = "test token issuing endpoint")
        JsonResult<Map<String, Object>> allowed() {
            return JsonResult.success();
        }
    }
}
