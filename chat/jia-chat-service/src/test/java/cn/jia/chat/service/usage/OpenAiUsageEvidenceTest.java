package cn.jia.chat.service.usage;

import com.openai.models.completions.CompletionUsage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;

class OpenAiUsageEvidenceTest {
    @Test void realSdkNativeUsagePreservesProviderCountersAndActualModelIdentity() throws Exception {
        String raw = """
                {"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,
                 "prompt_tokens_details":{"cached_tokens":80},
                 "completion_tokens_details":{"reasoning_tokens":12},"provider_extension":"preserved"}
                """;
        var nativeUsage = com.openai.core.ObjectMappers.jsonMapper().readValue(raw, CompletionUsage.class);
        var metadata = ChatResponseMetadata.builder().id("actual-provider-receipt").model("actual-model")
                .usage(new DefaultUsage(100, 20, 120, nativeUsage, 80L, null)).build();
        var evidence = OpenAiUsageEvidence.capture(new ChatResponse(List.of(), metadata), "channel", "standard");
        assertEquals("actual-provider-receipt", evidence.providerReceiptId());
        assertEquals("actual-model", evidence.actualModel());
        assertNotNull(evidence.normalizedUsage()); assertEquals(20L, evidence.normalizedUsage().tokens().get(INPUT));
        assertEquals(20L, evidence.normalizedUsage().tokens().get(OUTPUT));
        var saved = com.openai.core.ObjectMappers.jsonMapper().readTree(evidence.rawUsageJson());
        assertEquals(12, saved.get("completion_tokens_details").get("reasoning_tokens").asInt());
        assertEquals("preserved", saved.get("provider_extension").asText());
    }
    @Test void missingUsageOrProviderIdentityIsNotSynthesizedFromTokenEstimates() throws Exception {
        assertNull(OpenAiUsageEvidence.capture(null, "channel", "standard").normalizedUsage());
        var fallback = ChatResponseMetadata.builder().id("id").model("model").usage(new DefaultUsage(10, 20)).build();
        assertNull(OpenAiUsageEvidence.capture(new ChatResponse(List.of(), fallback), "channel", "standard").normalizedUsage());
        var usage = com.openai.core.ObjectMappers.jsonMapper().readValue("{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}", CompletionUsage.class);
        var unidentified = ChatResponseMetadata.builder().model("model").usage(new DefaultUsage(0, 0, 0, usage)).build();
        var evidence = OpenAiUsageEvidence.capture(new ChatResponse(List.of(), unidentified), "channel", "standard");
        assertNull(evidence.normalizedUsage()); assertNotEquals("null", evidence.rawUsageJson());
    }
}
