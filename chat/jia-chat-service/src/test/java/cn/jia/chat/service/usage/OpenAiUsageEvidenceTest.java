package cn.jia.chat.service.usage;

import com.openai.models.chat.completions.ChatCompletion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;

class OpenAiUsageEvidenceTest {
    private static ChatCompletion response(String extra) throws Exception {
        return com.openai.core.ObjectMappers.jsonMapper().readValue(
                "{\"object\":\"chat.completion\",\"created\":1,\"choices\":[],\"model\":\"actual-model\"" + extra + "}", ChatCompletion.class);
    }
    @Test void realSdkNativeUsagePreservesProviderCountersAndActualBillingIdentity() throws Exception {
        var receipt = response("""
                ,"id":"actual-provider-receipt","service_tier":"default",
                "usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,
                 "prompt_tokens_details":{"cached_tokens":80},
                 "completion_tokens_details":{"reasoning_tokens":12},"provider_extension":"preserved"}
                """);
        var evidence = OpenAiUsageEvidence.capture(receipt, "channel");
        assertEquals("actual-provider-receipt", evidence.providerReceiptId());
        assertEquals("actual-model", evidence.actualModel());
        assertEquals("default", evidence.actualTier());
        assertNotNull(evidence.normalizedUsage());
        assertEquals("standard", evidence.normalizedUsage().tier());
        assertEquals(20L, evidence.normalizedUsage().tokens().get(INPUT));
        assertEquals(80L, evidence.normalizedUsage().tokens().get(CACHED_INPUT));
        assertEquals(20L, evidence.normalizedUsage().tokens().get(OUTPUT));
        var saved = com.openai.core.ObjectMappers.jsonMapper().readTree(evidence.rawUsageJson());
        assertEquals(12, saved.get("completion_tokens_details").get("reasoning_tokens").asInt());
        assertEquals("preserved", saved.get("provider_extension").asText());
    }
    private static final String ZERO = ",\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}";
    @Test void explicitZeroIsKnownButAbsentUsageIsPending() throws Exception {
        assertNotNull(OpenAiUsageEvidence.capture(response(",\"id\":\"receipt\",\"service_tier\":\"default\"" + ZERO), "channel").normalizedUsage());
        assertNull(OpenAiUsageEvidence.capture(response(",\"id\":\"receipt\",\"service_tier\":\"default\""), "channel").normalizedUsage());
        assertNull(OpenAiUsageEvidence.capture(null, "channel").normalizedUsage());
    }
    @Test void missingIdentityOrActualTierNeverUsesRequestedValues() throws Exception {
        var unidentified = OpenAiUsageEvidence.capture(response(",\"service_tier\":\"default\"" + ZERO), "channel");
        assertNull(unidentified.normalizedUsage()); assertNotEquals("null", unidentified.rawUsageJson());
        var noTier = OpenAiUsageEvidence.capture(response(",\"id\":\"receipt\"" + ZERO), "channel");
        assertNull(noTier.actualTier()); assertNull(noTier.normalizedUsage());
        assertNotEquals("null", noTier.rawUsageJson());
    }
}
