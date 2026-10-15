package cn.jia.core.config;

import cn.jia.core.entity.JsonResult;
import org.springframework.http.ResponseEntity;

/** Explicit controller adapter; never registered as a global arbitrary-body wrapper. */
public final class ApiJsonResponses {
    private ApiJsonResponses() { }
    public static <T> ResponseEntity<JsonResult<T>> success(ResponseEntity<T> response) {
        var status = response.getStatusCode();
        if (status.value() == 204 || status.value() == 304) {
            if (response.getBody() != null) throw new IllegalStateException("Bodyless response has content");
            return new ResponseEntity<>(null, response.getHeaders(), status);
        }
        if (!status.is2xxSuccessful() || response.getBody() instanceof JsonResult<?>) {
            throw new IllegalStateException("Explicit success adapter received a non-business success");
        }
        return new ResponseEntity<>(JsonResult.success(response.getBody()), response.getHeaders(), status);
    }
    public static <T, U> ResponseEntity<JsonResult<U>> success(ResponseEntity<T> response, java.util.function.Function<T, U> project) {
        return success(new ResponseEntity<>(project.apply(response.getBody()), response.getHeaders(), response.getStatusCode()));
    }
    /** Keep already validated snapshot bytes unchanged inside data; budgets apply to those bytes. */
    public static byte[] rawJsonSuccess(byte[] data, jakarta.servlet.http.HttpServletRequest request) {
        String id = cn.jia.core.filter.RequestIdFilter.current(request);
        String prefix = "{\"protocolVersion\":1,\"requestId\":"
                + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(id)
                + ",\"code\":\"E0\",\"msg\":\"ok\",\"data\":";
        byte[] head = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] result = new byte[head.length + data.length + 1];
        System.arraycopy(head, 0, result, 0, head.length);
        System.arraycopy(data, 0, result, head.length, data.length);
        result[result.length - 1] = '}';
        return result;
    }
}
