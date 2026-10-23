package cn.jia.core.config;

import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.filter.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiEnvelopeBoundaryTest {
    record Input(String name, int count) {}
    @RestController
    static class Endpoints {
        @PostMapping("/test/protocol") JsonResult<Input> echo(@RequestBody JsonRequest<Input> request) {
            return JsonResult.success(request.data());
        }
        @PostMapping("/test/raw") JsonResult<String> raw(@cn.jia.core.annotation.ApiJsonData @RequestBody byte[] data) {
            return JsonResult.success(new String(data, java.nio.charset.StandardCharsets.UTF_8));
        }
        @GetMapping("/test/fail") JsonResult<Void> fail() { throw new IllegalStateException("private diagnostic"); }
        @GetMapping("/test/forbidden") JsonResult<Void> forbidden() { throw new org.springframework.security.access.AccessDeniedException("private"); }
        @GetMapping("/sms/receive") String external() { throw new IllegalStateException("provider failure"); }
    }
    private MockMvc mvc;
    @BeforeEach void setup() {
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        converters.add(new org.springframework.http.converter.ByteArrayHttpMessageConverter());
        converters.add(new JacksonJsonHttpMessageConverter());
        new ApiEnvelopeJsonConfig().extendMessageConverters(converters);
        mvc = MockMvcBuilders.standaloneSetup(new Endpoints())
                .setControllerAdvice(new ApiEnvelopeAdvice(), new ApiRequestEnvelopeAdvice(), new ExceptionHandlerAdvice())
                .setMessageConverters(converters.toArray(HttpMessageConverter[]::new))
                .addFilters(new RequestIdFilter()).build();
    }
    private String body(String id, String data) {
        return "{\"protocolVersion\":1,\"requestId\":\"" + id + "\",\"data\":" + data + "}";
    }
    @Test void acceptsBodyIdAndDoesNotOverwriteItInFilterFinally() throws Exception {
        mvc.perform(post("/test/protocol").contentType(MediaType.APPLICATION_JSON).content(body("r", "{\"name\":\"x\",\"count\":1}")))
                .andExpect(status().isOk()).andExpect(header().string("X-Request-Id", "r"))
                .andExpect(jsonPath("$.requestId").value("r")).andExpect(jsonPath("$.code").value("E0"))
                .andExpect(jsonPath("$.data.name").value("x")).andExpect(jsonPath("$.status").doesNotExist());
    }
    @Test void rejectsHeaderBodyMismatch() throws Exception {
        mvc.perform(post("/test/protocol").header("X-Request-Id", "header").contentType(MediaType.APPLICATION_JSON).content(body("body", "null")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.requestId").value("header"))
                .andExpect(jsonPath("$.code").value("PROTOCOL_REQUEST_ID_MISMATCH"));
    }
    @Test void rejectsDuplicateKeysMissingDataAndCoercion() throws Exception {
        for (String value : List.of(body("r", "{\"name\":\"x\",\"count\":1,\"count\":2}"),
                "{\"protocolVersion\":1,\"requestId\":\"r\"}", body("r", "{\"name\":\"x\",\"count\":\"1\"}"))) {
            mvc.perform(post("/test/protocol").contentType(MediaType.APPLICATION_JSON).content(value))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.protocolVersion").value(1));
        }
    }
    @Test void mapsErrorsToRealHttpStatusesWithoutLeakingExceptionText() throws Exception {
        mvc.perform(get("/test/fail").header("X-Request-Id", "r"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.requestId").value("r"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private diagnostic"))));
        mvc.perform(get("/test/forbidden"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("E403"));
    }
    @Test void externalCallbackFallbackDoesNotAdoptTheSelfJsonEnvelope() throws Exception {
        mvc.perform(get("/sms/receive"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.protocolVersion").doesNotExist())
                .andExpect(jsonPath("$.requestId").doesNotExist()).andExpect(jsonPath("$.status").value(200));
    }
    @Test void rawAdapterPreservesOriginalDataBytesAndCorrelation() throws Exception {
        String data = "{ \"name\":\"图片\", \"n\":1.00 }";
        mvc.perform(post("/test/raw").contentType(MediaType.APPLICATION_JSON).content(body("raw-id", data)))
                .andExpect(status().isOk()).andExpect(header().string("X-Request-Id", "raw-id"))
                .andExpect(jsonPath("$.data").value(data));
    }
    @Test void decoderPreservesEachJsonValueWithoutReserializing() {
        for (String data : List.of("{ \"图片\" : [1, true] }", "[ 1, null, \"文\" ]", "\"文\\n字\"", "null", "true", "123", "1.00", "-1.2e+3")) {
            byte[] bytes = body("r", data).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var input = new org.springframework.http.server.ServletServerHttpRequest(new org.springframework.mock.web.MockHttpServletRequest());
            var decoded = ApiJsonInput.decode(bytes, input);
            org.junit.jupiter.api.Assertions.assertArrayEquals(data.getBytes(java.nio.charset.StandardCharsets.UTF_8), decoded.data(), data);
        }
    }


    @Test void explicitResponseAdapterPreservesMetadataAndRejectsDoubleWrapping() {
        var raw = org.springframework.http.ResponseEntity.status(201).eTag("\"7\"").body(java.util.Map.of("data", "business"));
        var response = ApiJsonResponses.success(raw);
        org.junit.jupiter.api.Assertions.assertEquals(201, response.getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertEquals("\"7\"", response.getHeaders().getETag());
        org.junit.jupiter.api.Assertions.assertEquals(raw.getBody(), response.getBody().getData());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ApiJsonResponses.success(org.springframework.http.ResponseEntity.ok(JsonResult.success())));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ApiJsonResponses.success(org.springframework.http.ResponseEntity.status(403).body("denied")));
    }
    @Test void rawResponseKeepsCanonicalBytesInsideData() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        RequestIdFilter.accept(request, "raw-response");
        String data = "{ \"name\":\"图片\", \"decimal\":1.00 }";
        byte[] result = ApiJsonResponses.rawJsonSuccess(data.getBytes(java.nio.charset.StandardCharsets.UTF_8), request);
        String text = new String(result, java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(text.endsWith("\"data\":" + data + "}"));
        var decoded = tools.jackson.databind.json.JsonMapper.builder().build().readTree(result);
        org.junit.jupiter.api.Assertions.assertEquals("raw-response", decoded.get("requestId").asString());
        org.junit.jupiter.api.Assertions.assertEquals("图片", decoded.get("data").get("name").asString());
    }
}
