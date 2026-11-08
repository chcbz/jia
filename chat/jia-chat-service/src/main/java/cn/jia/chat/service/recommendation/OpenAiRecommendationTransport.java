package cn.jia.chat.service.recommendation;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Recommendation-specific provider boundary. Retains the SDK response (including actual
 * service_tier) instead of discarding billing fields through ChatResponseMetadata.
 * Reuses configured connection settings and HTTP customizers; one pooled client per bean,
 * no per-request client creation, alternate credential store, tools or automatic replay.
 */
@Component
@Lazy
public class OpenAiRecommendationTransport {
    private final OpenAIClient client;

    @Autowired
    public OpenAiRecommendationTransport(OpenAiCommonProperties common, OpenAiChatProperties chat,
            ObjectProvider<ObservationRegistry> observations, ObjectProvider<MeterRegistry> meters,
            ObjectProvider<OpenAiHttpClientBuilderCustomizer> customizers) {
        // ChatModel options contain inference settings, not the merged connection credentials.
        // Use the same common/chat precedence as Spring AI auto-configuration.
        var options = OpenAiAutoConfigurationUtil.resolveCommonProperties(common, chat);
        this.client = OpenAiSetup.setupSyncClient(options.getBaseUrl(), options.getApiKey(), options.getCredential(),
                options.getMicrosoftDeploymentName(), options.getMicrosoftFoundryServiceVersion(),
                options.getOrganizationId(), options.isMicrosoftFoundry(), options.isGitHubModels(),
                options.getModel(), options.getTimeout(), 0, options.getProxy(), options.getCustomHeaders(),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP), meters.getIfAvailable(),
                customizers.orderedStream().toList());
    }

    OpenAiRecommendationTransport(OpenAIClient client) { this.client = client; }

    public ChatCompletion complete(OpenAiChatOptions options, String instructions, String input) {
        return client.chat().completions().create(request(options, instructions, input));
    }

    /** Keep text inference settings local to this provider adapter, not the business/model port.
     * SDK extension properties serialize ordinary JSON without duplicating Spring's tool loop.
     */
    static ChatCompletionCreateParams request(OpenAiChatOptions options, String instructions, String input) {
        var builder = ChatCompletionCreateParams.builder().model(options.getModel())
                .addSystemMessage(instructions).addUserMessage(input);
        put(builder, "temperature", options.getTemperature());
        put(builder, "top_p", options.getTopP());
        put(builder, "max_tokens", options.getMaxTokens());
        put(builder, "max_completion_tokens", options.getMaxCompletionTokens());
        put(builder, "frequency_penalty", options.getFrequencyPenalty());
        put(builder, "presence_penalty", options.getPresencePenalty());
        put(builder, "logit_bias", options.getLogitBias());
        put(builder, "logprobs", options.getLogprobs());
        put(builder, "top_logprobs", options.getTopLogprobs());
        put(builder, "n", options.getN());
        put(builder, "seed", options.getSeed());
        put(builder, "stop", options.getStop());
        put(builder, "user", options.getUser());
        put(builder, "store", options.getStore());
        put(builder, "metadata", options.getMetadata());
        put(builder, "reasoning_effort", options.getReasoningEffort());
        put(builder, "verbosity", options.getVerbosity());
        put(builder, "service_tier", options.getServiceTier());
        put(builder, "prompt_cache_key", options.getPromptCacheKey());
        put(builder, "modalities", options.getOutputModalities());
        if (options.getOutputAudio() != null) put(builder, "audio", options.getOutputAudio().toChatCompletionAudioParam());
        if (options.getExtraBody() != null) options.getExtraBody().forEach((key, value) -> put(builder, key, value));
        try {
            var schema = com.openai.core.ObjectMappers.jsonMapper().readValue(options.getOutputSchema(),
                    com.openai.models.ResponseFormatJsonSchema.JsonSchema.Schema.class);
            builder.responseFormat(com.openai.models.ResponseFormatJsonSchema.builder().jsonSchema(
                    com.openai.models.ResponseFormatJsonSchema.JsonSchema.builder()
                            .name("json_schema").strict(true).schema(schema).build()).build());
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Invalid recommendation output schema", invalid);
        }
        return builder.build();
    }

    private static void put(ChatCompletionCreateParams.Builder builder, String key, Object value) {
        if (value != null) builder.putAdditionalBodyProperty(key, JsonValue.from(value));
    }

    @PreDestroy
    public void close() { client.close(); }
}
