package cn.jia.chat.service.recommendation;

import cn.jia.chat.service.usage.OpenAiUsageEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiRecommendationTransportTest {
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    @Test void nativeResponsePreservesActualTierInsteadOfRequestedTierAndRealUsage() throws Exception {
        exchange(200, "priority");
    }
    @Test void retryableFailureMakesOnlyOneRealRequest() throws Exception { exchange(500, "default"); }

    private static void exchange(int status, String actualTier) throws Exception {
        var json = new ObjectMapper(); var count = new AtomicInteger();
        var captured = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            count.incrementAndGet(); captured.set(json.readTree(exchange.getRequestBody()));
            String value = status == 200
                    ? "{\"id\":\"native-receipt\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"actual-model\",\"service_tier\":\"" + actualTier
                      + "\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}],"
                      + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120,\"prompt_tokens_details\":{\"cached_tokens\":80}}}"
                    : "{\"error\":{\"message\":\"synthetic failure\",\"type\":\"server_error\"}}";
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        var options = OpenAiChatOptions.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .apiKey("synthetic").model("requested-model").maxRetries(0).serviceTier("default")
                .temperature(0.25).maxCompletionTokens(300).reasoningEffort("low").outputSchema(SCHEMA).build();
        var shared = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        org.mockito.Mockito.when(shared.getOptions()).thenReturn(options);
        var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.registerBean(org.springframework.ai.chat.model.ChatModel.class, () -> shared);
        context.register(OpenAiRecommendationTransport.class);
        context.refresh();
        org.mockito.Mockito.verifyNoInteractions(shared); // @Lazy creates no client on context startup.
        var transport = context.getBean(OpenAiRecommendationTransport.class);
        assertSame(transport, context.getBean(OpenAiRecommendationTransport.class));
        try {
            if (status == 200) {
                var response = transport.complete(options, "system instruction", "user input");
                assertEquals("priority", response.serviceTier().orElseThrow().asString());
                var evidence = OpenAiUsageEvidence.capture(response, "channel");
                assertEquals("actual-model", evidence.actualModel());
                assertNotNull(evidence.normalizedUsage());
                assertEquals("priority", evidence.normalizedUsage().tier());
                assertEquals(20L, evidence.normalizedUsage().tokens().get(cn.jia.economy.pricing.ModelTokenPrice.Category.INPUT));
            } else assertThrows(RuntimeException.class, () -> transport.complete(options, "instruction", "input"));
            assertEquals(1, count.get());
            var request = captured.get();
            assertEquals("requested-model", request.path("model").asText());
            assertEquals("default", request.path("service_tier").asText());
            assertEquals("low", request.path("reasoning_effort").asText());
            assertEquals(0.25, request.path("temperature").asDouble());
            assertEquals(300, request.path("max_completion_tokens").asInt());
            assertEquals(json.readTree(SCHEMA), request.path("response_format").path("json_schema").path("schema"));
            assertTrue(request.path("response_format").path("json_schema").path("strict").asBoolean());
            assertEquals(2, request.path("messages").size()); assertFalse(request.has("tools"));
        } finally { context.close(); server.stop(0); }
    }
}
