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
